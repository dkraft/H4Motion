#pragma once

#include <stddef.h>
#include <stdint.h>

class HardwareSerial {
public:
  explicit HardwareSerial(int port);
  void begin(uint32_t baud, uint32_t config, int rx, int tx);
  void setRxBufferSize(size_t) {}
  size_t write(const uint8_t* data, size_t len);
  int available();
  int read();
};
