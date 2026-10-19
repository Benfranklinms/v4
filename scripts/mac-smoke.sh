#!/bin/bash
# Mac smoke: mount the DMG, check the app bundle (arm64 executable), launch it headless with --smoke.
# Usage: scripts/mac-smoke.sh out/v4-<version>.dmg   -> snapshots + report.json in out/mac-smoke
set -uo pipefail
cd "$(dirname "$0")/.."
DMG=${1:?dmg}
OUT=${ITUNE_SMOKE_OUT:-$PWD/out/mac-smoke}
mkdir -p "$OUT"
MP=/tmp/v4m
hdiutil detach "$MP" >/dev/null 2>&1 || true
hdiutil attach "$DMG" -nobrowse -readonly -mountpoint "$MP" || { echo "DMG did not mount"; exit 1; }
ls -la "$MP" | tee "$OUT/dmg-contents.txt"
BIN="$MP/iTune.app/Contents/MacOS/iTune"
test -x "$BIN" || { echo "no executable in iTune.app"; exit 1; }
ARCHS=$(lipo -archs "$BIN"); echo "archs: $ARCHS" | tee -a "$OUT/dmg-contents.txt"
echo "$ARCHS" | grep -q arm64 || { echo "not arm64"; exit 1; }
/usr/libexec/PlistBuddy -c "Print :CFBundleShortVersionString" "$MP/iTune.app/Contents/Info.plist" | tee -a "$OUT/dmg-contents.txt"
rm -rf /tmp/iTune.app && ditto "$MP/iTune.app" /tmp/iTune.app
hdiutil detach "$MP" >/dev/null 2>&1 || true
rm -rf "$HOME/Library/Application Support/iTune"
sw_vers | tee -a "$OUT/dmg-contents.txt"
export ITUNE_SMOKE_OUT="$OUT"
# launch from the copied bundle; hard cap 16 min
( /tmp/iTune.app/Contents/MacOS/iTune --smoke > "$OUT/stdout.txt" 2> "$OUT/stderr.txt" ) & PID=$!
for i in $(seq 1 960); do kill -0 $PID 2>/dev/null || break; sleep 1; done
if kill -0 $PID 2>/dev/null; then echo "smoke hung, killing"; kill -9 $PID; fi
wait $PID; CODE=$?
grep "^SMOKE" "$OUT/stderr.txt" || tail -50 "$OUT/stderr.txt"
cat "$OUT/report.json" 2>/dev/null
ls "$HOME/Library/Logs/DiagnosticReports" 2>/dev/null | grep -i itune && cp "$HOME"/Library/Logs/DiagnosticReports/*iTune* "$OUT/" 2>/dev/null
cp "$HOME/Library/Application Support/iTune/crash-history.log" "$OUT/" 2>/dev/null || true
echo "smoke exit code $CODE"
exit $CODE
