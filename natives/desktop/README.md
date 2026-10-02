# Desktop natives

`libmonerujo` for the desktop JVM target: `linux-x64`, `windows-x64`, `macos-arm64`.
It is the same JNI layer as on Android (`monerokit/src/main/cpp`, `monerokit/CMakeLists.txt`)
linked against monero 0.18.3.4 with the patches the Android libraries carry.

The monero sources are not in this repository: they are the local tree
`~/Work/xmrwallet/external-libs/monero` with uncommitted Trezor patches. Building therefore
happens on a machine that has that tree; consumers (JitPack, CI) download the result.

## Build

```sh
natives/desktop/build.sh windows-x64   # Docker (ubuntu 20.04, amd64), mingw-w64 posix threads
natives/desktop/build.sh linux-x64     # Docker (ubuntu 20.04, amd64), glibc >= 2.31
natives/desktop/build.sh macos-arm64   # Docker cross-build (depends clang + SDK), ad-hoc codesign on the Mac
```

The macOS build runs on an Apple Silicon Mac with Docker and JDK 21 (its `jni.h`): boost 1.64 from
depends does not build with current Xcode, so it is cross-built the way monero's own CI does.

Environment: `MONERO_SRC` (monero tree, read-only), `MONERO_DESKTOP_BUILD`
(work directory, default `~/Work/.monero-desktop-build`, one subdirectory per target), `JOBS`.

Steps, per target:

1. `rsync` of the monero tree into `$MONERO_DESKTOP_BUILD/<target>/src`, the original is only read.
   `SOURCE.manifest` lists the sha256 of every copied file.
2. `patches/trezor-desktop-transport.patch` lets `TREZOR_ANDROID_TRANSPORT` build outside Android
   (the transport is the plain C `monero_external_signer_*` ABI). Path `Trezor:android` and
   `TREZOR_ANDROID_COLD_ONLY` stay as they are: both are part of the wallet files' behaviour.
   `patches/boost-1.64-optional.patch`: boost 1.64 in depends lacks `optional::has_value()`
   (Android builds with boost 1.70).
3. `contrib/depends` builds boost, openssl, unbound, sodium, protobuf, ... for the host.
4. monero `wallet_api` with the Android flags (`MONERUJO`, Trezor with the external-signer
   transport, `STATIC`, `Release`) — the archives are collected in `<target>/out`.
5. `monerokit/CMakeLists.txt` links `libmonerujo` as `RelWithDebInfo`, debug info is split off:
   `prebuilt/<target>/` gets the stripped library (shipped in the jar) and
   `*.debug` / `*.dSYM` (released as a separate symbols asset).

## Attestation

- `MONERO_TREE.sha256` (committed) — sha256 of `SOURCE.manifest`: which monero tree the natives
  were built from. Checked only where that tree exists (release script).
- `prebuilt/<target>/SOURCE.sha256` — sha256 over the git blob hashes of the inputs in this
  repository (`monerujo.cpp`, `monerujo.h`, `monerokit/CMakeLists.txt`, `natives/desktop/**`),
  the same digest `git ls-files -s` gives once they are committed unchanged. Publishing compares
  it with the checkout, so natives from older sources cannot be released.
