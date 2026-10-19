#!/bin/bash
# Mac build: Rust core (aarch64-apple-darwin, static) -> Swift bindings -> xcodegen -> xcodebuild -> ad-hoc sign -> DMG.
# Needs: rustup, xcodegen (brew), Xcode. Output: out/v4-<version>.dmg
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
TD=${CARGO_TARGET_DIR:-$ROOT/target}
VER=${APP_VERSION_NAME:-0.2.0}
BUILD=${APP_VERSION_CODE:-1}
export MACOSX_DEPLOYMENT_TARGET=14.0

rustup target add aarch64-apple-darwin >/dev/null 2>&1 || true
cargo build --release --target aarch64-apple-darwin -p itune-core

GEN=mac/Generated
rm -rf "$GEN"; mkdir -p "$GEN"
cp "$TD/aarch64-apple-darwin/release/libitune_core.a" "$GEN/"
cargo run -q -p uniffi-bindgen -- generate --library "$TD/aarch64-apple-darwin/release/libitune_core.dylib" \
  --language swift --config core/uniffi.toml --out-dir "$GEN"
rm -f "$GEN"/*.modulemap
echo '#include "ItuneCoreFFI.h"' > "$GEN/Bridging.h"
ls -la "$GEN"

# Optional scrobbling keys (CI secrets) -> Info.plist; empty hides the feature.
/usr/libexec/PlistBuddy -c "Delete :LastfmApiKey" mac/App/Info.plist 2>/dev/null || true
/usr/libexec/PlistBuddy -c "Delete :LastfmApiSecret" mac/App/Info.plist 2>/dev/null || true
/usr/libexec/PlistBuddy -c "Add :LastfmApiKey string ${LASTFM_API_KEY:-}" mac/App/Info.plist
/usr/libexec/PlistBuddy -c "Add :LastfmApiSecret string ${LASTFM_API_SECRET:-}" mac/App/Info.plist

cd mac
xcodegen generate
mkdir -p build
if ! xcodebuild -project iTune.xcodeproj -scheme iTune -configuration Release -derivedDataPath build/dd \
  -destination 'platform=macOS,arch=arm64' ARCHS=arm64 ONLY_ACTIVE_ARCH=YES \
  CODE_SIGN_IDENTITY=- CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO \
  MARKETING_VERSION="$VER" CURRENT_PROJECT_VERSION="$BUILD" build > build/xcodebuild.log 2>&1; then
  grep -E "error:" build/xcodebuild.log | sort -u | head -80
  tail -30 build/xcodebuild.log
  exit 1
fi
grep -E "warning:" build/xcodebuild.log | grep -v "deprecated" | sort -u | head -20 || true
APP=build/dd/Build/Products/Release/iTune.app
test -x "$APP/Contents/MacOS/iTune"
codesign --force --deep -s - --entitlements App/iTune.entitlements "$APP"
codesign -dv "$APP" 2>&1 | head -5
# Strip the keys back out of the source plist so they never get committed.
git checkout -- App/Info.plist 2>/dev/null || true

STAGE=build/dmg; rm -rf "$STAGE"; mkdir -p "$STAGE"
ditto "$APP" "$STAGE/iTune.app"
ln -s /Applications "$STAGE/Applications"
mkdir -p "$ROOT/out"
hdiutil create -volname v4 -srcfolder "$STAGE" -ov -format UDZO "$ROOT/out/v4-$VER.dmg"
ls -la "$ROOT/out"
