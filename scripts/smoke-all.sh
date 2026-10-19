#!/bin/bash
# Emulator smoke test of this build: scripted UI walk through every screen + monkey; fails on any crash.
mkdir -p out/smoke
# generated core bindings (handy for checking the API the app was compiled against)
cp android/app/src/main/kotlin/dev/benfranklin/itune/core/itune_core.kt out/smoke/ 2>/dev/null || true
# lighter emulator: the google_apis image runs a lot of background apps that starve a 2-4 GB guest
adb wait-for-device
for p in com.google.android.googlequicksearchbox com.google.android.apps.messaging com.google.android.as \
         com.google.android.apps.wellbeing com.google.android.apps.photos com.google.android.youtube \
         com.google.android.apps.maps com.google.android.gm com.google.android.apps.docs com.google.android.calendar; do
  timeout 20 adb shell pm disable-user --user 0 $p >/dev/null 2>&1 || true
done
timeout 20 adb shell settings put global package_verifier_enable 0 >/dev/null 2>&1 || true
timeout 20 adb shell am kill-all >/dev/null 2>&1 || true
export SMOKE_CI=1
bash scripts/smoke.sh "" ci
