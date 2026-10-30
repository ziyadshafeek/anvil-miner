# SEHO: Split-Emitter, Hot-Path-Optimised Compilation for ARM64 RandomX Mining

## A testable compiler / build method for phone-only Monero mining

**Scope:** Mining only. Low level only. Phone first: NARZO 70 Pro 5G, Dimensity 7050
(2× Cortex-A78 @ 2.6 GHz + 6× Cortex-A55 @ 2.0 GHz, ARMv8.2-A, LPDDR5),
Anvil fork, MoneroOcean XMRig v6.25.0-mo1, NDK 28.2.13676358, TLS on, donate-level 0.
No seed, private key, mnemonic, or password is handled at any stage. Only a public
receiving address is sent to the pool.

**Status of this document:** An invention and a test plan, not a result.
No percentage improvement and no hashrate figure for Dimensity 7050 is claimed here.
Every number that matters must be measured on the phone, under the protocol in §8,
before it is quoted as a result.

---

## Abstract

RandomX mining on ARM64 is a two-code problem, and conventional compiler tuning
treats it as a one-code problem. One code body is compiled ahead of time (AOT):
the JIT compiler itself, program generation, dataset and Argon2 construction,
AES helpers, and network code. A second code body is generated at run time, per
hash, by that JIT compiler: a fresh random program, executed for 2,048 iterations,
then discarded. Ahead-of-time flags such as `-O3`, link-time optimisation (LTO),
and profile-guided optimisation (PGO) act directly only on the first body.
They act on the second body only indirectly, through the quality and latency of
the emitter that produces it.

This paper proposes SEHO — Split-Emitter, Hot-path-Optimised compilation — a
build method that makes that distinction explicit and testable. SEHO splits the
native build into three compilation tiers with different objectives, isolates
the JIT emitter and its helper stubs into a hot, cache-aligned, separately
optimised unit, collects a *sustained* profile that excludes dataset build,
warm-up, and throttled periods, and applies that profile only to the emitter
tier. It then subjects every variant to a one-variable-at-a-time A/B test whose
decision metric is 30-minute sustained 60-second hashrate with pool-accepted
shares, not a burst, not a benchmark table, and not an app balance.

SEHO's contribution is not a claimed speedup. It is a falsifiable pipeline:
a time model that predicts where AOT work *can* help and where it *cannot*,
a set of controls that prevent the usual confounds (thermal throttling,
dataset rebuild, light-mode fallback, rounding-mode error), and explicit
rejection criteria. If SEHO does not beat a correctly built baseline beyond
measurement noise, the method is rejected, not reinterpreted.

---

## 1. Problem

### 1.1 What is being optimised, exactly?

For one RandomX hash on the pinned fork, total wall time per hash, `T_hash`,
can be decomposed as:

```
T_hash = T_emit + T_execute + T_memory + T_overhead
```

where:

- `T_emit` — time for the JIT compiler to translate one random program
  (256 instructions in RandomX v1) into ARM64 native code, including
  instruction selection, register allocation, code emission, and the
  instruction-cache flush required before the new code can run.
- `T_execute` — time to run that program for 2,048 iterations.
- `T_memory` — time spent waiting on dataset and scratchpad accesses that
  miss cache and go to LPDDR5. Dataset is 2,080 MiB, shared by all threads.
  Scratchpad is 2 MiB per thread (L1 16 KiB, L2 256 KiB, L3 2 MiB).
- `T_overhead` — pool communication, share submission, thread scheduling,
  and thermal pauses, amortised per hash.

AOT compiler flags act on `T_emit` and on the helper code that runs outside
the JIT-generated program (dataset construction, AES helpers used during
initialisation, network code). They do **not** act directly on `T_execute`,
because `T_execute` runs code that did not exist at compile time. They act on
`T_execute` only insofar as a better emitter produces better generated code:
better instruction selection, fewer redundant instructions, better register
use, and fewer spills in the generated program.

This distinction is routinely lost. A build that reports a faster
`--bench` table, a smaller binary, or a faster dataset build has not
necessarily improved sustained mining at all.

### 1.2 Why the phone makes the problem harder

Three phone facts, all verified in prior work on this tree, tighten the problem:

1. **Cache is the bottleneck, not arithmetic.** One thread's 2 MiB scratchpad
   already equals a plausible whole shared L3 for this 2+6 topology
   (third-party tables suggest ~2 MB; MediaTek's exact Dimensity 7050 L3 was
   not verified from a datasheet and must be checked on the phone via sysfs,
   not assumed). Threads beyond the first one or two spill to LPDDR5.
   `T_memory` therefore dominates as thread count rises, and no AOT flag
   removes a DRAM trip.
2. **Thermal throttling converts burst into loss.** A run that bursts high
   in minute one and throttles in minute twenty averages less over 30 minutes
   than a steady run. Any method that raises peak power without raising
   sustained power is a regression, however good its first-minute number looks.
3. **Silent fallback destroys validity.** If a build falls back to light mode
   (256 MiB cache only, ~8× slower in upstream's fast-vs-light table),
   to the portable interpreter, or to software AES, its hashrate collapses
   for a reason unrelated to compiler flags. If a build shares rounding-mode
   state across threads, it produces locally plausible but pool-rejected
   shares — the VoltMiner Wasm rounding-mode bug, where 46 shares in a
   4-hour soak were all rejected, is the canonical warning. A compiler
   experiment that does not check these gates is not an experiment.

### 1.3 Problem statement

Design a compiler / build method for ARM64 RandomX that:

1. Maximises *sustained* hashrate per watt on a Dimensity 7050, within
   charging-only, CPU 65 °C / resume 55 °C, battery 40 °C / resume 37 °C limits.
2. Separates what AOT optimisation can change (`T_emit`, helper code)
   from what it cannot change directly (`T_execute` except via emitter
   quality, `T_memory` except via layout and prefetch).
3. Is testable in one-variable steps, with a baseline that must pass
   correctness gates before any speed comparison is allowed.
4. Can be rejected cleanly if it does not work.

---

## 2. Hypothesis

### 2.1 Primary hypothesis (H1)

> A split build — JIT emitter and helper stubs compiled hot, with
> target-specific ARMv8.2-A + crypto code generation, ThinLTO, and a
> *sustained* PGO profile applied to the emitter tier only, with cold
> code (network, logging, pool handling) compiled for size — will
> produce a higher 30-minute sustained 60-second hashrate, with
> pool-accepted shares, than a uniform generic arm64-v8a `-O2`/`-O3`
> build, on the same phone, same charger, same network, and same
> thermal limits.

### 2.2 Secondary hypotheses

- **H2 (emitter latency):** Most of any AOT gain, if present, will appear
  as reduced JIT time per hash (`T_emit`), not as faster execution of a
  single generated program. Therefore JIT time per hash must be logged
  alongside hashrate; a build that raises hashrate without changing
  `T_emit` or generated-code quality must be investigated for a confound
  (e.g. a different thread placement, a different prefetch mode, or a
  thermal difference), not credited to the compiler.
- **H3 (cache pollution):** Compiling cold code for size and placing it
  away from hot code will reduce instruction-cache pressure on the
  emitter, and will therefore help sustained hashrate more than it helps
  a 10-second burst, because the burst fits in cache anyway.
- **H4 (profile contamination):** A PGO profile taken from a whole run —
  including dataset build, first-start calibration, warm-up, and
  throttled periods — will optimise the wrong code and may *hurt*
  sustained hashrate. A profile restricted to steady-state, unthrottled,
  full-mode mining is therefore a necessary condition for any PGO gain.

### 2.3 Null hypothesis (H0)

SEHO makes no sustained difference beyond run-to-run noise, because
`T_emit` is a small fraction of `T_hash` on this phone, because
`T_memory` dominates, or because the upstream emitter is already
near-optimal for A78. H0 is the default. SEHO must disprove it; it does
not get the benefit of the doubt.

---

## 3. Related work

No 2024–2026 peer-reviewed paper found in prior hunts measures a RandomX
speedup on an ARM phone, let alone on Dimensity 7050. The work below is
therefore cited for its mechanism, not as a mining result. No percentage
from any of these sources is transferred to Dimensity 7050 RandomX.

### 3.1 RandomX and XMRig upstream

- tevador RandomX: JIT backends for x86-64, ARM64 (`jit_compiler_a64.cpp`,
  NEON + Crypto Extensions), and RISC-V; flags `RANDOMX_FLAG_JIT`,
  `RANDOMX_FLAG_HARD_AES`, `RANDOMX_FLAG_FULL_MEM`; documentation figures
  of JIT 2–3× versus interpreter and hardware AES 30–50% versus software
  AES are JIT-versus-interpreter and hardware-versus-software comparisons,
  **not** gains over an already-JIT, already-hardware-AES build. They are
  regression checks, not promises.
- XMRig changelog, ARM64 lineage: v6.21.0 ARM64 JIT (do not use `x18`,
  `sse2neon.h` update); v6.25.0 Aarch64 JIT instruction selection
  (#3708, better selection, do not emit instructions that add 0);
  v6.26.0 ARM64 fixes (#3778) and RandomX v2 support. RandomX v2 is
  ~52.9% heavier per hash on desktop (6,815,744 versus 4,456,448 ops)
  and is not a v1 Monero speedup; its transferable ideas are deeper
  prefetch and less frequent rounding-mode change, not the v2 hash itself.
- MoneroOcean XMRig fork v6.25.0-mo1: profit-switching via `algo-perf`
  benchmarking, pool-assigned most-profitable RandomX-family job, paid in
  XMR. VoltMiner's June 2026 phone calibration showed raw ArQmA at
  3,031 H/s (9× RandomX) yet the pool assigned plain `rx/0` on both test
  phones, because price ÷ difficulty, not raw hashrate, decides profit.
  SEHO therefore judges itself on dashboard XMR/day and accepted shares,
  never on a calibration H/s table alone.
- VoltMiner Wasm rounding-mode bug (October 2026): a process-global
  rounding-mode variable shared across threads produced locally valid-looking
  but pool-rejected shares. SEHO adopts per-thread rounding state and
  pool acceptance as validity gates (§8.3).

### 3.2 Heterogeneous scheduling and thermal management

- HARP (Smejkal et al., ACM Middleware '25): energy-aware adaptive
  management of heterogeneous processors; measured −12% execution time,
  −28% energy on two representative heterogeneous systems, not phones,
  not mining. Transferable pattern only: place threads, monitor, adapt.
- FBHS (Liang et al., IEEE Transactions on Sustainable Computing,
  Sept–Oct 2026): federated bundled hybrid scheduling on big.LITTLE;
  energy −18.3% to −31.7% and time −21% to −56% versus baselines on
  real-time DAG tasks, not mining. Transferable lesson only: do not treat
  all threads alike on big.LITTLE.
- Phoenix (Jeon et al., ACM Transactions on Embedded Computing Systems,
  vol. 25, no. 2, 2026): thermal-aware DNN placement that postpones
  throttling. Its multi-exit idea does **not** transfer: a RandomX hash
  must run fully or the share is invalid. Its placement idea does:
  place work where heat builds slowest, pause before the governor
  throttles you.
- iDEAS (Samadi et al., IEEE Access, 2025): deep-reinforcement-learning
  DVFS/scheduling/offloading on big.LITTLE mobile devices, with large
  energy reductions on task sets, not mining. An unrooted Android app
  cannot set DVFS governors directly; the implementable analogue is
  pause / step-down / resume with a dwell guard, which SEHO retains.

### 3.3 Compiler optimisation literature, and why it does not transfer naively

- General PGO literature reports 10–20% on SPEC-like workloads, and
  recent APGO work reports 5–30% on RISC-V / SPEC / NPB. Those workloads
  repeat the same code millions of times. RandomX programs are random
  per hash and discarded after 2,048 iterations. A profile from one
  seed poorly predicts the next program's branches. PGO for RandomX is
  therefore a hypothesis about the *emitter* and *helpers*, not about
  the generated programs, and must be tested as such.
- ThinLTO and full LTO literature reports 5–10% execution gains on large
  C++ applications. The hot loop here is JIT-generated, not AOT-compiled,
  so the LTO ceiling for sustained H/s is expected to be lower, and is
  unknown until measured.
- BOLT, MemProf, and MLGO inlining work are server / LLVM-toolchain
  research, not available in the NDK in a form usable for this build,
  and are listed as watch-only, not as SEHO components.

### 3.4 What is original in SEHO

SEHO does not claim a new JIT, a new hash, or a new instruction.
Its originality is in combination and in discipline:

1. **Tiering by role, not by file.** Existing builds compile the whole
   native tree uniformly. SEHO compiles by the role code plays in
   `T_hash`: emitter, helper, or cold.
2. **Sustained-only profiling.** Existing PGO recipes profile a whole run.
   SEHO profiles only steady-state, unthrottled, full-mode mining, and
   discards warm-up, dataset build, calibration, and throttled samples.
3. **Emitter latency as a first-class metric.** Existing benchmarks
   report H/s. SEHO reports JIT time per hash alongside H/s, so a gain
   can be attributed to the emitter, to generated-code quality, or to
   a confound.
4. **Gates before speed.** No speed comparison is valid until JIT on,
   hardware AES on, full mode, TLS, donate 0%, hash-sum verification,
   and pool acceptance all pass. This is a methodological contribution:
   it prevents the commonest false positives in phone-miner tuning.

---

## 4. Theoretical justification: why AOT flags can, and cannot, help

### 4.1 Where AOT can help

| Target | Mechanism | Expected direction | Ceiling reasoning |
|---|---|---|---|
| JIT emitter code | Better instruction selection, register allocation, and inlining in the emitter reduces `T_emit` per hash. | Positive, if emitter time is non-negligible. | Bounded by emitter share of `T_hash`. If `T_emit` is, say, a small fraction, even halving it moves total by only that fraction. The fraction is **unknown** on Dimensity 7050 until JIT time per hash is logged. |
| Generated-code quality | A better emitter (upstream #3708/#3778 class of change) emits fewer redundant instructions and fewer spills in the generated program, reducing `T_execute`. | Positive, but this is an *emitter* change, not an AOT flag change. AOT flags do not rewrite the emitter's output templates. | Bounded by how far the current emitter is from optimal for A78. Upstream already iterates here; SEHO's AOT work cannot substitute for an emitter diff. |
| Helper code | Dataset construction, Argon2 cache build, AES helpers during initialisation, and pool/network code run AOT. Faster helpers shorten time-to-first-share and reduce CPU contention during mining. | Positive for startup and for contention, small for steady-state H/s. | Dataset build is once per key, not per hash. Its gain amortises to near zero over a long run, unless the miner restarts often — which SEHO prevents by keeping dataset/VM alive across pause. |
| Instruction cache | Hot emitter code placed contiguously and aligned, cold code placed away and compiled for size, reduces icache misses during emission. | Positive for sustained runs, where the emitter runs repeatedly. | Bounded by icache size and by how much cold code actually interleaves with hot code in the baseline. |
| Profile-guided layout | Sustained PGO can place frequently executed emitter paths contiguously and predict their branches better. | Positive only if the profile represents steady-state mining. | Contaminated profiles (startup, throttling) actively mislead layout. Hence §6.3. |

### 4.2 Where AOT cannot help

1. **Memory wait (`T_memory`).** No compiler flag makes a 2 MiB scratchpad
   fit in a ~2 MiB shared L3 for many threads, or makes a pseudo-random
   2,080 MiB dataset access hit cache. Prefetch *distance* can hide some
   latency, but prefetch is a JIT / runtime tuning, not an AOT flag, and
   a prefetch that thrashes the small L3 hurts. SEHO therefore does not
   claim a memory gain from flags; it tests prefetch separately, one
   variable at a time, and keeps the upstream default on a tie.
2. **Generated-program semantics.** The generated program is random.
   No static profile can predict its branches. No AOT flag can make
   one random program run like another. Claims that PGO "optimises
   RandomX" without specifying *emitter* PGO are therefore
   methodologically suspect.
3. **Thermal physics.** A faster emitter that raises power draw without
   raising sustained throughput will throttle sooner and average lower.
   SEHO's decision metric is sustained, throttled-inclusive hashrate,
   so such a build fails on its own terms.
4. **Pool economics.** No compiler change changes price ÷ difficulty,
   pool fee, or payout threshold. SEHO's gains, if any, are in hashes
   per second and hashes per joule, not in XMR per hash.

### 4.3 A simple ceiling model

Let `f_emit = T_emit / T_hash` be the emitter share, measured on the phone.
If an AOT change improves emitter speed by a factor `g` (so new emitter
time is `T_emit / g`), and nothing else changes, the total improvement is:

```
new T_hash = T_hash - T_emit + T_emit / g
speedup    = T_hash / new T_hash = 1 / (1 - f_emit + f_emit / g)
```

If `f_emit` is 0.05, even a perfect emitter (`g → ∞`) gives at most
1 / 0.95 ≈ 1.053×. If `f_emit` is 0.20, a 2× faster emitter gives
1 / (0.80 + 0.10) ≈ 1.11×. SEHO's first measurement task is therefore
to estimate `f_emit` on the phone. Until that is known, any claimed
compiler gain larger than the ceiling implied by a plausible `f_emit`
should be treated as a confound, not a result.

This model is deliberately simple. It ignores cache, thermal, and
generated-code-quality effects, which is why SEHO measures sustained
hashrate directly rather than trusting the model. The model exists to
prevent impossible claims, not to replace measurement.

---

## 5. Method: SEHO in detail

### 5.1 Tier definitions

The native tree is partitioned, by source file and by function, into
three tiers. Partitioning is by role, not by directory, and must be
recorded in a build manifest so a rebuild reproduces it exactly.

**Tier E — Emitter (hot).**
- JIT compiler: `jit_compiler_a64.cpp` and its helpers, program
  generation, register allocation, code emission, icache flush.
- Helper stubs that are emitted or called per hash: prologue/epilogue,
  rounding-mode handling, scratchpad access helpers.
- Objective: lowest latency per emitted program, best generated-code
  quality, smallest icache footprint for the emission path.
- Flags (to be A/B tested, not assumed): `-march=armv8.2-a+crypto`,
  `-mcpu=cortex-a78`, `-O3`, ThinLTO within the tier, function
  alignment to 64 bytes for hot emitter entry points, `-ffunction-sections`
  + linker ordering so Tier E functions are contiguous.
- Forbidden: `-ffast-math`, `-Ofast`, any flag that changes IEEE 754
  floating-point semantics (RandomX relies on CFROUND rounding modes).

**Tier H — Helpers (warm).**
- Dataset construction, Argon2 cache build, SuperscalarHash scheduling,
  AES helpers used during initialisation, dataset read path.
- Objective: throughput for one-off work, without polluting the
  emitter's icache during steady-state mining. These functions run
  rarely after startup, so their optimisation matters for
  time-to-first-share, not for steady-state H/s.
- Flags: same target architecture as Tier E, `-O2` or `-O3` by A/B test,
  ThinLTO optional. Kept in a separate section from Tier E so their
  code does not interleave with emitter code in the binary layout.

**Tier C — Cold.**
- Network, Stratum/TLS handling, pool communication, logging,
  configuration parsing, update checking (repointed to the fork or
  removed, per prior audit — never phoning home to upstream).
- Objective: smallest size, lowest icache pressure, no performance
  regressions in share submission latency.
- Flags: `-Os`, no LTO unless it reduces size without hurting
  submission latency, placed after Tier E and Tier H in the binary.

**Shared rules for all tiers:**
- NDK 28.2.13676358, `ANDROID_ABI=arm64-v8a`, `ANDROID_PLATFORM=28`
  (not 26 — libuv needs `posix_spawn` declared at API ≥ 28),
  `CMAKE_BUILD_TYPE=Release`, `WITH_TLS=ON`, OpenSSL 3.4 static,
  libuv static, `WITH_OPENCL=OFF`, `WITH_CUDA=OFF`, `WITH_NVML=OFF`,
  `WITH_HWLOC=OFF`, `huge-pages=false` in the shipped config
  (Android does not grant huge pages to an unrooted app; a THP probe
  via `madvise(MADV_HUGEPAGE)` + `/proc/<pid>/smaps` `AnonHugePages`
  check is a separate experiment, not part of SEHO v1).
- No `-march=native` (in a cross-compile it targets the CI host, not
  the phone). No SVE/SVE2 (A78 is ARMv8.2-A, no SVE). No x86 flags
  (`--asm=intel`, VAES-512, BMI2) on ARM. No `mfpu=neon` (invalid on
  AArch64 — NEON/FP are mandatory).
- Donate-level 0 in the built config, verified on device, not assumed
  from a source comment. TLS verified by a TLSv1.3 handshake log to
  `gulf.moneroocean.stream:20032`, not by a config string alone.

### 5.2 Build pipeline

```
Step 0  Baseline gate (see §8.2)
        Uniform build, NDK 28 recipe, generic arm64-v8a.
        Must pass all gates before any comparison.

Step 1  Partition
        Annotate Tier E / H / C in CMake, produce build manifest
        (file → tier, flags per tier, linker order).

Step 2  Tier build, one variable at a time
        Variant A: target flags only (armv8.2-a+crypto, cortex-a78)
        Variant B: A + opt-level A/B (-O2 vs -O3, Tier E only)
        Variant C: A + ThinLTO (Tier E, then Tier E+H)
        Variant D: A + sustained PGO (Tier E only, see §5.3)
        Variant E: winners of B/C/D combined — gains are NOT additive,
                   test, do not add percentages.
        Each variant rebuilds from clean, with the same pinned
        MoneroOcean XMRig v6.25.0-mo1 source, and passes Step 0 gates
        again before benchmarking.

Step 3  Sustained profile collection (for Variant D only, see §5.3)

Step 4  A/B benchmark (see §8)
        30-minute sustained 60-second hashrate, accepted/rejected
        shares, temperatures, battery change, pauses, pool algo,
        MoneroOcean dashboard verification.

Step 5  Decision (see §9)
        Keep only variants that beat baseline beyond noise, with
        clean gates. Otherwise keep baseline and record SEHO as
        rejected for that variant.
```

### 5.3 Sustained-only PGO: the novel detail

Conventional PGO for this miner would profile a whole run. SEHO
prohibits that, for the reason in H4. The sustained profile is built as:

1. **Instrumented build:** Tier E (and only Tier E) compiled with
   LLVM instrumentation (`-fprofile-instr-generate`). Tier H and
   Tier C are built normally, so instrumentation overhead does not
   distort helper or network timing.
2. **Sustained run:** On the phone, charging-only, thermal pauses on,
   run `--bench` followed by a live MoneroOcean run. Discard:
   - the first 5 minutes (warm-up, JIT code-cache warm-up),
   - dataset build and first-start calibration periods,
   - any period where CPU ≥ 65 °C pause or battery ≥ 40 °C pause
     was active, or where 60-second hashrate fell more than 20%
     below the cool peak for that rung (throttled period),
   - any period with rejected-share rate above ~2–3% (fault, not
     steady state).
   Keep only steady-state, unthrottled, full-mode, JIT-on,
   hardware-AES-on samples.
3. **Profile merge and sanitisation:** Merge raw profiles from at
   least three sustained runs on the same phone, same charger, same
   network, same room conditions. Drop counters for functions that
   ran only during discarded periods. Record profile provenance
   (phone, build, dates, conditions) in the build manifest.
4. **PGO build:** Rebuild Tier E with `-fprofile-instr-use=<sustained.profdata>`.
   Tier H and Tier C are **not** PGO-optimised from this profile;
   their code does not run in steady state, so a steady-state profile
   would mis-optimise them.
5. **Freshness rule:** A profile older than the emitter source it was
   taken from is invalid. Any change to Tier E source invalidates the
   profile and requires a new sustained run. A stale profile must not
   be reused "because it was close".

**Why this is not just "PGO":** The invention is the *exclusion* rule
and the *tier restriction*. Without them, PGO optimises startup and
throttling artefacts. With them, PGO has a chance — not a guarantee —
to optimise the code that actually runs during sustained mining.

### 5.4 Emitter-aware layout (second novel detail)

In addition to flags, SEHO orders the binary so that:

- Tier E functions are placed contiguously, in call order where
  known, with 64-byte alignment for entry points, to reduce icache
  misses during emission.
- Tier E helper stubs that are emitted per hash are kept in the same
  section as the emitter, not scattered through Tier H.
- Tier C code is placed after Tier E and Tier H, so a share-submission
  or logging path does not evict emitter code from icache during
  steady-state mining.
- No function is duplicated across tiers. Duplication would create
  two versions of the same code with different flags, and a call-site
  ambiguity that invalidates the A/B test.

Layout is tested as its own variable (layout on versus layout off,
all flags otherwise identical) before it is combined with any flag
change. If layout alone does not beat noise, it is dropped, even if
it "should" help in theory.

### 5.5 What SEHO does not do

- Does not rewrite the JIT emitter. Emitter *diffs* (upstream #3708,
  #3778) are a separate workstream, A/B tested separately, and must
  pass hash-sum verification. SEHO is a build method around the
  existing emitter, not a new emitter.
- Does not change RandomX parameters: dataset 2,080 MiB, cache
  256 MiB, scratchpad 2 MiB/thread, program 256 instructions, are
  consensus parameters for `rx/0`. Changing them mines a different
  algorithm, not Monero.
- Does not change the pool, the payout threshold, or the wallet
  model. Pool remains `gulf.moneroocean.stream:20032`, TLS on,
  direct to the user's own public wallet address, no custodian,
  no DePIN token.
- Does not use root, forced huge pages, MSR modification, DVFS
  governor changes, or thermal-pause disabling. Those are either
  unavailable to an unrooted app or actively harmful to sustained
  speed and battery life.
- Does not use a Wasm JIT, an LLVM/Cranelift replacement JIT, or a
  tiered JIT. RandomX programs are discarded after 2,048 iterations;
  there is no hot program to tier-optimise.

---

## 6. Why this could work, and why it might not

### 6.1 Best case for SEHO

Emitter time is a measurable fraction of `T_hash` on A78, the upstream
emitter has redundant instructions or spills that target-specific
code generation and ThinLTO remove, sustained PGO places the hottest
emitter paths contiguously, and cold-code size reduction frees icache
for the emitter. Sustained hashrate rises, JIT time per hash falls,
accepted shares rise in proportion, temperatures do not rise faster
than hashrate. SEHO is accepted for the winning variant only.

### 6.2 Likely null case

Emitter time is small, memory wait dominates, and the upstream emitter
is already near-optimal for A78. Target flags, ThinLTO, and PGO each
move sustained hashrate within noise. SEHO is rejected, and the build
stays on the uniform baseline. This is a useful result: it closes the
compiler-flag line of inquiry and redirects effort to affinity,
prefetch, and thermal work, where the phone's ceiling actually lives.

### 6.3 Failure modes that look like success (and how SEHO blocks them)

| False success | How it happens | SEHO block |
|---|---|---|
| Burst gain | First-minute hashrate high, throttled later. | Decision metric is 30-minute sustained, not burst. |
| Light-mode gain | Build falls back to light mode, reports a different H/s. | Gate: full mode must be logged, else rejected. |
| Interpreter gain/loss | JIT off, hashrate collapses or changes for a non-flag reason. | Gate: JIT on must be logged, else rejected. |
| Software-AES gain/loss | Hardware AES off on a core, hashrate changes. | Gate: hardware AES on per core, checked via `/proc/cpuinfo` Features and log. |
| Rounding-mode invalid shares | Shared rounding state, shares look plausible locally, all rejected by pool. | Gate: multi-threaded live run, accepted shares must rise, reject % ≤ ~2–3%. |
| Thermal confound | One variant run in a cool room, another in a warm room. | Controls: same charger, network, room, case off, ventilated surface, pre-cool, cool-down between rungs (§8). |
| Dataset-rebuild tax | One variant restarts the process often, another keeps dataset alive. | Rule: dataset/VM kept alive across pause; Stop (process kill) is not used between rungs except for the recorded cool-down. |
| Profile contamination | PGO profile includes startup/throttling, optimises wrong code. | Sustained-only profile rule (§5.3), with discarded periods recorded. |
| Additive percentages | Adding B + C + D gains from separate tests. | Rule: combined variant tested as its own variant; gains are not additive. |

---

## 7. Controls

### 7.1 Fixed across all variants

- Phone: NARZO 70 Pro 5G, Dimensity 7050, same handset, same OS build.
- Charger and cable: same, slow charger preferred (5–10 W over 30 W+,
  to reduce charger heat), plugged in for every rung.
- Network: one network for the whole session (Jio or Wi-Fi, recorded),
  no switch mid-rung or between rungs in the same session.
- Room: same room, similar ambient temperature, recorded per rung.
  Phone on a hard ventilated surface, case off, not in sun, not on
  a bed, not under a blanket, screen off except when taking a reading.
- Other apps: all heavy apps closed. No video, game, download, second
  miner, or VPN during a rung.
- Safety: charging-only ON, CPU pause 65 °C / resume 55 °C, battery
  pause 40 °C / resume 37 °C, unchanged for every variant.
- Thread rung: the same rung for a given A/B comparison (start with
  big-cores-only, 2 threads, affine, after the affinity patch; then
  repeat the winning flag variant across the full ladder only if it
  wins at the first rung — see §8.4).
- Wallet: same public address, same worker (`AX-…`), same pool,
  same TLS setting. Address is never typed by hand in the test;
  it is pasted/scanned once and masked in the UI thereafter.

### 7.2 What varies, one at a time

Exactly one item from the Variant list in §5.2 changes between
baseline and a test variant. Compiler flags, tier partition, layout,
and PGO are never changed together in a single comparison, except in
the explicitly labelled Combined variant, which is itself one
comparison against baseline, not a sum of prior comparisons.

### 7.3 Randomisation and repetition

- Run order: baseline and variant order randomised per session,
  with full cool-down (at least 10 minutes, app stopped, CPU back
  near idle baseline) between rungs, to prevent heat carry-over
  from favouring the first run.
- Repetition: at least three sustained runs per variant, on
  separate occasions if possible, with the same conditions recorded.
  A single run is never a result.
- Control repeat: after the last variant, repeat baseline once.
  If repeat baseline differs materially from first baseline, session
  conditions drifted and the session's comparisons are marked
  unreliable, not averaged away.

---

## 8. Experiment design

### 8.1 Baseline (B0)

Uniform build, NDK 28 recipe in `docs/BUILD-XMRIG.md`, generic
arm64-v8a, Release, TLS on, OpenSSL 3, donate 0%. No tier split,
no target-specific flags, no LTO, no PGO, upstream layout.
Baseline must pass §8.2 gates. If it does not, fix the build;
do not benchmark a broken baseline.

### 8.2 Gates — must pass before any speed comparison

For every variant, including baseline, before benchmarking:

1. Launches on the phone, no crash, no missing-library error.
2. Log shows: JIT on, hardware AES on, full mode (not light),
   dataset built once (2,080 MiB), `OpenSSL 3` in strings,
   TLSv1.3 handshake to `gulf.moneroocean.stream:20032`,
   `DONATE 0%` banner or equivalent donate-level 0 verification.
3. Hash-sum verification: benchmark hash sum matches the reference
   for the same seed (via `xmrig --verify` / benchmark hash-sum
   check, as available in the fork). Any mismatch = rejected,
   however fast the build looks.
4. Single-threaded live run: accepted shares rise, rejected ~0.
5. Multi-threaded live run: accepted shares rise, rejected ≤ ~2–3%.
   This is the rounding-mode validity test; single-threaded
   equality alone is insufficient.
6. MoneroOcean dashboard: worker present with the recorded `AX-…`
   name, speed > 0, accepted shares increasing, pending XMR
   increasing over the session. App H/s alone is never proof.

Any variant failing any gate is rejected regardless of H/s.

### 8.3 Measurements per run

For every 30-minute rung, record:

- Variant, build manifest reference, thread rung and affinity
  (or "unpinned" label if the affinity patch is not present).
- Charger, network, room temperature, case on/off.
- 60-second hashrate at 5, 15, and 30 minutes. **Never** 10-second
  hashrate for decisions.
- 30-minute sustained average (decision number), and JIT time
  per hash if the fork logs it (or an emitter-time proxy from
  benchmark mode, labelled as such).
- Accepted shares at start and end, difference; rejected shares
  at start and end, difference; reject %.
- CPU temperature maximum, battery temperature maximum,
  battery % at start and end (change while charging; a fall
  while plugged in is a flag).
- Pause count and reasons (heat, battery, charging, network).
- Throttled? Yes/no, and how it was known (repeated pauses,
  falling 60-second hashrate, phone uncomfortable to hold).
- Pool algo in the log (`new job … algo ___`; expected `rx/0`
  in the normal case — a light-algo assignment is reported, not
  quoted as earnings, and dashboard XMR/day decides profit).
- MoneroOcean pending XMR at session start and end.
- Binary size, and, for the PGO variant, profile provenance.

### 8.4 Procedure

1. Pre-cool phone 15 minutes, idle, charger on. Record idle CPU /
   battery temperatures and battery %.
2. First-start benchmark once only (dataset + calibration, few
   minutes), before the rungs. Later rungs reuse cached `algo-perf`;
   do not clear it between rungs.
3. For each variant, in randomised order: run the rung for
   30 minutes, recording §8.3 at 5/15/30 minutes. Stop properly
   (Stop, not Pause) at the end of the rung, then cool down at
   least 10 minutes before the next variant.
4. Repeat each variant at least three times.
5. After all variants, repeat baseline once as a control.
6. Decide per §9. Write the short final report per §10.

### 8.5 Statistical treatment

- Report mean and range (not just mean) for sustained hashrate
  across the three runs per variant.
- Report run-to-run noise as the range of repeat baseline runs.
  A variant is a candidate only if its mean sustained hashrate
  exceeds baseline mean by more than the baseline range, and
  its lowest run still exceeds baseline mean — a single high
  run is not a win.
- Do not use a formal significance test on three runs to claim
  a precise p-value; with this sample size, the honest statement
  is "beyond observed noise" or "within noise", with the ranges
  shown. If more runs are affordable, increase repetitions
  before claiming a smaller gain.
- Record temperatures alongside hashrate. A variant that is
  faster but hotter, with more pauses, must be judged on its
  sustained, pause-inclusive average, not on its pre-throttle peak.

---

## 9. Falsification criteria

SEHO, or any single variant, is **rejected** if any of the following holds:

1. It fails any gate in §8.2.
2. Its mean sustained hashrate does not exceed baseline mean by
   more than baseline run-to-run range, across at least three runs.
3. Its JIT time per hash does not move in the direction predicted
   by H2, *and* its hashrate gain, if any, disappears when
   temperature, thread placement, or prefetch mode are controlled
   — i.e. the gain was a confound, not a compiler effect.
4. Its gain appears only in burst (5-minute) hashrate and not in
   30-minute sustained hashrate.
5. Its gain appears only with rejected shares above ~2–3%, or
   with battery % falling while charging, or with repeated pauses
   — i.e. it is unsustainable.
6. Its PGO variant's gain disappears when the profile is rebuilt
   from a fresh sustained run, indicating the first profile was
   contaminated or stale.
7. Its combined variant does not beat the best single variant —
   i.e. gains were not additive, and the combined build is not
   justified over the simpler winner.

Rejection is a result, not a failure of the paper. A rejected SEHO
variant must be recorded as rejected, with the ranges that rejected
it, so the next builder does not re-test it blindly.

---

## 10. Limitations

1. **Phone specificity.** SEHO is designed for Dimensity 7050, A78.
   Its flags, layout, and profile do not transfer to another SoC
   without re-measurement. `-mcpu=cortex-a78` is correct for this
   phone and wrong as a general default.
2. **Emitter share unknown.** Until JIT time per hash is measured
   on this phone, the ceiling model in §4.3 cannot bound the
   expected gain. SEHO may be testing a small fraction of total time.
3. **Profile collection cost.** Sustained PGO requires an
   instrumented build, a phone run, profile merging, and a rebuild.
   On a CI pipeline without phone-in-the-loop automation, this is
   manual work, and a stale profile is worse than no profile.
   SEHO v1 therefore treats PGO as an optional variant, not as a
   default.
4. **Thermal confounding.** Even with controls, ambient temperature,
   charger heat, and battery age drift between sessions. The
   control-repeat baseline (§7.3) mitigates this but does not
   eliminate it. Small gains (within a few percent) may be
   unresolvable on a phone without a temperature-controlled room.
5. **Pool and market confounding.** Dashboard XMR/day moves with
   price, difficulty, and pool assignment. SEHO judges compiler
   variants on hashrate and accepted shares, not on a single day's
   XMR figure, to avoid crediting market movement to the compiler.
6. **No new JIT, no new hash.** SEHO does not invent a faster
   RandomX program, a faster JIT algorithm, or a new proof-of-work.
   If the frontier gain lies in the emitter itself (upstream
   #3708/#3778 class), SEHO will not capture it; that work must
   be done as a separate, hash-sum-verified emitter diff.
7. **Android constraints.** An unrooted app cannot set DVFS governors,
   force huge pages, use MPAM/DSU cache partitioning, or load a
   custom scheduler. SEHO works within those constraints; it does
   not pretend to bypass them.
8. **Sample size.** Three runs per variant is a minimum, not a
   guarantee of precision. Small effects may remain unresolved,
   and the honest conclusion in that case is "within noise",
   not a rounded percentage.

---

## 11. What to build first, in order

1. Baseline gate (§8.2) — prove the uniform build passes JIT,
   hardware AES, full mode, TLS, donate 0%, hash-sum, and pool
   acceptance. No benchmarking before this.
2. JIT time per hash logging — add it to the fork's log if it is
   not already there, so `f_emit` can be estimated. Without this,
   H2 cannot be tested.
3. Tier partition + linker layout — one variable, A/B tested.
4. Target flags variant (armv8.2-a+crypto, cortex-a78) — one variable.
5. Opt-level A/B (`-O2` vs `-O3`, Tier E only) — one variable.
6. ThinLTO variant (Tier E, then Tier E+H) — one variable.
7. Sustained PGO variant (Tier E only, §5.3) — one variable, last,
   because it depends on a correct sustained profile.
8. Combined variant — only winners combined, tested as its own variant.
9. Final report (§12) — with ranges, gates, and a keep / reject
   decision per variant. No percentage is quoted without its range
   and its conditions.

---

## 12. Final report template (to be filled only after measurement)

1. Baseline sustained hashrate (mean, range, runs), with gates passed.
2. Per variant: sustained hashrate (mean, range), JIT time per hash,
   accepted/rejected, reject %, CPU/battery maxima, battery change,
   pauses, pool algo, dashboard pending XMR change.
3. Decision per variant: keep / reject, with the falsification
   criterion (§9) that decided it, in one sentence.
4. Winning build, if any: manifest reference, flags per tier,
   layout, profile provenance, binary size, signature status.
5. Limitations encountered in this run (temperature drift,
   profile staleness, gate failures, discarded runs and why).
6. Next step: either ship the winning variant into the APK build,
   or record SEHO as rejected and redirect effort to affinity,
   prefetch, and thermal work.

No $/month figure belongs in this report from a 30-minute test alone.
A $/month figure needs live price, network difficulty, and pending
XMR over days, and must be labelled as an estimate at the time it
is calculated, not as a promise.

---

## References (mechanism sources, not mining results)

- tevador RandomX documentation: configuration, performance optimisation,
  virtual machine, JIT-compiled VM for RISC-V.
  https://deepwiki.com/tevador/RandomX
- XMRig changelog, ARM64 JIT lineage (v6.21.0, v6.25.0 #3708,
  v6.26.0 #3778) and CPU configuration documentation.
  https://raw.githubusercontent.com/xmrig/xmrig/master/CHANGELOG.md
  https://xmrig.com/docs/miner/config/cpu
- MoneroOcean XMRig fork v6.25.0-mo1, as used in Anvil docs
  (`docs/BUILD-XMRIG.md` in this tree).
- Smejkal et al., HARP, ACM Middleware '25, DOI 10.1145/3721462.3770774.
- Liang et al., FBHS, IEEE Transactions on Sustainable Computing,
  vol. 11, pp. 516–529, Sept–Oct 2026.
- Jeon et al., Phoenix, ACM Transactions on Embedded Computing Systems,
  vol. 25, no. 2, 2026, DOI 10.1145/3793860.
- Samadi et al., iDEAS, IEEE Access, vol. 13, pp. 200711–200724, 2025,
  DOI 10.1109/access.2025.3636995.
- SChernykh, RandomX v2 testing gist (desktop, not a phone result).
  https://gist.github.com/SChernykh/6058ecf01c929883b9d19c7eeadc8809
- VoltMiner phone benchmarks (June 2026, blog, not peer-reviewed) and
  browser rounding-mode bug report (October 2026).
  https://www.voltminer.net/blog/how-much-monero-can-a-phone-mine
- Prior work in this tree: `docs/DIMENSITY7050-CONFIG.md`,
  `docs/WALLET-CONNECTION-UI.md`, `docs/THERMAL-POWER-SUSTAINED.md`,
  `docs/ALGORITHM-LEVEL-DESIGN.md`, `docs/FRONTIER-LOWLEVEL-A.md`,
  `docs/COMPILER-BUILD-FRONTIER.md` — read-only references, not
  results claimed here.

---

*End of paper. No Dimensity 7050 speedup is claimed. The next step is
measurement, under §8, on the phone itself.*
