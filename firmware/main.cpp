#include "SensorMesh.h"
#include "Rd03d.h"
#include "MotionLogic.h"
#include "ChannelOut.h"

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
static const char* kPrefsPath = "/h4motion.txt";

static void savePrefs() {
  File f = SPIFFS.open(kPrefsPath, "w");
  if (!f) return;
  f.printf("interval %d\n", motion.trackSecs());
  f.printf("near %d\n", motion.nearMm());
  f.printf("heart %d\n", motion.heartSecs());
  f.close();
}

static void loadPrefs() {
  File f = SPIFFS.open(kPrefsPath, "r");
  if (!f) return;
  char buf[64];
  while (f.available()) {
    int n = f.readBytesUntil('\n', buf, sizeof(buf) - 1);
    if (n <= 0) continue;
    buf[n] = 0;
    if (buf[n - 1] == '\r') buf[n - 1] = 0;
    if (memcmp(buf, "interval ", 9) == 0) motion.setTrackSecs(atoi(buf + 9));
    else if (memcmp(buf, "near ", 5) == 0) motion.setNearMm(atoi(buf + 5));
    else if (memcmp(buf, "heart ", 6) == 0) motion.setHeartSecs(atoi(buf + 6));
  }
  f.close();
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
      sprintf(reply, "n=%u %s d=%d.%d near=%d int=%ds heart=%ds ok=%lu bad=%lu",
              s.n, MotionLogic::stateName(s.st),
              tenths / 10, tenths % 10,
              motion.nearMm(), motion.trackSecs(), motion.heartSecs(),
              (unsigned long)radar.framesOk(), (unsigned long)radar.framesBad());
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
  if (display.begin()) {
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
  ui_task.begin(the_mesh.getNodePrefs(), FIRMWARE_BUILD_DATE, FIRMWARE_VERSION);
#endif

  motion.begin(millis());
  loadPrefs();
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
  ui_task.setSnapshot(motion.snap(), channel.name(), radar.framesOk());
  ui_task.loop();
#endif

  char line[160];
  bool is_alert = false;
  if (motion.takeMessage(millis(), line, sizeof(line), &is_alert)) {
    Serial.printf("mesh %s%s\n", is_alert ? "ALERT " : "", line);
    channel.sendText(the_mesh, *the_mesh.getRTCClock(), the_mesh.getNodeName(), line);
  }

  rtc_clock.tick();
#ifdef HAS_EXTERNAL_WATCHDOG
  external_watchdog.loop();
#endif
}
