#include "UITask.h"

#include <Arduino.h>
#include <stdio.h>
#include <string.h>

#ifndef USER_BTN_PRESSED
#define USER_BTN_PRESSED LOW
#endif

#ifndef RD03_RANGE_MM
#define RD03_RANGE_MM 8000
#endif

void UITask::begin(NodePrefs* node_prefs, const char* build_date, const char* firmware_version) {
  (void)build_date;
  (void)firmware_version;
  _prevBtnState = HIGH;
  _press_latched = false;
  _node_prefs = node_prefs;
  _display->turnOn();
}

void UITask::setSnapshot(const MotionSnapshot& snap, const char* channel_name, uint32_t frames_ok) {
  _snap = snap;
  _frames_ok = frames_ok;
  if (channel_name) _channel_name = channel_name;
}

bool UITask::consumeUserPress() {
  if (!_press_latched) return false;
  _press_latched = false;
  return true;
}

void UITask::drawRing(int cx, int cy, int r) {
  int x = r;
  int y = 0;
  int err = 1 - x;
  while (x >= y) {
    const int pts[8][2] = {
      {cx + x, cy + y}, {cx + y, cy + x}, {cx - y, cy + x}, {cx - x, cy + y},
      {cx - x, cy - y}, {cx - y, cy - x}, {cx + y, cy - x}, {cx + x, cy - y}
    };
    for (int i = 0; i < 8; i++) {
      int px = pts[i][0];
      int py = pts[i][1];
      if (px >= 70 && px < _display->width() && py >= 0 && py < _display->height()) {
        _display->fillRect(px, py, 1, 1);
      }
    }
    y++;
    if (err < 0) err += 2 * y + 1;
    else { x--; err += 2 * (y - x) + 1; }
  }
}

void UITask::renderCurrScreen() {
  char tmp[48];
  _display->setTextSize(1);
  _display->setColor(UIColor::primary_txt);

  const char* name = (_node_prefs && _node_prefs->node_name[0]) ? _node_prefs->node_name : "H4Motion";
  _display->setCursor(0, 0);
  _display->print(name);

  _display->setCursor(0, 14);
  if (_snap.banner[0]) {
    _display->setColor(UIColor::warning_txt);
    snprintf(tmp, sizeof(tmp), "%s", _snap.banner);
  } else {
    snprintf(tmp, sizeof(tmp), "n=%u %s", _snap.n, MotionLogic::stateName(_snap.st));
  }
  _display->print(tmp);
  _display->setColor(UIColor::primary_txt);

  _display->setCursor(0, 28);
  if (_snap.closest_mm >= 0) {
    int tenths = (_snap.closest_mm + 50) / 100;
    snprintf(tmp, sizeof(tmp), "%d.%dm az%d", tenths / 10, tenths % 10, (int)_snap.closest_az);
  } else {
    snprintf(tmp, sizeof(tmp), "clear");
  }
  _display->print(tmp);

  _display->setCursor(0, 42);
  snprintf(tmp, sizeof(tmp), "frm %lu", (unsigned long)_frames_ok);
  _display->print(tmp);

  _display->setCursor(0, 54);
  _display->print(_channel_name);

  const int cx = 100;
  const int cy = 32;
  const int radius = 28;
  _display->drawRect(72, 0, 55, 63);
  _display->fillRect(cx, 8, 1, 48);
  _display->fillRect(74, cy, 52, 1);
  for (int ring_m = 2; ring_m <= 8; ring_m += 2) {
    int r = (int)((ring_m * 1000L * radius) / RD03_RANGE_MM);
    if (r > 1) drawRing(cx, cy, r);
  }
  for (int i = 0; i < RD03_MAX_TARGETS; i++) {
    if (!_snap.tgt[i].valid) continue;
    int sx = cx + (int)(((int32_t)_snap.tgt[i].x_mm * radius) / RD03_RANGE_MM);
    int sy = cy - (int)(((int32_t)_snap.tgt[i].y_mm * radius) / RD03_RANGE_MM);
    if (sx < 73 || sy < 1 || sx > 124 || sy > 60) continue;
    _display->fillRect(sx - 1, sy - 1, 3, 3);
  }
}

void UITask::loop() {
#ifdef PIN_USER_BTN
  if (millis() >= _next_read) {
    int btnState = digitalRead(PIN_USER_BTN);
    if (btnState != _prevBtnState) {
      if (btnState == USER_BTN_PRESSED) _press_latched = true;
      _prevBtnState = btnState;
    }
    _next_read = millis() + 50;
  }
#endif
  if (_display->isOn() && millis() >= _next_refresh) {
    _display->startFrame();
    renderCurrScreen();
    _display->endFrame();
    _next_refresh = millis() + 200;
  }
}
