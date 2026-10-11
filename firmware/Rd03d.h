#pragma once

#include <Arduino.h>
#include <HardwareSerial.h>

// Ai-Thinker RD-03D multi-target frame (user manual table 5-1 / 5-2).
// 256000 8N1. Header AA FF 03 00, three targets, tail 55 CC.
// X/Y/speed are sign-magnitude int16: bit 15 set = positive, clear = negative.
// X/Y unit mm. Speed unit cm/s. Approaching the radar is negative.
// Coordinate frame: +Y straight ahead, +X to the right of boresight.

#ifndef RD03_RX_PIN
#define RD03_RX_PIN 47
#endif
#ifndef RD03_TX_PIN
#define RD03_TX_PIN 48
#endif
#ifndef RD03_BAUD
#define RD03_BAUD 256000
#endif

static const int RD03_MAX_TARGETS = 3;

struct RdTarget {
  int16_t x_mm;
  int16_t y_mm;
  int16_t spd_cms;
  uint16_t pix_mm;
  bool valid;
};

class Rd03d {
public:
  void begin();
  void loop();
  // True once per parsed frame. Targets are copied into out[RD03_MAX_TARGETS].
  bool takeFrame(RdTarget out[RD03_MAX_TARGETS]);
  uint32_t framesOk() const { return _ok; }
  uint32_t framesBad() const { return _bad; }
  uint32_t bytesRx() const { return _rx; }
  // Last bytes received, oldest first. Returns the count written.
  int lastBytes(uint8_t* out, int max_n) const;

  static int16_t decodeSignMag(uint8_t lo, uint8_t hi);
  static bool decodeFrame(const uint8_t* frame30, RdTarget out[RD03_MAX_TARGETS]);

private:
  void sendMultiTarget();
  void push(uint8_t b);

  HardwareSerial _serial{2};
  uint8_t _buf[30];
  uint8_t _got = 0;
  uint32_t _next_mode_ms = 0;
  uint32_t _ok = 0;
  uint32_t _bad = 0;
  uint32_t _rx = 0;
  uint8_t _tail[8];
  uint32_t _tail_n = 0;
  bool _fresh = false;
  RdTarget _tgt[RD03_MAX_TARGETS];
};
