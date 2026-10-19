#!/bin/bash
# Emulator smoke test: install, launch, poke around with monkey, fail on any crash.
# usage: smoke.sh [apk] [name]   -> out/smoke/<name>/{launch.png,after.png,logcat.txt,monkey.txt,crashes.txt}
set -u
PKG=in.benfranklin.itune
APK=${1:-}
[ -n "$APK" ] || APK=$(ls android/app/build/outputs/apk/release/*.apk | head -1)
NAME=${2:-ci}
O=out/smoke/$NAME
mkdir -p "$O"
adb uninstall $PKG >/dev/null 2>&1 || true
adb install -r "$APK" || exit 1
adb logcat -c
adb shell am start -W -n $PKG/dev.benfranklin.itune.MainActivity
sleep 20
adb exec-out screencap -p > "$O/launch.png"
adb shell monkey -p $PKG --pct-syskeys 0 --pct-appswitch 0 --throttle 500 -s 42 -v 600 > "$O/monkey.txt" 2>&1 || true
sleep 5
adb exec-out screencap -p > "$O/after.png"
adb logcat -d -v threadtime > "$O/logcat.txt"
# ANR stack traces (google_apis images allow adb root)
adb root >/dev/null 2>&1; sleep 2
adb shell 'ls /data/anr' > "$O/anr-list.txt" 2>&1 || true
adb pull /data/anr "$O/anr" >/dev/null 2>&1 || true
grep -nE "FATAL EXCEPTION|Fatal signal|ANR in $PKG|panicked at|CRASH: $PKG" "$O/logcat.txt" "$O/monkey.txt" > "$O/crashes.txt"
if [ -s "$O/crashes.txt" ]; then
  echo "::error::crash detected ($NAME)"
  cat "$O/crashes.txt"
  grep -n -A40 "FATAL EXCEPTION\|Fatal signal" "$O/logcat.txt" | head -150
  exit 1
fi
echo "smoke OK ($NAME)"
