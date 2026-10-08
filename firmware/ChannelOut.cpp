#include "ChannelOut.h"

#include <Arduino.h>
#include <Utils.h>
#include <string.h>
#include <helpers/TxtDataHelpers.h>

#ifndef MAX_TEXT_LEN
#define MAX_TEXT_LEN  (10 * CIPHER_BLOCK_SIZE)
#endif

static int hexVal(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

static bool parseHex(const char* hex, uint8_t* dest, int dest_len) {
  for (int i = 0; i < dest_len; i++) {
    int hi = hexVal(hex[i * 2]);
    int lo = hexVal(hex[i * 2 + 1]);
    if (hi < 0 || lo < 0) return false;
    dest[i] = (uint8_t)((hi << 4) | lo);
  }
  return true;
}

bool ChannelOut::beginHashtag(const char* name) {
  memset(&_ch, 0, sizeof(_ch));
  strncpy(_name, name, sizeof(_name) - 1);
  _name[sizeof(_name) - 1] = 0;
  uint8_t digest[32];
  mesh::Utils::sha256(digest, 32, (const uint8_t*)name, (int)strlen(name));
  memcpy(_ch.secret, digest, 16);
  mesh::Utils::sha256(_ch.hash, PATH_HASH_SIZE, _ch.secret, 16);
  Serial.printf("Channel %s key=", _name);
  for (int i = 0; i < 16; i++) Serial.printf("%02x", _ch.secret[i]);
  Serial.println();
  return true;
}

bool ChannelOut::beginPskHex(const char* hex) {
  memset(&_ch, 0, sizeof(_ch));
  int n = (int)strlen(hex);
  int key_len = 0;
  if (n == 32) key_len = 16;
  else if (n == 64) key_len = 32;
  else return false;
  if (!parseHex(hex, _ch.secret, key_len)) return false;
  mesh::Utils::sha256(_ch.hash, PATH_HASH_SIZE, _ch.secret, key_len);
  strncpy(_name, "psk", sizeof(_name) - 1);
  return true;
}

bool ChannelOut::sendText(mesh::Mesh& mesh, mesh::RTCClock& rtc, const char* node_name, const char* text) {
  uint8_t temp[5 + MAX_TEXT_LEN + 32];
  uint32_t timestamp = rtc.getCurrentTimeUnique();
  memcpy(temp, &timestamp, 4);
  temp[4] = TXT_TYPE_PLAIN;

  int max_body = MAX_TEXT_LEN;
  int prefix = snprintf((char*)&temp[5], max_body, "%s: ", node_name ? node_name : "H4Motion");
  if (prefix < 0) return false;
  if (prefix >= max_body) prefix = max_body - 1;

  int body_room = max_body - prefix;
  int text_len = (int)strlen(text);
  if (text_len > body_room) text_len = body_room;
  memcpy(&temp[5 + prefix], text, text_len);
  temp[5 + prefix + text_len] = 0;

  mesh::Packet* pkt = mesh.createGroupDatagram(PAYLOAD_TYPE_GRP_TXT, _ch, temp, 5 + prefix + text_len);
  if (!pkt) return false;
  mesh.sendFlood(pkt);
  return true;
}
