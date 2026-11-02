#!/usr/bin/env bash
# Builds an-claw's native executables for Android and drops them into
# app/src/main/jniLibs/<abi>/ as lib*.so (Android only lets an app execute
# files from its native library directory):
#
#   libclaw.so          claw (claw-code's CLI, run as `claw desktop-agent`)
#   libanclaw_hub.so    the bundled Hugging Face / GitHub MCP server
#   libllama_server.so  llama-server from llama-abominated (+ its ggml/llama libs)
#
# Sources are copied into native/build/src first and patched there
# (native/patches/<project>/*.patch), so the original checkouts are never
# modified.
#
# Usage: native/build-native.sh [all|claw|hub|llama] [abi...]
#   ABIs: arm64-v8a (default), x86_64 (emulator)
# Env:   CLAW_CODE_DIR, LLAMA_DIR, ANDROID_NDK_HOME, ANDROID_API (default 30)

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
CLAW_CODE_DIR="${CLAW_CODE_DIR:-$HOME/ws/src/rust/claw-code}"
LLAMA_DIR="${LLAMA_DIR:-$HOME/ws/src/cpp/nn/llama-abominated}"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1)}"
API="${ANDROID_API:-30}"
BUILD="$HERE/build"
JNI="$ROOT/app/src/main/jniLibs"
JOBS="${JOBS:-$(nproc)}"

what="${1:-all}"; shift || true
abis=("$@"); [ ${#abis[@]} -eq 0 ] && abis=(arm64-v8a)

[ -d "$ANDROID_NDK_HOME" ] || { echo "NDK not found (set ANDROID_NDK_HOME)"; exit 1; }
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64"
echo "NDK: $ANDROID_NDK_HOME  API: $API  ABIs: ${abis[*]}"

rust_triple() { case "$1" in arm64-v8a) echo aarch64-linux-android;; x86_64) echo x86_64-linux-android;; *) echo "bad abi $1" >&2; exit 1;; esac; }

# ---------------------------------------------------------------- sources

sync_src() { # name src_dir rsync-excludes...
  local name="$1" src="$2"; shift 2
  local dst="$BUILD/src/$name"
  mkdir -p "$dst"
  # Mirror the working tree (uncommitted changes included); keep build outputs out.
  rsync -a --delete --exclude .git --exclude /target --exclude '/build' --exclude '/build-*' "$@" "$src/" "$dst/"
  if [ -d "$HERE/patches/$name" ]; then
    for p in "$HERE/patches/$name"/*.patch; do
      [ -e "$p" ] || continue
      echo "patch $name: $(basename "$p")"
      patch -d "$dst" -p1 --forward --quiet < "$p"
    done
  fi
}

# ---------------------------------------------------------------- rust

cargo_android() { # abi manifest bin out_name
  local abi="$1" manifest="$2" bin="$3" out="$4"
  local triple; triple="$(rust_triple "$abi")"
  local cc="$TOOLCHAIN/bin/${triple}${API}-clang"
  local upper; upper="$(echo "$triple" | tr 'a-z-' 'A-Z_')"
  rustup target add "$triple" >/dev/null
  env \
    "CC_${triple//-/_}=$cc" \
    "CXX_${triple//-/_}=${cc}++" \
    "AR_${triple//-/_}=$TOOLCHAIN/bin/llvm-ar" \
    "CARGO_TARGET_${upper}_LINKER=$cc" \
    "CARGO_TARGET_${upper}_RUSTFLAGS=-C link-arg=-Wl,-z,max-page-size=16384" \
    CARGO_TARGET_DIR="$BUILD/target" \
    cargo build --release --manifest-path "$manifest" --target "$triple" --bin "$bin"
  mkdir -p "$JNI/$abi"
  cp "$BUILD/target/$triple/release/$bin" "$JNI/$abi/$out"
  "$TOOLCHAIN/bin/llvm-strip" "$JNI/$abi/$out"
  echo "→ $JNI/$abi/$out ($(du -h "$JNI/$abi/$out" | cut -f1))"
}

build_claw() {
  sync_src claw-code "$CLAW_CODE_DIR/rust"
  for abi in "${abis[@]}"; do
    cargo_android "$abi" "$BUILD/src/claw-code/Cargo.toml" claw libclaw.so
  done
}

build_hub() {
  for abi in "${abis[@]}"; do
    cargo_android "$abi" "$HERE/mcp-hub/Cargo.toml" anclaw-mcp-hub libanclaw_hub.so
  done
}

# ---------------------------------------------------------------- llama

build_llama() {
  sync_src llama-abominated "$LLAMA_DIR" --exclude 'models/*.gguf' --exclude benches --exclude media \
    --exclude overlay --exclude '*.log' --exclude ref_doc
  for abi in "${abis[@]}"; do
    local out="$BUILD/llama-$abi"
    local extra=()
    if [ "$abi" = arm64-v8a ]; then
      # One CPU backend per ARM feature level (dotprod, i8mm, sve…), picked at
      # run time from the native library directory.
      extra+=(-DGGML_CPU_KLEIDIAI=ON -DGGML_OPENMP=ON)
    else
      extra+=(-DGGML_OPENMP=OFF)
    fi
    cmake -S "$BUILD/src/llama-abominated" -B "$out" -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
      -DCMAKE_EXE_LINKER_FLAGS="-Wl,-z,max-page-size=16384" \
      -DBUILD_SHARED_LIBS=ON \
      -DGGML_NATIVE=OFF -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_LLAMAFILE=OFF \
      -DLLAMA_BUILD_SERVER=ON -DLLAMA_BUILD_TOOLS=ON -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TESTS=OFF \
      -DLLAMA_OPENSSL=OFF -DLLAMA_CURL=OFF \
      "${extra[@]}"
    cmake --build "$out" -j "$JOBS" --target llama-server llama-quantize
    mkdir -p "$JNI/$abi"
    cp "$out/bin/llama-server" "$JNI/$abi/libllama_server.so"
    # llama-quantize: the app's "optimize for this phone" (Q4_K → Q4_0, which ARM repacks for i8mm/KleidiAI).
    cp "$out/bin/llama-quantize" "$JNI/$abi/libllama_quantize.so"
    # Shared libraries next to it: llama, ggml, ggml-base, the CPU variants, mtmd.
    find "$out/bin" "$out" -maxdepth 3 -name 'lib*.so' -newer "$out/CMakeCache.txt" \
      -exec cp {} "$JNI/$abi/" \;
    # ggml is built with OpenMP: ship the NDK's libomp.so.
    local omp_arch; omp_arch="$([ "$abi" = arm64-v8a ] && echo aarch64 || echo x86_64)"
    local omp; omp="$(find "$TOOLCHAIN/lib/clang" -path "*/lib/linux/$omp_arch/libomp.so" | head -1)"
    if [ -n "$omp" ]; then cp "$omp" "$JNI/$abi/libomp.so"; fi
    for f in "$JNI/$abi"/lib*.so; do "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$f" || true; done
    ls -la "$JNI/$abi"
  done
}

case "$what" in
  all)   build_hub; build_claw; build_llama ;;
  claw)  build_claw ;;
  hub)   build_hub ;;
  llama) build_llama ;;
  *) echo "usage: $0 [all|claw|hub|llama] [abi...]"; exit 1 ;;
esac
