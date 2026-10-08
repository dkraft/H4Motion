# H4Motion

RD-03D radar on a **Heltec WiFi LoRa 32 V4**, reporting as a MeshCore sensor on hashtag channel **`#senseMB`**. The Android app draws a radar scope from those messages and raises an alert when someone enters or comes inside 2.5 m.

The radio module is a Heltec WiFi LoRa 32 V4. The sensor is the same shape as a MeshCore `simple_sensor` build. It does not use BLE companion mode, so the phone talks to a separate companion radio (the one you already use with the MeshCore app).

## Wiring

The RD-03D silkscreen next to the white plug is `5V GND TX RX DP DM`. Use those names, not the test pads.

| RD-03D | Heltec V4 |
| --- | --- |
| 5V | 5V (USB VBUS) |
| GND | GND |
| TX | GPIO47 |
| RX | GPIO48 |
| DP, DM, DEBUG | leave open |

GPIO47 is the Heltec RX. GPIO48 is the Heltec TX. The header pins are labeled `47` and `48`.

The onboard GPS already owns GPIO38/GPIO39, and the USB console owns the pins labeled RX/TX (GPIO44/GPIO43). The radar uses UART2 at **256000 8N1**.

Official RD-03D UART is 3.3 V TTL. Do not put a 5 V serial line on GPIO47. Power the Heltec from USB while the radar is attached. The 5V pin is USB power, not the battery connector.

+Y is straight out of the radar face. +X is to the right. Negative speed means the target is moving toward the radar.

## Channel

Reports go to **`#senseMB`**.

The key is the first 16 bytes of `sha256("#senseMB")`:

```
a95086e05a39519c2148b79f1d527c04
```

In the MeshCore app, or on the companion the Android app uses, join that hashtag. Anyone who knows the name can read it.

Lines look like this. The node name is prefixed by the firmware (`H4Motion: ...`).

```
RD n=1 st=MOVE -782,1713,-16
AL NEAR d=1.9 az=-25 x=-782 y=1713 s=-16
RD n=0 st=CLEAR
```

`RD` is a track. `AL` is an edge: `ENTER` when a target appears, `NEAR` when the closest one is inside the near range (default 2.5 m), `LEAVE` when the zone has been empty for 1.5 s. Coordinates are millimeters. Speed is cm/s. Azimuth is degrees, 0 ahead, positive to the right.

Tracks are sent at most every 3 s while someone is present, and a clear heartbeat every 60 s. There is at least 800 ms between floods.

Private channel instead of the hashtag:

```ini
; extra/platformio.local.ini build_flags
-D SENSE_CHANNEL_PSK='"0123456789abcdef0123456789abcdef"'
```

32 hex characters. Rebuild.

## Radio

Compile defaults match the local mesh companion: `910.525,62.5,10,5` (SF10, BW 62.5, CR 4/5, SX1262 power setting 10).

On a board that already saved other settings:

```
set radio 910.525,62.5,10,5
time <unix-epoch>
lat <degrees>
lon <degrees>
reboot
```

Advert position is `0,0` until you set `lat` and `lon`. Admin password starts as `password`. Change it with `password <new>` before this node sits on a shared mesh.

## Build / flash

PlatformIO is required. `make setup` clones [meshcore-dev/MeshCore](https://github.com/meshcore-dev/MeshCore) at commit `0679dbeffc504d562d2f09eb072fdc223f8ffc2a` and symlinks `extra/platformio.local.ini`.

```bash
make test            # host checks for the radar frame and the channel lines
make build           # env heltec_v4_motion
make upload
make monitor         # 115200
```

Heltec V4 DFU if the port is missing: hold **USR**, tap **RST**, release **USR**.

Serial and mesh admin CLI (Discover Sensors, log in, then the command):

| Command | Action |
| --- | --- |
| `rd` | targets, state, near range, intervals, frame counts |
| `rd report` | send a track line now |
| `rd interval 10` | seconds between tracks while occupied (2–120) |
| `rd near 2500` | NEAR distance in millimeters |
| `rd heart 60` | seconds between clear heartbeats |

The user button queues `rd report`. The OLED shows the count, the last alert, range and azimuth, and a small scope. Ahead is up, right is right.

## Android

`android/` is the radar display. Open that directory in Android Studio (JDK 17) and run it on a phone.

1. Pair or just be near the MeshCore **companion** radio. This app is not the sensor.
2. Allow Bluetooth and notifications.
3. Tap **Scan**. The app finds the companion Nordic UART service, joins `#senseMB` if a channel slot is free, and draws tracks.
4. **ENTER** and **NEAR** post a high-priority notification. The service stays in the foreground so that still happens with the screen covered.
5. **Demo** walks a fake target down the scope without a radio. **4 m / 8 m / 16 m** changes the ring scale.

The scope is the same frame as the firmware: center is the sensor, up is +Y, right is +X. Closing targets are amber. Inside 2.5 m they are red.

This tree was built and the host wire checks were run. The APK was not compiled here (no Android SDK / JDK 17 on the build machine).

## Layout

```
firmware/     RD-03D parser, alert state, channel send, OLED
extra/        PlatformIO env heltec_v4_motion
android/      radar scope and #senseMB alerts
tools/        host checks for the frame decoder and the text lines
MeshCore/     created by make setup, not committed
```
