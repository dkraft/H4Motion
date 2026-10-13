# H4Motion

RD-03D radar on a **Heltec WiFi LoRa 32 V4**, reporting as a MeshCore sensor on hashtag channel **`#sensemb`**. The Android app draws a radar scope from those messages and raises an alert when someone enters or comes inside 2.5 m.

The radio module is a Heltec WiFi LoRa 32 V4. The sensor is the same shape as a MeshCore `simple_sensor` build. It does not use BLE companion mode, so the phone talks to a separate companion radio (the one you already use with the MeshCore app).

## Wiring

The wires are on the round test pads, not the white connector. Left to right those pads are `RX TX GND DEBUG DP DM 5V`. The white plug is a different order (`5V GND TX RX DP DM`); leave it empty.

| RD-03D pad | Heltec V4 |
| --- | --- |
| 5V | 5V (USB VBUS) |
| GND | GND |
| TX | GPIO48 |
| RX | GPIO47 |
| DP, DM, DEBUG | leave open |

The header pins are labeled `47` and `48`. The TX pad goes to the pin labeled `48`. The other way around stays silent.

The onboard GPS already owns GPIO38/GPIO39, and the USB console owns the pins labeled RX/TX (GPIO44/GPIO43). The radar uses UART2 at **256000 8N1**.

Official RD-03D UART is 3.3 V TTL. Do not put a 5 V serial line on GPIO47. Power the Heltec from USB while the radar is attached. The 5V pin is USB power, not the battery connector.

+Y is straight out of the radar face. +X is to the right. Negative speed means the target is moving toward the radar.

## Channel

Reports go to **`#sensemb`**.

The key is the first 16 bytes of `sha256("#sensemb")`:

```
0d50fd356561e0129e349d3667462198
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

Advert position is `0,0` until you set `lat` and `lon`. The admin password is `123456`. This Heltec has no display, so that code is fixed instead of shown on a screen. Change it with `password <new>` before this node sits on a shared mesh.

On a fresh board there is no WiFi name until Config sends one. The name you type is the one that is saved. A passphrase shorter than 8 characters is rejected, so the sensor does not join. A saved network is kept across reboots. This radio only hears 2.4 GHz, so a 5 GHz-only name will not connect.

## Build / flash

PlatformIO is required. `make setup` clones [meshcore-dev/MeshCore](https://github.com/meshcore-dev/MeshCore) at commit `0679dbeffc504d562d2f09eb072fdc223f8ffc2a` and symlinks `extra/platformio.local.ini`.

```bash
make test            # host checks: radar frame, occupancy, channel lines
make build           # env heltec_v4_motion
make upload
make monitor         # 115200
```

Heltec V4 DFU if the port is missing: hold **USR**, tap **RST**, release **USR**.

The USB console accepts a command ended by Enter or Return. Serial and mesh admin CLI (Discover Sensors, log in, then the command):

| Command | Action |
| --- | --- |
| `rd` | targets, state, near range, intervals, frame counts |
| `rd report` | send a track line now |
| `rd interval 10` | seconds between tracks while occupied (2–120) |
| `rd near 2500` | NEAR distance in millimeters |
| `rd heart 60` | seconds between clear heartbeats |
| `wifi` | join state, or `wifi off`. Never prints the passphrase |
| `wifi <ssid> <password>` | store the network and start joining. Quote a field that contains spaces |
| `link` | `link lora`, `link wifi <ip>`, `link wait`, or `wifi fail` |
| `link lora` | flood tracks on `#sensemb`. WiFi stays associated |
| `link wifi` | send tracks on TCP port 4040 once the join succeeds. Alerts still flood |

The passphrase is stored on the sensor and is not sent on `#sensemb`. A failed join does not switch the sensor back to LoRa. The next `link wifi` tries again. Tracks stay on LoRa until the join succeeds. WPA needs a passphrase of 8 to 63 characters. The network name has to be the 2.4 GHz name.

The user button queues `rd report`. This board has no screen. The phone is the scope.

## Android

`android/` is the radar display. Open that directory in Android Studio (JDK 17) and run it on a phone.

1. Allow Bluetooth and notifications, then tap **Scan**. The service does not start until those are granted.
2. When Android asks, enter the companion's BLE PIN. The companion UART requires an encrypted bond. Being nearby is not enough, and this app is not the sensor.
3. The app finds the companion Nordic UART service, joins `#sensemb` if a channel slot is free, and draws tracks. If WiFi is the saved choice, Scan asks the sensor which link it is on and opens that socket when the sensor already has an address. Otherwise it sends the saved network. If LoRa is the saved choice, Scan sends `link lora` and leaves the socket closed.
4. **ENTER** and **NEAR** post a high-priority notification. The service stays in the foreground so that still happens with the screen covered.
5. **Config** replaces Demo on the main row. It is filled in with the saved WiFi name, the sensor admin password `123456`, and tracks on WiFi. The name box stays empty until you type one, and the name in that box is the name that is saved. A name that was filled in for you is cleared. The WiFi password and the sensor admin password are shown in clear text, and Apply keeps what you typed. The WiFi password needs 8 characters. If WiFi is not already up, Apply sends that name and password again instead of only asking the sensor to switch. **Normal** is the phosphor scope. **High contrast** is white on black, with near targets still red and closing targets still yellow. That choice stays on the phone and does not go out over LoRa. The LoRa and WiFi lamps follow the path that is carrying tracks, including a track that shows up on the other radio. **Demo** is inside that screen and walks a fake target without a radio. **4 m / 8 m / 16 m** changes the ring scale.
6. Apply logs into the sensor through the companion. WiFi sends `wifi` then `link wifi`. LoRa sends only `link lora`, so the access point is left alone. That is a private CLI reply, so it can take a minute or two. The phone must be on the same LAN as the sensor for WiFi tracks. Tracks then arrive on TCP port 4040. **ENTER** and **NEAR** still arrive on `#sensemb`. The sensor stays associated when tracks move back to LoRa. The LoRa or WiFi mark in Config stays lit the next time you open it.

The phone scope is a 120° wedge, the forward field of the RD-03D. The sensor is at the bottom, up is +Y, and right is +X. A return outside that wedge is drawn on the nearest edge. Closing targets are amber. Inside 2.5 m they are red.

Under the wedge, amber plates show the track path (LoRa or WiFi), the first entry, the latest entry, and the closest contact. The first plate locks for the session. It fills on ENTER, on NEAR (a person who appears already inside 2.5 m), and on the first contact already in view. The latest plate moves on each later ENTER or NEAR. The contact plate follows whichever return is nearest and reads NEAR inside 2.5 m. LAT is how old that picture is. On WiFi the sensor sends the latest position 3 times a second, and the phone paints at that same pace instead of replaying a burst. Those fixes are the radar's own frame-to-frame wander, so the dot jitters. LoRa sends one of those fixes about every 3 seconds, and the dot sits on it. The measurement is the same.

`make test` checks the RD-03D frame and the occupancy machine on the host. `make build` produces the Heltec image. The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk` after `assembleDebug` (JDK 17, compile SDK 34). Version 0.1.14 stores the WiFi name you type and drops the name that used to be filled in. The radio scans every 2.4 GHz channel. Version 0.1.13 stores the WiFi name you type and scans every 2.4 GHz channel for it. Version 0.1.12 keeps the WiFi password you type and sends it again when WiFi is not up. A passphrase under 8 characters is rejected. Version 0.1.9 keeps the deck lamps on the live track path. A LoRa line closes a leftover WiFi socket, and Apply sends `link` again when the lamp does not match the choice. The radar UART stays 256000 8N1. A torn 30-byte frame is dropped after a 15 ms gap, and multi-target mode is asked again after a run of bad frames. Admin password `123456`.

## Layout

```
firmware/     RD-03D parser, alert state, channel send, WiFi track socket, OLED
extra/        PlatformIO env heltec_v4_motion
android/      radar scope and #sensemb alerts
tools/        host checks for the frame decoder and the text lines
MeshCore/     created by make setup, not committed
```
