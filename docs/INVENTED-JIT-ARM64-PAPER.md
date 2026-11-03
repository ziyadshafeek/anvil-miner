# A Rounding-Domain-Isolated, Dual-Buffered JIT for RandomX on ARM64

## Preserving Bit-for-Bit Hash Correctness While Hiding JIT and Memory Latency

**Scope note.** Phone mining only, low level only. Target: ARM64 Android,
Dimensity 7050 class (2× Cortex-A78 + 6× Cortex-A55, ARMv8.2-A), XMRig
RandomX, Anvil fork, direct to MoneroOcean. No laptop mining, no other
earning, no DePIN, no seed / private-key handling at any layer.
Only a public receiving address is ever sent to a pool.

**Status of this document.** This is an invention draft, not a measured
result. No percentage improvement and no hashrate figure is claimed
for Dimensity 7050 anywhere in this paper. Every gain direction below
is a hypothesis to be falsified by the experiment in §6. Any number
quoted from another source is labelled with its own hardware and
workload, and is not transferred to this phone.

---

## Abstract

RandomX on ARM64 is already JIT-compiled in upstream XMRig
(`jit_compiler_a64.cpp`, NEON + Crypto Extensions). The remaining
costs on a phone are not a missing JIT, but three costs paid on
every hash: (i) JIT compilation of a fresh, random program,
(ii) floating-point rounding-mode handling, and (iii) stalls
waiting for dataset and scratchpad data.

We propose **RDDB-JIT** — Rounding-Domain-isolated, Dual-Buffered
JIT — a combination, not a single trick, that attacks all three
costs without changing any hash bit:

1. **Static rounding-mode segment coalescing.** Because a RandomX
   program is fully known before its 2,048 iterations run, the
   rounding mode at every program point can be computed by a
   forward data-flow analysis at JIT time. Redundant mode changes
   are removed; a mode change is emitted only when the mode
   actually changes, and only at a segment boundary.
2. **Per-thread rounding-domain isolation.** Rounding state is
   never shared across threads. Each thread owns a rounding-domain
   object (current mode, shadow mode, and a verification checksum
   of mode transitions) that lives in thread-local storage and is
   never written by another thread. This is a direct structural
   answer to the failure mode in which a shared rounding variable
   produced locally valid-looking hashes that a pool rejected.
3. **Dual-buffer pipelining.** While thread *t* executes the JIT
   code for hash *N*, it compiles the program for hash *N+1*
   into a second code buffer, using a second scratchpad window.
   JIT latency is hidden behind useful execution, rather than
   paid serially before every hash.
4. **Helper code-cache reuse.** Prologue, epilogue, AES helpers,
   dataset-read helpers, and rounding-mode segment stubs are
   deterministic across hashes. They are emitted once into a
   separate, W^X-managed helper code cache and called, not
   re-emitted per hash. Only the random program body itself is
   freshly compiled.
5. **Prefetch scheduling coupled to the iteration loop.**
   Dataset access in the main loop is sequential in iteration
   order, unlike scratchpad access, which is data-dependent.
   The JIT therefore emits a dataset prefetch for iteration
   *i+2* while executing iteration *i*, and emits no speculative
   prefetch for data-dependent scratchpad addresses.

Correctness is argued, not assumed: rounding-mode analysis is a
monotone data-flow over a four-element lattice; register pinning
is proved by a fixed register map with no spills in the inner
loop; prefetch is a hint that cannot change a value; helper reuse
is safe because helpers are pure with respect to the program
body except for explicitly listed state. Validity is proved
empirically only by hash-sum equality with the reference
implementation and by multi-thread pool-accepted shares — the
two tests that a shared-rounding bug would fail.

---

## 1. Problem

### 1.1 What RandomX actually does per hash

From the tevador RandomX specification and documentation:

- A hash initialises a scratchpad from the input, then executes
  8 random programs, each 256 instructions in v1, iterated
  2,048 times in the reference parameters, then fingerprints
  registers and scratchpad with Blake2b.
- Dataset: 2,080 MiB (2,048 + 32 MiB), shared by all threads,
  not per thread. Cache: 256 MiB (Argon2d).
- Scratchpad per thread: L1 16 KiB, L2 256 KiB, L3 2 MiB.
  XMRig's full-speed rule is 256 KiB L2 + 2 MiB L3 per thread.
- Modes: Full + JIT (fastest, mining mode), Full + Interpreted,
  Light + JIT, Light + Interpreted. Light mode (256 MiB cache
  only) is a verification fallback, not a mining mode.
- Flags include `RANDOMX_FLAG_JIT`, `RANDOMX_FLAG_HARD_AES`,
  `RANDOMX_FLAG_FULL_MEM`, `RANDOMX_FLAG_LARGE_PAGES`,
  `RANDOMX_FLAG_SECURE` (W^X: JIT pages never writable and
  executable at the same time).

Because the program is different for every hash, it cannot be
compiled ahead of time. The JIT must translate it at runtime,
per hash, per thread. That is the cost centre this paper
addresses.

### 1.2 Why a phone is the hard case

- Dimensity 7050: 2 fast A78 cores + 6 slow A55 cores.
  A plausible shared L3 for this topology is ~2 MiB (third-party
  tables only; MediaTek's exact Dimensity 7050 L3 was not
  verified from a datasheet in prior work and must be confirmed
  on the phone via sysfs before it is quoted as fact).
  One thread's 2 MiB L3 scratchpad already equals that whole
  shared L3. Threads beyond the first one or two therefore
  spill to LPDDR5, which is an order of magnitude slower than
  cache.
- An unrooted Android app cannot set DVFS governors, force
  huge pages, or use MSR / cache-partitioning interfaces.
  Large pages (5–10% on desktop in RandomX docs) and 1 GiB
  pages are effectively unavailable here.
- The phone's own thermal governor throttles a sustained load.
  A first-minute burst therefore overstates sustained speed.
  Only a 30-minute sustained average counts.

### 1.3 The correctness trap that motivates §4.2

VoltMiner's 3 October 2026 browser experiment is the canonical
warning for any JIT work in this area: a Wasm RandomX miner
found shares at exactly the difficulty-predicted rate, submitted
46 in a 4-hour soak, and every one was rejected as
"Low difficulty share". Root cause: Wasm has no `fesetround`,
so rounding mode was a process-global variable shared by all
threads. Two threads overwrote each other's mode mid-program.
Each thread's arithmetic was evaluated under its neighbour's
mode. The result was a valid-looking hash of nothing in
particular — locally it passed its own difficulty check, the
pool disagreed, because the pool recomputes with the correct
mode. Native Android mining on the same pool and wallet in
the same report: 358 accepted / 0 rejected.

Lesson, stated as a design rule: **rounding mode is per-VM,
per-thread state. Any JIT optimisation that shares, caches,
or hoists rounding-mode state across threads, or assumes
round-to-nearest, produces invalid shares.** It will look like
a performance problem and it is a correctness bug.

---

## 2. Hypothesis

### 2.1 Null and alternative

- **H0 (null):** RDDB-JIT produces no sustained hashrate gain
  over the pinned baseline (MoneroOcean XMRig v6.25.0-mo1,
  ARM64 JIT, Full + JIT, hardware AES on) on Dimensity 7050,
  when measured as 30-minute sustained 60-second hashrate with
  pool-accepted shares, at CPU ≤ 65 °C and battery ≤ 40 °C,
  charging-only.
- **H1 (alternative):** RDDB-JIT produces a sustained gain,
  by reducing per-hash JIT time, rounding-mode handling cost,
  and dataset-stall time, without changing any hash bit and
  without increasing the rejected-share rate.

No direction of magnitude is hypothesised. In particular, this
paper does **not** predict 10%, 20%, or any other percentage.
The experiment in §6 is designed to falsify H1, not to confirm
a chosen number.

### 2.2 What would count as a win

A win requires **all** of the following, simultaneously:

1. Hash-sum equality with the reference implementation for
   the same seed (see §6.3).
2. Multi-thread live run: accepted shares rise, rejected
   share rate does not rise above the baseline rate within
   measurement noise (see §6.4).
3. Sustained 60-second hashrate over 30 minutes exceeds the
   baseline sustained hashrate by more than run-to-run noise,
   defined in §6.5 before any run is scored.
4. No regression in thermal behaviour: pause count does not
   rise, and battery percentage does not fall while charging
   on a rung that was stable at baseline.

If any one fails, H1 is rejected for that variant, however
fast a local benchmark looks.

---

## 3. Related work

Related work is cited to show what is already known and where
this invention differs. No related-work percentage is transferred
to Dimensity 7050 RandomX.

### 3.1 Upstream RandomX and XMRig ARM64 JIT

- tevador RandomX: configuration, architecture, and performance
  documentation. JIT is 2–3× vs interpreted in upstream docs —
  that is JIT-vs-interpreter, **not** a gain over an already-JIT
  build, and not a Dimensity measurement.
- XMRig changelog, ARM64 lineage: v6.21.0 ARM64 JIT — do not
  use the `x18` register (platform-reserved), update
  `sse2neon.h`; v6.25.0 minor Aarch64 JIT changes — better
  instruction selection, do not emit instructions that add 0
  (#3708); v6.26.0 RandomX ARM64 fixes (#3778) and RandomX v2
  support. These are the baseline emitter this paper builds
  on, not a competing invention.
- Hardware AES: 30–50% vs software AES in RandomX docs (x86
  AES-NI framing). On ARM64 the equivalent is Crypto Extensions
  + NEON, auto-detected. Forcing hardware AES on a core that
  does not report it crashes the miner; A78 and A55 must be
  checked separately via `/proc/cpuinfo` Features.

**Difference from this paper:** upstream improves the emitter
itself. RDDB-JIT does not rewrite the emitter's instruction
selection. It changes *when* code is emitted (dual-buffer),
*what* is re-emitted (helpers cached), and *how* rounding
state is tracked (static analysis + per-thread domains).

### 3.2 Heterogeneous scheduling and thermal management

- HARP (Smejkal et al., ACM Middleware '25, peer-reviewed):
  energy-aware adaptive management of heterogeneous processors,
  −12% execution time, −28% energy on two representative
  heterogeneous systems (not phones, not mining).
- FBHS (Liang et al., IEEE Transactions on Sustainable
  Computing, Sept–Oct 2026, peer-reviewed): federated bundled
  hybrid scheduling on big.LITTLE, large energy and time
  reductions on real-time DAG tasks (not mining).
- Phoenix (Jeon et al., ACM Transactions on Embedded Computing
  Systems, vol. 25, no. 2, 2026, peer-reviewed): thermal-aware
  DNN placement, postpones throttling. Its multi-exit idea
  does **not** transfer: a RandomX hash cannot be
  early-exited; every hash must run its full program or the
  share is invalid.
- iDEAS (Samadi et al., IEEE Access, 2025, peer-reviewed):
  deep-reinforcement-learning DVFS / scheduling / offloading
  on big.LITTLE, large energy reductions on task sets
  (not a continuous memory-hard hash loop).

**Difference:** those papers justify *placement and
adaptation* (big-core ladder, adaptive step-down), which this
paper adopts as controls in §6, but they do not address JIT
code generation, rounding domains, or helper reuse.

### 3.3 JIT engineering in general

- General JIT literature (LLVM, Cranelift, V8 TurboFan):
  compile-time vs code-quality trade-offs. For RandomX,
  compile time is paid per hash, because the next hash is a
  different program. Tiered JIT (interpret → baseline → 
  optimising for hot code) is therefore rejected for the main
  loop: there is no hot program to optimise on a second tier,
  and profiling cost would be paid on every hash for no reuse.
- Wasm RandomX (randomx.js, RandomX-bonanza): full mode in a
  browser, ~10% of native on AMD64 and ~23–25% on Apple M4
  ARM64 in its own figures, with no native AES and no directed
  rounding. Its rounding-mode bug (§1.3) is the negative
  example this paper's §4.2 is designed against.
- RISC-V JIT in RandomX (6.25–6.26): vectorised dataset init,
  vector AES, scratchpad prefetch fix. Useful as a template
  for how a new JIT backend is structured (register map +
  AES path + prefetch), not as code for ARM64. NEON on A78 is
  fixed 128-bit; this SoC has no SVE/SVE2.

**Difference:** RDDB-JIT keeps tevador's A64 JIT as the
emitter and adds a scheduling and state-management layer
around it, rather than replacing it with a general-purpose
JIT framework.

### 3.4 RandomX v2 — explicitly not this invention

RandomX v2 (XMRig 6.26) is heavier per hash: program
256 → 384 instructions, 4,456,448 → 6,815,744 ops (+52.9%
in SChernykh's desktop gist), dataset prefetch 2 iterations
ahead instead of 1, with desktop work-per-Joule gains on Zen 5.
It is **not** mainnet Monero (`rx/0`), and mining Monero as
`rx/2` today would not be Monero mining. This paper borrows
only one mechanism from v2 — deeper prefetch scheduling —
and applies it inside v1, without changing the hash.

---

## 4. Method — RDDB-JIT

RDDB-JIT has five components. Each is described with its
invariant, its implementation sketch, and its failure mode.
Ablation in §6 tests each component alone and in combination;
no component is credited with a combined gain.

### 4.1 Static rounding-mode segment coalescing

**Observation.** A RandomX program is fully known before its
2,048 iterations run. The rounding mode at any program point
is therefore computable at JIT time, not only at run time,
provided control flow is analysed correctly.

**Method.**

1. At JIT time, build the program's control-flow graph.
   RandomX VM programs are short, bounded sequences with
   limited, statically known jump targets. If a jump target
   cannot be resolved statically, the analysis falls back to
   the conservative mode for that block (see step 4).
2. Run a forward data-flow analysis over the lattice:
   `{RN, RZ, RP, RM, UNKNOWN}`, ordered with `UNKNOWN` as top
   (least precise). The transfer function for a `CFROUND`
   instruction sets the mode to its operand; every other
   instruction propagates the incoming mode unchanged.
   Join at a merge point is: same mode → that mode;
   different modes → `UNKNOWN`.
3. Partition the program into **rounding segments**: maximal
   straight-line regions in which the analysed mode is a
   single known mode (not `UNKNOWN`).
4. Emit a mode change **only** at a segment boundary where
   the mode actually changes, and only once per segment.
   Inside a segment, emit no mode-change code at all.
   For a block whose incoming mode is `UNKNOWN`, emit the
   conservative sequence: set the mode explicitly before
   every FP operation in that block, exactly as the baseline
   does. This guarantees that analysis imprecision can only
   cost speed, never correctness.
5. Record, per thread, the segment table for the compiled
   program, so that a later verification pass can replay
   mode transitions without re-running the program.

**Invariant.** At every FP operation, the hardware rounding
mode equals the mode the reference interpreter would have
at that point. Proof sketch: by induction over the data-flow
fixpoint, the analysed mode at a point is either the exact
reference mode or `UNKNOWN`; in the `UNKNOWN` case the
conservative emission sets the mode explicitly, so the
invariant holds in both cases. A full proof requires the
control-flow graph to be complete; if the JIT encounters a
construct outside its analysed subset, it must fall back to
the baseline emitter for that program and log the fallback.
That fallback is a correctness requirement, not an
optimisation choice.

**Failure mode if wrong.** A missed mode change produces
hashes that differ in low FP bits. Those hashes may still
pass a local difficulty check and be rejected by the pool —
exactly the §1.3 failure. That is why §6 requires hash-sum
equality *and* multi-thread pool acceptance, not either
alone.

### 4.2 Per-thread rounding-domain isolation

**Method.**

1. Each mining thread owns a **rounding-domain object** in
   thread-local storage: current mode, shadow mode (the mode
   the next segment expects), a segment-transition counter,
   and a running checksum of mode transitions for the current
   program.
2. No rounding-domain object is ever written by another
   thread, and no rounding-domain object is ever shared
   between the two dual buffers in §4.3. Buffer A and buffer B
   each have their own domain object, even on the same thread.
3. The JIT emits mode changes through a domain-checked stub:
   the stub compares the requested mode with the domain's
   shadow mode; if they match, it emits nothing (the
   coalescing in §4.1 already handled it); if they differ,
   it emits the hardware mode change and updates the shadow.
   The stub is per-thread code, not a global variable.
4. At program end, the domain's transition checksum is
   compared with the segment table from §4.1. A mismatch
   aborts that hash, discards its result, and logs a
   rounding-domain fault. It must never be submitted as a
   share.

**Why this is not just "use a thread-local variable".**
The invention is the combination: static segment tables
(§4.1) + per-thread domains + a checksum that makes a
mode error *detectable before submission*, rather than
discoverable only as a pool rejection. A thread-local
variable alone, without the segment table and checksum,
would still allow a single-threaded mode bug to pass
unnoticed until the pool rejected it.

**W^X note.** Rounding-domain objects are data, not code.
They live in writable thread-local storage. JIT code pages
remain W^X: a page is never writable and executable at the
same time, per `RANDOMX_FLAG_SECURE` semantics where the
fork enables it. Dual buffers (§4.3) are swapped only
through the W^X transition already used by the baseline.

### 4.3 Dual-buffer pipelining

**Method.**

1. Each thread owns two code buffers, A and B, and two
   scratchpad windows, A and B, each with its own rounding
   domain (§4.2).
2. Pipeline, per thread:
   - Execute hash *N* from buffer A, window A.
   - Concurrently (on the same thread, interleaved at
     iteration boundaries, not by a second OS thread),
     JIT-compile the program for hash *N+1* into buffer B.
   - At the iteration boundary where hash *N* finishes,
     swap buffers: execute *N+1* from buffer B, compile
     *N+2* into buffer A, and so on.
3. Compilation is interleaved at iteration boundaries, not
   inside a single hash's 2,048-iteration loop, so that
   compilation never interrupts a hash mid-program and
   never shares rounding state with the executing hash.
4. If compilation of *N+1* is not finished when *N* ends,
   the thread waits — it does not execute an uncompiled or
   partially compiled program, and it does not reuse buffer
   A's code for a different hash.

**Why interleaving, not a second thread.** A second OS
thread for compilation would contend for the same cache,
bus, and thermal budget as the mining thread, and would
need synchronisation around the code cache (§4.4). On a
2-big-core phone, that contention is likely to cost more
than the JIT latency it hides. Interleaving on the same
thread, at iteration boundaries, hides JIT latency behind
the *next* hash's dataset-access stalls, which are already
idle time for the CPU.

**Originality note.** Pipelining itself is old. The
original combination here is pipelining *with* per-buffer
rounding domains and *with* helper reuse (§4.4), so that
the pipelined compile does not re-emit helpers and does
not share rounding state with the executing buffer.

### 4.4 Helper code-cache reuse

**Method.**

1. Identify helpers that are deterministic across hashes:
   prologue (register save, stack setup), epilogue
   (register restore, fingerprint call setup), AES helper
   stubs, dataset-read helper stubs, and rounding-segment
   stubs from §4.1.
2. Emit each helper once, at miner start, into a separate
   helper code cache. Helpers are called via a fixed
   calling convention, with all caller-saved registers
   listed explicitly in the helper contract.
3. Per-hash JIT code contains only the random program body
   and calls into the helper cache. It does not re-emit
   helper bodies.
4. Helper cache invalidation: the cache is invalidated
   only on process restart, on a change of CPU features
   (e.g. hardware AES detection changes), or on a change
   of the pinned XMRig version. It is never invalidated
   per hash, per program, or per pool job.
5. I-cache handling: after helper emission, flush dcache
   to icache once, using the platform's cache-flush
   primitive, before any hash executes. Per-hash code
   still requires its own flush, but helper flush cost is
   paid once, not per hash.

**Invariant.** A helper is pure with respect to the program
body except for explicitly listed state: input registers,
output registers, scratchpad window pointer, dataset
pointer, and rounding domain (read-only, except for the
rounding stub's shadow update, which is per-domain).
Any helper that violates this contract must be inlined
per hash, not cached.

**Failure mode.** A helper that implicitly depends on a
per-hash value (e.g. a program-specific constant folded
into a helper) would produce wrong hashes for every hash
after the first. The contract check in §6 (hash-sum for
many distinct seeds, not one seed) is designed to catch
exactly this: a first-hash-only bug passes a single-seed
test and fails a multi-seed test.

### 4.5 Prefetch scheduling coupled to the iteration loop

**Method.**

1. Dataset access in the main loop is sequential in
   iteration order: iteration *i* uses dataset item *i*
   (in the loop's logical order), regardless of the random
   program executed in that iteration. Scratchpad access,
   by contrast, is data-dependent and unpredictable.
2. The JIT therefore emits, while executing iteration *i*,
   a prefetch for the dataset cache line(s) needed by
   iteration *i+2* (distance 2, borrowed as a mechanism
   from RandomX v2's 2-ahead prefetch, applied inside v1
   without changing the hash). Distance is a tunable
   parameter, tested at 1, 2, and 3 in §6, not assumed.
3. No speculative prefetch is emitted for scratchpad
   addresses. Emitting one would be a guess that pollutes
   the small shared L3 and evicts the scratchpad itself.
4. Prefetch instructions are hints only. They cannot change
   a value, a register, or a rounding mode. A wrong prefetch
   distance can only cost speed (cache pollution, bus
   traffic), never correctness. That is why prefetch is
   the safest component to A/B test first.

**Why distance 2 is a hypothesis, not a claim.** On a
desktop Zen 5, v2's 2-ahead prefetch filled a DRAM-latency
gap. On a phone, A78 is slower and LPDDR5 latency is
similar, so the gap to fill is smaller. Distance 2 may be
too far (evicted before use) or too near (arrives late).
Only the §6 ladder, with distance as a variable, can
decide.

### 4.6 Register pinning — the fixed map

To make §4.1–§4.5 auditable, RDDB-JIT uses a fixed
register map for the inner loop, published here so that a
reviewer can check spills without reading the emitter:

| Role | Register (A64) | Notes |
|---|---|---|
| Scratchpad window pointer | x19 (callee-saved) | Pinned across the whole program |
| Dataset pointer | x20 (callee-saved) | Pinned across the whole program |
| Iteration counter | x21 (callee-saved) | Pinned across the 2,048-iteration loop |
| VM integer registers 0–7 | x22–x29, w-views where applicable | No spills in the inner loop; a spill is a bug, logged |
| VM FP registers | v8–v15 (callee-saved FP) | Pinned; rounding-domain object pointer in x18 is **not** used, because x18 is platform-reserved on some ARM64 ABIs (upstream v6.21.0 fix) |
| Rounding-domain pointer | x23 (callee-saved) | Per-thread, per-buffer domain (§4.2) |
| Temporaries | x9–x15, v0–v7 | Caller-saved, free for helpers, never live across a helper call unless saved by the caller |

If the emitter cannot honour this map for a given program
(e.g. a program needs more live values than the map
provides), it must spill explicitly, log the spill, and
the experiment must record spill count as a metric.
A hidden spill is a correctness and performance bug.

---

## 5. Theoretical justification

### 5.1 Why JIT time matters at all

Per hash, total time ≈ JIT time + execution time.
Execution time is dominated by 2,048 iterations of a random
program with data-dependent scratchpad accesses and
dataset accesses. JIT time is paid once per hash, before
execution. If JIT time is a non-negligible fraction of
total time — which must be measured, not assumed — then
hiding it behind execution (§4.3) and reducing re-emission
(§4.4) reduces total time per hash without changing
execution semantics. If JIT time is negligible, RDDB-JIT
will show no gain, and H1 will be falsified. That is a
valid outcome of the experiment, not a failure of the
paper.

### 5.2 Why rounding-mode coalescing is sound

Rounding-mode analysis is a monotone data-flow over a
finite lattice of height 5 (4 modes + UNKNOWN). The
transfer function is monotone (a CFROUND sets the mode;
other instructions preserve it). The fixpoint therefore
exists and is reached in bounded steps. At any point, the
analysed mode is either exact or UNKNOWN; in the UNKNOWN
case the conservative emission restores exactness. This
is the standard soundness argument for a forward
must-analysis, applied to a four-valued mode domain
instead of a constant-propagation domain.

The only assumption is control-flow completeness: every
jump target in the program must be in the graph. If it
is not, the analysis is unsound for that program, which
is why §4.1 requires a fallback to the baseline emitter
for any program outside the analysed subset, with logging.
Soundness is therefore conditional on the fallback, and
the experiment must count fallback rate as a metric.
A high fallback rate falsifies the practical value of
§4.1, even if its theory is sound.

### 5.3 Why dual-buffer pipelining does not change semantics

Dual-buffer pipelining changes *when* code is generated,
not *what* code is generated, and not *what* state it
reads or writes, provided:

- Buffers do not share rounding domains (§4.2).
- Buffers do not share scratchpad windows (§4.3).
- Helper code is pure except for listed state (§4.4).
- Compilation is interleaved only at iteration boundaries,
  never inside a hash (§4.3).

Under those conditions, the sequence of observable
operations for hash *N* is identical whether its code
was compiled just before execution or one hash earlier.
This is the same argument as for any ahead-of-time
compilation of a deterministic program: compilation time
is not part of program semantics.

### 5.4 Why prefetch cannot break correctness

A prefetch instruction, on ARM64, is a hint to bring a
cache line into cache. It does not write memory, does
not change a register, and does not change a rounding
mode. A prefetch for the wrong address, or at the wrong
distance, can evict useful data and slow execution, but
cannot change a hash bit. Prefetch is therefore the
lowest-risk component, and the correct first A/B test.

### 5.5 Complexity

- Rounding analysis: O(blocks × modes) per program, with
  modes = 5. For a 256-instruction program, this is
  negligible compared with JIT emission itself, but it
  must be measured as JIT time per hash in §6, not assumed.
- Dual-buffer memory: 2 code buffers + 2 scratchpad
  windows per thread, vs 1 + 1 at baseline. On a phone
  with a 2,080 MiB dataset already resident, the extra
  scratchpad window (2 MiB per thread) is small, but the
  extra code buffer size must be measured and bounded.
  If code-buffer memory grows without bound, the design
  is rejected.
- Helper cache: constant size, emitted once. Its size
  must be reported in §6.

---

## 6. Experiment design

### 6.1 Hardware and baseline — fixed before any run

- **Phone:** NARZO 70 Pro 5G, Dimensity 7050, Android,
  ARM64 only. Record Android version, kernel version,
  and RAM total before the first run.
- **Baseline:** pinned MoneroOcean XMRig v6.25.0-mo1,
  ARM64, NDK 28.2.13676358, platform 28, TLS on,
  OpenSSL 3.4, donate-level 0, Full + JIT, hardware AES
  on (verified in log, not assumed), huge-pages false.
- **Conditions for every run:** same charger and cable,
  same network (one network for the whole experiment,
  no Wi-Fi/Jio switch mid-run), same room temperature
  range, case off, phone on a ventilated surface, screen
  off except when taking a reading, other heavy apps
  closed, charging-only ON, CPU pause 65 °C / resume
  55 °C, battery pause 40 °C / resume 37 °C.
  No rooting, no forced huge pages, no disabling pauses.
- **Pool:** MoneroOcean, `gulf.moneroocean.stream:20032`,
  TLS on, direct to the test wallet's public address only.
  No seed, private key, or password is ever entered.

### 6.2 Variants — ablation, one variable at a time

| Variant | Change vs baseline |
|---|---|
| B0 | Baseline, no RDDB-JIT |
| V1 | §4.1 rounding coalescing only |
| V2 | §4.2 rounding-domain isolation only (with baseline emission, plus checksum) |
| V3 | §4.3 dual-buffer pipelining only |
| V4 | §4.4 helper code-cache only |
| V5 | §4.5 prefetch distance 1, 2, 3 (three sub-variants) |
| V6 | Full RDDB-JIT (V1+V2+V3+V4+V5 at the winning prefetch distance) |

Each variant is built from the same pinned source, differing
only in the RDDB-JIT code path. A variant that changes
donate-level, TLS, pool, or thread count is invalid and
must be discarded, however fast it looks.

### 6.3 Validity proof 1 — hash-sum verification

**This is a gate, not a metric. A variant that fails it
is rejected, regardless of hashrate.**

1. For a fixed set of at least 20 distinct seeds, covering
   varied inputs (including seeds that exercise all four
   rounding modes and seeds that exercise merge points
   where the analysis returns UNKNOWN), compute the
   RandomX hash with the reference implementation
   (upstream XMRig benchmark / `randomx-benchmark` hash
   sum, or the pinned fork's own `--verify` path, with
   the reference path documented before the run).
2. Compute the same hashes with each variant, single-threaded.
3. Require **bit-for-bit equality** for every seed.
   A single differing bit fails the variant.
4. Repeat with multi-threaded execution (2, 4, and 8
   threads, big-core affinity as in the existing test
   protocol), to catch shared-state bugs that a
   single-threaded test cannot catch. Again, require
   bit-for-bit equality for every seed.
5. Record, per variant: fallback rate (§4.1), spill count
   (§4.6), helper-cache size (§4.4), code-buffer memory
   (§4.3), JIT time per hash (§5.1).

A variant that passes single-threaded hash-sum but fails
multi-threaded hash-sum is the §1.3 failure mode, and must
be reported as such, not averaged away.

### 6.4 Validity proof 2 — multi-thread pool-accepted shares

**This is also a gate.**

1. For each variant that passes §6.3, run a live
   MoneroOcean session, charging-only, at the thread
   ladder from the existing test protocol (2 big-only,
   2+1, 2+2, 2+4, all 8), 30 minutes per rung, with
   cool-down between rungs.
2. Record, per rung: 60-second hashrate at 5, 15, and
   30 minutes, 30-minute sustained average, accepted
   shares, rejected shares, reject %, CPU temp max,
   battery temp max, battery % change while charging,
   pause count, pool algo in the log (expected `rx/0`),
   and MoneroOcean dashboard worker presence, hashrate
   > 0, accepted shares increasing, pending XMR increasing.
3. Require: accepted shares rise in every rung, and
   reject % does not exceed the baseline reject % by more
   than the pre-registered noise band (§6.5). A variant
   whose shares are rejected at a higher rate, however
   high its local hashrate, is rejected.
4. App hashrate alone is never proof. Dashboard
   verification is required.

### 6.5 Metrics, controls, and statistics — pre-registered

**Primary metric:** 30-minute sustained 60-second
hashrate, averaged over at least 3 independent sessions
per variant per rung, on different days, with the same
charger, network, and room conditions. Sessions that
violate §6.1 conditions (network switch, unplug, heavy
app use, covered phone) are discarded and the reason
recorded; they are not averaged in.

**Secondary metrics:** JIT time per hash (µs, mean and
p95), helper-cache size, code-buffer memory, fallback
rate, spill count, accepted/rejected shares, reject %,
CPU and battery temperature maxima, battery % change
while charging, pause count, time-to-first-share
(dataset build + first accepted share).

**Controls:** baseline B0 run before and after each
variant block (to detect drift in room temperature,
network, or phone state); same thread ladder for every
variant; same pool and wallet address for every variant;
dataset built once per key and reused across pause
(except where a variant explicitly tests rebuild).

**Noise band:** before scoring any variant, run B0
three times on the same rung and compute the standard
deviation of sustained hashrate. The noise band is
±2 standard deviations. A variant gain inside the noise
band is a **tie**, and the simpler build (baseline, or
the variant with fewer components) is kept. Gains are
never added across variants: V6 must be measured as a
whole, not summed from V1–V5.

**Statistics:** report mean, standard deviation, and
session count for every primary-metric figure. Do not
report a peak, a first-minute figure, or a single-session
figure as a result.

### 6.6 Falsification criteria — H1 is rejected if any holds

1. Any variant fails hash-sum equality (§6.3) for any
   seed, single- or multi-threaded.
2. Any variant's reject % exceeds baseline reject % by
   more than the noise band in §6.4.
3. Full RDDB-JIT (V6) sustained hashrate does not exceed
   B0 sustained hashrate by more than the noise band,
   averaged over 3 sessions per rung.
4. Fallback rate for §4.1 exceeds 50% of programs, making
   coalescing practically irrelevant, even if theory is
   sound.
5. Dual-buffer memory or helper-cache memory grows
   without bound, or causes low-memory kills on the phone.
6. Any variant increases pause count or causes battery %
   to fall while charging on a rung that was stable at
   baseline.

If H1 is rejected, the paper's conclusion must say so
plainly, and the components that failed must be named.
A rejected invention is still a valid paper outcome.

### 6.7 What this experiment does not prove

- It does not prove a $/month figure. A $/month figure
  needs live XMR price, network difficulty, and pending
  XMR over days, not a 30-minute hashrate, and must be
  labelled as an estimate at the time it is calculated,
  not as a promise.
- It does not prove the invention transfers to another
  SoC, another Android version, or a desktop CPU. Transfer
  requires a separate experiment on that hardware.
- It does not prove RandomX v2, a new coin, or a new
  pool is better. Those are separate hypotheses, tested
  separately.

---

## 7. Limitations

1. **Control-flow completeness.** §4.1 is sound only if
   the JIT's control-flow graph is complete for the
   programs it analyses. RandomX programs are short and
   bounded, but this paper does not provide a formal
   proof that every program in the RandomX program space
   falls inside the analysed subset. The fallback in
   §4.1 is therefore load-bearing, and fallback rate is
   a primary metric, not a footnote.
2. **No Dimensity measurement yet.** Every performance
   direction in this paper is a hypothesis. The phone's
   exact L3 size, hardware AES presence per core, and
   JIT time share are all unmeasured at the time of
   writing and must be measured in §6 before any gain
   is claimed.
3. **Thermal interaction.** Dual-buffer pipelining keeps
   the CPU busier (compilation hidden behind execution).
   On a phone, busier can mean hotter, which can mean
   more throttling, which can erase a JIT-time gain in
   sustained terms. That is why §6 uses sustained, not
   burst, hashrate, and why thermal metrics are gates,
   not afterthoughts.
4. **Helper-cache security.** A shared helper code cache
   is a new attack surface if its W^X transitions are
   wrong. This paper requires W^X discipline (§4.2),
   but does not provide a formal security proof for the
   cache implementation. A security audit of the cache
   transition code is required before shipping, separate
   from the performance experiment.
5. **Scope.** This invention addresses JIT, rounding,
   and prefetch costs only. It does not address cache
   capacity (a 2 MiB scratchpad cannot fit in a ~2 MiB
   shared L3 for many threads), dataset size (consensus
   fixed), or pool variance (a 500 H/s phone's share
   window problem). Those ceilings remain, whatever
   §6 finds.
6. **No new consensus.** RDDB-JIT mines the same
   `rx/0` RandomX, on the same pool, with the same
   payout rules. It does not create a new coin, a new
   algorithm, or a new pool. Any claim that it does
   would be false.

---

## 8. Conclusion

RDDB-JIT is a testable, falsifiable, low-level invention
for RandomX on ARM64, built from five combined ideas:
static rounding-mode coalescing, per-thread rounding-domain
isolation with a pre-submission checksum, dual-buffer
pipelining at iteration boundaries, helper code-cache
reuse, and iteration-coupled dataset prefetch.

Its value, if any, will be shown only by the experiment
in §6: hash-sum equality, multi-thread pool acceptance,
and sustained hashrate, with thermal gates. If that
experiment rejects it, the rejection itself is useful:
it will have shown, with evidence, which of JIT time,
rounding handling, and dataset stalls actually dominate
on a Dimensity 7050, and which do not.

That is the academic standard this invention is held to:
no gain is claimed before it is measured, and no
measurement counts unless the hashes are bit-for-bit
correct and the pool accepts them.

---

## Appendix A — Pseudocode (illustrative, not a patch)

```
// JIT time, per program, per thread
segments = analyse_rounding_modes(program)  // §4.1, lattice {RN,RZ,RP,RM,UNKNOWN}
domain   = thread_local_domain(buffer)      // §4.2, never shared across threads/buffers
for segment in segments:
    if segment.mode != domain.shadow:
        emit_mode_change(segment.mode)
        domain.shadow = segment.mode
        domain.checksum = update(domain.checksum, segment.mode)
    emit_program_body(segment, helpers=helper_cache)  // §4.4, body only
    // §4.5: while executing iteration i, prefetch dataset item i+2
    emit_prefetch(dataset_ptr, iteration + PREFETCH_DISTANCE)
assert domain.checksum == segments.checksum  // §4.2, abort hash on mismatch
```

## Appendix B — Test checklist for the builder

- [ ] Baseline B0 passes: launches, TLSv1.3, DONATE 0%,
  JIT on, hardware AES on, full-mem, accepted shares rise.
- [ ] Hash-sum gate (§6.3) passes for ≥20 seeds, single-
  and multi-threaded, for every variant before any live run.
- [ ] Pool-acceptance gate (§6.4) passes for every variant
  before any hashrate claim.
- [ ] Noise band (§6.5) computed from 3× B0 runs before
  scoring any variant.
- [ ] Fallback rate, spill count, helper-cache size,
  code-buffer memory, JIT time per hash recorded for
  every variant.
- [ ] Thermal gates (§6.6) checked for every rung.
- [ ] No seed, private key, or password handled at any
  layer; only a public address to the pool.
- [ ] If H1 is rejected, the rejection and the failing
  component are reported plainly.

---

*End of invention draft. No hashrate, percentage, or
$/month figure is claimed for Dimensity 7050 in this
document. All such figures, if they ever appear, must
come from §6 measurements on the actual phone, with
hash-sum and pool-acceptance proof.*
