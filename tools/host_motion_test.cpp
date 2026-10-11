// Host check for the RD-03D decoder and the occupancy machine.
// Compiles the firmware sources against tools/host_stubs. No board required.

#include "MotionLogic.h"
#include "Rd03d.h"
#include "WifiArgs.h"

#include <stdio.h>
#include <string.h>

static int g_fails = 0;

static void check(bool cond, const char* msg) {
  if (cond) {
    printf("ok %s\n", msg);
    return;
  }
  g_fails++;
  printf("FAIL %s\n", msg);
}

uint32_t millis() { return 0; }

HardwareSerial::HardwareSerial(int) {}
void HardwareSerial::begin(uint32_t, uint32_t, int, int) {}
size_t HardwareSerial::write(const uint8_t*, size_t n) { return n; }
int HardwareSerial::available() { return 0; }
int HardwareSerial::read() { return -1; }

static void feed(MotionLogic& logic, uint32_t now, int x, int y, int spd) {
  RdTarget t[RD03_MAX_TARGETS];
  memset(t, 0, sizeof(t));
  t[0].x_mm = (int16_t)x;
  t[0].y_mm = (int16_t)y;
  t[0].spd_cms = (int16_t)spd;
  t[0].valid = true;
  logic.observe(t, now);
}

static void feedEmpty(MotionLogic& logic, uint32_t now) {
  RdTarget t[RD03_MAX_TARGETS];
  memset(t, 0, sizeof(t));
  logic.observe(t, now);
}

static bool take(MotionLogic& logic, uint32_t now, char* buf, bool* alert) {
  buf[0] = 0;
  return logic.takeMessage(now, buf, 160, alert);
}

static bool takeFast(MotionLogic& logic, uint32_t now, char* buf, bool* alert) {
  buf[0] = 0;
  return logic.takeMessage(now, buf, 160, alert, true);
}

static void testDecode() {
  uint8_t frame[30];
  memset(frame, 0, sizeof(frame));
  frame[0] = 0xAA;
  frame[1] = 0xFF;
  frame[2] = 0x03;
  frame[3] = 0x00;
  frame[4] = 0x0E;
  frame[5] = 0x03;
  frame[6] = 0xB1;
  frame[7] = 0x86;
  frame[8] = 0x10;
  frame[9] = 0x00;
  frame[10] = 0x68;
  frame[11] = 0x01;
  frame[28] = 0x55;
  frame[29] = 0xCC;

  RdTarget out[RD03_MAX_TARGETS];
  check(Rd03d::decodeFrame(frame, out), "manual frame accepted");
  check(out[0].valid && out[0].x_mm == -782 && out[0].y_mm == 1713 &&
            out[0].spd_cms == -16 && out[0].pix_mm == 360,
        "manual target x=-782 y=1713 s=-16 pix=360");
  check(!out[1].valid && !out[2].valid, "empty slots are not targets");

  frame[8] = 0xF8;
  frame[9] = 0x00;
  check(Rd03d::decodeFrame(frame, out) && !out[0].valid, "speed 248 is not a target");
  frame[8] = 0x00;
  frame[9] = 0x01;
  check(Rd03d::decodeFrame(frame, out) && !out[0].valid, "speed 256 is not a target");

  frame[8] = 0x10;
  frame[9] = 0x00;
  frame[29] = 0x00;
  check(!Rd03d::decodeFrame(frame, out), "bad tail rejected");
  check(Rd03d::decodeSignMag(5, 0x80) == 5, "sign bit set is positive");
  check(Rd03d::decodeSignMag(5, 0x00) == -5, "sign bit clear is negative");
}

static void testOccupancy() {
  char buf[160];
  bool alert = false;

  MotionLogic one;
  one.begin(0);
  feed(one, 100, 0, 6000, -16);
  check(one.snap().st == ST_CLEAR, "one frame stays clear");
  check(!take(one, 1000, buf, &alert), "one frame sends nothing");

  feed(one, 200, 0, 6000, -16);
  check(!take(one, 200, buf, &alert), "enter waits for the air gap");
  check(take(one, 1100, buf, &alert), "enter is sent");
  check(alert && strcmp(buf, "AL ENTER d=6.0 az=0 x=0 y=6000 s=-16") == 0, buf);

  feed(one, 1200, 0, 2000, -10);
  check(!take(one, 1200, buf, &alert), "near waits for the air gap");
  check(take(one, 1900, buf, &alert), "near is sent");
  check(alert && strcmp(buf, "AL NEAR d=2.0 az=0 x=0 y=2000 s=-10") == 0, buf);

  // The manual example sits inside 2.5 m, so the appearance is NEAR rather than ENTER.
  MotionLogic inside;
  inside.begin(0);
  feed(inside, 100, -782, 1713, -16);
  feed(inside, 200, -782, 1713, -16);
  check(take(inside, 1000, buf, &alert) &&
            strcmp(buf, "AL NEAR d=1.9 az=-25 x=-782 y=1713 s=-16") == 0,
        buf);

  MotionLogic queued;
  queued.begin(10000);
  feed(queued, 10100, 0, 6000, -5);
  feed(queued, 10200, 0, 6000, -5);
  feed(queued, 10300, 0, 2000, -5);
  check(!take(queued, 10400, buf, &alert), "both alerts wait inside the air gap");
  check(take(queued, 10800, buf, &alert) &&
            strcmp(buf, "AL ENTER d=6.0 az=0 x=0 y=6000 s=-5") == 0,
        buf);
  check(take(queued, 11600, buf, &alert) &&
            strcmp(buf, "AL NEAR d=2.0 az=0 x=0 y=2000 s=-5") == 0,
        buf);

  MotionLogic left;
  left.begin(0);
  feed(left, 100, -782, 1713, -16);
  feed(left, 200, -782, 1713, -16);
  check(take(left, 1000, buf, &alert), "enter before empty leave");
  feedEmpty(left, 1900);
  check(take(left, 1900, buf, &alert), "empty frame leaves");
  check(alert && strcmp(buf, "AL LEAVE d=1.9 az=-25 x=-782 y=1713 s=-16") == 0, buf);

  MotionLogic quiet;
  quiet.begin(0);
  feed(quiet, 100, 0, 4000, -4);
  feed(quiet, 200, 0, 4000, -4);
  check(take(quiet, 1000, buf, &alert), "enter before silence leave");
  check(!take(quiet, 1701, buf, &alert), "silence leave waits for the air gap");
  check(take(quiet, 1800, buf, &alert), "silence leave is sent");
  check(alert && strcmp(buf, "AL LEAVE d=4.0 az=0 x=0 y=4000 s=-4") == 0, buf);

  MotionLogic heart;
  heart.begin(0);
  check(take(heart, 60000, buf, &alert) && !alert &&
            strcmp(buf, "RD n=0 st=CLEAR") == 0,
        buf);

  MotionLogic glitch;
  glitch.begin(0);
  feed(glitch, 100, 0, 5000, -3);
  feedEmpty(glitch, 200);
  feedEmpty(glitch, 2000);
  check(!take(glitch, 3000, buf, &alert), "one frame then empty does not alert");
  check(glitch.snap().st == ST_CLEAR, "glitch stays clear");

  MotionLogic stale;
  stale.begin(0);
  feed(stale, 100, 0, 5000, -3);
  feed(stale, 2000, 0, 5000, -3);
  feed(stale, 4000, 0, 5000, -3);
  check(stale.snap().st == ST_CLEAR, "frames more than 1.5 s apart do not enter");
  check(!take(stale, 4000, buf, &alert), "stale frames send no alert");

  MotionLogic hyst;
  hyst.begin(0);
  feed(hyst, 100, 0, 2000, -1);
  feed(hyst, 200, 0, 2000, -1);
  check(take(hyst, 1000, buf, &alert) &&
            strcmp(buf, "AL NEAR d=2.0 az=0 x=0 y=2000 s=-1") == 0,
        buf);
  feed(hyst, 1100, 0, 3001, 1);
  check(hyst.snap().st == ST_MOVE, "crossing 1.2x near returns to move");
  check(!take(hyst, 1800, buf, &alert), "unlatch sends no alert");
  feed(hyst, 3500, 0, 3001, 1);
  check(hyst.snap().st == ST_MOVE, "still moving outside the near zone");
  check(take(hyst, 4000, buf, &alert) && !alert &&
            strcmp(buf, "RD n=1 st=MOVE 0,3001,1") == 0,
        buf);

  MotionLogic overflow;
  overflow.begin(0);
  feed(overflow, 100, 0, 6000, -5);
  feed(overflow, 200, 0, 6000, -5);
  feed(overflow, 300, 0, 2000, -5);
  feedEmpty(overflow, 1900);
  feed(overflow, 2000, 0, 6000, -5);
  feed(overflow, 2100, 0, 6000, -5);
  feed(overflow, 2200, 0, 2000, -5);
  check(take(overflow, 2200, buf, &alert) &&
            strcmp(buf, "AL NEAR d=2.0 az=0 x=0 y=2000 s=-5") == 0,
        buf);
  check(take(overflow, 3000, buf, &alert) &&
            strcmp(buf, "AL LEAVE d=2.0 az=0 x=0 y=2000 s=-5") == 0,
        buf);
  check(take(overflow, 3800, buf, &alert) &&
            strcmp(buf, "AL ENTER d=6.0 az=0 x=0 y=6000 s=-5") == 0,
        buf);
  check(take(overflow, 4600, buf, &alert) &&
            strcmp(buf, "AL NEAR d=2.0 az=0 x=0 y=2000 s=-5") == 0,
        buf);
}

static void testFastTracks() {
  char buf[160];
  bool alert = false;
  MotionLogic logic;
  logic.begin(0);
  feed(logic, 100, 0, 6000, -16);
  feed(logic, 200, 0, 6000, -16);
  check(!takeFast(logic, 200, buf, &alert), "fast track holds the 333 ms pace");
  check(takeFast(logic, 400, buf, &alert) && !alert && strncmp(buf, "RD ", 3) == 0,
        "fast track can update while an alert waits for LoRa");
  check(takeFast(logic, 800, buf, &alert) && alert &&
            strcmp(buf, "AL ENTER d=6.0 az=0 x=0 y=6000 s=-16") == 0,
        buf);
  check(!takeFast(logic, 1000, buf, &alert), "fast track waits 333 ms");
  check(takeFast(logic, 1133, buf, &alert) && !alert && strncmp(buf, "RD ", 3) == 0,
        "fast track does not wait out the LoRa air gap");
  check(!takeFast(logic, 1233, buf, &alert), "second fast track still waits 333 ms");
}

static void testWifiArgs() {
  WifiArgs args;
  check(parseWifiCommand("", &args) && args.status_only, "bare wifi is status");
  check(parseWifiCommand("   ", &args) && args.status_only, "blank wifi is status");
  check(parseWifiCommand("home secret", &args) && strcmp(args.ssid, "home") == 0 &&
            strcmp(args.pass, "secret") == 0,
        "ssid token and passphrase");
  check(parseWifiCommand("home secret words", &args) && strcmp(args.pass, "secret words") == 0,
        "passphrase keeps spaces");
  check(parseWifiCommand("\"my net\" \"sec ret\"", &args) && strcmp(args.ssid, "my net") == 0 &&
            strcmp(args.pass, "sec ret") == 0,
        "quoted ssid and passphrase");
  check(parseWifiCommand("home", &args) && args.pass[0] == 0 && strcmp(args.ssid, "home") == 0,
        "open network has an empty passphrase");
  check(parseWifiCommand("home \"\"", &args) && strcmp(args.ssid, "home") == 0 && args.pass[0] == 0,
        "quoted empty passphrase is an open network");
  check(!parseWifiCommand("\"\"", &args), "empty ssid is rejected");
  check(!parseWifiCommand("\"noend", &args), "unclosed quote is rejected");
  char long_ssid[40];
  memset(long_ssid, 'a', 33);
  long_ssid[33] = 0;
  check(!parseWifiCommand(long_ssid, &args), "33-byte ssid is rejected");
  char ok_ssid[40];
  memset(ok_ssid, 'b', 32);
  ok_ssid[32] = 0;
  check(parseWifiCommand(ok_ssid, &args) && strlen(args.ssid) == 32, "32-byte ssid is accepted");
  char long_pass[80];
  memcpy(long_pass, "home ", 5);
  memset(long_pass + 5, 'c', 64);
  long_pass[69] = 0;
  check(!parseWifiCommand(long_pass, &args), "64-byte passphrase is rejected");
  long_pass[68] = 0;
  check(parseWifiCommand(long_pass, &args) && strlen(args.pass) == 63, "63-byte passphrase is accepted");
  check(!parseWifiCommand("home bad\"quote", &args), "quote in passphrase is rejected");
}

int main() {
  testDecode();
  testOccupancy();
  testFastTracks();
  testWifiArgs();
  if (g_fails) {
    printf("%d failed\n", g_fails);
    return 1;
  }
  printf("all motion checks passed\n");
  return 0;
}
