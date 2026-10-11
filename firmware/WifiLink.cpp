#include "WifiLink.h"

#include <WiFi.h>
#include <lwip/sockets.h>
#include <errno.h>

#include <stdio.h>
#include <string.h>

#undef write
#undef read
#undef close

static const uint32_t kJoinMs = 20000;
static const int kClients = 2;

struct WifiLink::Impl {
  WiFiServer server;
  WiFiClient clients[kClients];
  Impl() : server(WifiLink::kPort) {}
};

void WifiLink::begin() {
  _ssid[0] = 0;
  _pass[0] = 0;
  _ip[0] = 0;
  _state = IDLE;
  _started_ms = 0;
  _radio_up = false;
  _listening = false;
  _impl = new Impl();
}

bool WifiLink::setCreds(const char* ssid, const char* pass) {
  if (ssid == NULL || ssid[0] == 0 || strlen(ssid) > 32) return false;
  if (pass == NULL) pass = "";
  if (strlen(pass) > 63) return false;
  strncpy(_ssid, ssid, sizeof(_ssid) - 1);
  _ssid[sizeof(_ssid) - 1] = 0;
  strncpy(_pass, pass, sizeof(_pass) - 1);
  _pass[sizeof(_pass) - 1] = 0;
  return true;
}

void WifiLink::copyPass(char* dest, size_t n) const {
  if (n == 0) return;
  strncpy(dest, _pass, n - 1);
  dest[n - 1] = 0;
}

void WifiLink::connect() {
  if (_ssid[0] == 0) return;
  if (!_radio_up) {
    WiFi.persistent(false);
    WiFi.mode(WIFI_STA);
    WiFi.setAutoReconnect(true);
    WiFi.setSleep(false);
    _radio_up = true;
  }
  _state = CONNECTING;
  _ip[0] = 0;
  _started_ms = millis();
  WiFi.disconnect(false, false);
  WiFi.begin(_ssid, _pass);
}

void WifiLink::noteIp() {
  IPAddress a = WiFi.localIP();
  snprintf(_ip, sizeof(_ip), "%u.%u.%u.%u", a[0], a[1], a[2], a[3]);
}

void WifiLink::acceptClients() {
  if (_impl == NULL) return;
  if (!_listening) {
    _impl->server.begin();
    // begin() forces _noDelay false. Set it after, so later accepts
    // get TCP_NODELAY inside the server as well as below.
    _impl->server.setNoDelay(true);
    _listening = true;
  }
  for (int n = 0; n < 4; n++) {
    WiFiClient incoming = _impl->server.accept();
    if (!incoming) break;
    incoming.setNoDelay(true);
    int slot = -1;
    for (int i = 0; i < kClients; i++) {
      if (!_impl->clients[i] || !_impl->clients[i].connected()) {
        slot = i;
        break;
      }
    }
    if (slot < 0) {
      _impl->clients[0].stop();
      slot = 0;
    }
    _impl->clients[slot] = incoming;
  }
}

void WifiLink::loop() {
  if (_impl == NULL || _ssid[0] == 0 || _state == IDLE) return;
  wl_status_t st = WiFi.status();
  if (st == WL_CONNECTED) {
    if (_state != UP) {
      _state = UP;
      noteIp();
    }
    acceptClients();
    return;
  }
  if (_state == UP) {
    _state = CONNECTING;
    _ip[0] = 0;
    _started_ms = millis();
    return;
  }
  if (_state == CONNECTING && (uint32_t)(millis() - _started_ms) >= kJoinMs) {
    _state = FAIL;
  }
}

// Print::availableForWrite() is a stub that returns 0 on this core, and
// WiFiClient::write() waits up to a second, ten times, inside select().
// Either one would mute tracks or stall the mesh loop. Probe the socket
// with a zero timeout and send once without waiting.
static bool writeNow(WiFiClient& c, const uint8_t* buf, size_t n) {
  int sock = c.fd();
  if (sock < 0) return false;
  fd_set set;
  FD_ZERO(&set);
  FD_SET(sock, &set);
  struct timeval tv;
  tv.tv_sec = 0;
  tv.tv_usec = 0;
  if (select(sock + 1, NULL, &set, NULL, &tv) <= 0) return false;
  size_t off = 0;
  while (off < n) {
    int res = send(sock, buf + off, n - off, MSG_DONTWAIT);
    if (res > 0) {
      off += (size_t)res;
      continue;
    }
    // A torn line would glue to the next one. Drop the client.
    c.stop();
    return false;
  }
  return true;
}

bool WifiLink::sendLine(const char* line) {
  if (_impl == NULL || line == NULL || _state != UP) return false;
  size_t n = strlen(line);
  if (n == 0 || n >= 160) return false;
  uint8_t buf[160];
  memcpy(buf, line, n);
  buf[n++] = '\n';
  bool sent = false;
  for (int i = 0; i < kClients; i++) {
    WiFiClient& c = _impl->clients[i];
    if (!c || !c.connected()) continue;
    if (writeNow(c, buf, n)) sent = true;
  }
  return sent;
}
