#pragma once

#include <Mesh.h>

class ChannelOut {
public:
  bool beginHashtag(const char* name);
  bool beginPskHex(const char* hex16_or_32);
  bool sendText(mesh::Mesh& mesh, mesh::RTCClock& rtc, const char* node_name, const char* text);
  const char* name() const { return _name; }

private:
  mesh::GroupChannel _ch;
  char _name[32];
};
