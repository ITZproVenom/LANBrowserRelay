#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: build-proot-android.sh <android-ndk-path>" >&2
  exit 2
fi

NDK="$(cd "$1" && pwd)"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
test -x "$TOOLCHAIN/bin/llvm-ar"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Pinned Android-aware PRoot fork and pinned talloc source.
PROOT_COMMIT="52d65a4dc140ca554d241a1438ddd4f6b00c9813"
TALLOC_VERSION="2.5.0"
TALLOC_SHA256="912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007"
TALLOC_URL="https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz"
SHMEM_VERSION="0.7"
SHMEM_SHA256="1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867"
SHMEM_URL="https://github.com/termux/libandroid-shmem/archive/refs/tags/v${SHMEM_VERSION}.tar.gz"

curl --fail --location --retry 3 "$TALLOC_URL" -o "$WORK/talloc.tar.gz"
curl --fail --location --retry 3 "$SHMEM_URL" -o "$WORK/libandroid-shmem.tar.gz"
echo "$SHMEM_SHA256  $WORK/libandroid-shmem.tar.gz" | sha256sum --check --status
echo "$TALLOC_SHA256  $WORK/talloc.tar.gz" | sha256sum --check --status

git clone --quiet https://github.com/termux/proot.git "$WORK/proot"
git -C "$WORK/proot" checkout --quiet --detach "$PROOT_COMMIT"
test "$(git -C "$WORK/proot" rev-parse HEAD)" = "$PROOT_COMMIT"

ANSWERS="$WORK/cross-answers.txt"
cat > "$ANSWERS" <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
EOF

build_one() {
  local abi="$1"
  local target="$2"
  local clang="$TOOLCHAIN/bin/$target"
  local prefix="$WORK/$abi-deps"
  local talloc="$WORK/talloc-$abi"
  local proot="$WORK/proot-$abi"

  test -x "$clang"
  mkdir -p "$prefix/include" "$prefix/lib" "$talloc"
  tar -xzf "$WORK/talloc.tar.gz" --strip-components=1 -C "$talloc"
  (
    cd "$talloc"
    CC="$clang" \
      AR="$TOOLCHAIN/bin/llvm-ar" \
      RANLIB="$TOOLCHAIN/bin/llvm-ranlib" \
      CFLAGS="-O2 -fPIC" \
      ./configure \
        --prefix="$prefix" \
        --disable-rpath \
        --disable-python \
        --cross-compile \
        --cross-answers="$ANSWERS"
    make -j2
    find bin/default -maxdepth 1 -type f -name 'talloc*.o' -print0 \
      | xargs -0 "$TOOLCHAIN/bin/llvm-ar" rcs "$prefix/lib/libtalloc.a"
    TALLOC_HEADER="$(find . -type f -name talloc.h -print -quit)"
    if [[ -z "$TALLOC_HEADER" ]]; then
      echo "talloc.h was not generated or included in the pinned talloc source." >&2
      exit 1
    fi
    install -m 0644 "$TALLOC_HEADER" "$prefix/include/talloc.h"
  )

  local shmem="$WORK/libandroid-shmem-$abi"
  mkdir -p "$shmem"
  tar -xzf "$WORK/libandroid-shmem.tar.gz" --strip-components=1 -C "$shmem"
  (
    cd "$shmem"
    CC="$clang" AR="$TOOLCHAIN/bin/llvm-ar" RANLIB="$TOOLCHAIN/bin/llvm-ranlib" \
      CFLAGS="-O2 -fPIC" make libandroid-shmem.a
    install -m 0644 libandroid-shmem.a "$prefix/lib/libandroid-shmem.a"
    install -D -m 0644 shm.h "$prefix/include/sys/shm.h"
  )

  cp -a "$WORK/proot" "$proot"
  local out="$ROOT/lanlink-agent-android/app/src/main/jniLibs/$abi"
  mkdir -p "$out"
  (
    cd "$proot"
    local version_suffix
    version_suffix="$(echo "$PROOT_COMMIT" | cut -c1-8)"
    local common_cpp="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072 -DVERSION=\\\"termux-$version_suffix\\\" -I$prefix/include -I."
    local common_c="-O2 -Wall -Wextra -fPIE"
    local common_ld="-L$prefix/lib -ltalloc -landroid-shmem -landroid -llog -Wl,-z,noexecstack -pie"

    make -C src clean
    make -C src build.h proot \
      CC="$clang" \
      LD="$clang" \
      STRIP="$TOOLCHAIN/bin/llvm-strip" \
      OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy" \
      OBJDUMP="$TOOLCHAIN/bin/llvm-objdump" \
      CPPFLAGS="$common_cpp" \
      CFLAGS="$common_c" \
      LDFLAGS="$common_ld" \\
      PROOT_WITH_LIBANDROID_SHMEM=true

    test -s src/proot
    "$TOOLCHAIN/bin/llvm-strip" src/proot
    install -m 0755 src/proot "$out/libproot.so"
    "$TOOLCHAIN/bin/llvm-readelf" -h "$out/libproot.so" > "$WORK/$abi-elf.txt"
    grep -q "DYN" "$WORK/$abi-elf.txt"
  )
}

build_one "armeabi-v7a" "armv7a-linux-androideabi28-clang"
build_one "arm64-v8a" "aarch64-linux-android28-clang"

echo "Built Android PRoot binaries:"
file "$ROOT/lanlink-agent-android/app/src/main/jniLibs/armeabi-v7a/libproot.so"
file "$ROOT/lanlink-agent-android/app/src/main/jniLibs/arm64-v8a/libproot.so"
sha256sum "$ROOT/lanlink-agent-android/app/src/main/jniLibs/"*/libproot.so
