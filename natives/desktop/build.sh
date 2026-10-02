#!/usr/bin/env bash
# Builds libmonerujo for one desktop target into natives/desktop/prebuilt/<target>/.
# Usage: natives/desktop/build.sh <linux-x64|windows-x64|macos-arm64>
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
source "$HERE/lib.sh"

IMAGE=monerokit-desktop-natives
MONERO=${MONERO_SRC:-$HOME/Work/xmrwallet/external-libs/monero}
WORK=${MONERO_DESKTOP_BUILD:-$HOME/Work/.monero-desktop-build}

target_config() {
    case $1 in
        linux-x64) HOST=x86_64-linux-gnu LIB=libmonerujo.so ;;
        windows-x64) HOST=x86_64-w64-mingw32 LIB=monerujo.dll ;;
        # cross-built like monero's own CI: boost 1.64 does not build with current Xcode
        macos-arm64) HOST=aarch64-apple-darwin11 LIB=libmonerujo.dylib ;;
        *) echo "usage: $0 <linux-x64|windows-x64|macos-arm64>" >&2; exit 2 ;;
    esac
}

# --- host side ---

sync_sources() {
    local dir=$WORK/$TARGET excludes=() e
    for e in "${MONERO_TREE_EXCLUDES[@]}"; do excludes+=(--exclude "$e"); done
    mkdir -p "$dir/src"
    rsync -a --delete "${excludes[@]}" "$MONERO/" "$dir/src/"
    monero_tree_manifest "$dir/src" > "$dir/SOURCE.manifest"
    local p
    for p in "$HERE"/patches/*.patch; do patch -p1 -F0 -s -d "$dir/src" -i "$p"; done
}

attest() {
    local kit=$HERE/../.. out=$HERE/prebuilt/$TARGET
    sha256_of < "$WORK/$TARGET/SOURCE.manifest" > "$HERE/MONERO_TREE.sha256"
    source_hash "$kit" > "$out/SOURCE.sha256"
    if [[ -n $(git -C "$kit" status --porcelain -- "${SOURCE_HASH_INPUTS[@]}") ]]; then
        echo "warning: SOURCE.sha256 attests uncommitted inputs; commit them unchanged" >&2
    fi
}

# --- container side ---

build_depends() {
    local extra=()
    # Static sodium is linked into a shared library; depends builds it non-PIC on linux.
    [[ $TARGET == linux-x64 ]] && extra+=(sodium_config_opts_linux=--with-pic)
    # Package builds stay off the bind mount: macOS refuses the mode-0 files cctools `ar x` creates.
    extra+=(base_build_dir=/tmp/depends/build base_staging_dir=/tmp/depends/staging)
    # No -j: GNU make 4.2 loses the jobserver inside the packages' recursive installs.
    make -C "$W/src/contrib/depends" HOST="$HOST" "${extra[@]}"
}

build_monero() {
    local depends=$W/src/contrib/depends/$HOST
    cmake -S "$W/src" -B "$W/monero" -DCMAKE_TOOLCHAIN_FILE="$depends/share/toolchain.cmake" \
        -DMONERUJO=ON -DUSE_DEVICE_TREZOR=ON -DUSE_DEVICE_TREZOR_LIBUSB=OFF -DTREZOR_ANDROID_TRANSPORT=ON \
        -DBUILD_GUI_DEPS=ON -DSTATIC=ON -DBUILD_TESTS=OFF -DSTACK_TRACE=OFF -DCMAKE_BUILD_TYPE=Release
    make -C "$W/monero" -j"$JOBS" wallet_api

    rm -rf "$W/out" && mkdir -p "$W/out"
    find "$W/monero" -name '*.a' -exec cp {} "$W/out/" \;
    local lib
    for lib in "$depends"/lib/*.a; do
        # boost "tagged" names (libboost_thread_win32-mt-s.a) -> the plain names CMakeLists uses
        cp "$lib" "$W/out/$(basename "$lib" | sed -E 's/_win32//; s/-mt(-s)?\.a$/.a/')"
    done
}

link_monerujo() {
    # Same compilers and flags as the monero archives; the depends toolchain pins Release.
    local toolchain=$W/monerujo-toolchain.cmake
    printf 'include(%s)\nset(CMAKE_BUILD_TYPE RelWithDebInfo)\n' \
        "$W/src/contrib/depends/$HOST/share/toolchain.cmake" > "$toolchain"
    rm -rf "$W/monerujo"
    cmake -S "$K/monerokit" -B "$W/monerujo" -DCMAKE_TOOLCHAIN_FILE="$toolchain" \
        -DMONERO_DESKTOP_LIBS="$W/out" -DJNI_HEADER_DIR="$JNI"
    make -C "$W/monerujo" -j"$JOBS"
}

# --- shipped library + separate debug info ---

split_debug_info() {
    local out=$K/natives/desktop/prebuilt/$TARGET
    rm -rf "$out" && mkdir -p "$out"
    cp "$W/monerujo/$LIB" "$out/"
    cd "$out"
    local tools=
    case $TARGET in
        windows-x64) tools=$HOST- ;;
        macos-arm64) tools=$W/src/contrib/depends/$HOST/native/bin/$HOST- ;;
    esac
    if [[ $TARGET == macos-arm64 ]]; then
        # here, not on the Mac: the debug map points at the object files under /work
        dsymutil "$LIB" -o "$LIB.dSYM"
        "${tools}strip" -x "$LIB"
    else
        "${tools}objcopy" --only-keep-debug "$LIB" "$LIB.debug"
        "${tools}strip" --strip-debug "$LIB"
        "${tools}objcopy" --add-gnu-debuglink="$LIB.debug" "$LIB"
    fi
}

if [[ ${1:-} == --inside ]]; then
    TARGET=$2; target_config "$TARGET"
    W=/work K=/kit JOBS=${JOBS:-$(nproc)}
    case $TARGET in
        linux-x64) JNI=/opt/jdk-linux/include ;;
        windows-x64) JNI=/opt/jdk-windows/include ;;
        macos-arm64) JNI=/work/jni ;;
    esac
    build_depends
    build_monero
    link_monerujo
    split_debug_info
    exit
fi

TARGET=${1:-}; target_config "$TARGET"
sync_sources
if [[ $TARGET == macos-arm64 ]]; then
    rm -rf "$WORK/$TARGET/jni"
    cp -R "$(/usr/libexec/java_home -v 21)/include" "$WORK/$TARGET/jni"
fi
docker build -q --platform linux/amd64 -t "$IMAGE" "$HERE" >/dev/null
docker run --rm --platform linux/amd64 ${JOBS:+-e JOBS="$JOBS"} -v "$WORK/$TARGET:/work" \
    -v "$(cd "$HERE/../.." && pwd):/kit" "$IMAGE" /kit/natives/desktop/build.sh --inside "$TARGET"
# arm64 macOS refuses unsigned code; the cross linker does not sign
[[ $TARGET == macos-arm64 ]] && codesign -f -s - "$HERE/prebuilt/$TARGET/$LIB"
attest
echo "built $HERE/prebuilt/$TARGET/$LIB"
