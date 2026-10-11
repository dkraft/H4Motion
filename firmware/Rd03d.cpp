#include "Rd03d.h"

#include <string.h>

// FD FC FB FA | len=2 | cmd 0x0090 multi-target | 04 03 02 01
static const uint8_t kMultiTarget[] = {
  0xFD, 0xFC, 0xFB, 0xFA, 0x02, 0x00, 0x90, 0x00, 0x04, 0x03, 0x02, 0x01
};

int16_t Rd03d::decodeSignMag(uint8_t lo, uint8_t hi) {
  uint16_t raw = (uint16_t)lo | ((uint16_t)hi << 8);
  int16_t mag = (int16_t)(raw & 0x7FFF);
  if (raw & 0x8000) return mag;
  return (int16_t)(-mag);
}

bool Rd03d::decodeFrame(const uint8_t* f, RdTarget out[RD03_MAX_TARGETS]) {
  if (f[0] != 0xAA || f[1] != 0xFF || f[2] != 0x03 || f[3] != 0x00) return false;
  if (f[28] != 0x55 || f[29] != 0xCC) return false;
  for (int i = 0; i < RD03_MAX_TARGETS; i++) {
    const uint8_t* p = f + 4 + i * 8;
    RdTarget t;
    t.x_mm = decodeSignMag(p[0], p[1]);
    t.y_mm = decodeSignMag(p[2], p[3]);
    t.spd_cms = decodeSignMag(p[4], p[5]);
    t.pix_mm = (uint16_t)p[6] | ((uint16_t)p[7] << 8);
    int spd_abs = t.spd_cms < 0 ? -t.spd_cms : t.spd_cms;
    // 248/256 cm/s show up as Doppler "no measurement" on this module.
    bool sentinel = (spd_abs == 248 || spd_abs == 256);
    t.valid = (t.x_mm != 0 || t.y_mm != 0) && !sentinel;
    out[i] = t;
  }
  return true;
}

void Rd03d::begin() {
  // 256000 8N1 is exact on an 80 MHz APB (divider 312.5). A 30-byte frame
  // is about 1.2 ms, and the module reports about ten frames a second.
  // The software FIFO has to cover a LoRa transmit, which can stall loop()
  // for seconds while the UART interrupt keeps draining the 128-byte HW FIFO.
  _serial.setRxBufferSize(4096);
  _serial.begin(RD03_BAUD, SERIAL_8N1, RD03_RX_PIN, RD03_TX_PIN);
  _got = 0;
  _fresh = false;
  _rx = 0;
  _bad_run = 0;
  _tail_n = 0;
  _last_byte_ms = millis();
  _next_mode_ms = millis() + 400;
  memset(_tgt, 0, sizeof(_tgt));
  memset(_tail, 0, sizeof(_tail));
}

void Rd03d::sendMultiTarget() {
  _serial.write(kMultiTarget, sizeof(kMultiTarget));
}

void Rd03d::push(uint8_t b) {
  if (_got == 0) {
    if (b == 0xAA) _buf[_got++] = b;
    return;
  }
  if (_got == 1 && b != 0xFF) { _got = (b == 0xAA) ? 1 : 0; if (_got) _buf[0] = 0xAA; return; }
  if (_got == 2 && b != 0x03) { _got = (b == 0xAA) ? 1 : 0; if (_got) _buf[0] = 0xAA; return; }
  if (_got == 3 && b != 0x00) { _got = (b == 0xAA) ? 1 : 0; if (_got) _buf[0] = 0xAA; return; }
  _buf[_got++] = b;
  if (_got < 30) return;
  if (decodeFrame(_buf, _tgt)) {
    _ok++;
    _bad_run = 0;
    _fresh = true;
  } else {
    _bad++;
    _bad_run++;
  }
  _got = 0;
}

int Rd03d::lastBytes(uint8_t* out, int max_n) const {
  int n = _tail_n < 8 ? _tail_n : 8;
  if (n > max_n) n = max_n;
  // _tail is a ring once 8 bytes have arrived. The newest byte sits just behind _tail_n.
  int start = (_tail_n < 8) ? 0 : (int)(_tail_n % 8);
  for (int i = 0; i < n; i++) {
    int idx = (_tail_n < 8) ? i : (start + i) % 8;
    out[i] = _tail[idx];
  }
  return n;
}

void Rd03d::loop() {
  uint32_t now = millis();
  // Header is locked in after 4 bytes. A gap much longer than one frame
  // (1.2 ms) means bytes were lost. Drop the partial instead of gluing the
  // next frame onto it. A lone 0xAA between frames is not a torn frame.
  if (_got >= 4 && (uint32_t)(now - _last_byte_ms) > 15) {
    _got = 0;
    _bad++;
    _bad_run++;
  } else if (_got > 0 && _got < 4 && (uint32_t)(now - _last_byte_ms) > 15) {
    _got = 0;
  }
  // Boot is single-target (shorter frames, so the 30-byte tail check fails).
  // Ask again after a run of bad frames; the module can leave multi-target.
  if ((_ok == 0 || _bad_run >= 8) && (int32_t)(now - _next_mode_ms) >= 0) {
#if defined(ARDUINO)
    if (_bad_run >= 8) Serial.println("radar framing");
#endif
    sendMultiTarget();
    _bad_run = 0;
    _next_mode_ms = now + 1000;
  }
  while (_serial.available() > 0) {
    uint8_t b = (uint8_t)_serial.read();
    _rx++;
    _last_byte_ms = now;
    _tail[_tail_n % 8] = b;
    _tail_n++;
    push(b);
  }
}

bool Rd03d::takeFrame(RdTarget out[RD03_MAX_TARGETS]) {
  if (!_fresh) return false;
  memcpy(out, _tgt, sizeof(_tgt));
  _fresh = false;
  return true;
}
