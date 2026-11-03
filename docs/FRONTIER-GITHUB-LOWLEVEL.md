# Frontier Workstream G — GitHub low-level experimental miners, patches and forks

Scope: mining only, low level only. Target in parent context: NARZO 70 Pro 5G, Dimensity 7050, Android ARM64, Anvil fork, direct to MoneroOcean, direct to user's own wallet.
No repo files were edited by this workstream. This is an audit / inspiration report only.

## Verdict first

No Sept–Oct 2026 GitHub advance found beats, or safely replaces, the current base:
**Anvil + MoneroOcean XMRig v6.25.0-mo1, direct to MoneroOcean, donate-level 0, TLS on.**

What the frontier actually gave us is:
- one upstream patch set worth auditing line-by-line (XMRig 6.26 ARM64 fixes),
- one 0%-fee rebuild method corroborated Oct 2026 (VoltMiner),
- a RISC-V vectorisation pattern worth translating to NEON, not copying,
- a RandomX v2 prefetch idea worth testing, not the v2 algorithm itself,
- several new Android miners to watch, not install,
- and a hard supply-chain warning that is more important than any speed claim.

**No new repo found with a Dimensity 7050 benchmark, and no new repo found with a phone payout proof beating MoneroOcean rx/0.** Any speed % below is the repo's own claim, on its own hardware, not a Dimensity 7050 result. Do not quote it as a phone gain until measured on the NARZO.

## How to read the verdicts

- USE = audit it, test it on device, it may go into the build.
- USE FOR INSPIRATION = steal the idea / method, not the binary or engine.
- WATCH = do not install, do not base on it, monitor for a benchmark + payout + audit.
- REJECT = do not install, do not copy, reason given. Rejection is not a judgement on the whole coin, only on using it for this phone build.

## USE — upstream XMRig patches

### 1. xmrig/xmrig v6.26.0 — ARM64 fixes, PR #3778 — USE, audit first
- Source: upstream changelog, v6.26.0 dated 2026-03-28 in mirror listings.
- Low-level content: RandomX v2 support (PRs #3769, #3772, #3774–#3776, #3782, #3783), RISC-V vectorised RandomX main loop (#3746), VAES-512 for Zen5 (#3758), and **RandomX: ARM64 fixes (#3778)**.
- Also in v6.25.0: **Minor Aarch64 JIT changes (#3708) — better instruction selection, don't emit instructions that add 0**, and armv8l added to 32-bit ARM targets (#3680).
- Earlier ARM64 JIT work in the same changelog: v6.21.0 **ARM64 JIT: don't use x18 register (#3346)** and **update to latest sse2neon.h (#3348)**; v6.19.0 update to latest sse2neon.h (#3144); v6.21.3 correct memcpy size for JIT initialisation (#3462).
- Benchmark: none in the changelog itself. It is an engine changelog, not a phone test.
- Payout proof: not applicable — engine, not a pool.
- Seed/key: none found in changelog material. XMRig takes a wallet address as pool username, not a seed.
- Verdict: USE, audit first. Diff #3778 and #3708 against MoneroOcean v6.25.0-mo1, check they do not break algo-perf benchmarking, TLS, or donate 0%, then A/B on the phone. Do NOT blindly upgrade to upstream 6.26: upstream has no MoneroOcean algo-switching, and RandomX v2 is not automatically faster (see below).

### 2. MoneroOcean/xmrig fork, pinned v6.25.0-mo1 — USE, pinned only
- Source: MoneroOcean/xmrig, fork of xmrig/xmrig, described as Monero (rx/0, rx/wow, rx/loki, defyx, rx/arq, rx/sfx, rx/keva, CryptoNight family, argon2/chukwa, argon2/wrkz, astrobwt) CPU/GPU miner.
- Phone evidence: VoltMiner's June 2026 phone benchmark used **MoneroOcean XMRig fork v6.25.0-mo1 (ARM64)**, the same binary VoltMiner ships. No verified Sept–Oct 2026 "mo1" release for 6.26 was found in these searches — do not claim one exists.
- Low-level value: algo-perf benchmarking, pool-assigned most-profitable RandomX-family job, paid in XMR, 0% when mining direct to MoneroOcean with donate zeroed.
- Seed/key: none found. Wallet address is the username.
- Verdict: USE, pinned. Stay on v6.25.0-mo1 build path, build from source, verify TLS and DONATE 0% on device. Never drop in a prebuilt MO binary from a mirror.

### 3. VoltMiner 0% rebuild method, 3 Oct 2026 — USE FOR INSPIRATION
- Source: voltminer.net, benchmark blog 22 June 2026, update 3 Oct 2026.
- Method: rebuilt the bundled XMRig fork with kDefaultDonateLevel = 0 and kMinimumDonateLevel = 0, so mining direct to MoneroOcean waives the fork's 1%. App fee now claimed 0%, V1 1.64% time-slice retired.
- Phone benchmark (other phones, not Dimensity): Helio G99 ~420 H/s all cores, 2 threads 328 H/s (77%), 4 threads 385 H/s (91%), 8 threads 424 H/s (100%); Snapdragon 865 ~750 H/s, 4 big cores 748 H/s peak, 8 threads 684 H/s (-9%). MoneroOcean assigned rx/0 on both, despite rx/arq 3,031 H/s (9x), cn-pico 1,802 H/s (5x), panthera 689 H/s (2x).
- Release status: still in development, not released, "Coming soon" for Aptoide / APKPure / direct APK, with a signed APK + SHA-256 promised on release day. It will not be on Google Play (Play bans on-device miners).
- Seed/key: VoltMiner material checked says non-custodial, paste your own XMR address, pool pays you directly, never holds coins. No seed asking found in material checked.
- Verdict: USE FOR INSPIRATION. Take the 0% rebuild method, the thread-ladder method (2/4/8, then big-core-only), and the "verify on pool dashboard, not app balance" discipline. Do NOT install a VoltMiner APK until it is actually released with a published SHA-256.

### 4. Anvil itself, j3r0nim0/android-xmrig-monero — USE as base
- From prior parent audit, not re-verified on a GitHub page in this search: GPL-3.0, v0.2.0, 19 Sep 2026, HEAD 8ceede55652d19c3d42155cba2dcb94b7a007c42, package io.github.j3r0nim0.anvil, ARM64 only, Android 8+.
- Low-level value: Kotlin host already audited, wallet validation, thermal/charging defaults, TLS MoneroOcean default. Native binary is absent from the clone — that is the build gap, not a reason to switch base.
- Verdict: USE as the fork base. No Sept–Oct 2026 Anvil fork with a new phone benchmark was found in these searches.

## USE FOR INSPIRATION — patterns, not engines

### 5. RISC-V vectorisation in XMRig 6.26 — USE FOR INSPIRATION, translate, do not port
- PRs: #3746 vectorised RandomX main loop, #3748 auto-detect and use vector code for all RandomX AES functions, #3749 detect and use hardware AES, #3750 use vector hardware AES instead of scalar, #3757 fixed scratchpad prefetch, removed an unnecessary instruction, #3736 vectorised dataset init, #3740 vectorised soft AES, #3725 RISC-V support with JIT.
- Why it matters for ARM: it proves the current frontier work is vector AES + prefetch + dataset-init, not a new hash function. The ARM analogue is NEON + ARM Crypto Extensions + scratchpad prefetch tuning, already in the low-level design workstream.
- Verdict: USE FOR INSPIRATION. Audit the RISC-V diffs for the prefetch and vector-AES pattern, then test a NEON equivalent on the phone. Do not port RISC-V code to ARM64.

### 6. RandomX v2 prefetch idea — USE FOR INSPIRATION ONLY, NOT the v2 algorithm
- Source: SChernykh gist, RandomX v2 testing, desktop only.
- v2 hash: 6,815,744 total ops vs 4,456,448 in v1 (+52.9%). Program size 256 → 384, CFROUND tweaked to change rounding 16x less often, Group F/E register mix using 16 AES ops, **dataset prefetch 2 iterations ahead instead of 1**.
- Desktop work-per-Joule figures (not phone, not hashrate): Ryzen 9 9950X v2 150.72% of v1 work/Joule, Ryzen AI 9 365 v2 156.73%, Ryzen 9 7945HX figures in the same gist. These are desktop Zen 5 / Zen 4, large pages on, MSR mod on — none of which transfers to an unrooted Android phone.
- Verdict: USE FOR INSPIRATION ONLY. Test "prefetch 2 ahead" as a tuning idea inside v1 if the pinned fork exposes it. Do NOT mine Monero as rx/2, do NOT quote v2 H/s or work/Joule as a phone gain.

### 7. tevador/RandomX issue #318 — USE as a build checklist
- Issue: "Update benchmark version to v2.0", with an ARM64 linker failure reported: undefined symbols for arm64, `___clear_cache` referenced from JitCompilerA64, and `_randomx_program_aarch64_main_loop` missing, with a fix referenced in xmrig commit 10d292092a45941da4f79a0cd029705a34bd713b.
- Verdict: USE as a checklist. Any Android NDK build of RandomX JIT must verify __clear_cache linkage and the aarch64 main loop symbol, or the build will link on x86 simulator and fail on arm64. This is a porting pitfall, not a speed gain.

## WATCH — do not install, do not base on it

### 8. XMRig Multi, ImL1s/xmrig-multi, 5 Sep 2026 — WATCH
- Announced 5 Sep 2026, GPLv3, repo https://github.com/ImL1s/xmrig-multi (named in announcement text).
- Idea: one XMRig-based app for Android, iOS, desktop (Tauri), plus Wear OS / Apple Watch stat companions (watches do not mine). User enters own wallet address, chooses a pool, sees hashrate, accepted/rejected shares, mining status. Browser demo uses RandomX.js + WebSocket proxy.
- Fee: **1% developer fee based on mining time** — worse than 0%.
- Benchmark: none found. Payout proof: none found. Star/commit counts: not returned by search — not invented here.
- Seed/key: announcement says user enters own wallet address, not a seed. That is the expected pattern, but it was not independently audited here.
- Verdict: WATCH. Take only the UX ideas (own wallet + pool choice + accepted/rejected visibility), which Anvil already largely has. Do not adopt its engine or its 1% fee.

### 9. Crypto-Steer native Android XMR miner, 6 Aug 2026 — WATCH
- Source: Monero Observer, 6 Aug 2026. Native Android miner built specifically for Android, in C and C++, **not XMRig, not an XMRig fork, not a Termux wrapper**. ARM64 build for Android 16 and newer, pool configuration, tuning controls, live statistics, optional account-based rig control.
- Fee: **2% dev contribution** — worse than 0%.
- Benchmark: none found. Payout proof: none found. Maintainer also runs pool.thecryptosteer.com, noted as temporarily down in that report. Monero Observer note: "not endorsed by Monero Observer and it is still in development. Use with caution."
- Seed/key: no seed asking found in report material. Wallet handling was not audited here.
- Verdict: WATCH. The architecture idea — a purpose-built native engine + tuning UI — is inspiration. Do not replace the proven XMRig engine with this until it publishes a phone benchmark, a payout, and a full source audit, and the 2% fee alone already makes it worse on identical work.

### 10. Glaciem Lattice, May 2026 — WATCH, frontier PoW idea only, NOT for Monero
- Source: Glaciem whitepaper v1.0, 20 May 2026, Monero fork with original CPU-only PoW called Lattice. Mainnet launched 20 May 2026, no premine, 2-minute blocks, smooth + tail emission.
- Low-level idea, genuinely different: single-phase, integer-only walk over a large per-nonce scratchpad (~1 MiB per nonce) plus a shared epoch dataset (4 MiB dataset in whitepaper summary), serial latency-bound dependency — no parallelism within a nonce, wide high-IPC CPU cores win, GPU occupancy collapses (same mechanism as RandomX, but with a smaller per-nonce set than RandomX's 2 MiB scratchpad + 2,080 MiB dataset).
- Native miner + wallet apps for macOS, Windows, Android (arm64, API 26+), Linux. **Critical flag: each miner ships with an embedded GLAC wallet, first launch generates a wallet, back up the 25-word seed.** That is a wallet-app pattern. It must NOT be copied into Anvil, which must never generate, ask for, or handle a seed. Anvil's rule stays: public address only, wallet made elsewhere.
- Audit status: whitepaper itself states Lattice has **not yet received external cryptographic review**, GLAC has **no exchange listing, no market price, no liquidity**, and miners are told it is not an investment. No Dimensity benchmark, no payout in a convertible currency.
- Verdict: WATCH, frontier idea only. Lattice's "1 MiB serial walk, integer-only, cache-fitting" design is a useful counterpoint to RandomX's 2 GiB dataset — it shows a phone-friendlier memory footprint is possible in principle for a *new* coin. It does not make Monero mining faster, and it does not give this phone a payable route today.

### 11. XELIS Hash v3 — WATCH, different coin, phone-friendly design
- Source: XELIS docs, updated 3 days before search. Hash using ChaCha8 + Blake3, ASIC-resistant, designed to run on any device from smartphone to server, CPU and GPU together, memory-focused.
- Low-level numbers in docs: scratchpad 544 KB in v3 (440 KB in v2), time per hash ~1.20 ms on a CPU in v3 (~1–1.50 ms in v2), mainnet v3 since 14 Dec 2025 at block 3,282,150, MIT source on GitHub.
- Verdict: WATCH. A 544 KB scratchpad that fits in CPU cache is the opposite design pole from RandomX's 2 MiB per thread + 2 GiB dataset, and is worth noting for any future new-coin work. It is not Monero, it has no Dimensity benchmark in the material found, and it does not change the Anvil build.

### 12. RandomX-bonanza / WebRandomX Wasm JIT — WATCH, do NOT port to native
- Source: aa022/RandomX-bonanza, commit f9848e3 cited in VoltMiner browser experiment notices, built with Emscripten 4.0.10; WebRandomX commit 780908a, BSD-3-Clause, CC0 for the Wasm JIT backend.
- Claimed performance on randomx.cc: full mode ~10% of native on AMD64, **~23–25% on Apple M4 ARM64 (10 cores, vs native xmrig at same thread count)**; light mode ~53–67% of native light mode on M4. Full mode builds the full dataset in <10s on most modern CPUs, shared via SharedArrayBuffer.
- Warning from VoltMiner's own browser post: a process-global floating-point rounding mode + two hashing threads meant the Wasm miner could not tell a found share from a found number, and **every single share was rejected** in that experiment.
- Verdict: WATCH / inspiration for JIT concepts only. Native ARM64 already has a JIT in XMRig. Wasm is 4–10x slower than native even on M4, and the FP-rounding failure mode is a reason not to port it. Do not use a browser miner as the phone engine.

### 13. XternA/xmrig container — WATCH, packaging only
- Source: XternA/xmrig, lightweight Alpine, native CPU builds for amd64 & arm64, CUDA image separate, docker run with pool URL, wallet address, worker name.
- Claim in its docs: huge pages on supported systems improve CPU hashrate by **up to 30%**. That is a desktop/container claim, with root/sysctl control. It does NOT transfer to an unrooted Android app.
- Install pattern flag: its CUDA docs use `curl -fsSL .../nvidia-toolkit.sh | bash`. That pattern is rejected for this build — never pipe a remote script to shell.
- Verdict: WATCH, packaging only. Take build-hygiene inspiration (Alpine, multi-arch, sensible defaults), do not import its container or its %.

### 14. Droid XMR Miner, JPToledo, Play since May 2025 — WATCH, not a base
- Source: AppBrain, data 2026-10-08. 17,000 installs, 2.5 stars from 104 ratings, v1.7.2, last update 27 Sep 2026, Android 9+, 24.54 MB.
- User reports in listing: "seems legit, no ads", but also "start button is disabled" on OnePlus 12 Android 15, "does not work, tried different wallets, START does not work", "not work on Samsung Galaxy S24 Ultra".
- Source audit: no GitHub source audit was returned in these searches. No phone benchmark, no payout proof in material found.
- Verdict: WATCH. Do not base on an app with a broken-start pattern and no source audit in evidence.

## REJECT — do not install, do not copy

### 15. xmrig.mine.bz Android binary, Bitcointalk #5338550 — REJECT
- Offer: Android command-line XMRig binary, ARMv8 64-bit claimed ~200% faster than 32-bit (ARMv8 AES), no root, Termux/terminal required, supports rx/0, cn-pico, panthera, argon2/chukwav2, astrobwt, etc.
- Disqualifiers, all in the offer itself: **binary only, not source for the binary itself; 5% dev fee/donate level; install via `curl .../miner.sh | sh`; old 1.9.5 zip link** (xmrigARM-1.9.5-android-arm64v8.zip). No published SHA verification in evidence found, no phone payout proof tied to this binary.
- Verdict: REJECT. Never install a random binary without a SHA, never pipe a remote script to shell, and 5% is strictly worse than 0%.

### 16. backend-url-provider/access, libmine-arm64.so — REJECT, malware
- Source: Quick Heal technical analysis, 2026. Fake banking app, miner payload hosted on GitHub, Cloudflare Pages, and a custom domain. Payloads libmine-arm32.so / libmine-arm64.so, encrypted to evade static detection, decrypted with AES, written as d-miner, derived from XMRig's Android build.
- Behaviour: mines on device lock, connects to a Monero pool over TLS, RandomX, ~2.3 GB RAM, 8 CPU threads, phone temperature 32.0°C → 45.0°C over 30 minutes while locked. Quick Heal detects as Android.Dminer.A variants.
- Verdict: REJECT, hard. This is the exact pattern to avoid: encrypted binary, hidden execution, no user wallet, no consent, no SHA. It also proves a phone *can* be forced to 8 threads and 2.3 GB — and will heat to 45°C doing it — which is why thermal pauses exist.

### 17. DevOpsGPT injected XMRig 6.21.0, 30 Aug 2026 — REJECT, supply-chain injection
- Source: GitGuardian / SecurityBoulevard / DEV, Oct 2026. Commit titled "fix(subtask): update the OpenAI version", pushed with a forged author email, modifies four files to embed XMRig 6.21.0 in the Docker image, stripped and UPX-packed as /usr/local/bin/pyworker, config XOR-encrypted with key `devops2024`, wallet to pool.supportxmr.com, 45-second restart loop.
- Verdict: REJECT, hard. Never trust a commit message, a star count, or a familiar repo name. Check author, diff, and Actions workflows before building any fork.

### 18. GhostAction workflows, Aug–Oct 2026 — REJECT the pattern, factory rule
- Campaign: GhostAction, first Sep 2025 (817 repos, 327 users, 3,325 secrets). New wave 31 Aug–30 Sep 2026: **772 repos**, 2,500+ secrets. Oct 2026 burst: 346 repos in two sweeps, then **30,000+ repos across 3,000 users/orgs, ~1,900 compromised accounts, 3,174 secrets attempted**, per GitGuardian Oct 13 update.
- Payload: workflow files `github_actions_security.yml` / `security-audit.yml`, exfiltration to 193.32.204.199 over plain HTTP, plus other endpoints (bold-dhawan.45-139-104-115.plesk.page, carte-avantage.com, 170.39.218.2).
- Verdict: REJECT the pattern, and adopt as a factory rule (see below). Any fork with an injected workflow, a forged author, or a new CI secret exfiltration endpoint is rejected on sight, however good its speed claim looks.

### 19. XMRig-for-Android / react-native-xmrig, Garry Lachman — REJECT as base, stale
- Versions found: 0.1.3, 26 Aug 2023 / May 4 2024 listing, 52,946 downloads on one store listing, package com.xmrigforandroid, armeabi-v7a / x86 / arm64-v8a / x86_64. Based on XMRig + MoneroOcean auto-switching.
- Verdict: REJECT as a base. No Sept–Oct 2026 update found, no new phone benchmark or payout proof found. Inspiration only: the foreground-service idea is already in Anvil.

### 20. xmrigCC, Bendr0id — REJECT as base, stale + attack surface
- Claim in old releases: **5–10% hashrate improvement on ARMv8 CPUs when mining CN-based algos compared to stock xmrig** (v2.0.0, rebase on XMRig 3.1.1, ~6 years ago in listing). Later releases 2.1.0 / 2.2.0 / 2.8.2 (Oct 2020) with SHA256 checksums published for those old builds.
- Disqualifiers: the ARMv8 claim is for **CryptoNight-based algos, not RandomX rx/0**, and is from the XMRig 3.x era. The fork's core feature is **remote control and monitoring via a CC Server / Dashboard / REST API** — a command-and-control surface a phone miner must not add.
- Verdict: REJECT as a base. Take only the monitoring-UI idea (show H/s, accepted shares, temperature). Do not import its server, its old engine, or its %.

### 21. Other rejected patterns found in this hunt
- **MobileMonero Night Moves**: Termux + `curl -o signup.py -L https://gist.githubusercontent.com/DevGruGold/...` + python signup.py. Reject the curl-gist install pattern, however "prototype" it claims to be.
- **NERD Mobile Miner** (video, Oct 2026): Android CPU miner for DGB (SHA-256d), XMR (RandomX), LTC/DOGE (Scrypt). Its own notes: DGB had accepted shares in tests, **Monero, Litecoin and Dogecoin integrations are experimental and still require complete validation with the pools**. No source audit, no Dimensity benchmark. Reject as a base, watch only for UI ideas.
- **Dilithion**: post-quantum coin, CRYSTALS-Dilithium + RandomX PoW, 4-minute blocks, 21M supply, launch 1 Jan 2026, whitepaper states software **has NOT been professionally audited, may contain bugs**, AI-assisted development. No phone benchmark. Reject for this build — a new unaudited coin is not a low-level speed gain.
- **SourceForge XMRig mirror**: listings carry a "Warning! Malware detected. Download at your own risk." flag in search results. Reject mirrors generally — build from GitHub pinned source only.
- **SoapBox browser miner**: honest about its own numbers — browser ~8.8 H/s per core, native ~450–700 H/s per core, phone browser ~2 H/s per core, browser 50–80x slower than native. Useful only as a negative control: browser/Wasm is not a phone engine.

## Low-level ideas to actually test on Dimensity 7050, in priority order

1. **Audit #3778 + #3708 against mo1.** If clean, A/B on device. If it breaks algo-perf, drop it. No % claimed until measured.
2. **Use VoltMiner's 0% donate rebuild method** (zero both donate constants) — already in the Anvil plan, now independently corroborated by a 3 Oct 2026 rebuild note.
3. **Use VoltMiner's thread-ladder method** (2 / 4 / 8, then big-core-only) on Dimensity 7050, and keep its finding in mind: on a 2-big-core phone, big-only is likely 75–80% of max with far less heat; all-cores can be slower once throttling starts.
4. **Test the RISC-V prefetch pattern as a NEON prefetch tuning** (dataset prefetch depth, scratchpad prefetch fix) — one variable at a time, sustained 60s H/s over 30 minutes, not a burst.
5. **Test sse2neon latest** as part of the upstream diff, not as a separate "NEON on" switch — NEON is already the ARM vector path when built for arm64-v8a.
6. **Steal UI ideas, not engines, from Crypto-Steer and XMRig Multi:** tuning controls, live accepted/rejected, temperature, and a clear pool-assigned-algo line ("new job … algo rx/0").
7. **Do not chase:** NFP XTM-MINER, any 2023 Android build, any binary-only ARM miner, any Wasm JIT port, RandomX v2 as a Monero algorithm, Glaciem Lattice as a Monero replacement, or XELIS Hash as a Monero replacement — until pool + phone proof exists for that specific route.

## Factory supply-chain rules, non-negotiable

1. Build only from pinned source (MoneroOcean v6.25.0-mo1 path, upstream diffs audited commit by commit).
2. Publish and check SHA-256 for any APK/binary. No SHA, no install.
3. Check any fork's Actions workflows and recent commits for injected workflows (`github_actions_security.yml`, `security-audit.yml`) or forged authors before building.
4. Never accept a binary, a `curl | sh` installer, a gist installer, or an APK with no source + SHA.
5. Never enter a seed phrase, private key, or wallet password into any miner, pool page, or "wallet-connection" screen — only a public receiving address. Glaciem's embedded-wallet pattern is explicitly excluded from Anvil for this reason.
6. Never run a miner that mines on lock, hides its binary, or encrypts its config — that is the malware pattern in §16, not a feature.

## Limits of this search, stated plainly

- Search returned full GitHub metadata only for MoneroOcean/xmrig (fork, algo list) and for the changelog files. For Crypto-Steer, XMRig Multi, NFP, Glaciem, and the small Android forks, search returned announcements, blog/report pages, and whitepapers, **not** star/commit counts — those numbers are not invented here.
- Anvil's star/commit figures in prior parent context were not re-verified on a GitHub page in this search.
- No Sept–Oct 2026 MoneroOcean "mo1" release for XMRig 6.26 was found — absence in search is not proof it does not exist, but the builder must not assume it and must stay on the pinned mo1 path until a release page proves otherwise.
- This report is inspiration + audit only. The only speed proof that counts for this phone is a measured, sustained, on-device benchmark with MoneroOcean dashboard verification.
