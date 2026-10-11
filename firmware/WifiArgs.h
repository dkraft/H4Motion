#pragma once

#include <stddef.h>
#include <stdint.h>
#include <string.h>

// Arguments for the private admin command `wifi`.
// An unquoted SSID is one token. The rest of the line is the passphrase,
// so a passphrase may contain spaces. Quote either field to allow spaces
// in the SSID. Quotes and control bytes are rejected.

struct WifiArgs {
  char ssid[33];
  char pass[64];
  bool status_only;
};

inline bool wifiTakeToken(char*& p, char* dest, size_t n) {
  while (*p == ' ') p++;
  size_t i = 0;
  if (*p == '"') {
    p++;
    while (*p && *p != '"') {
      if ((unsigned char)*p < 0x20 || i + 1 >= n) {
        dest[0] = 0;
        return false;
      }
      dest[i++] = *p++;
    }
    if (*p != '"') {
      dest[0] = 0;
      return false;
    }
    p++;
  } else {
    if (*p == 0) {
      dest[0] = 0;
      return false;
    }
    while (*p && *p != ' ') {
      if (*p == '"' || (unsigned char)*p < 0x20 || i + 1 >= n) {
        dest[0] = 0;
        return false;
      }
      dest[i++] = *p++;
    }
  }
  dest[i] = 0;
  // A quoted passphrase may be empty (open network). An unquoted token
  // already returned above when there was nothing to read.
  return true;
}

// `args` is the text after the `wifi` token. It is not modified.
inline bool parseWifiCommand(const char* args, WifiArgs* out) {
  out->ssid[0] = 0;
  out->pass[0] = 0;
  out->status_only = false;
  if (args == NULL) {
    out->status_only = true;
    return true;
  }
  char buf[128];
  size_t len = strlen(args);
  if (len >= sizeof(buf)) return false;
  memcpy(buf, args, len + 1);
  char* p = buf;
  while (*p == ' ') p++;
  if (*p == 0) {
    out->status_only = true;
    return true;
  }
  if (!wifiTakeToken(p, out->ssid, sizeof(out->ssid))) return false;
  if (out->ssid[0] == 0) return false;
  while (*p == ' ') p++;
  if (*p == 0) {
    out->pass[0] = 0;
    return true;
  }
  if (*p == '"') {
    if (!wifiTakeToken(p, out->pass, sizeof(out->pass))) return false;
    while (*p == ' ') p++;
    if (*p != 0) return false;
  } else {
    size_t i = 0;
    while (*p) {
      if (*p == '"' || (unsigned char)*p < 0x20 || i + 1 >= sizeof(out->pass)) return false;
      out->pass[i++] = *p++;
    }
    out->pass[i] = 0;
  }
  return true;
}
