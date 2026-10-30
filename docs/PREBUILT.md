# Prebuilt `libxmrig.so`

The binary is gitignored (7+ MB). Copy it in before a local build:

```bash
mkdir -p app/src/main/jniLibs/arm64-v8a
cp prebuilt/libxmrig.so app/src/main/jniLibs/arm64-v8a/libxmrig.so
```

Shipped `v6.25.0-mo1` (TLS on), SHA-256:

```
1f86e6fe9272b84f0fc5365b4e143ad33269c594e7c28cc730cc49929d5059a8
```

Rebuild from source with [BUILD-XMRIG.md](BUILD-XMRIG.md) (NDK, TLS on, `v6.25.0-mo1`).
Do not drop in a random internet XMRig — Android needs the NDK build (`filesDir` is noexec; we exec from `nativeLibraryDir`).
