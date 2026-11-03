# Anvil TODO

## Done (v0.2.0)

- [x] Rename launcher / display name to **Anvil**
- [x] Operator UX 1–17 (Wi-Fi only, quiet hours, no-internet grace, MO verify, worker id, QR, paste/copy, subaddress tip, harm sheet, 100% warning, live settings, re-benchmark, battery meter, pause banner, notif actions, About, GitHub update check)
- [x] Mine / Wallet / Settings tabs
- [x] Wallet Save / Change Address (VoltMiner flow)

## Next — copy later (20–25)

These were the leftover VoltMiner miner features, without the game.

### 20. Hashrate 10s and 60s, accepted/rejected bigger

**Now:** Mine shows one number (`hashrate10s`). Accepted/rejected are two small cards that only appear while running. `hashrate60s` is already in `MiningState` and unused.

**Change:**
- Hero number = 60s if > 0, else 10s (same as VoltMiner — 60s is the number people compare).
- Sub-line: `10s  xxx H/s · 60s  yyy H/s` so the jumpiness of 10s is visible.
- Keep accepted/rejected on Mine even when idle (last session, or `0`). Bigger type, same row as now.

### 21. Session totals on-device

**Now:** accepted / rejected / uptime reset when XMRig stops. Nothing survives a kill.

**Not this:** VoltMiner’s Firestore mining-day streak. No cloud, no clan gate.

**Change:** SharedPreferences, this device only.
- Per session: hashes (10s × time is fine), accepted, rejected, duration, ended-at.
- Lifetime: sessions, hashes, accepted. Show under the log or on Settings — one quiet block, not a dashboard.
- Wipe with uninstall (same as wallet). No export.

### 22. Share a stats image — not clan rank

**Now:** nothing to share.

**Not this:** VoltMiner flex card (clan name, rank, flag, download QR).

**Change:** one PNG from Mine (share icon while running or after a session):
- Anvil, current or last H/s (60s), worker `AX-…`, **0% fee**, pool `gulf.moneroocean.stream` TLS
- Optional truncated wallet (`4…xxxx`)
- Link: GitHub Releases, not voltminer.net
- System share sheet only. No in-app social.

### 23. `monero:` / share-sheet intent

**Now:** `monero:` VIEW filter is on `MainActivity`; `maybeHandleMoneroIntent` parses and Save-commits. Untested against a real wallet app. No `ACTION_SEND`.

**Change:**
- Keep `monero:` / `monero:<addr>`. Open Wallet in edit mode with the address filled; user still taps Save (don’t silent-overwrite a saved wallet).
- Add `ACTION_SEND` `text/plain` so Cake / Monerujo “share address” can land in Anvil.
- Same parse as QR (`Wallet.parse`). Invalid → Wallet tab + error, no toast-only fail.

### 24. Start-diff as an advanced field

**Now:** `MiningService.START_DIFFICULTY = 30000` is a compile constant. XMRig already sends `wallet+30000` to MoneroOcean (VarDiff start). `0` = pool auto. No UI. VoltMiner hardcodes this too.

**Change:** Settings → Advanced (collapsed).
- Number field, default **30000**. Empty / 0 = pool auto (`wallet` with no `+N`).
- Persist in Prefs. Changing it while mining restarts XMRig (same as threads — confirm).
- One-line hint: “Higher = fewer small shares, slower first accept. Leave 30000 unless you know.”
- Not on Mine. Not a slider.

### 25. Custom pool host / port / TLS

**Now:** host/port/TLS are constants: `gulf.moneroocean.stream:20032` TLS on. `XMRigManager` already takes `poolUrl` + `poolTls`. UI cannot change them. Donate stays 0% in the config template regardless of pool.

**Change:** Settings → Advanced, under start-diff.
- Host, port, TLS switch. Defaults = current MoneroOcean TLS. A **Reset to MoneroOcean** control.
- Persist in Prefs. Changing pool while mining restarts XMRig (confirm).
- “Verify on MoneroOcean” only if host is still `*.moneroocean.stream`. Otherwise hide or swap for “open pool URL” if we have one.
- Worker id (`AX-…`) still goes as XMRig `rig-id` / pass. Don’t invent pool-specific usernames.
- Keep `"donate-level": 0` even off MoneroOcean — Anvil’s cut stays 0. (XMRig-dev donate is a different flag; leave it 0.)
- TLS default **on**. Turning it off needs a one-time warning (cleartext wallet to the pool).
- Don’t build a pool picker / nicehash / failover list. One host.

## Later (not 20–25)

- 18. Short onboarding (0% cut, heat, set wallet). Skip clan slides.

---

# Performance track (the Android optimisation thread)

Split from the browser-miner thread on 2026-10-06. Everything above is *features*; this is
*hash rate and efficiency*. Rule for this section: each item carries the evidence that put
it here, so nobody re-derives it. Ordered by expected value. See
`voltminer-web/docs/BACKLOG.md` for the sibling thread.

## Already verified good — do not re-open

- **RandomX A64 native JIT is on.** `common.hpp` sets `RANDOMX_HAVE_COMPILER 1` +
  `JitCompilerA64` whenever `__aarch64__` is defined, which the NDK sets. The phone runs
  JIT-compiled RandomX. (The *browser* build is the one without a JIT — different problem,
  different thread.)
- **Hardware AES is on.** Chain verified, not assumed:
  `cmake/cpu.cmake:119` probes `-march=armv8-a+crypto` for ARM8 + Clang → the probe
  **succeeds** with NDK 28.2 (`Performing Test XMRIG_ARM_CRYPTO - Success`,
  `XMRIG_ARM_CRYPTO:INTERNAL=1`) → `cmake/flags.cmake:79-80` applies it to C and CXX flags →
  defines `__ARM_FEATURE_CRYPTO` → RandomX `HAVE_AES 1` (`intrin_portable.h:473`) →
  `randomx.cpp:57` requests `RANDOMX_FLAG_HARD_AES`.
  This chain matters because `HAVE_AES` is a **compile-time** constant: had the probe failed,
  `randomx.cpp` would never request hard AES even on a phone that has it, and because
  `jit_compiler_a64.cpp:177` patches in `..._soft_aes`, **the JIT itself would run software
  AES**. The Android arm64 baseline does not define the macro on its own — only
  `-march=armv8-a+crypto` does. We are shipping the fast path.
- Release hardening is present (`-funroll-loops -fmerge-all-constants`, xmrig's Clang branch).
  `-DCMAKE_BUILD_TYPE=Release` is enough because xmrig's own cmake adds the arch flags.

## 0. Enabling step: an on-device A/B benchmark

There is no repeatable measurement, so every item below is currently an argument rather than
a number. Before optimising: pin a device, record `xmrig --bench=250K` (fast + light) plus a
real-pool session, and script the flags matrix so each change is measured. Without this,
items 1–4 will be guesswork.

## 1. `-DWITH_HWLOC=OFF` — the highest-expected-value gap

hwloc gives xmrig L3 cache size (to size threads/dataset sanely) and CPU affinity. Without
it, on a big.LITTLE SoC mining threads can land on efficiency cores and cannot be pinned to
the performance cluster. Desktop xmrig normally has it ON; we turned it off in the recipe
because it is awkward to build for Android. This is a *choice*, not a platform limit — so
it is the first thing to test. Needs: hwloc cross-built for `arm64-v8a`, then A/B on device.

## 2. `-march=armv8-a+crypto` is a floor, not a tune

armv8-a is Cortex-A53-era plus AES. No scheduling for A76/A78/X-series cores, no dotprod.
That is the *right* call for one APK that must run on every phone (an A78-tuned binary
SIGILLs on an old device) — so this is not a bug, it is a ceiling. The upgrade path is a
second binary with `-mcpu=<core>+crypto` and runtime selection by core, for maybe 5–15%.
Complexity is real; only worth it after item 0 exists.

## 3. No LTO

`-flto` is absent. Typically a few percent. Cheap to test once item 0 exists.

## 4. Huge pages for the 2 GB dataset — likely the largest single loss, and the hardest

Android generally will not give 2 MB pages to an app, and RandomX benefits substantially from
them on desktop. No compile flag fixes this. Investigate THP/`madvise` behaviour and xmrig's
huge-pages options; if the answer is "not available on Android", *write that down here* so it
stops being re-investigated.

## 5. Thread count and affinity policy

Check what the app actually passes: thread slider default, whether it caps at performance
cores, `--cpu-priority`, and whether `rig-id`/pass changes anything pool-side. Related to
item 1 — hwloc is what would make the "one thread per performance core" policy expressible.

## 6. Vendored RandomX version

`v6.25.0-mo1` vendors a RandomX snapshot. Diff against current upstream for perf fixes that
landed after it was vendored.

## Cross-repo, same thread

- **Strip the V1 dev-fee machinery** — `onboarding_screen.dart:88`, the `FeeScheduler` call
  sites, `dev_fee_obfuscation.dart`. This is the Flutter product (`voltminer`), same Android
  thread, different repo. Entry already written in that repo's `TODO.md`.
- **GPL-3 propagation decision** if the APK ships Anvil/XMRig — same repo, `voltminer`.
- **Untracked leftovers now gitignored**: `anvil-release.keystore`, `xmrig-tls-candidate`
  (7.2 M), `xmrig-no-tls-backup-2.4mb` (2.3 M). Decide keep-or-delete; they are no longer a
  commit hazard but they are clutter.
- **`tools/web-stratum-bridge/`** (208 K) belongs to the *browser* thread — it is the web
  miner's relay and it now exists in both repos. Move it to `voltminer-web` and delete it
  here.

