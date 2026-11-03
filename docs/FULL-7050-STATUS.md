# Full-7050 status — Dimensity 7050 mining-only APK2 upgrade

Branch: `full-7050`, based on `apk2` at `e0c0bef`.
Date of work: 2026-11-03. No push, no tag, no signing, no publishing.
No keystore, password, wallet, seed or private-key code was touched.
Safety pauses unchanged: CPU 65 °C / resume 55 °C, battery 40 °C /
resume 37 °C, charging-only default ON.
Pool default unchanged: `gulf.moneroocean.stream:20032`, TLS on.

**No on-device benchmark exists for this branch.** No H/s, temperature,
or AnonHugePages figure in this file is a measured result from this
branch, because no device benchmark was run (see Verification, below).
Any figure quoted elsewhere for APK2 (~500 H/s) is from earlier APK2
measurements, not from this branch.

## Verdict summary

| # | Item | Verdict |
|---|------|---------|
| 1 | L3-isolated big-core + asymmetric dual-cluster | PARTIAL — per-algo arrays implemented; simultaneous dual-algo NOT done, and NOT claimed |
| 2 | 2-stream NEON pipelining + CFROUND caching in RandomX JIT | PARTIAL — native dual-stream AES/FP64/PMULL module implemented and compile-checked; XMRig A64 dead-CFROUND patch created and applies cleanly, full build NOT proven; full intrinsic SHA-256 schedule NOT implemented |
| 3 | posix_memalign(2 MB) + madvise THP + smaps_rollup UI readout | PARTIAL — wrapper native allocator + app/XMRig-child smaps readout implemented; XMRig dataset allocator NOT changed; huge-pages stays false |
| 4 | Mali-G68 Vulkan 1.1 compute co-miner | PARTIAL — real Vulkan SHA-256 benchmark kernel + host code, compile-checked, GLSL compiled to SPIR-V; pool-valid RandomX/Verus GPU mining NOT implemented |
| 5 | MoneroOcean algo-perf + merge-mining presets | PARTIAL — algo-perf verified as already present in the fork and preserved by APK2; 10001 / HashVault+Tari / DragonX / Verus PBaaS NOT added as presets (unverified) |

## 1. L3 isolation + asymmetric — PARTIAL

CORRECTION 2026-11-03 (after the batch handoff, direct source read):
The per-algo arrays as first committed used
`{"low_power_mode":false,"affine_to_cpu":N}` objects. That format is
NOT parsed by XMRig v6.25.0-mo1: `src/backend/cpu/CpuThread.cpp`
parses only `[intensity, affinity]` arrays or plain integer
affinities, and `src/` contains zero occurrences of `affine_to_cpu`
or `low_power_mode` (those keys appear only in old `doc/api/`
examples). An object element therefore silently became an unpinned
thread (affinity -1, intensity 1), no matter what the APK log line
said. This also means APK2's earlier "big-only / pinned" claims
rested on the same unparsed format and were unpinned in practice
unless XMRig logged otherwise on-device — that must be re-checked
on-device, not assumed. The code has now been fixed to emit
`[1, cpu]` pairs, which the parser actually honours
(`CpuThread([1,6])` → intensity 1, affinity 6). The fix is
code-correct per the parser; pinning on the phone is still
unmeasured until the CI APK runs and its XMRig log shows the
pinned profile.

Implemented:
- New thread mode `THREAD_MODE_L3_ISOLATED = 3` (`Prefs.kt`), with a
  Settings switch (`l3Isolated`) mutually exclusive with big-only and
  asymmetric, and a label in `MainActivity`.
- Per-algo thread arrays in `XMRigManager`: `rx/0`, `rx/wow`,
  `cn-heavy`, `cn/ccx` use `__THREAD_ARRAY_RX__`; `cn-pico`,
  `cn-lite`, `cn`, `cn/r` use `__THREAD_ARRAY_LIGHT__`.
  In L3-isolated mode, rx = big cores only (first 2 by sysfs max
  frequency), light = little cores only (the rest). In all other modes
  both arrays equal the previous single array, so behaviour is
  unchanged outside this mode. Meta key bumped to `v4` so switching
  mode forces a config rewrite.
- Template updated accordingly (`xmrig_config_template.json`).

Honest limit, verified in source (MoneroOcean/xmrig v6.25.0-mo1,
`src/backend/cpu/CpuBackend.cpp::setJob` and
`src/backend/common/Threads.h::get`):
- One XMRig process runs ONE pool-assigned algorithm at a time.
  On an algo change it stops and restarts workers with that algo's
  thread array. Per-algo arrays therefore select which cores run
  *when* an algo is assigned; they do not run rx/0 and cn-pico
  simultaneously. Simultaneous dual-algo would need two XMRig
  processes, two pool connections and two datasets, and is NOT done
  here (duplicate worker, double memory, thermal cost, pool risk).
- Nuance on the "L1/L2-resident" premise: cn-pico's 256 KB scratchpad
  is larger than the A55's 128 KB private L2 in the user's own
  hardware description, so light work can still touch shared L3.
  What L3-isolated mode actually guarantees is narrower and true:
  no little core is ever scheduled onto an rx/0 thread, so little
  cores cannot evict rx scratchpad lines *while rx is running*,
  because when a light algo runs, rx is not running.
- The existing asymmetric mode (2 big + 2 little, same algo) is
  unchanged and remains a test, not a claimed win.

Files: `Prefs.kt`, `XMRigManager.kt`, `MainActivity.kt`,
`tab_settings.xml`, `xmrig_config_template.json`.

## 2. NEON pipelining + CFROUND — PARTIAL

Implemented in the wrapper (new native module):
- `app/src/main/cpp/anvil_native.cpp`: dual-stream AES-128 using
  `vaeseq_u8` / `vaesmcq_u8` (two blocks in separate vector variables,
  interleaved per round), dual-stream FP64 using `vfmaq_f64`, and
  PMULL using `vmull_p64`. AES key expansion uses a programmatically
  generated S-box (GF inverse + affine transform), not a copied table.
  The JNI self-test checks block 0 against the FIPS-197 vector
  (key 000102…0f, plaintext 00112233…ff → 69c4e0d86a7b0430d8cdb78070b4c55a)
  and reports `aes_ok=1/0` — it does not assert a hashrate.
- ARM SHA-256 intrinsic path: only the first-16-rounds prefix using
  `vsha256hq_u32` / `vsha256h2q_u32` is present, explicitly labelled
  `NOT_IMPLEMENTED_FULL_SCHEDULE`. The full su0/su1 schedule is NOT
  implemented, produces no hash, and must not be quoted as verified
  SHA-256. A portable SHA-256 in the same file is used for the
  "abc" vector check (ba7816bf…015ad).
- `app/src/main/cpp/CMakeLists.txt` + `build.gradle.kts`
  `externalNativeBuild` wire the library `anvil_native` in, with the
  requested flags
  `-O3 -march=armv8.2-a+crypto+aes+sha2+dotprod+fp16 -mtune=cortex-a78 -flto -funroll-loops -fomit-frame-pointer`.
  `NativeBench.kt` loads it with a safe fallback if absent.

Implemented as a vendored XMRig patch (not as a comment):
- `patches/xmrig-a64-cfround-cache.patch` (51 insertions, 2 files:
  `src/crypto/randomx/jit_compiler_a64.cpp`,
  `src/crypto/randomx/jit_compiler_a64.hpp`).
  It adds `prevCFROUNDPos / prevCFROUNDLen / prevFPPos` tracking to
  `JitCompilerA64`, marks rounding-sensitive FP handlers
  (FADD_R/M, FSUB_R/M, FMUL_R, FDIV_M, FSQRT_R), invalidates on
  CBRANCH, resets per program in both `generateProgram` and
  `generateProgramLight`, and NOPs (0xD503201F) a previous CFROUND
  emission when the next CFROUND arrives with no FP use in between
  — the A64 analogue of the x86 JIT's existing prevCFROUND NOP-out
  (`jit_compiler_x86.cpp::h_CFROUND`).
  It does NOT skip `msr fpcr` when the mode is merely unchanged at
  runtime, because the mode is derived from a runtime register and a
  compile-time mode comparison is not available; a runtime
  mrs/cmp/branch guard would cost more than the rare CFROUND itself.
  CFROUND frequency in RandomX config is low, so any gain here is
  expected to be small — no gain figure is claimed.
- CI step added in `.github/workflows/release.yml`: after cloning
  xmrig, if the patch file exists in the checked-out tree, apply it
  with `git -C xmrig apply`; a failed apply fails the job, it is
  never skipped silently. If the file is absent (older tags), the
  build proceeds unpatched.

Patch verification, exact:
- `git apply --check` on a fresh `v6.25.0-mo1` clone: OK.
- `git apply` on that fresh clone: OK, `git diff --stat` shows the
  same 2 files / 51 insertions.
- Standalone TU compile of `jit_compiler_a64.cpp` with NDK clang++
  fails with 13 errors about missing `Log2_ScratchpadL3` /
  `Log2_DatasetBaseSize` etc. — and the UNPATCHED file fails with
  the identical errors, because those fields come from a
  CMake-selected RandomX configuration, not from this patch. So the
  patch is proven to apply cleanly, but NOT proven by a full XMRig
  build on this VM. The CI XMRig build is the required proof step.

Blocked / not done:
- True 2-stream interleaving inside RandomX's own JIT (two nonce
  streams through the RandomX VM) is not implemented; the wrapper
  module demonstrates the intrinsics, it does not rewrite the VM.
- No `sched_setaffinity` JNI worker threads were added, because
  XMRig workers are already pinned via config `affine_to_cpu`
  (`Worker::trySetThreadAffinity` in the fork); adding a second
  affinity layer in the wrapper would not change XMRig's threads.

## 3. Aligned allocator + THP readout — PARTIAL

Implemented in the wrapper:
- `anvil_aligned_alloc` in `anvil_native.cpp`: for sizes >= 256 KB,
  `posix_memalign(&p, 2097152, size rounded up to 2 MB)` then
  `madvise(p, aligned, MADV_HUGEPAGE | MADV_WILLNEED)`; the madvise
  return value is reported by the self-test (`madvise_rc`), never
  claimed as proof huge pages were granted. Smaller sizes use 64-byte
  alignment. This allocator serves native self-test/bench buffers
  only.
- Readouts, all labelled by process, in `ThpProbe.kt`:
  - app process: `/proc/self/smaps_rollup` AnonHugePages (existing
    from `e0c0bef`);
  - XMRig child: read-only scan of `/proc/<pid>/smaps_rollup` for a
    process whose cmdline contains `libxmrig`, returning
    `xmrig pid=<pid> AnonHugePages: … (child-process total, NOT
    dataset-only proof)` or a plain not-found/unreadable message.
  - `MiningService` SUSTAINED60 log now includes both readouts.
  - The app UI does not have a dedicated THP text field; the
    readouts appear in the SUSTAINED60 log lines (visible in the
    app's XMRig output log). A UI field was not added in this run;
    the log is the readout surface.

Honest limit, verified in fork source:
- XMRig's scratchpad pool (`VirtualMemory`, `CpuWorker.cpp`) is
  already 2 MB-aligned and already calls
  `madvise(..., MADV_HUGEPAGE)` via `adviseLargePages` when the
  alignment is >= the default huge-page size — even with
  `huge-pages=false`. The RandomX dataset/cache allocator
  (`DefaultAllocator` / `rx_aligned_alloc` with cache-line
  alignment) is NOT 2 MB-aligned and is NOT madvised by the fork.
  Changing that would mean patching RandomX cache allocation
  selection, which needs on-device THP evidence first and is NOT
  attempted here. The wrapper allocator cannot change the XMRig
  child's dataset.
- `huge-pages` stays `false` in the template, on purpose. It is not
  silently flipped to `true`, and no THP gain is claimed.

## 4. Vulkan co-miner — PARTIAL (benchmark kernel only)

Implemented, real code:
- `app/src/main/cpp/sha256.comp`: GLSL 450 compute shader, SHA-256
  compression over one 64-byte block per invocation, FIPS 180-4 K
  table, local size 64. Compiled with NDK `glslc` to SPIR-V
  (magic `03022307`, 2500 words, 9.8 KB) and embedded as
  `sha256_spv.h` (generated, do not hand-edit).
- `app/src/main/cpp/vulkan_miner.cpp`: Vulkan 1.1 host code via NDK
  `libvulkan.so` — instance, physical device, compute queue family,
  logical device, `HOST_VISIBLE | HOST_COHERENT` storage buffers,
  descriptor set, compute pipeline, command buffer, fence wait.
  Input element 0 is always the padded "abc" block; the function
  returns `GPU SHA256 TEST FAIL` unless element 0's digest equals
  `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`.
  On pass it returns the first nonce-block hash and the device name.
  Dispatch is padded to whole 64-thread workgroups so no invocation
  reads past the input buffer (a bug found and fixed during this run).
- `NativeBench.runGpuBenchOnce(1024)` + a Settings button
  ("Run native GPU bench (once, SHA-256 test)") that runs it on a
  background thread and shows/logs the result. It runs once, on tap
  only — it is not started automatically during mining.

Explicitly NOT claimed / NOT done:
- This is NOT a pool-valid RandomX or Verus GPU miner. RandomX needs
  a 2 MB scratchpad per thread and a 2,080 MB dataset; it does not fit
  a <= 16 KB shared-memory kernel, and no shared-memory benefit is
  claimed for this kernel (its 64-word schedule lives in private
  registers; the shared-memory staging originally drafted was removed
  because it was not load-bearing).
- No subgroup size is enforced: Valhall's 16-wide subgroups are a
  hardware property, not a shader guarantee, and no subgroup builtin
  is used. No `VK_MEMORY_PROPERTY` fallback is silently substituted:
  if HOST_VISIBLE|HOST_COHERENT is unavailable, the bench fails
  with a VkResult, it does not run on a different memory type.
- No stratum job parsing, no difficulty-target check, no share
  submission, no earnings. Any future pool-valid GPU miner is a
  separate, large piece of work (job parsing + target check + share
  encoding + a GPU algorithm whose memory actually fits the GPU).

## 5. Algo-perf + merge-mining — PARTIAL

Verified as already present (no new pool switch):
- In MoneroOcean/xmrig v6.25.0-mo1, `WITH_MO_BENCHMARK` defaults to
  ON (`CMakeLists.txt:29`), `MoBenchmark` benchmarks each algo, and
  `Network` sends `algo-perf` on login. The CI workflow does not
  turn this off, so a CI-built APK2 binary includes it. APK2
  preserves the config file across restarts (meta-key logic), so
  re-starts do not re-benchmark. GhostRider is a supported family
  in `CpuConfig::generate`. The pool then assigns jobs; on measured
  phones it assigned `rx/0`. No new algo-perf code was needed, and
  none is claimed as new.
- `PoolPresets.kt` documents the current default and the fork's own
  default, and is deliberately NOT wired into `MiningService`.

Not added, with reasons:
- `gulf.moneroocean.stream:10001`: third-party tables describe it as
  plaintext, starting difficulty 1000, "mobile", and the 10xxx →
  20xxx pattern would make 20001 its TLS twin. That is consistent
  but NOT an official MoneroOcean source fetched in this run, and
  the fork's own `src/config.json` default is 10128 plaintext, not
  10001. So 10001 is a candidate to test manually (connect, watch
  the XMRig log for "new job"), not a preset, and the default was
  NOT switched to it and TLS was NOT turned off for it.
- HashVault + Tari XTM merge: HashVault's own page
  (monero.hashvault.pro) shows pool.hashvault.pro, RandomX,
  PPLNS/SOLO, 0.9% fee, pool-config minimum 0.001 XMR but default
  wallet minimum 0.11 XMR, hourly payouts — and nothing about
  Tari/XTM, no Tari wallet field, no merge config. A third-party
  aggregator lists "Tari XTM + XMR merged" with no host, port, TLS
  flag, or wallet/coin/pass syntax. Tari's RandomX merge-mined lane
  (RxM) is real as a protocol lane, but that does not verify a
  HashVault preset, and no source says MoneroOcean algo-switching
  merge-mines or pays Tari XTM. NOT added.
- DragonX: the coin is real (custom RandomX, DRGX), but its pool
  endpoint rests only on single-author social screenshots
  (pool.dragonx.is:3433, plaintext, 1% pool / 3% solo) and its pool
  client is DRG-XMRIG, not stock MoneroOcean XMRig. NOT added.
- Verus PBaaS: LuckPool's own connect page verifies VerusHash 2.2
  + PBaaS at na./eu./ap.luckpool.net, CPU port 3960 plaintext,
  SSL 3958 (plus 3957/3956), username = VRSC address + `.rig`,
  password `X`, merge-mining only for R-addresses — but VerusHash
  is mined with ccminer/SRBMiner-class miners, not XMRig, so it
  cannot be added as an XMRig-MO pool entry. NOT added.
- MoneroOcean minimum payout is widely listed as 0.003 XMR in
  third-party tables (fee given as 0% in some, 1% in others — do
  not hard-code a fee); verify on moneroocean.stream before quoting
  it — the APK does not set payouts.
- The same caveat applies in reverse to the APK's own current
  default 20032: no official MoneroOcean port list was fetched in
  this run confirming it either; by the 10xxx → 20xxx pattern it
  looks like the TLS counterpart of 10032, but that is inference,
  not a verified fact. It is kept only as the existing default, and
  a live check of MoneroOcean's own Getting Started page is still
  owed before it is re-affirmed.

## Files changed (branch full-7050, uncommitted at the time of writing this file)

Modified:
- `app/src/main/java/io/github/j3r0nim0/anvil/Prefs.kt` — mode 3
- `app/src/main/java/io/github/j3r0nim0/anvil/XMRigManager.kt` — per-algo arrays, v4 meta key
- `app/src/main/java/io/github/j3r0nim0/anvil/MainActivity.kt` — L3 switch, GPU bench button, labels
- `app/src/main/java/io/github/j3r0nim0/anvil/MiningService.kt` — SUSTAINED60 THP readouts, native self-test log at start
- `app/src/main/java/io/github/j3r0nim0/anvil/ThpProbe.kt` — XMRig-child smaps scan
- `app/src/main/res/layout/tab_settings.xml` — L3 switch, GPU bench button + note
- `app/src/main/assets/xmrig_config_template.json` — rx/light arrays, huge-pages still false
- `app/build.gradle.kts` — CMake externalNativeBuild
- `.github/workflows/release.yml` — CFROUND patch apply step

New:
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/anvil_native.cpp`
- `app/src/main/cpp/sha256.comp`
- `app/src/main/cpp/sha256_spv.h` (generated by glslc)
- `app/src/main/cpp/vulkan_miner.cpp`
- `app/src/main/java/io/github/j3r0nim0/anvil/NativeBench.kt`
- `app/src/main/java/io/github/j3r0nim0/anvil/PoolPresets.kt`
- `patches/xmrig-a64-cfround-cache.patch`
- `docs/FULL-7050-STATUS.md` (this file)

Untouched, as instructed: keystores, passwords, wallet handling,
seed/private-key code, safety pauses, pool default.

## Verification — exact commands and outputs

All on this VM, 2026-11-03, host x86_64 (no qemu, no /dev/kvm, no
Android emulator; ARM64 code can be compiled here but cannot run here):

- `git diff --check` → exit 0, no output.
- Brace/paren balance (Python count) → all Kotlin and C++ files
  balanced, including `anvil_native.cpp` (43/43, 189/189) and
  `vulkan_miner.cpp` (63/63, 184/184).
- Template JSON parses after placeholder substitution → OK.
- NDK `glslc sha256.comp -o sha256.spv` → OK, SPIR-V magic
  `03022307`, 2500 words.
- NDK `aarch64-linux-android34-clang++ -std=c++17 -O3
  -march=armv8.2-a+crypto+aes+sha2+dotprod+fp16 -mtune=cortex-a78
  … -c anvil_native.cpp` → exit 0. First attempt failed with
  missing `<string>` include; fixed, recompiled OK. Disassembly of
  the non-LTO object shows interleaved `aese v0 / aesmc v0 /
  aese v2 / aesmc v2` and `fmla v0.2d` for the FP64 path, and the
  JNI symbol `Java_io_github_j3r0nim0_anvil_NativeBench_nativeCpuSelfTest`.
- Same compiler for `vulkan_miner.cpp` → exit 0 after fixing two
  goto-bypasses-initialisation errors (first attempt failed on
  those; fixed, recompiled OK). Symbol
  `Java_io_github_j3r0nim0_anvil_NativeBench_nativeVulkanSha256`
  present; object is `ELF 64-bit LSB relocatable, ARM aarch64`.
- XMRig patch: `git apply --check` on a fresh v6.25.0-mo1 clone → OK;
  `git apply` → OK, 2 files / 51 insertions. Standalone JIT TU
  compile fails identically with and without the patch (missing
  CMake-selected RandomX config fields) — see item 2.
- Local Gradle: `gradle-8.7/bin/gradle compileDebugKotlin
  --no-daemon` → FAILURE: "Could not receive a message from the
  daemon." This is the known VM sandbox IPC limitation, not a
  Kotlin syntax result. Compilation was never reached, so Kotlin
  correctness is NOT proven by a build here. CI is the only build
  route.
- `adb devices` (toolchain adb at
  `~/workspace/toolchains/android-sdk/platform-tools/adb`) →
  "List of devices attached" with no devices. The paired phone is
  not an adb device, so no install and no on-device benchmark was
  possible from this VM.
- Verification clones under `~/workspace/xmrig-verify*` were deleted
  after use; they are not part of the repo.

## Remaining steps — CI signed APK + on-device 60-second benchmark

These steps are NOT done and require the user / parent agent; do not
present this branch as a finished APK.

1. Parent agent (normal user turn, not a background turn): push
   branch `full-7050` to `ziyadshafeek/anvil-miner`, then create a
   release tag from that branch so `.github/workflows/release.yml`
   runs. Watch the `build-xmrig` job: it must apply
   `patches/xmrig-a64-cfround-cache.patch` without error and then
   build XMRig with the new flags. If the patch or the full XMRig
   build fails in CI, stop — that is the proof step this VM could
   not provide.
2. Sign the CI APK with the existing fork key (same key as APK2;
   uninstall-first rule applies if the old first APK1 was ever
   installed). Do NOT install an unsigned CI APK as a daily miner.
3. On the phone, with the phone charging, case off, and safety
   pauses on: install, paste the existing Monero wallet address
   (public address only, never a seed), and run, in order, 60
   seconds each at minimum (30 minutes per rung is the APK2
   protocol for a sustained verdict; 60 seconds is only a smoke
   check):
   - big-only (existing switch),
   - L3-isolated per-algo (new switch),
   - asymmetric (existing switch).
   Record from the app log for each rung: SUSTAINED60 60s H/s,
   accepted/rejected, CPU and battery temperatures, and both THP
   readouts (app + XMRig child). Do not use 10s H/s.
   Also check XMRig's own startup log for the thread profile it
   actually used: with the corrected `[1, cpu]` format it must show
   the intended affinities; if it shows unpinned threads, the
   affinity fix has failed and no L3-isolation conclusion may be
   drawn.
4. Tap Settings → "Run native GPU bench (once, SHA-256 test)" and
   record the exact message. `GPU SHA256 TEST FAIL` means the kernel
   is not trusted, full stop. `GPU SHA256 OK` means only that the
   SHA-256 kernel hashes correctly; it is not GPU mining income.
5. Check the mining-start log line for the native CPU self-test:
   `aes_ok` must be 1 and `sha_portable_ok` must be 1. If either is
   0, the native module is wrong on this phone regardless of any
   other result.
6. Only after steps 3–5 produce real log lines may anyone report a
   before/after H/s, temperature, or AnonHugePages comparison for
   this branch. Until then, the honest statement is: code written
   and compile-checked where the VM allows, not benchmarked.
