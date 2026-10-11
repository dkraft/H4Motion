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
  const char* _link_tag;
  uint32_t _frames_ok;
  bool _panel;

  void renderCurrScreen();
  void drawRing(int cx, int cy, int r);
public:
  UITask(DisplayDriver& display)
    : _display(&display), _next_read(0), _next_refresh(0),
      _prevBtnState(HIGH), _press_latched(false), _node_prefs(nullptr),
      _channel_name("#sensemb"), _link_tag("L"), _frames_ok(0), _panel(false) {
    memset(&_snap, 0, sizeof(_snap));
  }
  void begin(NodePrefs* node_prefs, const char* build_date, const char* firmware_version, bool panel);
  void setSnapshot(const MotionSnapshot& snap, const char* channel_name, uint32_t frames_ok, const char* link_tag);
  bool consumeUserPress();
  void loop();
};
