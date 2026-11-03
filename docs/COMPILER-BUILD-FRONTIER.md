# FRONTIER WORKSTREAM D — Compiler and build frontier for ARM CPU mining

**Scope:** Mining only, low level only, phone first.
**Target:** NARZO 70 Pro 5G, Dimensity 7050 — 2× Cortex-A78 @ 2.6 GHz + 6× Cortex-A55 @ 2.0 GHz,
ARMv8.2-A, ARM64, Android.
**Base already proven in this tree:** Anvil fork, MoneroOcean XMRig v6.25.0-mo1,
NDK 28.2.13676358, CMake toolchain, `ANDROID_ABI=arm64-v8a`,
`ANDROID_PLATFORM=28`, `CMAKE_BUILD_TYPE=Release`, `WITH_TLS=ON`,
OpenSSL 3.4 static, libuv static, `WITH_HWLOC=OFF`, `WITH_OPENCL/CUDA/NVML=OFF`.
Verified on-device 2026-06-24: TLS 1.3 handshake, DONATE 0%.
See `docs/BUILD-XMRIG.md` and `docs/DIMENSITY7050-CONFIG.md` in this tree.

**No % in this report is a Dimensity 7050 RandomX measurement.**
Every % below is labelled with the hardware / workload it was actually
measured on. Anything not measured on this phone is UNKNOWN until the
A/B matrix in §7 runs. Do not copy a generic compiler % into a mining claim.

---

## 0. The one distinction that decides this whole workstream

RandomX has **two** kinds of code, and AOT compiler flags only touch one of them:

1. **AOT code, compiled by Clang at build time:** the JIT compiler itself
   (the code that *emits* machine code), program generation, dataset /
   cache construction (Argon2), AES helpers, pool / network / config code,
   libuv, OpenSSL.
2. **JIT code, generated at runtime, per hash:** a new random RandomX
   program is generated and JIT-compiled for every hash. Its speed is
   set by the JIT emitter's instruction selection, by hardware AES, and
   by memory / cache behaviour — **not** by `-O3`, `-flto` or PGO applied
   to the AOT binary.

Consequences, stated plainly:

- PGO / LTO / BOLT / MLGO can only improve (1). They cannot directly
  optimise (2). If (2) dominates a hash's time — and for a memory-hard
  VM loop it does — the ceiling for all AOT compiler work together is
  low, probably low single-digit % on sustained H/s, not 10–30%.
- The exception that keeps this workstream alive: the JIT emitter *is*
  hot, because a new program is JIT-compiled per hash. Better inlining /
  branch layout in the emitter, and better AOT code for dataset /
  Argon2 initialisation, can still move the total — but only by the
  emitter's share of total time, which has never been measured on this
  phone. Measure it before claiming it.
- RandomX branches are deliberately data-dependent and random.
  PGO's main lever — laying out the branches that actually happen —
  is therefore weaker here than on a predictable server workload.
  A profile taken on one seed / epoch does not predict the next
  random program's branches.

That is why the generic slogans below ("PGO 10–20%", "LTO 10–50%")
are **rejected as mining claims**, even where they are real for other
workloads.

---

## 1. Verdict table — proven / promising / rejected

| Technique | Verdict | Why, in one line |
|---|---|---|
| `-march=armv8.2-a+crypto` + `-mtune=cortex-a78` (or `-mcpu=cortex-a78`) | **PROMISING — test first** | Matches the actual A78 ISA; current NDK default is generic arm64-v8a (armv8-a). Only AOT code changes; JIT path must be re-verified. |
| `-O2` vs `-O3` A/B | **PROMISING — test both** | -O3 can bloat i-cache and lose on memory/branch-heavy code; -O2 is the safe default. Neither is proven for this phone. |
| ThinLTO (`-flto=thin`, lld) | **PROMISING — test separately** | Real cross-TU inlining, but hot path is JIT-generated, so expect 0–2% on H/s, not generic 5–10%. |
| Full LTO | **PROMISING, second** | Same ceiling as ThinLTO, slower link, higher risk. Only if ThinLTO wins. |
| Instrumentation PGO (`-fprofile-generate` / `-fprofile-use`) | **PROMISING, narrow** | Only helps AOT emitter / dataset code; random branches cap the gain; Android profile collection is extra work. |
| AutoFDO / sampled PGO, CSSPGO | **REJECTED for this phone** | Needs BRBE (ARMv9.2, A520/A720/X4/X925 class) or ETM access — not on A78, not on unrooted Android. |
| BOLT post-link layout | **PROMISING-LATER, rejected for v1** | Real for large server binaries, but BOLT is not in the NDK, Android `.so` handling is hard, and the hot code is JIT-generated. |
| MemProf (`-fmemory-profile`, hot/cold layout) | **PROMISING-RESEARCH, rejected for v1** | Needs tcmalloc hot/cold support + ThinLTO; RandomX dataset is one huge allocation, not many objects to regroup. |
| MLGO / AlphaEvolve Magellan (2025) | **WATCH, not a build** | Inlining-for-size +4.27% in a 2025 pilot; inlining-for-performance and regalloc gains were 0.3–1.5% on datacenter code, models not shipped in NDK Clang. |
| GCC 15 incremental LTO, AArch64 early scheduling at -O3 | **REJECTED for Android build** | GCC was deprecated in the NDK since r13; NDK is Clang-only. Interesting only for a Linux test build. |
| LLVM 22 / 23 (C1, Armv9.6/9.7, SPEC +3–4% on zstd/leela) | **WATCH, toolchain change** | No A78-specific scheduling model in those notes; upgrading NDK to get them needs full TLS/donate re-verification. |
| `-ffast-math` / `-Ofast` | **REJECTED, dangerous** | Relaxes IEEE FP semantics; RandomX uses FP rounding semantics (CFROUND). Do not use. |
| `-march=native` in cross-compile | **REJECTED, wrong** | Detects the *host* (x86 CI machine), not the phone. Clang does not even support `-march=native` on ARM. |
| `-mfpu=neon` | **REJECTED, invalid** | 32-bit ARM flag. On AArch64 NEON/FP are mandatory and on by default. |
| SVE / SVE2, AVX / VAES-512, Zen 5 flags | **REJECTED, wrong ISA** | A78 is ARMv8.2-A: no SVE. AVX/VAES are x86. |
| Mold linker, `-fuse-ld=mold` | **REJECTED, no runtime gain** | Link-time only; NDK already uses lld. |
| `-funroll-loops`, `-fvect-cost-model=cheap` as a blanket | **REJECTED as blanket, test-only for Argon2** | Main loop is JIT-generated and data-dependent; auto-vectorisation cannot vectorise it. May help dataset init only. |

---

## 2. Target flags — what to actually set, and what not to

### Current baseline (do not silently change it)
The proven recipe in `docs/BUILD-XMRIG.md` does **not** pass an explicit
`-march`. The NDK Android toolchain therefore defaults to generic
`arm64-v8a` = ARMv8-A. That is the baseline to beat, and it already
runs, with TLS and DONATE 0% verified.

### Candidate A — the only target change worth testing first
```
-march=armv8.2-a+crypto -mtune=cortex-a78
# Clang form: -mcpu=cortex-a78 is the equivalent single flag in LLVM;
# GCC form: -mcpu=cortex-a78 or -mtune=cortex-a78.cortex-a55 for big.LITTLE.
# For a miner whose threads should be on big cores, tune for A78, not for A55,
# and do not tune for a generic A72 (the old AArch64 default in some guides).
```
Evidence for the flag names, not for a speed gain:
- WikiChip Cortex-A78: ISA ARMv8.2, extensions FPU + NEON; compiler
  support table lists GCC `-mcpu=cortex-a78` / `-mtune=cortex-a78` and
  LLVM `-march=cortex-a78` / `-mtune=cortex-a78`, plus GCC
  `-mtune=cortex-a78.cortex-a55` for a big.LITTLE pair.
- Independent cross-compile guides use the same pattern:
  `-march=armv8.2-a+crypto+fp16+rcpc -mtune=cortex-a78 -O3`.
- libdeflate's Android build maps arm64 to `-march=armv8-a+crc+crypto`
  as its baseline — i.e. crypto is *not* automatic in every Android
  build script, it must be requested.

What this can change: instruction selection and scheduling in AOT
code (dataset init, Argon2, AES helpers, JIT emitter). What it cannot
change: the JIT emitter's own A64 instruction selection, which is
hand-written in XMRig (`jit_compiler_a64.cpp`) and already targets
ARM64 — upstream changelogs show continued A64 JIT work (v6.21.0:
do not use `x18`; v6.25.0 #3708: better instruction selection, do not
emit add-0; v6.26.0 #3778: ARM64 fixes). Test the flag, then check the
on-device log still shows JIT on and hardware AES on; a flag that
breaks the JIT is worse than a slower AOT build.

### Never set, with reason
- `-march=native`: cross-compile trap, see table. On a Linux x86 CI
  host it would emit x86 code or fail; on Clang/ARM it is unsupported
  for `-march` (only `-mcpu`/`-mtune` accept `native`, and then only
  when compiling *on* ARM hardware).
- `-mfpu=neon`: AArch64 rejects it; NEON is mandatory in ARMv8-A.
- `-ffast-math` / `-Ofast`: see table. RandomX is not a "close enough"
  FP workload.
- `-march=armv9-a`, `+sve`, `+sve2`: the phone does not have them.
  A binary requiring them will fault on launch, not run slower.

---

## 3. -O2 vs -O3 — the honest, unsexy answer

Generic evidence, all **not** RandomX:
- GCC guides and SPEC summaries: -O3 wins on average for compute-heavy
  code, but loses on 10–20% of cases, especially branch-heavy or
  memory-bound code, because aggressive inlining / unrolling bloats
  code and evicts it from L1/L2 i-cache. Linus Torvalds' kernel
  argument is the same: -O2 is the default because -O3 code size hurts
  cache.
- Concrete (old, x86, illustrative only): sorted-array sum 2.99 s at
  -O3 vs 1.98 s at -O2 on GCC 4.9; bubble sort 1.93 s at -O3 vs 1.01 s
  at -O2 on GCC 11 / Ryzen 3700X. Those numbers prove -O3 *can* lose
  badly; they do not prove anything about RandomX on A78.

Transfer to this miner: **UNKNOWN, and likely small either way.**
The AOT code is not the hash loop. The hash loop is JIT-generated
and memory-bound (2,080 MiB dataset, 2 MiB scratchpad/thread, small
shared L3). i-cache bloat in AOT code matters most for the JIT
emitter's own code footprint, which is small compared with the
dataset. Expect -O2 vs -O3 to move sustained H/s by ~0–3%, in either
direction, and only the A/B in §7 can say which direction on this phone.
Ship whichever wins sustained, not whichever sounds more aggressive.
Do not ship -O3 on the slogan that "more aggressive = faster".

---

## 4. LTO / ThinLTO — real, but capped by the JIT wall

Generic evidence:
- Guides claim LTO typically 5–10% execution, 5–25% size, with link
  time up to ~48× (Clang) / ~86× (GCC) slower in the worst case.
  Those are large-C++-app numbers, where cross-translation-unit
  inlining actually removes call overhead on hot paths.
- A June 2025 experiment (Reid Atcheson, LTO vectorisation) verified
  from first principles that GCC *can* vectorise through LTO across
  translation units — but on a matrix-multiply microbenchmark, not
  on a JIT miner.

Transfer to XMRig on Android:
- **ThinLTO first, if anything.** In CMake/NDK terms:
  `-flto=thin` on compile *and* link, `-fuse-ld=lld` (NDK default),
  same flags for xmrig, RandomX, and any static libs you want to
  inline across. OpenSSL and libuv built without LTO will not be
  inlined — either rebuild them with ThinLTO too, or accept that
  part of the gain is unavailable.
- **Ceiling:** the hot hash loop is not an AOT call graph, so there
  is little cross-TU call overhead for LTO to remove. Realistic
  expectation for sustained H/s: **0–2%, possibly 0%**. A 5–10%
  claim for this miner would need a measured A/B, not a guide quote.
- **Cost / risk:** longer CI link, larger intermediate files, and a
  new failure mode (LTO + old CMake policy + NDK toolchain). That is
  acceptable for a release build *after* ThinLTO wins an A/B, not
  before.
- **Full LTO** (`-flto`, not `=thin`): same ceiling, slower link.
  Test only if ThinLTO wins and you want to check whether full
  buys anything extra. Do not start with full LTO.

---

## 5. PGO — the most misunderstood technique in this workstream

### 5.1 Instrumentation PGO (the only PGO that can run here)
```
# Pass 1 (instrumented, on device or in an ARM test build):
clang -fprofile-instr-generate -O2 … -o xmrig.instr
# Run: ./xmrig.instr --bench=1M  (and ideally a live MoneroOcean run)
# Pass 2:
llvm-profdata merge default.profraw -o default.profdata
clang -fprofile-instr-use=default.profdata -O2 … -o xmrig
```
Generic evidence: guides quote 10–20% (some 15–30% for
"optimisation-responsive" workloads, some 5–30% on RISC-V in the
Jan 2026 APGO / CGO workshop slides, on SPEC / NPB / server code).
None of that is RandomX on A78.

Why the gain is capped here, specifically:
1. **Random branches.** RandomX programs are random. A branch profile
   from one program / epoch is a poor predictor of the next program's
   branches. PGO's layout win assumes tomorrow's branches look like
   yesterday's. Here they do not, by design.
2. **One-time code dominates the profile if you profile wrong.**
   If the profile run is mostly dataset build (Argon2, one-time,
   minutes), PGO will optimise dataset build — which then saves
   seconds at startup and ~0% on sustained H/s. The profile run must
   be a sustained `--bench` / live run, with dataset build excluded
   or weighted down, or PGO will optimise the wrong thing.
3. **Android collection friction.** An instrumented binary must run
   on the phone (or an ARM device with the same ISA), write
   `.profraw` files somewhere readable, and those files must be
   merged on the host and fed back into the CI build. That pipeline
   is buildable, but it is not free, and it must be re-run when the
   code, the ISA target, or the workload changes. A stale profile
   can *hurt* — LLVM's own PGO docs warn that mismatched profiles
   degrade layout decisions.
4. **JIT wall, again.** PGO does not profile JIT-generated code.
   It profiles the emitter. The emitter's share of total time is
   unmeasured on this phone.

**Verdict: PROMISING, narrow, test as variant D in §7 only.**
Expect 0–3% on sustained H/s, possibly 0%. If variant D does not
beat variant A (target flags alone) by a clear, repeatable margin
on 30-minute sustained runs, drop PGO — the pipeline cost is not
worth a noise-level gain.

### 5.2 Sampled / context-sensitive PGO — rejected for this phone
- **AutoFDO / sampled PGO** needs `perf` samples with good
  branch attribution. On Android, unrooted, without kernel perf
  access, that pipeline is not reliably available.
- **CSSPGO** needs context-sensitive samples. The Oct 2026
  CSSPGO/ETM write-up is explicit: full CSSPGO with BRBE needs
  ARMv9.2-A, first cores A520/A720/X4/X925, Neoverse V3 class,
  "just started appearing" in Oct 2026. Dimensity 7050 is ARMv8.2-A
  and has no BRBE. ETM (older tracing) is not a sampling substitute
  on an unrooted phone and is not in the NDK pipeline.
- **Verdict: REJECTED.** Do not spend build time on CSSPGO for A78.

### 5.3 A note on "Dynamic PGO"
.NET / JVM "dynamic PGO" (recompiling hot methods at runtime from
live profiles) is sometimes quoted as if it applied here. It does
not: RandomX's JIT already *is* a runtime compiler, but it compiles
a *new random program per hash* — it does not recompile the same
method with a better profile. There is no "warm up and then go
faster" profile to accumulate for the hash loop itself, because
the loop changes every hash. Do not cite Dynamic PGO as a lever
for this miner.

---

## 6. BOLT, MemProf, MLGO — frontier, not first-build

### 6.1 BOLT (Binary Optimization and Layout Tool)
- What it is: post-link reordering of basic blocks / functions from
  `perf` traces, to improve i-cache and branch prediction. Arm's
  Mar 2025 Neoverse data (Peter Waller, via Phoronix / LLVM
  Discourse) showed "very nice speed-ups" from PGO + BOLT across
  Neoverse N1/N2/V1/V2 — **server cores, server workloads, no
  numbers quoted in the search summary, and no phone / RandomX
  result**. Treat that as "PGO+BOLT can be large on server code",
  not as a phone number.
- Why it is hard here: BOLT is an LLVM tool, not shipped in the
  NDK; it operates on ELF executables, and Anvil ships XMRig as
  a `.so` (`libxmrig.so`) loaded by the app, not as a standalone
  ELF with a stable entry layout; collecting a representative
  `perf` trace on an unrooted phone is the same problem as §5.2.
- **Verdict: PROMISING-LATER.** Revisit only if AOT profiling
  (e.g. `simpleperf` on a rooted test device, or a Linux ARM
  test build) shows the JIT emitter is i-cache bound. Do not
  put BOLT in the first shipped build.

### 6.2 MemProf (LLVM, documented Dec 2025, PR #172238)
- What it is: memory PGO — `-fmemory-profile` instruments
  allocations, `llvm-profdata` merges them, `-fmemory-profile-use`
  feeds them back, with hot/cold heap layout (via tcmalloc's
  `hot_cold` hint) and static-data partitioning (hot globals
  together, improving dTLB / cache). ThinLTO is preferred for
  context disambiguation.
- Why it is weak here: RandomX's memory is **one 2,080 MiB
  dataset + one 256 MiB cache + one 2 MiB scratchpad per thread**.
  There are not many allocations with different lifetimes to
  regroup, and the scratchpads already have identical lifetimes.
  Static data in XMRig is small. The dataset's TLB / cache
  behaviour is set by its size and access pattern, not by
  regrouping it with other allocations.
- Android blocker: the hot/cold heap path needs an allocator
  that implements the hint (tcmalloc in LLVM docs). Android's
  bionic allocator does not, and porting tcmalloc into the
  Anvil `.so` is a large, risky change for a small expected gain.
- **Verdict: PROMISING-RESEARCH, rejected for v1.** The idea
  to steal is narrower: check dataset / scratchpad alignment
  and page behaviour directly (see §6.4), not via MemProf.

### 6.3 MLGO and AlphaEvolve / Magellan (LLVM Dev Meeting, Oct 2025)
- MLGO (Google, in LLVM): RL-trained policies replacing
  heuristics for inlining-for-size and register allocation.
  Published results: inlining-for-size deployed in Chrome /
  Fuchsia / Android; register-allocation policy +0.3–1.5% QPS
  on internal datacenter workloads. Those are *size* and
  *datacenter* results, not RandomX speed results.
- Magellan (Chen et al., LLVM Dev Meeting 28 Oct 2025):
  AlphaEvolve discovering new inlining heuristics. Pilot
  (inlining-for-size, unambiguous reward): **+4.27% better
  than the heuristic after 1.5 days of evolution**. The same
  deck is explicit that inlining-for-*performance* is harder
  because modelling a performance reward is challenging.
- **Verdict: WATCH.** No MLGO model is shipped in NDK Clang
  by default for this workload, and training a RandomX-specific
  policy would need a training pipeline, a reward (sustained
  H/s on the phone), and validation that the policy generalises
  across seeds. That is a research project, not a build flag.
  Do not quote +4.27% (size) as a hashrate gain.

### 6.4 The cheaper memory ideas that actually fit this phone
These are not compiler flags, but they are the memory-side
counterparts to §6.2, and they are testable without a new toolchain:
- **Alignment:** 16-byte alignment is optimal for 128-bit NEON;
  crossing a 64-byte cache line costs 2–3 cycles on AArch64.
  Check dataset / scratchpad base alignment in the built binary
  (`posix_memalign` / aligned allocation in RandomX's memory
  pool). If misaligned, fixing alignment is a one-line-class
  change with a real, if small, effect. If already aligned,
  close this line.
- **Page size:** NDK r28 defaults to 16 KiB page alignment for
  arm64-v8a. That is a *link* alignment, not huge pages, and
  does not grant the 5–10% desktop large-page RandomX gain.
  Do not confuse the two. Huge pages stay off on unrooted
  Android (see Dimensity config doc).
- **Prefetch:** RandomX v2's deeper prefetch (2 iterations
  ahead vs 1) is a *mechanism* to steal, not a version to ship
  (v2 is ~1.5× heavier per hash, see XMRig 6.26 / SChernykh
  v2 gist). Test XMRig's existing prefetch modes on the pinned
  mo1 fork, one at a time, on sustained runs.

---

## 7. Ordered A/B matrix — run these, in this order, one variable at a time

**Baseline B0 (must pass before any variant counts):**
NDK 28.2.13676358, CMake Release, arm64-v8a default, WITH_TLS=ON,
OpenSSL 3.4 static, libuv static. Verify: launches on phone,
`strings` shows OpenSSL 3, on-device log shows TLSv1.3 to
`gulf.moneroocean.stream:20032`, DONATE 0%, JIT on, hardware AES
on, full-mem (not light) mode, accepted shares rise on MoneroOcean.

**For every variant, same protocol, otherwise the comparison is void:**
same charger, same network (no switch mid-run), same room temp,
heavy apps closed, charging-only ON, CPU 65 °C / battery 40 °C
pauses ON. Record: `--bench=1M` H/s, then 15-minute live run,
60-second H/s at 5 / 15 / 30 minutes, 30-minute sustained average,
accepted / rejected shares and reject %, CPU + battery temp max,
battery % change while charging, pause count, binary size,
build time, and MoneroOcean dashboard pending XMR (not app H/s alone).
Decision number is **30-minute sustained H/s**, not a burst.
If a variant beats baseline by <10% sustained, it is a tie for
practical purposes — keep the simpler build (see Dimensity config
decision rule, applied to builds as well as thread rungs).

| Variant | Single change vs B0 | What would prove it | Expected ceiling (honest) |
|---|---|---|---|
| **A — target** | `-march=armv8.2-a+crypto -mtune=cortex-a78` (Clang: `-mcpu=cortex-a78`) | Sustained H/s up, JIT/AES still on, launches on phone | 0–3% on H/s, possibly 0% |
| **B — opt level** | A + `-O2` (vs A at `-O3`, and vs B0) | Sustained H/s up or binary smaller with same H/s | Either direction, 0–3% |
| **C — ThinLTO** | A + `-flto=thin` (all xmrig/RandomX TUs, lld) | Sustained H/s up, link succeeds in CI | 0–2% on H/s |
| **D — PGO** | A + instrumentation PGO, profile from a sustained `--bench` + live run (dataset-build excluded/weighted down) | Sustained H/s up, repeatable across two profile runs | 0–3% on H/s, possibly 0% |
| **E — combined** | A + winner of B + winner of C + winner of D, only if each individually won | Sustained H/s up vs best single variant | Sum is *not* additive; test, do not add %s |
| **F — full LTO** | E with `-flto` (full) instead of thin | Sustained H/s up vs E, link time acceptable | 0–1% over ThinLTO, if anything |

**Stop rules:** if a variant fails to launch, falls back to light /
interpreted mode, loses hardware AES, breaks TLS, or shows DONATE
≠ 0%, it is rejected regardless of H/s. If a variant's H/s gain
is within run-to-run noise (repeat the baseline twice and use
that spread as the noise floor), it is a tie — keep the simpler build.

**Explicitly not in the matrix (rejected, see §1):**
`-ffast-math`, `-Ofast`, `-march=native`, `-mfpu=neon`,
SVE/SVE2, AVX/VAES, GCC in the NDK, CSSPGO/BRBE, BOLT in v1,
MemProf + tcmalloc in v1, MLGO training in v1, mold linker,
blanket `-funroll-loops` / `-fvect-cost-model=cheap`.

---

## 8. What would actually be a compiler breakthrough here (and why it is not in this list)

A real breakthrough would not be a new `-O` flag. It would be:

1. **A faster A64 JIT emitter** — better instruction selection /
   scheduling for the *generated* RandomX program, proven by
   differential testing against upstream JIT output (same hashes,
   valid shares). Upstream already iterates here (v6.25 #3708,
   v6.26 #3778); backporting and A/B-testing those diffs against
   mo1 is more valuable than any AOT flag in this report.
2. **A better memory path for the dataset / scratchpad** —
   alignment, prefetch distance, and TLB behaviour on LPDDR5
   with a 2 MiB/thread working set that does not fit in shared L3.
   That is a data-layout experiment, not a compiler-flag experiment.
3. **A profile that actually predicts RandomX** — i.e. a
   workload-aware JIT that adapts to the *current* random
   program's structure, rather than a static PGO profile from
   yesterday's random programs. No 2025–2026 paper found here
   delivers that for RandomX on ARM.

Until one of those three is measured on the Dimensity 7050,
the honest compiler-frontier summary is: **target flags +
ThinLTO + PGO are worth one A/B each, each worth low single
digits at best, and the JIT emitter + memory path — not the
AOT optimiser — are where any larger gain would have to come from.**

---

## Sources (only those actually used)

- Anvil tree in this workspace: `docs/BUILD-XMRIG.md`
  (NDK 28.2.13676358, platform 28, TLS ON, OpenSSL 3.4, donate 0%),
  `docs/DIMENSITY7050-CONFIG.md` (2,080 MiB dataset, 2 MiB
  scratchpad/thread, huge-pages false, 65 °C / 40 °C pauses).
- XMRig changelog (upstream, raw.githubusercontent.com/xmrig/xmrig):
  v6.26.0 #3778 ARM64 fixes, #3708 A64 JIT in v6.25.0, #3346 ARM64
  JIT x18 fix in v6.21.0, RandomX v2 +52.9% ops (SChernykh gist).
- RandomX performance docs (tevador/RandomX DeepWiki):
  JIT 2–3× vs interpreter, HARD_AES 30–50% vs software AES
  (x86 AES-NI figure, not an ARM measurement), LARGE_PAGES
  5–10% on systems that grant them, FULL_MEM 2,080 MiB.
- WikiChip Cortex-A78: ARMv8.2, FPU + NEON, GCC/LLVM
  `-mcpu`/`-mtune=cortex-a78` support.
- LLVM / GCC 2025–2026 material found in search (all generic,
  none RandomX-on-A78): LLVM 23 release notes (C1 scheduling,
  Armv9.6/9.7, SPEC +3–4% on zstd/leela); GCC 15 AArch64 early
  scheduling at -O3, incremental LTO; Arm PGO+BOLT Neoverse
  data via Phoronix (Mar 2025, no phone numbers quoted);
  LLVM MemProf docs (Dec 2025, PR #172238); MLGO / Magellan
  LLVM Dev Meeting Oct 2025 (inlining-for-size +4.27% pilot,
  regalloc +0.3–1.5% QPS); APGO / CGO Jan 2026 slides
  (5–30% PGO on RISC-V / SPEC / NPB, not RandomX);
  CSSPGO/ETM write-up Oct 2026 (BRBE = ARMv9.2, not A78).
- Generic -O2/-O3, LTO, PGO guides: quoted only as generic
  ranges (LTO 5–10%, PGO 10–20%, -O3 losing 10–20% of cases)
  and explicitly **not** transferred to RandomX H/s.

**Limits:** No Dimensity 7050 compiler A/B was run in this
workstream — that is the builder's §7 matrix, on the phone.
No NDK 29 / LLVM 22–23 build was verified here; upgrading the
toolchain is a separate, re-verification-gated change, not a
flag flip. No seed, private key, wallet address, or pool
credential was used or requested in this workstream.
