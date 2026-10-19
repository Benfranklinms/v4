#!/bin/bash
# Builds libitune_core.so for arm64-v8a and generates the Kotlin bindings.
# CI (x86_64): uses cargo-ndk with $ANDROID_NDK_HOME. Sandbox (aarch64 host): set NDK_SYSROOT_ROOT to an
# extracted NDK and it links with the system clang + NDK sysroot.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$PWD
OUT=android/app/src/main/jniLibs/arm64-v8a
TD=${CARGO_TARGET_DIR:-$ROOT/target}
mkdir -p "$OUT"
if [ -n "${NDK_SYSROOT_ROOT:-}" ]; then
  TC=$NDK_SYSROOT_ROOT/toolchains/llvm/prebuilt/linux-x86_64
  SYS=$TC/sysroot
  RT=$(ls -d $TC/lib/clang/*/lib/linux | head -1)
  W=$TD/ndk-wrap; mkdir -p $W
  cat > $W/cc <<EOW
#!/bin/sh
exec clang --target=aarch64-linux-android26 --sysroot=$SYS "\$@"
EOW
  cat > $W/link-clang <<EOW
#!/bin/sh
exec clang --target=aarch64-linux-android26 --sysroot=$SYS -fuse-ld=lld -L$RT -Wl,-z,max-page-size=16384 "\$@" -l:libclang_rt.builtins-aarch64-android.a -lunwind
EOW
  # libunwind.a ships in the per-arch rt dir
  cp -f $RT/aarch64/libunwind.a $W/ 2>/dev/null || true
  chmod +x $W/cc $W/link-clang
  export CC_aarch64_linux_android=$W/cc AR_aarch64_linux_android=$(command -v llvm-ar || command -v llvm-ar-19)
  export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$W/link-clang
  export RUSTFLAGS="-L$W -L$RT/aarch64"
  cargo build --release --target aarch64-linux-android -p itune-core
  cp "$TD/aarch64-linux-android/release/libitune_core.so" "$OUT/"
else
  T=""; for a in ${CORE_ABIS:-arm64-v8a}; do T="$T -t $a"; done
  cargo ndk $T -P 26 -o android/app/src/main/jniLibs build --release -p itune-core
fi
# Bindings from the library's metadata (host build).
cargo build -p itune-core --lib
HOSTLIB=$TD/debug/libitune_core.so
[ -f "$HOSTLIB" ] || HOSTLIB=$TD/debug/libitune_core.dylib
cargo run -q -p uniffi-bindgen -- generate --library "$HOSTLIB" --language kotlin --config core/uniffi.toml --out-dir android/app/src/main/kotlin --no-format
ls -la "$OUT"
