# FRONTIER, LOW LEVEL — Workstream A: ARM64 assembly / NEON / crypto for RandomX

**Scope:** Mining only, low level only. Phone target first: NARZO 70 Pro 5G, Dimensity 7050
(2× Cortex-A78 @ 2.6 GHz + 6× Cortex-A55 @ 2.0 GHz, ARMv8.2-A, LPDDR5).
Also reported: any new ARM technique that could apply to that phone.
**No % improvement is claimed on Dimensity 7050 in this report.** Every number below
is labelled with the hardware it was measured on, or marked UNKNOWN until measured
on this phone. No fake hashrate, no pool-rule bypass, no seed/key handling.

## Verdict first — not empty-handed, but not a breakthrough either

There is **no new low-level trick found in 2024–2026 that, on evidence, lifts a
Dimensity 7050 by an order of magnitude, or even by a proven double-digit %**.
That is not a failure of the hunt — it is what the frontier actually looks like:
RandomX on ARM is already JIT + hardware AES + NEON in upstream XMRig, and the
remaining frontier is (a) removing translation penalties in helper code,
(b) backporting the newest upstream ARM64 JIT fixes, (c) copying the
RISC-V vectorisation *pattern* into ARM form, and (d) using the RandomX v2
AES-fill idea to hide DRAM latency — none of which is a drop-in phone win,
and all of which must be measured on this phone before any claim.

The single most useful new lead is **Primo ARM Miner**: a pure native-ARM
miner (no sse2neon layer) that *matches* XMRig-class RandomX numbers on
other ARM hardware (~700 H/s class, 8 threads). It proves native intrinsics
are viable, it does **not** prove they beat XMRig's JIT on Dimensity 7050.
Treat it as a design reference to audit, not an engine to swap in blindly —
and note its 1% fee vs Anvil/MoneroOcean 0%.

## Graded table

| # | Idea | Verdict | Evidence / source |
|---|---|---|---|
| A1 | XMRig 6.26 ARM64 fixes (#3778) + 6.25 A64 JIT selection (#3708) | **PROMISING — test first** | XMRig CHANGELOG v6.26.0 / v6.25.0 |
| A2 | Native ARM intrinsics, no sse2neon (Primo pattern) | **PROMISING — audit, do not swap** | Primo ARM Miner, primolab.dev |
| A3 | RISC-V vectorised main loop / vector AES / vector dataset init, ported as ARM pattern | **PROMISING — pattern only** | XMRig 6.25/6.26 RISC-V PRs #3736, #3740, #3746, #3748–#3750, #3757 |
| A4 | RandomX v2 AES register-mix + 2-ahead prefetch (hide DRAM latency) | **PROMISING for work/Joule, NOT a v1 H/s speedup** | SChernykh v2 gist; MoneroWorld v2 explainer |
| A5 | Android THP via madvise(MADV_HUGEPAGE) on dataset/scratchpad | **PROMISING experiment, unproven on phone** | Android kernel THP docs; XMRig 6.25 THP support (Linux) |
| A6 | Batch / pipelined hash preparation (hash N+1 while N computes) | **PROMISING, small, testable** | cpuminer-scash batch mode claim ~5–10% (other miner, not XMRig) |
| A7 | Compiler: -march=armv8.2-a+crypto, -O3 vs -O2, LTO | **UNKNOWN — A/B only** | No Dimensity measurement found |
| A8 | Big-core affinity + dynamic discovery (sysfs freq groups) | **PROVEN as a design, gain UNKNOWN on 7050** | Prior Anvil docs; G99/SD865 ladders bracket both outcomes |
| A9 | JIT 2–3×, hardware AES ~10× (vs soft AES) | **PROVEN vs interpreter/soft AES, NOT a gain over current JIT build** | RandomX docs; cpuminer-scash docs |
| A10 | SVE / SVE2, VAES-512, AVX2, BMI2 prefetch, MSR mod, NUMA, 1 GB pages | **REJECTED for this phone** | x86 / desktop / newer-ISA only; A78 has no SVE in this SoC |
| A11 | Pure-Go RandomX, Wasm JIT port | **REJECTED** | Go impl warns test vectors do not yet pass; Wasm JIT had share-rejection bug in VoltMiner experiment |
| A12 | Forcing huge-pages=true, rooting to set sysfs, disabling thermal pauses | **REJECTED** | Android does not grant them to apps; heat cuts sustained speed |

## 1. What the phone actually is (known vs unknown)

Known, from Arm / MediaTek-class sources:
- Cortex-A78: ARMv8.2-A, 4-wide decode, 2× 128-bit ASIMD/FP pipelines,
  64 KB L1 I + 64 KB L1 D and 256 KB private L2 on the big core in the
  A311Y3 example with the same 2× A78 + 6× A55 topology (32 KB / 128 KB
  on A55), shared L3 up to 4 MB by architecture, crypto extensions
  optional and licence-gated — **presence must be checked per device**,
  not assumed from the SoC name.
- Dimensity 7050 exact L3 size was **not** verified from a MediaTek
  datasheet in this hunt. The 2 MB shared-L3 figure seen in third-party
  tables is plausible for this topology, but must be confirmed on the
  phone via `/sys/devices/system/cpu/cpu*/cache/index*/size` and
  `/proc/cpuinfo` Features (`aes`, `sha1`, `sha2`, `crc32`) before it is
  quoted as fact. Do not hardcode big-core indices either: discover them
  from `cpuinfo_max_freq` groups (2.6 GHz vs 2.0 GHz).

Why this matters for RandomX: dataset 2,080 MiB shared, cache 256 MiB,
scratchpad per thread L1 16 KiB / L2 256 KiB / L3 2 MiB. One thread's
2 MiB L3 working set already equals a plausible whole shared L3, so
threads beyond the first one or two spill to LPDDR5. That is the physical
ceiling affinity and prefetch have to work inside — no instruction trick
removes the DRAM trip.

## 2. A1 — Upstream ARM64 JIT fixes (test first)

XMRig changelog, verified in search:
- v6.21.0: ARM64 JIT — do not use `x18` register (platform-reserved on
  Android/iOS); update `sse2neon.h` (#3346, #3348).
- v6.25.0 (23 Dec 2025): Minor Aarch64 JIT changes — better instruction
  selection, do not emit instructions that add 0 (#3708).
- v6.26.0 (28 Mar 2026): RandomX ARM64 fixes (#3778); RandomX v2 support
  (#3769–#3783); VAES-512 for Zen5 (#3758, x86 only, ignore for phone).

What to do, in order:
1. Diff #3778 and #3708 against the pinned MoneroOcean fork
   (v6.25.0-mo1 in the Anvil build docs). If the diff touches JIT
   emission, register use, or dataset read, it is a candidate.
2. Backport only if it does **not** break MoneroOcean algo-perf
   benchmarking, TLS, or donate 0%. Do **not** upgrade wholesale to
   upstream 6.26: upstream has no MoneroOcean switching, and v2 is not
   v1 Monero (see §5).
3. Measure with `--bench` + live accepted shares, JIT on, hardware AES
   on, full mode — a JIT fix that produces invalid shares pays nothing,
   however fast it looks in a benchmark.

No % is attached to #3778/#3708 in the changelog, and none is claimed here.

## 3. A2 — Native ARM intrinsics, no sse2neon (Primo pattern)

**New lead, strongest in this hunt.** Primo ARM Miner (primolab.dev,
v1.1.0, crawled 3 days before this report):
- Pure ARM-native: Verus, Monero, scrypt, SHA256d written directly in
  ARM intrinsics and AArch64 assembly, using PMULL, AES and SHA2 hardware
  crypto. Explicitly: "No SSE-to-NEON translation layer."
- Per-core runtime optimisation: detects big.LITTLE topology at startup
  and picks the fastest kernel per core; hotplug-resilient core pinning.
- Measured, not projected (their words, their hardware):
  - RK3588 (4× A76 + 4× A55): RandomX ~160 H/s per A76 core, ~690 H/s
    over 8 threads.
  - Galaxy S10+ (Exynos 9820): ~700 H/s RandomX over 8 threads, and a
    screenshot caption of 760 H/s.
  - Light mode (low-RAM fallback) ~5× slower; fast mode needs ~2.5 GiB free.
  - RandomX dev fee 1% (60 s per 100 min), logged in console.

Why this is promising, and why it is not a win yet:
- The sse2neon penalty is real in principle. x86 `AESENC` XORs at the
  *end* of the round; ARM `AESE` XORs at the *beginning*. Emulating x86
  semantics through sse2neon therefore passes a zero key to `AESE` and
  adds a manual `EOR` afterwards — wasting the built-in XOR and adding
  an instruction per round (Brase, 2018, on x86-AES-on-ARMv8). Native
  `AESE` + `AESMC` sequences avoid that extra instruction.
- **But:** XMRig's RandomX *main loop* is already native A64 JIT, not
  sse2neon-translated. The sse2neon path mainly affects helper code
  (Argon2/dataset init, some AES helpers). So the native-intrinsics gain,
  if any, is confined to helpers and dataset init, not to the whole hash.
  Primo's ~690–760 H/s on 8 threads is in the same class as XMRig's
  Snapdragon 865 figure (750 H/s) and above the Helio G99 figure
  (424 H/s) — it does not demonstrate a step-change over XMRig JIT.
- Audit verdict: **use Primo as a reference implementation to compare
  helper-code sequences against** (dataset init, SuperscalarHash, AES
  rounds), and copy its per-core kernel selection + pinning *idea* into
  the Anvil fork. Do not swap engines: Primo is 1% fee vs 0%, has no
  Dimensity 7050 benchmark, and has not had the Anvil Kotlin audit
  (wallet validation, thermal/charging defaults, TLS pool default).

## 4. A3 — RISC-V vectorisation, as an ARM pattern (not a port)

XMRig 6.25/6.26 RISC-V work is the most active low-level RandomX work in
the window, and it is instructive precisely because it is *not* ARM:
- #3725 RISC-V JIT; #3736 vectorised dataset init; #3740 vectorised
  soft AES; #3746 vectorised RandomX main loop; #3748 auto-detect and
  use vector code for all AES functions; #3749 detect hardware AES;
  #3750 use vector hardware AES instead of scalar; #3757 fixed
  scratchpad prefetch, removed an unnecessary instruction.

Transfer to ARM64, honestly:
- **Vector AES functions:** ARM already has 128-bit NEON AES (`AESE`/
  `AESMC`) — the RISC-V lesson is "vectorise *all* AES call sites and
  auto-detect", not "invent vector AES". Audit whether every AES site
  in the mo1 fork actually takes the hardware path on A78/A55, or
  whether any site falls back to scalar/soft AES on a core without crypto.
- **Vector dataset init:** SuperscalarHash / dataset generation is
  regular, parallelisable work — the best candidate for a native NEON
  rewrite, unlike the sequential VM main loop. Test a NEON dataset-init
  variant against the current init time; dataset init is a one-off cost
  per epoch, so even a large init speedup is a small *sustained* H/s
  gain — measure it as time-to-first-share, not as H/s.
- **Scratchpad prefetch fix:** #3757 removed one unnecessary instruction
  from the prefetch path. That is the right *kind* of fix for ARM too:
  audit the A64 JIT prefetch emission for redundant moves/loads, rather
  than assuming the JIT is optimal.
- **What does not transfer:** RISC-V V is scalable-vector; ARM NEON on
  A78 is fixed 128-bit, and this SoC has no SVE/SVE2. Do not claim a
  RISC-V vector speedup as an ARM speedup.

## 5. A4 — RandomX v2: the DRAM-latency idea, not a v1 speedup

RandomX v2 (SChernykh gist, desktop Zen 5, large pages + MSR on):
- v1 hash: 4,194,304 VM instructions + 262,144 AES = 4,456,448 ops.
- v2 hash: 6,291,456 VM instructions + 524,288 AES = 6,815,744 ops
  (+52.9%). Program 256 → 384 instructions; CFROUND rounding change
  16× less often; Group F/E register mix via 16 AES rounds (the extra
  262,144 AES ops); dataset prefetch 2 iterations ahead instead of 1.
- Desktop results: H/s essentially flat to slightly down/up by chip
  (9950X v2 98.6% of v1 H/s; HX 370 v2 108.0%; AI 9 365 v2 102.5% in
  XMRig), but **work per Joule +33% to +65%** (e.g. 150.7% on 9950X,
  165.1% on HX 370, 156.7% on AI 9 365) — because v2 fills the DRAM
  latency gap (~50–55 ns) with useful AES work instead of idling.

Why v1 CPUs idle: the RandomX iteration is tuned so compute takes
~50–55 ns, the same as a DRAM fetch, so the CPU computes while the next
dataset item is prefetched. Faster CPUs finish early and stall; v2 adds
AES work into that stall. On a phone the gap is smaller (A78 is slower
than Zen 5, LPDDR5 latency is similar), so the v2 work/Joule benefit
on Dimensity 7050 is **UNKNOWN and cannot be copied from Zen 5**.

Rules for this build:
- Do **not** mine Monero as rx/2 / v2. v2 is not mainnet rx/0, and a
  heavier hash at flat H/s does not pay more on a v1 pool.
- **Do** steal the mechanism for a future experiment: test a
  2-ahead dataset prefetch variant and an AES-mix-during-wait variant
  *inside v1* only if the pool still accepts the resulting shares —
  i.e. only if the change is to scheduling/prefetch, never to the hash
  itself. Any change to the hash is a different algorithm and must not
  be shipped as Monero mining.
- The v2 lesson for sustained phone speed is thermal, not H/s: if v2
  ever activates on mainnet, re-benchmark from scratch; do not assume
  v1 numbers carry over.

## 6. A5 — Android THP: the one memory experiment not yet run

XMRig 6.25 added transparent huge pages support on Linux. Android's
kernel THP documentation (android15-6.6) says THP works for anonymous
memory and tmpfs/shmem, is controlled via sysfs + `madvise(2)` /
`prctl(2)`, and that applications that benefit "should use
`madvise(MADV_HUGEPAGE)` on their critical mmapped regions" — with the
caveat that promotion is best-effort, depends on fragmentation and
kernel config, and is **not** the same as hugetlbfs reserved huge pages.

For this phone, that makes THP a legitimate *experiment*, not a claim:
1. Keep shipped config `huge-pages: false` (Anvil template today).
2. In a test build only, `madvise(MADV_HUGEPAGE)` the dataset and
   scratchpad regions, then read `/proc/<pid>/smaps` for
   `AnonHugePages`. If it stays 0 on this phone, close the line —
   that is the answer, and no desktop huge-page % (up to 50% in some
   guides, 1–3% for 1 GB pages on Linux) may be quoted for Android.
3. No root, no sysfs writes, no `echo always > …/enabled`. If the
   kernel is in `madvise` mode and khugepaged does not collapse the
   2 GiB dataset, the experiment ends there.

## 7. A6–A9 — Smaller, still testable levers

- **Batch pipelining (A6, PROMISING, small):** cpuminer-scash documents
  `calculate_hash_first()` / `calculate_hash_next()` batch mode —
  prepare hash N+1 while computing N — claiming ~5–10% throughput in
  that miner. XMRig's per-thread VM is sequential, so the transferable
  version is cross-thread pipelining (prefetch next job / next nonce
  while the current hash runs) and overlapping pool I/O with compute,
  not changing the VM. Test as job-pipeline latency, not as H/s magic.
- **Compiler (A7, UNKNOWN):** test one variable at a time on the phone:
  `-march=armv8.2-a+crypto` vs generic arm64-v8a; `-O3` vs `-O2`
  (watch the v6.21.2 fortify_source crash fix — do not assume -O3 is
  safe without a launch test); LTO separately. Never `-march=native`
  in a cross-compile (targets the CI host, not the phone), never
  `-Ofast`/fast-math (RandomX needs IEEE-754-compliant FP).
- **Affinity (A8):** the prior ladder already brackets the outcome —
  G99 2-big 328 H/s (77%), 4 threads 385 (91%), 8 threads 424 (100%);
  SD865 4-big 748 peak vs 8 threads 684 (−9%). Dimensity 7050, with
  only 2 big cores, could land on either side. The frontier addition
  here is Primo's per-core kernel selection: if A55 cores lack crypto
  extensions while A78 cores have them, the little-core penalty is
  larger than frequency alone suggests — check `Features` per core.
- **JIT / hard AES baselines (A9):** RandomX docs quote JIT as 2–3×
  vs interpreter, and cpuminer docs quote hardware AES as ~10× vs
  soft AES *for the AES function*. Both are already on in a correct
  Anvil build. They are a regression check (log JIT on, hard AES on,
  full mode, else stop and fix), not a new gain.

## 8. Rejected, with reasons (do not revisit without new evidence)

- **SVE/SVE2:** not present on Cortex-A78 in this SoC class; SVE is
  Armv8.2 optional, SVE2 is Armv9. No code path to enable.
- **VAES-512 / AVX2 / BMI2 prefetch / MSR mod / NUMA / 1 GB pages:**
  x86 desktop only. `scratchpad_prefetch_mode` exists in XMRig since
  6.3.4, but its BMI2 variant is x86; the ARM prefetch to test is the
  A64 JIT's own prefetch emission, not BMI2.
- **Pure-Go RandomX:** its own warning says test-vector infrastructure
  is in place but hash outputs do not yet match the reference — do not
  use for mining until vectors pass.
- **Wasm JIT:** VoltMiner's browser experiment found a rounding-mode +
  two-thread bug that rejected every share. Native A64 JIT already
  exists; do not port Wasm to the phone.

## 9. Ordered test plan for the builder (lowest level only)

1. **Verify the baseline:** arm64-v8a binary, NDK 28 recipe, TLS on,
   OpenSSL 3, donate 0%, JIT on, hard AES on, full mode, dataset built
   once and reused. Fix any silent fallback first.
2. **Affinity + discovery:** sysfs freq groups, `affine_to_cpu` big-first,
   "Big cores only" preset. Ladder 2 / 2+1 / 2+2 / 2+4 / 8, 30-min
   sustained 60 s H/s, accepted/rejected, temps, pauses.
3. **Backport audit:** diff #3708/#3778 vs mo1; test if clean.
4. **Helper-code audit:** compare dataset-init / AES helper sequences
   against Primo's native pattern; test a NEON dataset-init variant
   for time-to-first-share only.
5. **Prefetch audit:** check A64 JIT prefetch emission for redundant
   instructions (RISC-V #3757 pattern); test 1-ahead vs 2-ahead
   *scheduling* only, never changing the hash.
6. **THP probe:** madvise experiment + smaps check; close if 0.
7. **Compiler A/B:** one flag at a time, sustained H/s only.
8. **Batch pipeline:** overlap next-job preparation with compute;
   measure rejected-share rate — a pipeline that starves the pool
   connection loses more than it gains.

Decision rule throughout: highest 30-minute sustained H/s at
CPU ≤ 65 °C and battery ≤ 40 °C without repeated pauses; <10%
sustained gain keeps the lower rung; reject % above ~2–3% or battery
% falling while charging steps down regardless of raw H/s.

## Sources

- XMRig CHANGELOG v6.21–v6.26 — https://raw.githubusercontent.com/xmrig/xmrig/master/CHANGELOG.md
- RandomX architecture / flags / performance — https://deepwiki.com/tevador/RandomX , https://deepwiki.com/tevador/RandomX/6.2-performance-optimization
- RandomX v2 testing gist (SChernykh, desktop) — https://gist.github.com/SChernykh/6058ecf01c929883b9d19c7eeadc8809
- Primo ARM Miner — https://primolab.dev/ , https://primolab.dev/mine-monero.html
- Cortex-A78 — https://en.wikipedia.org/wiki/ARM_Cortex-A78 , https://en.wikichip.org/w/index.php?title=arm_holdings/microarchitectures/cortex-a78&printable=yes
- Android THP docs — https://android.googlesource.com/kernel/common/+/refs/tags/android15-6.6-2024-07_r57/Documentation/admin-guide/mm/transhuge.rst
- x86 AES on ARMv8 penalty (Brase) — https://blog.michaelbrase.com/2018/06/04/optimizing-x86-aes-intrinsics-on-armv8-a/amp/
- Prior Anvil tree in ~/workspace/anvil-miner: docs/DIMENSITY7050-CONFIG.md, docs/BUILD-XMRIG.md, docs/BENCHMARKS.md
