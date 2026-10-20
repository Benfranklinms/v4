#!/bin/bash
# Emulator smoke test of this build: scripted UI walk through every screen + monkey; fails on any crash.
mkdir -p out/smoke
# generated core bindings (handy for checking the API the app was compiled against)
cp android/app/src/main/kotlin/dev/benfranklin/itune/core/itune_core.kt out/smoke/ 2>/dev/null || true
# lighter emulator: the google_apis image runs a lot of background apps that starve a 2-4 GB guest
adb wait-for-device
# the Gradle daemons from the build step hold ~5 GB on the runner; the emulator needs it more
pkill -f GradleDaemon 2>/dev/null || true
pkill -f KotlinCompileDaemon 2>/dev/null || true
for p in com.google.android.googlequicksearchbox com.google.android.apps.messaging com.google.android.as \
         com.google.android.apps.wellbeing com.google.android.apps.photos com.google.android.youtube \
         com.google.android.apps.maps com.google.android.gm com.google.android.apps.docs com.google.android.calendar; do
  timeout 20 adb shell pm disable-user --user 0 $p >/dev/null 2>&1 || true
done
timeout 20 adb shell settings put global package_verifier_enable 0 >/dev/null 2>&1 || true
timeout 20 adb shell am kill-all >/dev/null 2>&1 || true
# diagnostic leg: flat glass (no gradients / ambient wash) on the emulator only
[ "${SMOKE_LITE:-0}" = 1 ] && adb shell setprop debug.itune.lite 1
# informational full-glass leg on API 34 (SwiftShader-ANGLE): force the real Haze blur path that phones run, so a
# crash in it shows up somewhere (the gating lite leg never blurs)
if [ "${SMOKE_LITE:-0}" != 1 ] && [ "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" -ge 33 ] 2>/dev/null; then
  adb shell setprop debug.itune.blur 1; export SMOKE_BLUR=1
fi
export SMOKE_CI=1
bash scripts/smoke.sh "" ci
