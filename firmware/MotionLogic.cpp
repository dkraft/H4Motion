#include "MotionLogic.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

static const uint32_t kAirGapMs = 800;
static const uint32_t kFastTrackMs = 100;
static const uint32_t kLeaveMs = 1500;
static const uint32_t kBannerMs = 4000;

const char* MotionLogic::stateName(TrackState st) {
  switch (st) {
    case ST_MOVE: return "MOVE";
    case ST_ENTER: return "ENTER";
    case ST_LEAVE: return "LEAVE";
    case ST_NEAR: return "NEAR";
    default: return "CLEAR";
  }
}

void MotionLogic::begin(uint32_t now_ms) {
  memset(&_snap, 0, sizeof(_snap));
  _snap.closest_mm = -1;
  _snap.st = ST_CLEAR;
  _occupied = false;
  _near_latched = false;
  _raw_hits = 0;
  _last_raw_ms = now_ms;
  _last_track_ms = now_ms;
  _last_heart_ms = now_ms;
  _last_tx_ms = now_ms;
  _banner_until = 0;
  _force = false;
  _have_hold = false;
  _aq_n = 0;
}

void MotionLogic::setNearMm(int mm) {
  if (mm < 300) mm = 300;
  if (mm > 20000) mm = 20000;
  _near_mm = mm;
}

void MotionLogic::setTrackSecs(int secs) {
  if (secs < 2) secs = 2;
  if (secs > 120) secs = 120;
  _track_ms = (uint32_t)secs * 1000u;
}

void MotionLogic::setHeartSecs(int secs) {
  if (secs < 15) secs = 15;
  if (secs > 3600) secs = 3600;
  _heart_ms = (uint32_t)secs * 1000u;
}

void MotionLogic::forceReport() { _force = true; }

void MotionLogic::recomputeClosest() {
  _snap.n = 0;
  _snap.closest_mm = -1;
  _snap.closest_az = 0;
  _snap.closest_x = 0;
  _snap.closest_y = 0;
  _snap.closest_s = 0;
  uint32_t best = 0xFFFFFFFFu;
  for (int i = 0; i < RD03_MAX_TARGETS; i++) {
    if (!_snap.tgt[i].valid) continue;
    _snap.n++;
    int32_t x = _snap.tgt[i].x_mm;
    int32_t y = _snap.tgt[i].y_mm;
    uint32_t d2 = (uint32_t)(x * x + y * y);
    if (d2 < best) {
      best = d2;
      float dist = sqrtf((float)d2);
      if (dist > 32767.0f) dist = 32767.0f;
      _snap.closest_mm = (int16_t)(dist + 0.5f);
      float az = atan2f((float)x, (float)y) * (180.0f / 3.14159265f);
      if (az >= 0) _snap.closest_az = (int16_t)(az + 0.5f);
      else _snap.closest_az = (int16_t)(az - 0.5f);
      _snap.closest_x = _snap.tgt[i].x_mm;
      _snap.closest_y = _snap.tgt[i].y_mm;
      _snap.closest_s = _snap.tgt[i].spd_cms;
    }
  }
  if (_snap.n > 0) {
    _have_hold = true;
    _hold_mm = _snap.closest_mm;
    _hold_az = _snap.closest_az;
    _hold_x = _snap.closest_x;
    _hold_y = _snap.closest_y;
    _hold_s = _snap.closest_s;
  }
}

void MotionLogic::queueAlert(const char* kind, uint32_t now_ms) {
  int mm = _snap.closest_mm;
  int az = _snap.closest_az;
  int x = _snap.closest_x;
  int y = _snap.closest_y;
  int s = _snap.closest_s;
  if (mm < 0 && _have_hold) {
    mm = _hold_mm;
    az = _hold_az;
    x = _hold_x;
    y = _hold_y;
    s = _hold_s;
  }
  if (mm < 0) mm = 0;
  int tenths = (mm + 50) / 100;
  if (tenths < 0) tenths = 0;
  if (_aq_n >= kAlertQ) {
    memmove(_aq[0], _aq[1], sizeof(_aq[0]) * (kAlertQ - 1));
    _aq_n = kAlertQ - 1;
  }
  snprintf(_aq[_aq_n], sizeof(_aq[0]),
           "AL %s d=%d.%d az=%d x=%d y=%d s=%d",
           kind, tenths / 10, tenths % 10, az, x, y, s);
  _aq_n++;
  strncpy(_snap.banner, kind, sizeof(_snap.banner) - 1);
  _snap.banner[sizeof(_snap.banner) - 1] = 0;
  _banner_until = now_ms + kBannerMs;
}

void MotionLogic::formatRd(char* out, size_t n, TrackState st) const {
  int used = snprintf(out, n, "RD n=%u st=%s", _snap.n, stateName(st));
  if (used < 0 || (size_t)used >= n) return;
  for (int i = 0; i < RD03_MAX_TARGETS; i++) {
    if (!_snap.tgt[i].valid) continue;
    int w = snprintf(out + used, n - (size_t)used, " %d,%d,%d",
                     (int)_snap.tgt[i].x_mm, (int)_snap.tgt[i].y_mm,
                     (int)_snap.tgt[i].spd_cms);
    if (w < 0 || (size_t)used + (size_t)w >= n) break;
    used += w;
  }
}

void MotionLogic::considerEdges(uint32_t now_ms) {
  bool raw = _snap.n > 0;
  if (raw) {
    _last_raw_ms = now_ms;
    if (_raw_hits < 250) _raw_hits++;
  } else {
    _raw_hits = 0;
  }

  bool want = _occupied;
  if (!_occupied && _raw_hits >= 2) want = true;
  if (_occupied && !raw && (uint32_t)(now_ms - _last_raw_ms) > kLeaveMs) want = false;

  if (want && !_occupied) {
    _occupied = true;
    _near_latched = false;
    if (_snap.closest_mm >= 0 && _snap.closest_mm <= _near_mm) {
      _near_latched = true;
      queueAlert("NEAR", now_ms);
    } else {
      queueAlert("ENTER", now_ms);
    }
  } else if (!want && _occupied) {
    _occupied = false;
    _near_latched = false;
    queueAlert("LEAVE", now_ms);
  } else if (_occupied && _snap.closest_mm >= 0 && _snap.closest_mm <= _near_mm && !_near_latched) {
    _near_latched = true;
    queueAlert("NEAR", now_ms);
  }

  if (_occupied && _near_latched && _snap.closest_mm > (_near_mm * 6) / 5) {
    _near_latched = false;
  }

  if (_banner_until && (int32_t)(now_ms - _banner_until) >= 0 && _aq_n == 0) {
    _snap.banner[0] = 0;
    _banner_until = 0;
  }
  if (_occupied) _snap.st = _near_latched ? ST_NEAR : ST_MOVE;
  else _snap.st = ST_CLEAR;
}

void MotionLogic::observe(const RdTarget t[RD03_MAX_TARGETS], uint32_t now_ms) {
  // A lone frame must not pair with another frame after the zone has gone quiet.
  if (!_occupied && _raw_hits > 0 && (uint32_t)(now_ms - _last_raw_ms) > kLeaveMs) {
    _raw_hits = 0;
  }
  memcpy(_snap.tgt, t, sizeof(_snap.tgt));
  recomputeClosest();
  considerEdges(now_ms);
}

void MotionLogic::tick(uint32_t now_ms) {
  // Radar silence (no frames at all) still has to drop occupancy.
  if (_occupied && (uint32_t)(now_ms - _last_raw_ms) > kLeaveMs) {
    memset(_snap.tgt, 0, sizeof(_snap.tgt));
    _snap.n = 0;
    _snap.closest_mm = -1;
    _raw_hits = 0;
    _occupied = false;
    _near_latched = false;
    queueAlert("LEAVE", now_ms);
    _snap.st = ST_CLEAR;
  }
  if (_banner_until && (int32_t)(now_ms - _banner_until) >= 0 && _aq_n == 0) {
    _snap.banner[0] = 0;
    _banner_until = 0;
  }
}

bool MotionLogic::takeMessage(uint32_t now_ms, char* out, size_t out_len, bool* is_alert, bool fast_tracks) {
  tick(now_ms);
  if (_banner_until && (int32_t)(now_ms - _banner_until) >= 0 && _aq_n == 0) {
    _snap.banner[0] = 0;
    _banner_until = 0;
  }

  const bool air_open = (uint32_t)(now_ms - _last_tx_ms) >= kAirGapMs;
  if (_aq_n > 0) {
    if (!air_open) {
      if (!fast_tracks) return false;
    } else {
      snprintf(out, out_len, "%s", _aq[0]);
      memmove(_aq[0], _aq[1], sizeof(_aq[0]) * (_aq_n - 1));
      _aq_n--;
      _last_tx_ms = now_ms;
      _last_track_ms = now_ms;
      if (is_alert) *is_alert = true;
      return true;
    }
  } else if (!fast_tracks && !air_open) {
    return false;
  }

  const uint32_t track_gap = fast_tracks ? kFastTrackMs : _track_ms;
  if (_force || (_occupied && (uint32_t)(now_ms - _last_track_ms) >= track_gap)) {
    TrackState st = _occupied ? (_near_latched ? ST_NEAR : ST_MOVE) : ST_CLEAR;
    formatRd(out, out_len, st);
    _force = false;
    _last_track_ms = now_ms;
    if (!fast_tracks) _last_tx_ms = now_ms;
    if (!_occupied) _last_heart_ms = now_ms;
    if (is_alert) *is_alert = false;
    return true;
  }

  if (!_occupied && (uint32_t)(now_ms - _last_heart_ms) >= _heart_ms) {
    formatRd(out, out_len, ST_CLEAR);
    _last_heart_ms = now_ms;
    if (!fast_tracks) _last_tx_ms = now_ms;
    if (is_alert) *is_alert = false;
    return true;
  }
  return false;
}
