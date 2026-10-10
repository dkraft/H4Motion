#!/usr/bin/env python3
"""Wire-format checks for H4Motion.

The sign-magnitude decoder matches firmware/Rd03d.cpp. The line parser matches
android/.../SenseParser.kt. Both sides speak the lines documented in README.md.
"""

import math
import re
import sys

def decode_sign_mag(lo, hi):
    raw = lo | (hi << 8)
    mag = raw & 0x7FFF
    return mag if (raw & 0x8000) else -mag

def decode_frame(frame):
    assert len(frame) == 30
    assert frame[0:4] == bytes([0xAA, 0xFF, 0x03, 0x00])
    assert frame[28:30] == bytes([0x55, 0xCC])
    out = []
    for i in range(3):
        p = frame[4 + i * 8:12 + i * 8]
        x = decode_sign_mag(p[0], p[1])
        y = decode_sign_mag(p[2], p[3])
        spd = decode_sign_mag(p[4], p[5])
        pix = p[6] | (p[7] << 8)
        spd_abs = abs(spd)
        valid = (x != 0 or y != 0) and spd_abs not in (248, 256)
        out.append((x, y, spd, pix, valid))
    return out

RD_RE = re.compile(
    r"^RD\s+n=(\d+)\s+st=(CLEAR|MOVE|ENTER|LEAVE|NEAR)(.*)$"
)
TGT_RE = re.compile(r"(-?\d+),(-?\d+),(-?\d+)")
AL_RE = re.compile(
    r"^AL\s+(ENTER|LEAVE|NEAR)\s+d=(-?\d+(?:\.\d+)?)\s+az=(-?\d+)\s+"
    r"x=(-?\d+)\s+y=(-?\d+)\s+s=(-?\d+)\s*$"
)

def strip_prefix(text):
    s = text.strip().replace("\x00", "")
    if s.startswith("RD ") or s.startswith("AL "):
        return s
    i = s.find(": ")
    if 0 < i <= 24:
        rest = s[i + 2:]
        if rest.startswith("RD ") or rest.startswith("AL "):
            return rest
    return s

def parse_line(text):
    s = strip_prefix(text)
    m = RD_RE.match(s)
    if m:
        rest = m.group(3)
        targets = [(int(a), int(b), int(c)) for a, b, c in TGT_RE.findall(rest)]
        return ("RD", int(m.group(1)), m.group(2), targets)
    m = AL_RE.match(s)
    if m:
        return ("AL", m.group(1), float(m.group(2)), int(m.group(3)),
                int(m.group(4)), int(m.group(5)), int(m.group(6)))
    return None

def screen_pos(x_mm, y_mm, range_mm=8000, radius=28, cx=100, cy=32):
    sx = cx + (x_mm * radius) / range_mm
    sy = cy - (y_mm * radius) / range_mm
    return sx, sy

def main():
    fails = 0

    def check(cond, msg):
        nonlocal fails
        if cond:
            print("ok", msg)
        else:
            fails += 1
            print("FAIL", msg)

    # Manual example: AA FF 03 00 0E 03 B1 86 10 00 68 01 + two empty targets + 55 CC
    sample = bytes.fromhex(
        "AAFF0300 0E03 B186 1000 6801 0000000000000000 0000000000000000 55CC".replace(" ", "")
    )
    check(len(sample) == 30, "sample frame is 30 bytes")
    targets = decode_frame(sample)
    check(targets[0] == (-782, 1713, -16, 360, True), "manual target 1 decodes x=-782 y=1713 s=-16")
    check(targets[1][4] is False and targets[2][4] is False, "empty slots are not targets")
    check(decode_sign_mag(5, 0x80) == 5, "sign bit set is positive")
    check(decode_sign_mag(5, 0x00) == -5, "sign bit clear is negative")

    az = math.degrees(math.atan2(-782, 1713))
    check(abs(az - (-24.53)) < 0.1, "azimuth of the manual target is about -25 deg")

    # +Y ahead is up on the scope, +X right is to the right.
    sx, sy = screen_pos(0, 8000)
    check(abs(sx - 100) < 1e-6 and abs(sy - 4) < 1e-6, "boresight at max range sits above center")
    sx, sy = screen_pos(8000, 0)
    check(sx > 100 and abs(sy - 32) < 1e-6, "positive X is to the right")

    rd = "H4Motion: RD n=1 st=MOVE -782,1713,-16"
    parsed = parse_line(rd)
    check(parsed == ("RD", 1, "MOVE", [(-782, 1713, -16)]), "RD line with node prefix")
    check(parse_line("RD n=0 st=CLEAR") == ("RD", 0, "CLEAR", []), "clear heartbeat")
    al = parse_line("AL NEAR d=1.9 az=-25 x=-782 y=1713 s=-16")
    check(al == ("AL", "NEAR", 1.9, -25, -782, 1713, -16), "NEAR alert")
    bad_tail = bytearray(sample)
    bad_tail[29] = 0x00
    try:
        decode_frame(bytes(bad_tail))
        check(False, "bad tail rejected")
    except AssertionError:
        check(True, "bad tail rejected")

    sentinel = bytearray(sample)
    sentinel[8] = 0xF8
    sentinel[9] = 0x00
    check(decode_frame(bytes(sentinel))[0][4] is False, "speed 248 is not a target")
    sentinel[8] = 0x00
    sentinel[9] = 0x01
    check(decode_frame(bytes(sentinel))[0][4] is False, "speed 256 is not a target")

    check(parse_line("hello") is None, "garbage is ignored")
    check(parse_line("NotANode: RD n=2 st=ENTER 1,2,3 -4,5,-6")[3] == [(1, 2, 3), (-4, 5, -6)],
          "two targets")

    # Channel hashtag key, first 16 bytes of sha256("#sensemb").
    import hashlib
    key = hashlib.sha256(b"#sensemb").hexdigest()[:32]
    check(key == "0d50fd356561e0129e349d3667462198", "hashtag key")

    if fails:
        print(fails, "failed")
        return 1
    print("all checks passed")
    return 0

if __name__ == "__main__":
    sys.exit(main())
