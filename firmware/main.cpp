#include "SensorMesh.h"
#include "Rd03d.h"
#include "MotionLogic.h"
#include "ChannelOut.h"
#include "WifiArgs.h"
#include "WifiLink.h"

#include <mbedtls/sha256.h>

#ifdef DISPLAY_CLASS
  #include "UITask.h"
  static UITask ui_task(display);
#endif

#ifndef SENSE_CHANNEL_NAME
#define SENSE_CHANNEL_NAME "#sensemb"
#endif

static Rd03d radar;
static MotionLogic motion;
static ChannelOut channel;
static WifiLink wifi;
static bool want_wifi = false;
static const char* kPrefsPath = "/h4motion.txt";


static const char* linkTag() {
  if (want_wifi && wifi.connected()) return "W";
  if (want_wifi) return "w";
  return "L";
}

// SHA-256 of the passphrase that used to be filled in automatically.
// Compared by hash so that text is not stored in the image.
static bool retiredWifiPass(const char* pass) {
  static const uint8_t kOld[32] = {
    0xd1, 0x19, 0x4a, 0x3f, 0x12, 0xf8, 0x99, 0x15,
    0x20, 0x9b, 0xe4, 0xe1, 0xe8, 0x97, 0x2a, 0xea,
    0x4b, 0xa9, 0xfe, 0x4b, 0x0d, 0xcc, 0x9e, 0x3b,
    0xd9, 0x86, 0xa1, 0xd9, 0x86, 0x9c, 0x85, 0xec
  };
  if (pass == NULL || pass[0] == 0) return false;
  uint8_t dig[32];
  if (mbedtls_sha256_ret((const unsigned char*)pass, strlen(pass), dig, 0) != 0) return false;
  return memcmp(dig, kOld, 32) == 0;
}

static void replyWifiStatus(char* reply) {
  if (!wifi.hasCreds()) strcpy(reply, "wifi off");
  else if (wifi.connected()) snprintf(reply, 48, "wifi %s", wifi.ip());
  else if (wifi.connecting()) strcpy(reply, "wifi joining");
  else if (wifi.failed()) strcpy(reply, "wifi fail");
  else strcpy(reply, "wifi off");
}

static void replyLinkStatus(char* reply) {
  if (want_wifi && wifi.connected()) snprintf(reply, 48, "link wifi %s", wifi.ip());
  else if (want_wifi && wifi.connecting()) strcpy(reply, "link wait");
  else if (want_wifi && wifi.failed()) snprintf(reply, 48, "wifi fail %s", wifi.ssid());
  else if (wifi.connected()) snprintf(reply, 48, "link lora %s", wifi.ip());
  else strcpy(reply, "link lora");
}

static void savePrefs() {
  File f = SPIFFS.open(kPrefsPath, "w");
  if (!f) return;
  char pass[64];
  wifi.copyPass(pass, sizeof(pass));
  f.printf("interval %d\n", motion.trackSecs());
  f.printf("near %d\n", motion.nearMm());
  f.printf("heart %d\n", motion.heartSecs());
  f.printf("ssid %s\n", wifi.ssid());
  f.printf("pass %s\n", pass);
  f.printf("link %s\n", want_wifi ? "wifi" : "lora");
  f.close();
  memset(pass, 0, sizeof(pass));
}

static void loadPrefs() {
  File f = SPIFFS.open(kPrefsPath, "r");
  if (!f) return;
  char buf[96];
  char ssid[33];
  char pass[64];
  bool saw_ssid = false;
  ssid[0] = 0;
  pass[0] = 0;
  while (f.available()) {
    int n = f.readBytesUntil('\n', buf, sizeof(buf) - 1);
    if (n <= 0) continue;
    buf[n] = 0;
    if (buf[n - 1] == '\r') buf[n - 1] = 0;
    if (n == (int)sizeof(buf) - 1) {
      while (f.available()) {
        int c = f.read();
        if (c < 0 || c == '\n') break;
      }
      continue;
    }
    if (memcmp(buf, "interval ", 9) == 0) motion.setTrackSecs(atoi(buf + 9));
    else if (memcmp(buf, "near ", 5) == 0) motion.setNearMm(atoi(buf + 5));
    else if (memcmp(buf, "heart ", 6) == 0) motion.setHeartSecs(atoi(buf + 6));
    else if (memcmp(buf, "ssid ", 5) == 0) {
      strncpy(ssid, buf + 5, sizeof(ssid) - 1);
      ssid[sizeof(ssid) - 1] = 0;
      saw_ssid = true;
    } else if (memcmp(buf, "pass ", 5) == 0) {
      strncpy(pass, buf + 5, sizeof(pass) - 1);
      pass[sizeof(pass) - 1] = 0;
    } else if (strcmp(buf, "link wifi") == 0) {
      want_wifi = true;
    } else if (strcmp(buf, "link lora") == 0) {
      want_wifi = false;
    }
  }
  f.close();
  if (saw_ssid && ssid[0]) wifi.setCreds(ssid, pass);
  memset(pass, 0, sizeof(pass));
}

class MyMesh : public SensorMesh {
public:
  MyMesh(mesh::MainBoard& board, mesh::Radio& radio, mesh::MillisecondClock& ms, mesh::RNG& rng, mesh::RTCClock& rtc, mesh::MeshTables& tables)
     : SensorMesh(board, radio, ms, rng, rtc, tables),
       battery_data(12 * 24, 5 * 60)
  {
  }

protected:
  Trigger low_batt, critical_batt;
  TimeSeriesData battery_data;

  void onSensorDataRead() override {
    float batt_voltage = getVoltage(TELEM_CHANNEL_SELF);
    battery_data.recordData(getRTCClock(), batt_voltage);
    alertIf(batt_voltage < 3.4f, critical_batt, HIGH_PRI_ALERT, "Battery is critical!");
    alertIf(batt_voltage < 3.6f, low_batt, LOW_PRI_ALERT, "Battery is low");
  }

  int querySeriesData(uint32_t start_secs_ago, uint32_t end_secs_ago, MinMaxAvg dest[], int max_num) override {
    (void)max_num;
    battery_data.calcMinMaxAvg(getRTCClock(), start_secs_ago, end_secs_ago, &dest[0], TELEM_CHANNEL_SELF, LPP_VOLTAGE);
    return 1;
  }

  bool handleCustomCommand(uint32_t sender_timestamp, char* command, char* reply) override {
    (void)sender_timestamp;
    const MotionSnapshot& s = motion.snap();
    if (strcmp(command, "rd") == 0) {
      int tenths = s.closest_mm < 0 ? 0 : (s.closest_mm + 50) / 100;
      uint8_t raw[8];
      int raw_n = radar.lastBytes(raw, 8);
      char hex[8 * 3 + 1];
      int hp = 0;
      for (int i = 0; i < raw_n && hp < (int)sizeof(hex) - 3; i++) {
        hp += sprintf(hex + hp, "%02x ", raw[i]);
      }
      hex[hp] = 0;
      sprintf(reply, "n=%u %s d=%d.%d near=%d int=%ds heart=%ds ok=%lu bad=%lu rx=%lu %s%s",
              s.n, MotionLogic::stateName(s.st),
              tenths / 10, tenths % 10,
              motion.nearMm(), motion.trackSecs(), motion.heartSecs(),
              (unsigned long)radar.framesOk(), (unsigned long)radar.framesBad(),
              (unsigned long)radar.bytesRx(), hex, linkTag());
      return true;
    }
    if (strcmp(command, "wifi") == 0 || memcmp(command, "wifi ", 5) == 0) {
      WifiArgs args;
      const char* rest = (command[4] == 0) ? "" : command + 5;
      if (!parseWifiCommand(rest, &args)) {
        strcpy(reply, "wifi bad");
        return true;
      }
      if (args.status_only) {
        replyWifiStatus(reply);
        return true;
      }
      // A short or retired passphrase must not replace one that is already stored.
      if (!wifiPassUsable(args.pass) || retiredWifiPass(args.pass)) {
        memset(args.pass, 0, sizeof(args.pass));
        strcpy(reply, "wifi bad");
        return true;
      }
      if (!wifi.setCreds(args.ssid, args.pass)) {
        strcpy(reply, "wifi bad");
        return true;
      }
      memset(args.pass, 0, sizeof(args.pass));
      savePrefs();
      wifi.connect();
      strcpy(reply, "wifi joining");
      return true;
    }
    if (strcmp(command, "link") == 0) {
      replyLinkStatus(reply);
      return true;
    }
    if (strcmp(command, "link lora") == 0) {
      want_wifi = false;
      savePrefs();
      replyLinkStatus(reply);
      return true;
    }
    if (strcmp(command, "link wifi") == 0) {
      if (!wifi.hasCreds()) {
        want_wifi = false;
        savePrefs();
        strcpy(reply, "wifi fail");
        return true;
      }
      // A failed join used to save "link lora" here, so tracks stayed on LoRa
      // across reboots. Keep the request and start the join again.
      want_wifi = true;
      savePrefs();
      if (wifi.failed() || (!wifi.connected() && !wifi.connecting())) wifi.connect();
      replyLinkStatus(reply);
      return true;
    }
    if (strcmp(command, "rd report") == 0) {
      motion.forceReport();
      strcpy(reply, "report queued");
      return true;
    }
    if (strcmp(command, "rd interval") == 0) {
      sprintf(reply, "interval=%d", motion.trackSecs());
      return true;
    }
    if (memcmp(command, "rd interval ", 12) == 0) {
      motion.setTrackSecs(atoi(command + 12));
      savePrefs();
      sprintf(reply, "interval=%d", motion.trackSecs());
      return true;
    }
    if (strcmp(command, "rd near") == 0) {
      sprintf(reply, "near=%d", motion.nearMm());
      return true;
    }
    if (memcmp(command, "rd near ", 8) == 0) {
      motion.setNearMm(atoi(command + 8));
      savePrefs();
      sprintf(reply, "near=%d", motion.nearMm());
      return true;
    }
    if (strcmp(command, "rd heart") == 0) {
      sprintf(reply, "heart=%d", motion.heartSecs());
      return true;
    }
    if (memcmp(command, "rd heart ", 9) == 0) {
      motion.setHeartSecs(atoi(command + 9));
      savePrefs();
      sprintf(reply, "heart=%d", motion.heartSecs());
      return true;
    }
    return false;
  }
};

StdRNG fast_rng;
SimpleMeshTables tables;
MyMesh the_mesh(board, radio_driver, *new ArduinoMillis(), fast_rng, rtc_clock, tables);

void halt() {
  while (1) ;
}

static char command[160];
static bool command_overflow = false;

void setup() {
  Serial.begin(115200);
  delay(1000);

  board.begin();

#ifdef HAS_EXTERNAL_WATCHDOG
  external_watchdog.begin();
#endif

#ifdef DISPLAY_CLASS
  bool display_ok = display.begin();
  if (display_ok) {
    display.startFrame();
    display.print("H4Motion...");
    display.endFrame();
  }
#endif

  if (!radio_init()) { halt(); }

  fast_rng.begin(radio_driver.getRngSeed());

  FILESYSTEM* fs;
#if defined(ESP32)
  SPIFFS.begin(true);
  fs = &SPIFFS;
  IdentityStore store(SPIFFS, "/identity");
#else
  #error "H4Motion targets the Heltec V4"
#endif
  if (!store.load("_main", the_mesh.self_id)) {
    MESH_DEBUG_PRINTLN("Generating new keypair");
    the_mesh.self_id = radio_new_identity();
    int count = 0;
    while (count < 10 && (the_mesh.self_id.pub_key[0] == 0x00 || the_mesh.self_id.pub_key[0] == 0xFF)) {
      the_mesh.self_id = radio_new_identity();
      count++;
    }
    store.save("_main", the_mesh.self_id);
  }

  Serial.print("Sensor ID: ");
  mesh::Utils::printHex(Serial, the_mesh.self_id.pub_key, PUB_KEY_SIZE);
  Serial.println();

  command[0] = 0;
  sensors.begin();
  the_mesh.begin(fs);

#ifdef DISPLAY_CLASS
  ui_task.begin(the_mesh.getNodePrefs(), FIRMWARE_BUILD_DATE, FIRMWARE_VERSION, display_ok);
#endif

  motion.begin(millis());
  wifi.begin();
  loadPrefs();
  // Do not invent a network name. A short passphrase cannot join, and the
  // old placeholder name is dropped only when it has no real passphrase.
  {
    char ssid[33];
    char pass[64];
    ssid[0] = 0;
    pass[0] = 0;
    if (wifi.hasCreds()) {
      strncpy(ssid, wifi.ssid(), sizeof(ssid) - 1);
      ssid[sizeof(ssid) - 1] = 0;
      wifi.copyPass(pass, sizeof(pass));
    }
    bool retired = retiredWifiPass(pass);
    bool drop_name = strcmp(ssid, "BPSSI") == 0;
    if (strcmp(ssid, "Starlink") == 0 && (pass[0] == 0 || !wifiPassUsable(pass) || retired)) drop_name = true;
    bool short_pass = (pass[0] != 0 && !wifiPassUsable(pass)) || retired;
    if (drop_name) {
      wifi.clearCreds();
      want_wifi = false;
      savePrefs();
      pass[0] = 0;
    } else if (short_pass) {
      wifi.setCreds(ssid, "");
      want_wifi = false;
      savePrefs();
      pass[0] = 0;
    }
    bool join = pass[0] == 0 ? want_wifi : wifiPassUsable(pass);
    memset(pass, 0, sizeof(pass));
    if (wifi.hasCreds() && join) wifi.connect();
  }
  // A board that already booted still has the old compiled default saved.
  NodePrefs* prefs = the_mesh.getNodePrefs();
  if (prefs->password[0] == 0 || strcmp(prefs->password, "password") == 0) {
    strncpy(prefs->password, ADMIN_PASSWORD, sizeof(prefs->password) - 1);
    prefs->password[sizeof(prefs->password) - 1] = 0;
    the_mesh.savePrefs();
  }
  radar.begin();

#ifdef SENSE_CHANNEL_PSK
  if (!channel.beginPskHex(SENSE_CHANNEL_PSK)) {
    Serial.println("Invalid SENSE_CHANNEL_PSK, falling back to hashtag");
    channel.beginHashtag(SENSE_CHANNEL_NAME);
  }
#else
  channel.beginHashtag(SENSE_CHANNEL_NAME);
#endif

#if ENABLE_ADVERT_ON_BOOT == 1
  the_mesh.sendSelfAdvertisement(16000, false);
#endif
}

void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\r' || c == '\n') {
      if (!command_overflow && command[0]) {
        char reply[160];
        reply[0] = 0;
        the_mesh.handleCommand(0, command, reply);
        if (reply[0]) {
          Serial.print("  -> ");
          Serial.println(reply);
        } else {
          Serial.println();
        }
      }
      command_overflow = false;
      command[0] = 0;
      continue;
    }
    if (command_overflow) continue;
    size_t len = strlen(command);
    if (len >= sizeof(command) - 1) {
      command_overflow = true;
      command[0] = 0;
      Serial.println("\nline too long");
      continue;
    }
    command[len] = c;
    command[len + 1] = 0;
    Serial.print(c);
  }

  the_mesh.loop();
  sensors.loop();
  radar.loop();
  wifi.loop();

  RdTarget frame[RD03_MAX_TARGETS];
  if (radar.takeFrame(frame)) {
    motion.observe(frame, millis());
    static uint32_t last_log = 0;
    if (millis() - last_log > 500) {
      last_log = millis();
      const MotionSnapshot& s = motion.snap();
      Serial.printf("rd n=%u %s", s.n, MotionLogic::stateName(s.st));
      for (int i = 0; i < RD03_MAX_TARGETS; i++) {
        if (!s.tgt[i].valid) continue;
        Serial.printf(" %d,%d,%d", s.tgt[i].x_mm, s.tgt[i].y_mm, s.tgt[i].spd_cms);
      }
      Serial.println();
    }
  }

#ifdef DISPLAY_CLASS
  if (ui_task.consumeUserPress()) motion.forceReport();
  ui_task.setSnapshot(motion.snap(), channel.name(), radar.framesOk(), linkTag());
  ui_task.loop();
#endif

  char line[160];
  bool is_alert = false;
  bool on_wifi = want_wifi && wifi.connected();
  if (motion.takeMessage(millis(), line, sizeof(line), &is_alert, on_wifi)) {
    bool lora = is_alert || !on_wifi;
    // Tag is the path this line actually took, so the phone lamp can follow
    // it even when the private CLI reply is lost. Alerts stay on LoRa, and
    // their tag still says W when the tracks themselves are on WiFi.
    char tagged[176];
    int tn = snprintf(tagged, sizeof(tagged), "%s %s", line, linkTag());
    const char* out = (tn > 0 && tn < (int)sizeof(tagged)) ? tagged : line;
    Serial.printf("%s %s%s\n", lora ? "mesh" : "wifi", is_alert ? "ALERT " : "", out);
    if (lora) channel.sendText(the_mesh, *the_mesh.getRTCClock(), the_mesh.getNodeName(), out);
    else wifi.sendLine(out);
  }

  rtc_clock.tick();
#ifdef HAS_EXTERNAL_WATCHDOG
  external_watchdog.loop();
#endif
}
