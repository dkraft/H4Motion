#pragma once

#include <stddef.h>
#include <stdint.h>
#include "Rd03d.h"

#ifndef RD03_NEAR_MM
#define RD03_NEAR_MM 2500
#endif
#ifndef RD03_TRACK_SECS
#define RD03_TRACK_SECS 3
#endif
#ifndef RD03_HEART_SECS
#define RD03_HEART_SECS 60
#endif

// Wire text on MeshCore channel #sensemb (no node-name prefix; ChannelOut adds that):
//   RD n=<0-3> st=<CLEAR|MOVE|ENTER|LEAVE|NEAR> <x_mm>,<y_mm>,<spd_cm_s> ...
//   AL <ENTER|LEAVE|NEAR> d=<m.m> az=<deg> x=<mm> y=<mm> s=<cm/s>
// +Y is straight ahead, +X is to the right. az 0 is ahead, positive is right.
// Negative speed is motion toward the radar.

enum TrackState : uint8_t {
  ST_CLEAR = 0,
  ST_MOVE,
  ST_ENTER,
  ST_LEAVE,
  ST_NEAR
};

struct MotionSnapshot {
  RdTarget tgt[RD03_MAX_TARGETS];
  uint8_t n;
  TrackState st;
  int16_t closest_mm;
  int16_t closest_az;
  int16_t closest_x;
  int16_t closest_y;
  int16_t closest_s;
  char banner[8];
};

class MotionLogic {
public:
  void begin(uint32_t now_ms);
  void observe(const RdTarget t[RD03_MAX_TARGETS], uint32_t now_ms);
  void tick(uint32_t now_ms);
  void forceReport();
  // Copies one outbound line. Alerts go first and always honor the LoRa air gap.
  // fast_tracks sends RD lines about 10 times a second and does not spend that
  // air gap, for a local socket. The default keeps the mesh pacing.
  bool takeMessage(uint32_t now_ms, char* out, size_t out_len, bool* is_alert, bool fast_tracks = false);

  void setNearMm(int mm);
  void setTrackSecs(int secs);
  void setHeartSecs(int secs);
  int nearMm() const { return _near_mm; }
  int trackSecs() const { return (int)(_track_ms / 1000u); }
  int heartSecs() const { return (int)(_heart_ms / 1000u); }
  const MotionSnapshot& snap() const { return _snap; }

  static const char* stateName(TrackState st);

private:
  void considerEdges(uint32_t now_ms);
  void queueAlert(const char* kind, uint32_t now_ms);
  void formatRd(char* out, size_t n, TrackState st) const;
  void recomputeClosest();

  MotionSnapshot _snap;
  bool _occupied = false;
  bool _near_latched = false;
  uint8_t _raw_hits = 0;
  uint32_t _last_raw_ms = 0;
  uint32_t _last_track_ms = 0;
  uint32_t _last_heart_ms = 0;
  uint32_t _last_tx_ms = 0;
  uint32_t _banner_until = 0;
  uint32_t _track_ms = RD03_TRACK_SECS * 1000u;
  uint32_t _heart_ms = RD03_HEART_SECS * 1000u;
  int _near_mm = RD03_NEAR_MM;
  bool _force = false;
  bool _have_hold = false;
  int16_t _hold_mm = 0;
  int16_t _hold_az = 0;
  int16_t _hold_x = 0;
  int16_t _hold_y = 0;
  int16_t _hold_s = 0;
  static const int kAlertQ = 4;
  char _aq[kAlertQ][96];
  uint8_t _aq_n = 0;
};
