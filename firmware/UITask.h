#pragma once

#include <helpers/ui/DisplayDriver.h>
#include <helpers/CommonCLI.h>
#include "MotionLogic.h"

class UITask {
  DisplayDriver* _display;
  unsigned long _next_read, _next_refresh;
  int _prevBtnState;
  bool _press_latched;
  NodePrefs* _node_prefs;
  MotionSnapshot _snap;
  const char* _channel_name;
  uint32_t _frames_ok;

  void renderCurrScreen();
  void drawRing(int cx, int cy, int r);
public:
  UITask(DisplayDriver& display)
    : _display(&display), _next_read(0), _next_refresh(0),
      _prevBtnState(HIGH), _press_latched(false), _node_prefs(nullptr),
      _channel_name("#senseMB"), _frames_ok(0) {
    memset(&_snap, 0, sizeof(_snap));
  }
  void begin(NodePrefs* node_prefs, const char* build_date, const char* firmware_version);
  void setSnapshot(const MotionSnapshot& snap, const char* channel_name, uint32_t frames_ok);
  bool consumeUserPress();
  void loop();
};
