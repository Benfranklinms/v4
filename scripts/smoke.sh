#!/bin/bash
# Emulator smoke test: install, launch, poke around with monkey, fail on any crash. Leaves out/smoke/*.
set -u
PKG=in.benfranklin.itune
APK=$(ls android/app/build/outputs/apk/release/*.apk | head -1)
mkdir -p out/smoke
adb install -r "$APK" || exit 1
adb logcat -c
adb shell am start -W -n $PKG/dev.benfranklin.itune.MainActivity
sleep 20
adb exec-out screencap -p > out/smoke/launch.png
adb shell monkey -p $PKG --pct-syskeys 0 --pct-appswitch 0 --throttle 500 -s 42 -v 250 > out/smoke/monkey.txt 2>&1 || true
sleep 5
adb exec-out screencap -p > out/smoke/after.png
adb logcat -d -v threadtime > out/smoke/logcat.txt
grep -nE "FATAL EXCEPTION|Fatal signal|ANR in $PKG|panicked at|CRASH: $PKG" out/smoke/logcat.txt out/smoke/monkey.txt > out/smoke/crashes.txt
if [ -s out/smoke/crashes.txt ]; then
  echo "::error::crash detected"; cat out/smoke/crashes.txt
  grep -n -A40 "FATAL EXCEPTION\|Fatal signal" out/smoke/logcat.txt | head -150
  exit 1
fi
echo "smoke OK"
