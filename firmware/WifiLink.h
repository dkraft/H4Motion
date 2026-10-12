#pragma once

#include <stddef.h>
#include <stdint.h>

// STA join plus a small TCP fanout of the same AL/RD lines the mesh carries.
// Joining does not block the mesh loop. The passphrase is kept for reconnect
// and is never copied into a log, an OLED line, or a channel send.

class WifiLink {
public:
  static const uint16_t kPort = 4040;

  void begin();
  bool setCreds(const char* ssid, const char* pass);
  void clearCreds();
  bool hasCreds() const { return _ssid[0] != 0; }
  const char* ssid() const { return _ssid; }
  void copyPass(char* dest, size_t n) const;
  // Starts a join. Returns immediately.
  void connect();
  void loop();

  bool connecting() const { return _state == CONNECTING; }
  bool connected() const { return _state == UP; }
  bool failed() const { return _state == FAIL; }
  const char* ip() const { return _ip; }
  // Writes one line and a newline to each connected client.
  // A full TCP buffer is skipped so a stuck phone cannot stall LoRa.
  bool sendLine(const char* line);

private:
  enum State : uint8_t { IDLE, CONNECTING, UP, FAIL };

  void acceptClients();
  void noteIp();
  void dropClients();

  char _ssid[33];
  char _pass[64];
  char _ip[16];
  State _state;
  uint32_t _started_ms;
  bool _radio_up;
  bool _listening;

  // Owned in the .cpp so this header does not pull in the ESP32 WiFi stack.
  struct Impl;
  Impl* _impl;
};
