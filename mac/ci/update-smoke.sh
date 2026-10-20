#!/bin/bash
# Updater smoke: version N (the build) updates itself to N+1 (same build, version 9.9.9, re-signed) headless.
#   A) N runs from ~/Downloads        -> replaced in place
#   B) N runs from a read-only image  -> installed to /Applications
# Each case presents the real update sheet first (in 0.2.1 the sheet swallowed terminate and the install hung).
# Asserts: the old process quits, the bundle version changed, codesign verifies, the new version relaunched.
# Usage: mac/ci/update-smoke.sh out/v4-<version>.dmg   -> out/update-smoke/
set -uo pipefail
cd "$(dirname "$0")/../.."
DMG=${1:?dmg}
OUT=$PWD/out/update-smoke
mkdir -p "$OUT"
LOG="$HOME/Library/Logs/v4-update.log"
NEXT=9.9.9
FAILS=0
pass() { echo "PASS $*" | tee -a "$OUT/result.txt"; }
fail() { echo "FAIL $*" | tee -a "$OUT/result.txt"; FAILS=$((FAILS+1)); }
ver() { /usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$1/Contents/Info.plist" 2>/dev/null; }
killall_app() { pkill -9 -f "iTune.app/Contents/MacOS/iTune" 2>/dev/null; sleep 1; }

# --- N from the build, N+1 = same app with a higher version, re-signed ad hoc, in its own DMG
MN=/tmp/v4u-n; hdiutil detach "$MN" -force >/dev/null 2>&1
for t in 1 2 3; do hdiutil attach "$DMG" -nobrowse -readonly -mountpoint "$MN" >/dev/null && break; sleep 3; done
test -d "$MN/iTune.app" || { echo "N image did not mount"; exit 1; }
CUR=$(ver "$MN/iTune.app"); echo "N = $CUR, N+1 = $NEXT" | tee "$OUT/result.txt"
rm -rf /tmp/v4u-stage /tmp/v4u-n.app && mkdir -p /tmp/v4u-stage
ditto "$MN/iTune.app" /tmp/v4u-n.app
ditto "$MN/iTune.app" /tmp/v4u-stage/iTune.app
hdiutil detach "$MN" -force >/dev/null 2>&1
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $NEXT" /tmp/v4u-stage/iTune.app/Contents/Info.plist
/usr/libexec/PlistBuddy -c "Set :CFBundleVersion 999" /tmp/v4u-stage/iTune.app/Contents/Info.plist
codesign --force --deep -s - --entitlements mac/App/iTune.entitlements /tmp/v4u-stage/iTune.app
ln -s /Applications /tmp/v4u-stage/Applications
rm -f /tmp/v4-$NEXT.dmg
hdiutil create -volname v4 -srcfolder /tmp/v4u-stage -ov -format UDZO /tmp/v4-$NEXT.dmg >/dev/null || { echo "N+1 image failed"; exit 1; }

run_case() { # name, path of the app to start, expected target
  local name=$1 app=$2 target=$3
  echo "=== case $name: $app -> $target"
  killall_app
  rm -f "$LOG"
  "$app/Contents/MacOS/iTune" --update-test=/tmp/v4-$NEXT.dmg --update-test-version=$NEXT > "$OUT/$name-stdout.txt" 2> "$OUT/$name-stderr.txt" &
  local pid=$! t0=$(date +%s) quit=0
  for i in $(seq 1 90); do kill -0 $pid 2>/dev/null || { quit=1; break; }; sleep 0.5; done
  if [ $quit = 1 ]; then
    wait $pid; local code=$?
    if [ $code = 0 ]; then pass "$name: old app quit by itself in $(( $(date +%s) - t0 )) s"; else fail "$name: old app exited with $code"; fi
  else
    fail "$name: old app still running 45 s after the update started"
    sample $pid 3 -file "$OUT/$name-sample.txt" >/dev/null 2>&1; kill -9 $pid
  fi
  local ok=0
  for i in $(seq 1 60); do
    if [ "$(ver "$target")" = "$NEXT" ] && grep -q "relaunched version $NEXT" "$LOG" 2>/dev/null; then ok=1; break; fi
    sleep 1
  done
  [ "$(ver "$target")" = "$NEXT" ] && pass "$name: bundle version $(ver "$target") at $target" || fail "$name: bundle version at $target is '$(ver "$target")'"
  [ $ok = 1 ] && pass "$name: relaunched as $NEXT" || fail "$name: no relaunch of $NEXT in the log"
  pgrep -f "$target/Contents/MacOS/iTune" >/dev/null && pass "$name: new process running" || fail "$name: new process not running"
  codesign --verify --deep --strict "$target" 2>>"$OUT/$name-codesign.txt" && pass "$name: codesign verifies" || fail "$name: codesign"
  [ -e "$target.old" ] || [ -e "$target.v4new" ] && fail "$name: leftovers next to the app" || pass "$name: no leftovers"
  grep -q "sheet attached = true" "$LOG" && pass "$name: sheet was up during the install" || echo "WARN $name: sheet not seen as attached"
  if [ -n "$(xattr -p com.apple.quarantine "$target" 2>/dev/null)" ]; then fail "$name: quarantine on the installed app"; else pass "$name: no quarantine"; fi
  cp "$LOG" "$OUT/$name-update.log" 2>/dev/null
  echo "--- $name log"; cat "$LOG" 2>/dev/null
  killall_app
}

# A) from ~/Downloads (in place)
rm -rf "$HOME/Downloads/iTune.app"; mkdir -p "$HOME/Downloads"
ditto /tmp/v4u-n.app "$HOME/Downloads/iTune.app"
run_case downloads "$HOME/Downloads/iTune.app" "$HOME/Downloads/iTune.app"

# B) from a read-only disk image (installs to /Applications)
rm -rf /Applications/iTune.app
hdiutil detach "$MN" -force >/dev/null 2>&1
hdiutil attach "$DMG" -nobrowse -readonly -mountpoint "$MN" >/dev/null || fail "image B did not mount"
if [ -w /Applications ]; then
  run_case readonly "$MN/iTune.app" "/Applications/iTune.app"
else
  echo "WARN /Applications not writable on this runner, case B skipped"
fi
hdiutil detach "$MN" -force >/dev/null 2>&1
rm -rf /Applications/iTune.app "$HOME/Downloads/iTune.app"

echo "update smoke failures: $FAILS" | tee -a "$OUT/result.txt"
exit $FAILS
