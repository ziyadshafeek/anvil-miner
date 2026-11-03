# Fork changes — ziyadshafeek/anvil-miner (phone only, mining only)

Target: NARZO 70 Pro 5G, Dimensity 7050, ARM64 only, Android.
Pool: gulf.moneroocean.stream:20032, TLS on. Donate-level 0 on MoneroOcean.
Safety, unchanged and not loosened: charging-only ON by default,
CPU pause 65°C / resume 55°C, battery pause 40°C / resume 37°C.
No rooting. Huge pages stay false (Android does not grant them).

## Changes in this fork

1. monero: intent fills edit mode only. It never auto-saves. Saving
   requires an explicit Save/Continue tap, and only a valid address
   (Wallet.isValid) can be saved. A scanned seed phrase fails
   validation and is never saved.
2. UpdateChecker repointed from upstream j3r0nim0/android-xmrig-monero
   to this fork, ziyadshafeek/anvil-miner. Settings has an opt-out
   switch; when off, no update-check network call is made at all.
3. Wallet UI: Wallet.mask() returns first 6 + last 4 only, "Not set"
   otherwise. The saved view shows the masked address only. The
   full-address copy button was removed. A 6-step in-app wallet flow
   (separate wallet, copy public address only, paste, check masked
   address, short test, check MoneroOcean yourself) sits on the Wallet
   tab, with a non-dismissible red "NEVER type your seed phrase here"
   banner and an "About your money" explainer (pool in the middle,
   ~0.003 XMR payout threshold, 73–104 day estimate at 400–600 H/s
   from the earlier docs, India 30% tax + 1% TDS note).
   No seed, private-key, or password field, storage, log, or
   transmission was added anywhere. Only the public address is stored,
   in SharedPreferences "settings", MODE_PRIVATE, and sent to the pool.
4. Big-core affinity: XMRigManager discovers cores on the device
   from /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq, sorts
   by max frequency descending, and emits affine_to_cpu per thread,
   big-first. No CPU index is hardcoded. Settings has a
   "Big cores only (2 threads, pinned)" preset. The config meta key
   now includes the mode and the affinity list, so switching mode
   forces a full config rewrite instead of a user-only patch that
   would keep the old thread array.
5. Test every rung: big-only, 25%, 50%, 75%, 100%. The winner is the
   highest SUSTAINED 60-second hashrate over 30 minutes at the safety
   limits, not the coolest run and not the highest 10-second burst.
   No rung is pre-declared the winner in code or in this doc.
6. Native build (release.yml, pinned MoneroOcean XMRig v6.25.0-mo1,
   NDK 28.2.13676358, platform 28, TLS ON, OpenSSL 3.4, arm64-v8a):
   WITH_ASM=ON, -O3, -march=armv8.2-a+crypto (safe for Cortex-A78,
   the target phone only), and interprocedural optimisation (LTO)
   attempted. XMRig's A64 JIT and hardware AES path are used as
   provided by XMRig; no hand-written RandomX code was added.
   Algo-perf benchmarking stays on, and the cached algo config is
   preserved across restarts so the phone does not re-benchmark on
   every restart — that is the dataset/algo-cache reuse in this build.
   The RandomX dataset itself is rebuilt by XMRig on each process
   start; it is not stored on disk, and this fork does not claim
   otherwise. Pool-assigned algo must be read from the log
   ("new job ... algo ...") and from the MoneroOcean dashboard,
   not from a light-algo headline hashrate.

## Latest GitHub ideas — used, and rejected, with reasons (checked 30 Oct 2026)

USED:
- VoltMiner / Anvil benchmark finding (June 2026, voltminer.net):
  big cores do most RandomX work; 8 threads can be slower than
  big-cores-only on Snapdragon 865 (-9%). Used as the reason for
  affinity + big-only preset, and for testing all rungs. Not copied
  as code; the idea was implemented independently in XMRigManager.
- Upstream Anvil performance note (6 Oct 2026 commit, in the
  upstream repo history): A64 JIT is on, hardware AES is on via
  XMRig's -march=armv8-a+crypto probe, WITH_HWLOC=OFF means no
  cache detection / affinity in the stock build, no LTO. Used to
  justify the affinity patch and the LTO + march step in CI.
- Upstream XMRig 6.25.0 Aarch64 JIT changes (better instruction
  selection) and 6.26.0 ARM64 fixes: noted, but NOT adopted by
  switching engines (see Rejected below), because the MoneroOcean
  fork's algo-perf feature is fork-only.

REJECTED / DEFERRED (not in this APK):
- MoneroOcean v6.26.0-mo5 (newest tag found 30 Oct 2026) and
  upstream XMRig 6.26.0 (RandomX v2, ARM64 fixes): deferred, not
  rejected forever. Reason: the pinned, audited engine in this fork
  is v6.25.0-mo1, the version in the original build docs and the
  VoltMiner benchmarks. Switching the engine without a phone test
  would be a blind change, and RandomX v2 is for the FCMP++
  stressnet, not mainnet Monero mining today. Revisit only after
  the mo1 APK is measured on the phone.
- Crypto-Steer native Android miner (6 Aug 2026, Monero Observer):
  rejected for this build. It is a separate native RandomX engine,
  not XMRig, with a 2% dev contribution, and its pool was reported
  temporarily down. No code or binary from it was used.
- Primo ARM Miner (ARM intrinsics / AArch64 assembly, Verus +
  RandomX): rejected for this build. It is a different miner, not
  the audited Anvil/XMRig path, and its RandomX numbers are on
  different hardware (RK3588, Galaxy S10+). No code or binary used.
- XMRig-for-Android / bitcointalk ARM binaries, and any
  prebuilt libxmrig.so from the internet: rejected. This build
  compiles the native binary from pinned source in CI only.
- Browser / WebAssembly RandomX miners (VoltMiner web experiment):
  rejected for this build. Scope is a native phone app, not a
  browser miner, and Wasm RandomX is a toy hashrate by its own
  description.
- Huge pages, MSR mods, rooting, disabling thermal/battery
  pauses, and forcing a light algo by hand: rejected. Android
  does not grant huge pages, MSR is not an Android path, and
  loosening safety or faking the algo would raise heat, wear,
  or rejected shares without a measured gain.

## Not measured yet — do not quote as results

No hashrate, % improvement, or $/month figure in this doc is a
measured result from this fork on the Dimensity 7050. The only
numbers that count are the ones recorded on the phone: 60-second
hashrate at 5, 15, and 30 minutes per rung, accepted/rejected
shares, CPU and battery temperature, pauses, and the MoneroOcean
dashboard pending balance. Until that ladder is run, every
improvement claim is unknown.
