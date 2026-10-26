#!/usr/bin/env bash
# FEAT-M: builds libplitems.so for the app's four ABIs and puts them in app_pojavlauncher/src/main/jniLibs/<abi>/.
# Needs Rust with the Android targets (rustup target add aarch64-linux-android armv7-linux-androideabi
# i686-linux-android x86_64-linux-android) and the NDK (ANDROID_NDK_HOME, else the SDK's ndk/<projectsNdkVersion>).
# The libraries are aligned to 16 KB pages (Android 15+ devices). Usage: bash build.sh [target dir]
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
libs="$here/../../jniLibs"
export CARGO_TARGET_DIR="${1:-${CARGO_TARGET_DIR:-$here/target}}"
ndk="${ANDROID_NDK_HOME:-}"
if [ -z "$ndk" ]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}"
  ndk="$(ls -d "$sdk"/ndk/27.* 2>/dev/null | sort -V | tail -1)"
fi
[ -d "$ndk" ] || { echo "no NDK found (set ANDROID_NDK_HOME)" >&2; exit 2; }
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) host=windows-x86_64; ext=.cmd ;;
  Darwin) host=darwin-x86_64; ext= ;;
  *) host=linux-x86_64; ext= ;;
esac
bin="$ndk/toolchains/llvm/prebuilt/$host/bin"
flags="-C link-arg=-Wl,-z,max-page-size=16384"
build() {
  local target="$1" abi="$2" clang="$3" var
  var="$(echo "$target" | tr 'a-z-' 'A-Z_')"
  export "CARGO_TARGET_${var}_LINKER=$bin/$clang$ext"
  export "CARGO_TARGET_${var}_RUSTFLAGS=$flags"
  cargo build --release --locked --manifest-path "$here/Cargo.toml" --target "$target"
  mkdir -p "$libs/$abi"
  cp "$CARGO_TARGET_DIR/$target/release/libplitems.so" "$libs/$abi/libplitems.so"
  echo "$abi: $(wc -c < "$libs/$abi/libplitems.so") bytes"
}
build aarch64-linux-android arm64-v8a aarch64-linux-android21-clang
build armv7-linux-androideabi armeabi-v7a armv7a-linux-androideabi21-clang
build i686-linux-android x86 i686-linux-android21-clang
build x86_64-linux-android x86_64 x86_64-linux-android21-clang
