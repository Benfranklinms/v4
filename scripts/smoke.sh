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
# no system dialogs over the app during the scripted walk (notification permission, battery prompt)
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
adb shell dumpsys deviceidle whitelist +$PKG >/dev/null 2>&1 || true
adb shell settings put secure show_ime_with_hard_keyboard 0 >/dev/null 2>&1 || true
adb logcat -c
# host-side evidence in case the emulator process itself goes away (OOM killer, crash): memory + qemu RSS
( while true; do echo "== $(date +%T)"; free -m | sed -n 2,3p; ps -eo pid,rss,vsz,pcpu,comm --sort=-rss | head -4; sleep 3; done > "$O/host.txt" 2>&1 & echo $! > /tmp/host.pid )
# stream logcat + CPU samples to files from the start, so a hung emulator still leaves evidence
( adb logcat -v threadtime > "$O/logcat-live.txt" 2>&1 & echo $! > /tmp/logcat.pid )
( while true; do echo "== $(date +%T)"; timeout 10 adb shell top -b -n 1 -m 8 2>&1; sleep 20; done > "$O/top.txt" 2>&1 & echo $! > /tmp/top.pid )
adb shell am start -W -n $PKG/dev.benfranklin.itune.MainActivity
sleep 20
adb exec-out screencap -p > "$O/launch.png"
timeout 1800 bash scripts/ui-smoke.sh "$O/ui"; UI=$?
echo "ui-smoke exit=$UI"
timeout 30 adb shell am start -n $PKG/dev.benfranklin.itune.MainActivity >/dev/null 2>&1; sleep 3
timeout 600 adb shell monkey -p $PKG --pct-syskeys 0 --pct-appswitch 0 --throttle 500 -s 42 -v 600 > "$O/monkey.txt" 2>&1 || true
sleep 5
timeout 30 adb exec-out screencap -p > "$O/after.png"
timeout 60 adb logcat -d -v threadtime > "$O/logcat.txt"; kill $(cat /tmp/logcat.pid /tmp/top.pid /tmp/host.pid) 2>/dev/null
sudo dmesg -T 2>/dev/null | tail -120 > "$O/host-dmesg.txt" || true
ls -la /tmp/android-runner/ "$HOME/.android/avd/test.avd/" > "$O/host-emu-files.txt" 2>&1 || true
pgrep -af qemu > "$O/host-qemu.txt" 2>&1 || echo "no qemu process" > "$O/host-qemu.txt"
# emulator crash reports (crashpad minidumps) if qemu went away
cp -r /tmp/android-runner/emu-crash-*.db "$O/emu-crash" 2>/dev/null || true
find "$O/emu-crash" -type f -size +1k 2>/dev/null | head -20 > "$O/emu-crash-files.txt"
# ANR stack traces (google_apis images allow adb root)
timeout 30 adb root >/dev/null 2>&1; sleep 2
timeout 30 adb shell 'ls /data/anr' > "$O/anr-list.txt" 2>&1 || true
timeout 60 adb pull /data/anr "$O/anr" >/dev/null 2>&1 || true
grep -nE "FATAL EXCEPTION|Fatal signal|ANR in $PKG|panicked at|CRASH: $PKG" "$O/logcat.txt" "$O/logcat-live.txt" "$O/monkey.txt" > "$O/crashes.txt"
if [ -s "$O/crashes.txt" ]; then
  echo "::error::crash detected ($NAME)"
  cat "$O/crashes.txt"
  grep -n -A40 "FATAL EXCEPTION\|Fatal signal" "$O/logcat.txt" | head -150
  exit 1
fi
if [ "$UI" != 0 ]; then
  echo "::error::scripted UI smoke failed ($NAME)"
  cat "$O/ui/steps.txt" 2>/dev/null | grep -E "MISSING|DIED" || true
  exit 1
fi
echo "smoke OK ($NAME)"
