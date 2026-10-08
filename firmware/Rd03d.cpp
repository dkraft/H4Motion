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
  _serial.begin(RD03_BAUD, SERIAL_8N1, RD03_RX_PIN, RD03_TX_PIN);
  _got = 0;
  _fresh = false;
  _mode_left = 3;
  _next_mode_ms = millis() + 400;
  memset(_tgt, 0, sizeof(_tgt));
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
    _fresh = true;
  } else {
    _bad++;
  }
  _got = 0;
}

void Rd03d::loop() {
  uint32_t now = millis();
  if (_mode_left && (int32_t)(now - _next_mode_ms) >= 0) {
    sendMultiTarget();
    _mode_left--;
    _next_mode_ms = now + 500;
  }
  while (_serial.available() > 0) {
    push((uint8_t)_serial.read());
  }
}

bool Rd03d::takeFrame(RdTarget out[RD03_MAX_TARGETS]) {
  if (!_fresh) return false;
  memcpy(out, _tgt, sizeof(_tgt));
  _fresh = false;
  return true;
}
