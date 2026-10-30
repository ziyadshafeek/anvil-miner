# FRONTIER — Workstream B: JIT compiler frontier for RandomX on ARM64

Scope: mining only, low level only. Phone target in the parent factory is a
Dimensity 7050 (2x Cortex-A78 + 6x Cortex-A55), but no JIT result below is
measured on that phone unless explicitly stated. Do not quote any percentage
in this file as a Dimensity 7050 gain.

## Verdict first

There is no 2025–2026 JIT breakthrough that replaces tevador RandomX's
ARM64 JIT for Monero mining. The frontier is incremental, not a new engine:

1. Keep tevador's `jit_compiler_a64.cpp` — it is the proven path.
2. Audit and test the two most recent upstream A64 changes: v6.25.0
   instruction-selection cleanup (#3708) and v6.26.0 ARM64 fixes (#3778).
3. The only genuinely new JIT work with phone relevance is the Wasm JIT
   (RandomX-bonanza / randomx.js) — and it is *slower* than native, and
   its rounding-mode bug is a warning, not a technique to copy.
4. A general-purpose LLVM / Cranelift JIT is a research detour, not a
   shortcut: it would be slower to compile, likely worse code, and would
   still have to reproduce RandomX's exact floating-point, AES and
   scratchpad semantics bit-for-bit or every share is invalid.
5. RandomX v2 is not a JIT speedup for v1 mining. It is a heavier program
   (384 vs 256 instructions, +52.9% ops) whose metric is work-per-Joule
   on desktop Zen 5, not H/s on a phone.

If the parent factory wants "not empty-handed", the honest frontier is:
audit #3708 + #3778, verify JIT + hardware AES + full mode on-device,
overlap JIT compilation with hashing (batch/pipeline), keep the code
cache alive across pause/resume, and test prefetch depth — then measure.
Everything else below is promising or rejected, with the reason stated.

## 1. Baseline: how RandomX JIT actually works

- One hash = initialise scratchpad from the input, then execute 8 random
  programs, each 256 instructions (v1) in a loop for 2048 iterations,
  then Blake2b the registers + scratchpad fingerprint.
- The program is different for every hash, so it cannot be compiled
  ahead of time. The JIT translates RandomX bytecode to native code
  at runtime, per hash, per thread.
- Modes (tevador RandomX docs):
  - Full + JIT: 2080 MiB dataset — fastest, mining mode.
  - Full + Interpreted: 2080 MiB — medium, fallback.
  - Light + JIT: 256 MiB cache only — slow, verification.
  - Light + Interpreted: 256 MiB — slowest, verification fallback.
  - Fast vs light is also described as ~8x in the design docs; light
    mode generates dataset items on the fly with SuperscalarHash and
    is not a mining optimisation.
- Flags: `RANDOMX_FLAG_JIT`, `RANDOMX_FLAG_HARD_AES`,
  `RANDOMX_FLAG_FULL_MEM`, `RANDOMX_FLAG_LARGE_PAGES`,
  `RANDOMX_FLAG_SECURE` (W^X: JIT pages never writable and executable
  at the same time, slight cost).
- JIT backends shipped in tevador RandomX: x86-64 (`jit_compiler_x86.cpp`),
  ARM64 (`jit_compiler_a64.cpp`, NEON + Crypto Extensions),
  RISC-V (`jit_compiler_rv64`, template + assembly, Zba/Zbb optional,
  software AES where hardware AES is absent). Everything else falls
  back to the portable interpreter, described throughout as "much slower".

## 2. JIT vs interpreter vs native — the numbers that exist

| Comparison | Claimed / measured | Status and limit |
|---|---|---|
| JIT vs interpreter, RandomX docs | 2–3x speedup on supported platforms | PROVEN as a direction, DOCUMENTATION figure, not a Dimensity 7050 measurement. It is JIT-vs-interpreter, not a gain over an already-JIT build. Do not add 2–3x to a current Anvil number. |
| Hardware AES vs software AES, RandomX docs | 30–50% if CPU supports AES-NI / Crypto Extensions | PROVEN as a direction, x86 wording in the docs; on ARM64 it is Crypto Extensions + NEON. Verify `aes` in `/proc/cpuinfo` Features on the actual phone, per core — do not assume A78 and A55 report the same. |
| Large pages, RandomX docs | 5–10% | DESKTOP / privileged only. On unrooted Android, effectively unavailable. Do not budget it for the phone. |
| Full vs light | ~8x (design docs) | PROVEN architecture ratio. Light mode is a verification mode, not a competing miner. |
| Native x86 JIT vs Wasm JIT, RandomX-bonanza | Wasm full mode ~10% of native on AMD64, ~23–25% on Apple M4 ARM64 (10 cores, same thread count); light mode ~53–67% of native light on M4 | PROMISING as a port, REJECTED as a phone speed path. Wasm is still a large fraction slower than native, has no native AES, and needs cross-origin isolation + SharedArrayBuffer for full mode. See §5. |
| Cranelift vs LLVM, general JIT literature | Cranelift compiles ~10x faster than LLVM, code ~86% of LLVM quality; vs V8 TurboFan ~2% slower, vs LLVM Wasm engine ~14% slower | PROMISING for fast-compile JITs in general, NOT a RandomX result. Faster compilation does not help RandomX much, because each program is executed 2048 iterations — compile time is amortised, execution quality dominates. See §6. |
| Python 3.15 JIT, general JIT news Oct 2026 | 7–12% on benchmarks, some benchmarks 15% slower | IRRELEVANT to RandomX except as a caution: JIT headlines shrink once the baseline is checked (the CPython tail-call story: 10–15% became 1–5% vs a correct baseline). Apply the same scepticism to any JIT claim without a RandomX benchmark. |

### Why JIT-vs-native is the wrong question for RandomX

There is no "native" RandomX program to compare against, because the
program is generated per hash. The choice is only:

- JIT: translate once, execute natively 2048 times — wins by a large
  factor over interpretation, because the loop is long.
- Interpreter: decode and dispatch every instruction, every iteration
  — pays dispatch cost 2048x.
- AOT: impossible in the general case, because the program depends on
  the hash input / seed. Caching compiled programs across hashes does
  not help: the next hash has a different program.

Consequence: the frontier is not "replace JIT with native". It is
"make the per-hash JIT cheaper and the generated code better, without
changing its semantics".

## 3. PROVEN — use these, in this order

### P1. Ship tevador A64 JIT, verified on-device — not assumed
- Backend: `jit_compiler_a64.cpp`, NEON + Crypto Extensions.
- Verify in the built binary's log, before any benchmark claim:
  JIT on, hardware AES on, full mode (2080 MiB), dataset built once.
- If any of those silently falls back (interpreter, software AES,
  light mode), that is a build/detection bug to fix first. A silent
  fallback collapses hashrate and must be logged as a fault, not
  averaged into a benchmark.
- Secure JIT (W^X) is a policy choice: keep it off for mining unless
  the fork already enables it, and note the cost if it is on. Do not
  claim a W^X gain — it is a security trade, not a speed trade.

### P2. Audit upstream A64 changes #3708 and #3778 against the pinned fork
- XMRig 6.25.0, #3708: "Minor Aarch64 JIT changes (better instruction
  selection, don't emit instructions that add 0, etc)".
- XMRig 6.26.0, #3778: "RandomX: ARM64 fixes".
- Earlier A64 history that shows this backend is actively maintained,
  not frozen: v6.21.0 "ARM64 JIT: don't use `x18` register"
  (x18 is reserved on some ARM64 ABIs, notably Apple / Android
  platform code — using it is a correctness bug, not just a speed
  issue); v6.19.3 "RandomX: optimized program generation";
  v6.19.x "RandomX: optimized ARMv8 dataset read" and
  "added BMI2 version for scratchpad prefetch" (x86 analogue — the
  principle, not the instruction, transfers).
- Action: diff #3708 and #3778 against MoneroOcean XMRig v6.25.0-mo1
  (the pinned fork in the Anvil docs). If clean, test on-device with
  `--bench` + live accepted shares. If either breaks MoneroOcean
  algo-perf benchmarking, donate 0%, or TLS, drop it. No percentage
  is claimed for either change on ARM64 in the changelog — do not
  invent one.
- Sources: XMRig CHANGELOG (raw.githubusercontent.com/xmrig/xmrig/
  master/CHANGELOG.md); hashrate.no XMRig changelog mirror.

### P3. Batch / pipeline: compile the next program while finishing this hash
- The scash cpuminer description of batch mode is the clearest public
  statement of this technique: "The VM begins JIT-compiling programs
  for nonce N+1 while finishing the hash for nonce N, improving
  pipeline utilization."
- This is already the shape of `randomx_calculate_hash` batch APIs
  in tevador's benchmark (`--mine`, batch by default in the benchmark
  tool). For the phone miner, the check is: does the Anvil fork use
  batch mode, or does it calculate hashes one by one and pay full
  JIT + dataset-access latency serially?
- This is the highest-value *free* JIT-adjacent win available without
  touching the JIT itself: it hides JIT latency behind useful work.
  Measure it as sustained 60s H/s, not as a compile-time microbenchmark.

### P4. Keep JIT code, VMs and dataset alive across pause/resume
- Dataset: 2080 MiB, built once per key. Cache: 256 MiB (Argon2d).
  Scratchpad per thread: L1 16 KiB / L2 256 KiB / L3 2 MiB.
- Pause (process alive) preserves JIT programs, VMs and dataset.
  Stop destroys them and forces a full rebuild — minutes of zero
  hashing, plus re-benchmarking if algo-perf is not cached.
- Frontier rule: never restart the native process on a transient
  pool disconnect; reconnect and resume with the existing dataset
  if the seed is unchanged. Never rebuild the dataset per share,
  per pause, or per thread. This is not a JIT code change, but it
  is where most phone miners lose more time than any JIT micro-
  optimisation would save.

### P5. Prefetch depth — test, do not copy from x86 or v2
- v1 dataset prefetch is 1 iteration ahead; v2 moves to 2 iterations
  ahead (see §7). On x86, scratchpad prefetch has BMI2 and other
  variants; on ARM64 the equivalent is the dataset-read path
  optimised in v6.19.x.
- Action: test each prefetch mode exposed by the pinned fork on the
  same thread ladder, one variable at a time, sustained H/s only.
  Keep the upstream default on a tie. A deeper prefetch that thrashes
  the phone's small shared L3 will hurt, not help — the phone's
  cache, not the desktop's, decides.

## 4. PROMISING — test, with a falsifiable benchmark each

### Q1. Register allocation and instruction selection for A64, tuned for A78
- What the JIT already does: maps RandomX registers to hardware
  registers, fuses operations where possible, uses efficient
  addressing modes, optimises branches. The RISC-V JIT documentation
  shows the same discipline explicitly: all 32 integer + 32 FP
  registers assigned fixed roles (scratchpad pointer, dataset/cache
  pointer, iteration counter, VM registers, temporaries, IMUL_RCP
  literals), because spills to memory in the inner loop are fatal.
- Frontier idea: review the A64 register map the same way — are
  scratchpad pointer, dataset pointer, iteration counter and the
  8 integer + 8 FP VM registers pinned to callee-saved registers
  across the whole program, or are any spilled per iteration?
  Are `CFROUND` / rounding-mode changes handled without flushing
  the FP pipeline unnecessarily? (v2 tweaks CFROUND to change
  rounding 16x less often — that is a hint that rounding-mode
  handling is a real cost, even if v2 itself is not adopted.)
- Test: differential benchmark, same seed, same thread count,
  upstream JIT vs modified JIT, with hash-sum verification
  (`xmrig --verify` / benchmark hash sum). Any change that alters
  a single hash bit is rejected, however fast it looks.
- Status: PROMISING, unmeasured on Dimensity 7050. No percentage claimed.

### Q2. Superscalar / dataset-generation optimisation, not main-loop JIT
- Dataset generation uses SuperscalarHash: a superscalar program
  (128 bytes overhead per program in the Go port's constants) that
  must be scheduled across execution ports with latencies.
- The Go RandomX port (`go-randomx`) exposes the machinery
  explicitly: `ScheduleMop`, `ScheduleUop`, port-busy tables,
  decoder types, latencies — i.e. dataset generation is a scheduling
  problem, not just a code-generation problem.
- Frontier idea: the phone pays dataset-build cost once per key,
  not per hash, so this is a startup cost, not a sustained H/s cost
  — unless the miner restarts often (see P4). Optimising it matters
  for time-to-first-share, not for steady-state H/s. Do not confuse
  the two in a benchmark.
- Status: PROMISING for startup, NEUTRAL for sustained mining.

### Q3. Code-cache management: size, alignment, icache flush
- Every JIT-compiled program is new code. On ARM64, newly written
  code must be flushed from dcache to icache before execution, or
  the CPU executes stale bytes. General AArch64 JIT backends
  (e.g. Dolphin ARMJIT_A64, cited here only as a JIT-engineering
  reference, not a RandomX source) treat `FlushIcache` /
  `FlushIcacheSection` as a first-class operation, with a register
  cache across instruction boundaries and dedicated host registers
  for memory base / state.
- Frontier idea: check the A64 JIT's icache handling and code-cache
  reuse: is code emitted per hash and discarded, or is there any
  safe reuse across hashes with the same program shape? (RandomX
  programs are random, so reuse is limited — but the *prologue /
  epilogue* and helper stubs are not random and should not be
  re-emitted per hash if they already are.)
- Status: PROMISING, engineering hygiene, not a headline gain.
  Measure as JIT time per hash, not just H/s.

### Q4. Tiered JIT: fast baseline JIT, then optimising JIT for hot programs — REJECTED for RandomX as designed, kept here to close the idea
- General JITs (JVM, V8, Wasmtime) tier: interpret, then baseline
  JIT, then optimising JIT for hot code, with profiling and
  deoptimisation. That works because real programs repeat the same
  code millions of times.
- RandomX programs do the opposite: each program runs 2048
  iterations and is then discarded, never to repeat. There is no
  hot program to optimise on a second tier, and profiling cost
  would be paid on every hash for no reuse.
- Status: REJECTED for RandomX main loop. The only tiering that
  makes sense is P3 (pipeline the next compile) and P4 (keep
  helpers / dataset alive), not a second optimisation tier.

## 5. Wasm JIT — the most interesting new work, and why it does NOT beat native on a phone

### What exists
- `randomx.js` (l1mey112): complete rewrite of RandomX core in C
  (~5000 lines) + TypeScript glue, Wasm JIT VM, Wasm SIMD for crypto,
  directed rounding via semifloat, shared memory / threads.
  Originally light mode only, because "no one on earth would give
  a webpage multiple gigabytes of memory" and because Wasm / JS
  lack directed rounding (only round-to-nearest is native).
- `RandomX-bonanza` (aa022, CC0 Wasm JIT backend): full mode in
  the browser, shared dataset via SharedArrayBuffer, full dataset
  built in <10s on most modern CPUs in its own claim. VoltMiner's
  Browser Experiment ships it (commit f9848e3, Emscripten 4.0.10)
  alongside WebRandomX (commit 780908a) for single-thread.
- Performance claimed by randomx.cc: full mode ~10% of native on
  AMD64, ~23–25% on Apple M4 ARM64 (10 cores, same thread count);
  light mode ~53–67% of native light on M4. Those are M4 / AMD64
  numbers, not Dimensity 7050 numbers, and they are still a large
  fraction below native.

### Why it is slower, in principle
1. No native AES instruction in Wasm. AES must be emulated with
   SIMD or software — RandomX is AES-heavy (AesGenerator1R/4R,
   AesHash1R, plus v2's extra AES in register mixing).
2. No directed rounding in Wasm / JS. RandomX *requires* all IEEE
   754 rounding modes (CFROUND changes the mode inside the hashing
   loop). Wasm ports emulate it with a variable + software float
   fallback (semifloat). That fallback is the slow path, and it is
   taken whenever the mode is not round-to-nearest.
3. No 2 GiB dataset guarantee in a browser. Full mode needs
   cross-origin isolation (COOP + COEP) for SharedArrayBuffer,
   and a browser tab can be throttled / killed far more aggressively
   than a foreground Android service.

### The rounding-mode bug — the validity lesson for any JIT work
- VoltMiner, 3 Oct 2026, "RandomX in a Browser: Why Every Single
  Share Was Rejected": the browser miner found shares at exactly
  the difficulty-predicted rate, submitted 46 in a 4-hour soak,
  and every one was rejected `Low difficulty share`.
- Root cause: Wasm has no `fesetround`, so rounding mode was a
  process-global variable. One VM per thread, one rounding mode
  for all of them. Two threads overwrote each other's mode
  mid-program, so each thread's arithmetic was evaluated under its
  neighbour's mode. The result was "a valid-looking hash of nothing
  in particular" — locally it passed its own difficulty check,
  the pool disagreed, because the pool recomputes with the correct
  mode.
- Native Android mining on the same pool / wallet in the same
  report: 358 accepted / 0 rejected. The bug was not the pool,
  the port, the nonce offset, or the blob — the Wasm hash was
  byte-identical to native for a single-threaded test. It was
  thread-shared rounding state.
- Frontier rule, non-negotiable for any JIT change: rounding mode
  is per-VM, per-thread state. Any JIT optimisation that shares,
  caches, or hoists rounding-mode state across threads, or that
  assumes round-to-nearest, produces invalid shares. It will look
  like a performance problem (shares at the predicted rate, all
  rejected) and it is a correctness bug. Test multi-threaded
  with pool acceptance, not just single-threaded hash equality.
- Status: Wasm JIT = PROMISING as a portability / verification
  experiment, REJECTED as a phone speed path. Do not port Wasm
  JIT ideas into the native A64 JIT. Do adopt its lesson:
  per-thread rounding state, pool-accepted shares as the only
  validity proof.

## 6. LLVM / Cranelift / general JIT frameworks — REJECTED as a RandomX replacement, useful as a benchmark for JIT design

- LLVM: best code quality, slowest compilation, gigabytes of
  dependencies, optimised for output quality even with optimisations
  off. LLVM 22.1 (Feb 2026) still carries the SSA IR design from
  2000. For RandomX, compile time is paid per hash; LLVM's
  optimisation passes would dominate the 2048-iteration execution
  unless the program were cached — and it is not, because the next
  hash is a different program.
- Cranelift (Bytecode Alliance, Wasmtime): fast, secure, simple,
  aarch64 + x86-64 + riscv64, ~10x faster compilation than LLVM,
  ~86% code quality, ~2% slower than V8 TurboFan, ~14% slower
  than an LLVM Wasm engine in third-party Wasmtime benchmarks.
  That is the right trade for a *general* JIT that compiles many
  small programs — but RandomX's JIT is already a hand-written,
  single-purpose code generator that knows the VM's registers,
  scratchpad layout and AES paths. Replacing it with a general
  IR would add an IR translation layer, lose the hand-tuned
  register map, and still have to reproduce CFROUND, semifloat
  behaviour, and dataset prefetch exactly.
- Zig / Rust self-hosted backends (2025): faster debug builds,
  5–50% wall-clock improvements on real projects by bypassing
  LLVM for debug code, while still emitting LLVM bitcode for
  release optimisation. The pattern — fast path for iteration,
  LLVM for release — does not map to RandomX, where every hash
  is already a release-quality execution of a fresh program.
- Status: REJECTED for the Anvil fork. Keep LLVM / Cranelift in
  mind only as a source of JIT-engineering ideas (register
  allocation verification, e-graph optimisation, fuzzing with
  differential comparison against a reference implementation) —
  not as a code generator to adopt.

## 7. RandomX v2 — JIT-relevant, but not a v1 speedup

- Upstream XMRig 6.26.0 adds RandomX v2 support (PRs #3769, #3772,
  #3774–#3776, #3782, #3783). SChernykh's v2 test gist (desktop,
  not peer-reviewed, not a phone):
  - v1: 4,194,304 VM instructions + 262,144 AES = 4,456,448 ops.
  - v2: 6,291,456 VM instructions + 524,288 AES = 6,815,744 ops (+52.9%).
  - Program size 256 → 384 instructions.
  - CFROUND tweaked to change rounding 16x less often.
  - Group F / Group E register mix via 16 AES operations
    (the extra 262,144 AES ops).
  - Dataset prefetch 2 iterations ahead instead of 1.
  - v2 is 1.5x "heavier" per hash, so H/s comparison is for
    reference only; the real metric in the gist is work-per-Joule.
  - Desktop results in the gist: Ryzen 9 9950X v2 H/s 98.55% of
    v1, but work/Joule 150.72%; Ryzen AI 9 365 (28W, XMRig) v2 H/s
    102.5% of v1, work/Joule 156.73%; other Zen 5 rows 133–165%
    work/Joule. All desktop, large pages + MSR on, HWInfo64 power.
- What transfers to ARM64 JIT thinking:
  - Deeper prefetch (2 ahead) is a testable idea for v1, if the
    phone's cache can hold it — see P5. Do not assume it helps
    on a 2 MiB shared L3 phone.
  - Less frequent CFROUND changes reduce rounding-mode pipeline
    flushes — a hint for Q1, not a reason to adopt v2.
  - More AES per hash in register mixing fills memory-wait time
    with useful work — again, a design hint, not a v1 change.
- What does NOT transfer:
  - v2 H/s on desktop Zen 5 is not a phone H/s, and v2 work/Joule
    on desktop is not phone work/Joule (phone power is charger +
    battery + thermal throttling, not HWInfo64 on a desktop).
  - Mining Monero as `rx/2` today would not be Monero mining.
    v2 is a test-network / future-protocol change, not a pool job
    MoneroOcean will pay for as `rx/0`.
- Status: WATCH ONLY for v2 protocol. BORROW prefetch + CFROUND
  ideas for v1 testing. Do NOT upgrade the pinned fork to 6.26 /
  v2 mid-build without a separate verification pass (donate 0%,
  TLS, pool behaviour, hash-sum verification).

## 8. Other 2025–2026 JIT-adjacent work — checked, not adopted

- RISC-V JIT in RandomX (6.25.0: RISC-V JIT compiler added;
  6.26.0: vectorised main loop, vector AES, scratchpad prefetch
  fix): useful as a *template* for how a new JIT backend is
  structured (template assembly + register map + Zba/Zbb), not
  as code for ARM64. The RISC-V lesson is that a new backend
  takes explicit register allocation + AES path + prefetch work
  — i.e. the A64 backend's remaining work is in those same three
  places, not in a new framework.
- VAES-512 / Zen 5 (6.26.0 #3758, #3759): x86 only, irrelevant
  to ARM64. Do not quote VAES numbers for a phone.
- Transparent huge pages (6.25.0 #3743, Linux): desktop Linux
  only, not unrooted Android. See P1 / large-pages note above.
- ARMv8 dataset read optimisation (v6.19.x #2398): already in
  the lineage of the pinned fork's ancestry — verify it is present
  in the mo1 build, do not re-implement it.
- Secure JIT / W^X (RandomX 1.1.9–1.1.10 notes: macOS JIT privs
  are not ARM-specific, Apple Silicon force W^X + hardware AES):
  on Android, W^X policy for JIT pages is a platform / SELinux
  question, not a speed question. If the Anvil fork already runs
  JIT on the target phone, W^X is not blocking it; if it does
  not, that is a platform bug to diagnose, not a JIT algorithm
  to replace.

## 9. What breaks validity — the reject list for any JIT change

Any of these, however fast the local H/s looks, produces invalid
or rejected shares and must be rejected:

1. Changing floating-point rounding behaviour, sharing rounding
   mode across threads, or assuming round-to-nearest. (VoltMiner
   Wasm bug, §5 — the canonical example.)
2. Changing the program: fewer instructions, different instruction
   selection that alters semantics, skipping CFROUND, or
   "optimising away" a VM instruction that looks like a no-op
   (e.g. an add of 0 that affects flags / rounding / register state
   in a later instruction — #3708's "don't emit instructions that
   add 0" is safe only because upstream verified it does not
   change semantics; a fork must verify the same, not assume it).
3. Changing scratchpad / dataset / cache sizes, or using light
   mode for mining. Light mode gives the same *result* for
   verification in tevador's design, but it is ~8x slower and is
   not a mining optimisation; presenting light H/s as mining H/s
   is a category error.
4. Changing AES: software AES where hardware AES is expected,
   or vice versa, without verifying the hash sum. AES path must
   be bit-identical.
5. Spoofing `algo-perf`, benchmark results, or accepted-share
   counts. A JIT that reports H/s without pool-accepted shares
   is not mining.
6. Using `-Ofast` / fast-math in the JIT or VM host code.
   RandomX needs IEEE 754-compliant floating point; fast-math
   breaks rounding semantics. (The scash cpuminer `-Ofast`
   default is a warning example, not a model to copy.)
7. Forcing hardware AES on a core that does not report it.
   XMRig docs warn this crashes the miner; on a heterogeneous
   phone, A78 and A55 must be checked separately.
8. Sharing a JIT code cache, VM state, or scratchpad across
   threads without per-thread isolation. RandomX VMs are per-thread
   for a reason.

Validity proof, in order, for any JIT change:
1. Benchmark hash sum matches the reference (`xmrig --verify`
   / `randomx-benchmark` hash sum) for the same seed.
2. Single-threaded live run: accepted shares rise, rejected ~0.
3. Multi-threaded live run: accepted shares rise, rejected ~0
   — this is the test the Wasm bug failed.
4. Sustained 60s H/s over 30 minutes, not a 10s burst, at safe
   temperatures, charging-only.
5. MoneroOcean dashboard: worker present, hashrate > 0, pending
   XMR increasing. App H/s alone is never proof.

## 10. Prioritised handover to the builder

1. Verify JIT + hard AES + full mode on-device from logs; fix any
   silent fallback first. (P1)
2. Diff and test upstream #3708 + #3778 against pinned mo1;
   keep only if hash-sum + pool acceptance pass. (P2)
3. Confirm batch / pipeline mode is used; if hashes are calculated
   one by one, switch to batch. (P3)
4. Keep JIT code / VMs / dataset alive across pause/resume;
   never restart the native process on a transient disconnect. (P4)
5. Test prefetch modes one at a time, sustained H/s only; keep
   default on a tie. (P5)
6. Review A64 register map + CFROUND handling for spills /
   pipeline flushes; test any change differentially. (Q1)
7. Do NOT adopt: LLVM / Cranelift as a RandomX JIT, Wasm JIT
   as a native path, tiered JIT, RandomX v2 as a v1 speedup,
   `-Ofast`, forced hardware AES, or any shared rounding state.
8. Report JIT time per hash alongside H/s, so a future JIT change
   can be judged on compile cost + execution cost, not H/s alone.

## Sources (verbatim URLs from search results)

- https://deepwiki.com/tevador/RandomX/6.2-performance-optimization
- https://deepwiki.com/tevador/RandomX
- https://deepwiki.com/tevador/RandomX/2.2-virtual-machine
- https://deepwiki.com/tevador/RandomX/2-randomx-architecture
- https://deepwiki.com/tevador/RandomX/4.4-jit-compiled-vm-for-risc-v
- https://raw.githubusercontent.com/xmrig/xmrig/master/CHANGELOG.md
- https://www.hashrate.no/miners/xmrig/changelog?currency=GBP
- https://XMRig.com/docs/miner/config/cpu
- https://gist.github.com/SChernykh/6058ecf01c929883b9d19c7eeadc8809
- https://randomx.cc/
- https://web.voltminer.net/notices.html
- https://www.voltminer.net/blog/randomx-in-a-browser-the-rounding-mode-bug
- https://blog.l-m.dev/talks/zero-to-randomx-js-webmining.pdf
- https://cranelift.dev/
- https://docs.rs/rxvm-sys/latest/rxvm_sys/fn.randomx_create_vm.html
- https://data.safetycli.com/packages/pypi/randomx/changelog

Limits: no 2025–2026 peer-reviewed paper was found in this hunt
that proves a new RandomX JIT beats tevador's A64 JIT on an ARM
phone. The sources above are upstream docs, changelogs, gists,
and engineering blogs — marked as such throughout. No Dimensity
7050 JIT measurement was found; every phone number in this file
must be replaced by an on-device `--bench` + live pool-accepted
measurement before it is quoted as a result.
