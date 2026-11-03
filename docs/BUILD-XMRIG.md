# Building the bundled XMRig binary

`assets/binaries/xmrig` is **not** in git (gitignored; the preBuild Gradle task copies it to
`android/app/src/main/jniLibs/arm64-v8a/libxmrig.so` — that's how the binary becomes executable on API 29+
where `filesDir` is noexec). It's a **self-compiled** MoneroOcean fork (`v6.25.0-mo1`, ARM64), cross-built
with the **Android NDK**. This recipe is **proven** — built and verified on-device 2026-06-24 (`"tls":true`
loads, real **TLS 1.3** handshake to MoneroOcean, `DONATE 0%` direct).

> **TLS:** the *original* binary was built `-DWITH_TLS=OFF` (no OpenSSL — `"tls":true` rejected, direct
> mining cleartext). The recipe below builds it **`-DWITH_TLS=ON`** so direct mining gets TLS on the wire.
> TLS-on adds OpenSSL static → binary ~**7.5 MB** (vs ~2.4 MB no-TLS).

## Toolchain & the three gotchas (all hit + fixed)
- **Android NDK 28.2.13676358** — use its CMake toolchain, *not* Docker/QEMU on the host (a Linux build
  links glibc/OpenSSL dynamically and won't run on Android). `export NDK=~/Library/Android/sdk/ndk/28.2.13676358`
- **① `ANDROID_PLATFORM=28`, not 26** — libuv uses `posix_spawn*`, which Android only *declares* at API ≥ 28.
  Platform 26 → `call to undeclared function 'posix_spawnattr_init'` and the build dies.
- **② `-DCMAKE_POLICY_VERSION_MINIMUM=3.5`** — modern CMake (4.x) refuses these projects' old
  `cmake_minimum_required`. This flag restores compatibility.
- **③ Empty `libpthread.a` / `librt.a` stubs** — NDK 28's bionic libc *contains* pthread/rt, so there's no
  separate lib, but OpenSSL's link interface still requests `-lpthread -lrt` → `ld.lld: unable to find
  library`. Create empty static archives and add their dir to the link path.

## Recipe (proven — produces the TLS-enabled binary)

```bash
export NDK=~/Library/Android/sdk/ndk/28.2.13676358
NCPU=$(sysctl -n hw.ncpu)                 # macOS (Linux: nproc)
SR=$PWD/sysroot                            # install prefix for libuv + OpenSSL
HOSTTAG=darwin-x86_64                       # NDK prebuilt host (Linux: linux-x86_64)

git clone --depth 1 --branch v6.25.0-mo1 https://github.com/MoneroOcean/xmrig.git
git clone --depth 1 https://github.com/libuv/libuv.git
git clone --depth 1 --branch openssl-3.4   https://github.com/openssl/openssl.git

# 1) libuv → static (★ platform 28, ★ policy flag)
cmake -S libuv -B libuv-build \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=28 \
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DLIBUV_BUILD_TESTS=OFF \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5
cmake --build libuv-build -j"$NCPU"
cmake --install libuv-build --prefix "$SR"

# 2) OpenSSL → static (the only TLS-specific dependency)
( cd openssl
  export ANDROID_NDK_ROOT=$NDK
  export PATH=$NDK/toolchains/llvm/prebuilt/$HOSTTAG/bin:$PATH
  ./Configure android-arm64 -D__ANDROID_API__=28 no-shared no-tests no-docs --prefix="$SR"
  make -j"$NCPU" && make install_sw )          # core libs build first; a late cmake-export target may
                                                # "Terminate" — harmless, libssl.a/libcrypto.a are done

# 3) pthread/rt stubs (★)
mkdir -p stublibs
"$NDK/toolchains/llvm/prebuilt/$HOSTTAG/bin/llvm-ar" crs stublibs/libpthread.a
"$NDK/toolchains/llvm/prebuilt/$HOSTTAG/bin/llvm-ar" crs stublibs/librt.a

# 4) xmrig (★ WITH_TLS=ON, OpenSSL paths, ★ stub link path, ★ policy flag)
cmake -S xmrig -B xmrig-build \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=28 -DCMAKE_BUILD_TYPE=Release \
  -DWITH_TLS=ON -DOPENSSL_ROOT_DIR="$SR" \
  -DOPENSSL_SSL_LIBRARY="$SR/lib/libssl.a" -DOPENSSL_CRYPTO_LIBRARY="$SR/lib/libcrypto.a" \
  -DOPENSSL_INCLUDE_DIR="$SR/include" \
  -DWITH_OPENCL=OFF -DWITH_CUDA=OFF -DWITH_NVML=OFF -DWITH_HWLOC=OFF \
  -DUV_LIBRARY="$SR/lib/libuv.a" -DUV_INCLUDE_DIR="$SR/include" \
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DCMAKE_EXE_LINKER_FLAGS="-L$PWD/stublibs"
cmake --build xmrig-build -j"$NCPU"
cp xmrig-build/xmrig assets/binaries/xmrig    # preBuild copies it → jniLibs/libxmrig.so
```

For a **no-TLS** build (the original), drop steps 2 + 3, set `-DWITH_TLS=OFF`, and remove the OpenSSL/stub
flags from step 4.

## Verify
```bash
strings assets/binaries/xmrig | grep -iE 'OpenSSL 3'      # → "OpenSSL 3.4.7…"  (TLS linked)
# on-device (adb): a config with "tls":true on :20032 should load and log
#   net  use pool gulf.moneroocean.stream:20032 TLSv1.3
# and the banner should show  * DONATE 0%  (direct → fork waives)
```

## Donate
Mining **direct** to MoneroOcean makes the fork waive its donate → **0%** (the 1% only appears
*through* a proxy). If you ever mine through a proxy and want 0% anyway, zero it in source before
building: `src/donate.h` → `kDefaultDonateLevel = 0` and `kMinimumDonateLevel = 0`.

## Reproducible build (trust asset)

Same recipe in a pinned container so anyone can rebuild and confirm the shipped binary matches source —
the "verify before you trust" signal from the marketing plan. Pin exact source commits before release.

```dockerfile
# docs/xmrig.Dockerfile  —  docker buildx build -f docs/xmrig.Dockerfile -o type=local,dest=out .
FROM debian:12-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends \
      ca-certificates curl git unzip cmake make perl build-essential && rm -rf /var/lib/apt/lists/*
RUN curl -fsSLo /tmp/ndk.zip https://dl.google.com/android/repository/android-ndk-r28b-linux.zip \
 && unzip -q /tmp/ndk.zip -d /opt && mv /opt/android-ndk-* /opt/ndk
ENV NDK=/opt/ndk  HOSTTAG=linux-x86_64
WORKDIR /src
RUN git clone --depth 1 --branch v6.25.0-mo1 https://github.com/MoneroOcean/xmrig.git \
 && git clone --depth 1 https://github.com/libuv/libuv.git \
 && git clone --depth 1 --branch openssl-3.4 https://github.com/openssl/openssl.git
# then run steps 1–4 above (NCPU=$(nproc), HOSTTAG=linux-x86_64), and: cp /src/xmrig-build/xmrig /out/
```
