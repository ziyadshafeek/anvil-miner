# Factory Workstream 2 — ALGORITHM LEVEL design report
## Phone mining only — NARZO 70 Pro 5G, Dimensity 7050, XMRig RandomX

**Status: design report only. No repo source files were edited in this workstream.**
**Scope: phone only, mining only. No laptop mining, no other earning, no DePIN.**
**No pre-claimed % improvement in this report.** Every gain below is marked
VERIFIED IN UPSTREAM / MEASURED ON PHONES / UNMEASURED ON THIS PHONE.
If it has not been measured on a Dimensity 7050, it is unknown until the
phone test ladder measures it.

Target, restated from the existing tree:
- 2× Cortex-A78 @ 2.6 GHz (big) + 6× Cortex-A55 @ 2.0 GHz (little), ARM64
- Anvil v0.2.0, MoneroOcean XMRig fork v6.25.0-mo1 in the tree
- Default pool `gulf.moneroocean.stream:20032`, TLS on, `donate-level: 0`
- Charging-only ON, CPU pause 65 °C, battery pause 40 °C — kept, see §8
- Existing config baseline: `~/workspace/anvil-miner/docs/DIMENSITY7050-CONFIG.md`

---

## 1. Verdict first

Algorithm-level work does **not** change the hardware ceiling, but it decides
whether the phone gets close to it or wastes a large fraction of it.

1. **Use Full + JIT, always.** That is the only mining mode that matters.
   Light mode and interpreted mode are verification/fallback modes, not
   mining modes. Shipping or falling back to either silently would be a
   large, invisible loss.
2. **The bottleneck on this phone is memory, not arithmetic.** RandomX is
   deliberately memory-hard: a 2,080 MiB dataset, a 2 MiB scratchpad per
   thread, and a small shared L3. Extra threads that do not fit in cache
   spill to LPDDR5 and steal bandwidth from the big cores. That is why
   thread count and algorithm tuning interact — they cannot be tuned
   separately.
3. **MoneroOcean will almost certainly assign plain `rx/0` (Monero) on this
   phone, as it did on both VoltMiner test phones.** Do not budget, quote,
   or display a light-algo hashrate as earnings. Algo switching is a
   free, automatic safety net for an altcoin spike, not a phone speedup.
4. **RandomX v2 (XMRig 6.26) is NOT a Monero speedup to adopt blindly.**
   It is a heavier variant (program 256 → 384 instructions, ~1.5× work per
   hash in the reference notes). It is not `rx/0`. Do not point a Monero
   job at `rx/2` and do not claim a v2 hashrate as a Monero gain.
   The v2 ideas worth borrowing are the *mechanisms* (deeper prefetch,
   AES in register mixing to fill memory-wait time), not the variant itself.
5. **The highest-value algorithm work for the builder is boring and
   verifiable:** keep JIT on, keep hardware AES on, build the dataset
   once and reuse it, never rebuild per job, keep VMs alive across pause
   (not stop), keep algo-perf cached across restarts, and test
   scratchpad-prefetch modes by measurement only.

## 2. RandomX in one page — what actually runs per hash

RandomX is a virtual machine (VM) executing randomly generated programs,
not a single fixed hash loop:

- **Per hash:** 8 VM programs are executed, each 256 instructions
  (v1 / `rx/0`), each program iterated 2,048 times in the VM loop in the
  reference parameters. The program, registers, and scratchpad state are
  seeded from the input; the final hash is derived from VM registers
  plus a fingerprint of the scratchpad.
- **VM instruction set:** a CISC-like set including integer arithmetic,
  floating-point (`CFROUND` and related), branches, and memory operations
  into the scratchpad and dataset. This is why a JIT matters: the program
  is *different every hash*, so it cannot be pre-compiled once for all
  hashes — it must be translated fast, every time, or interpreted slowly.
- **Memory hierarchy (fixed sizes, not tunable by the app):**
  - Cache: 256 MiB, initialised from the key with Argon2d (memory-hard).
  - Dataset: 2,080 MiB = 2,048 MiB base + 32 MiB extra, 64-byte items,
    derived from the cache with SuperscalarHash (8 cache accesses per
    dataset item in the reference parameters). Read-only during mining.
  - Scratchpad per thread: L1 16 KiB, L2 256 KiB, L3 2 MiB (nested:
    L1 ⊂ L2 ⊂ L3). XMRig's optimisation guide states the same rule
    as 256 KiB L2 + 2 MiB L3 per thread for full speed.
- **Modes (RandomX / XMRig documentation):**
  - Full + JIT — fastest, mining. Requires the full 2,080 MiB dataset.
  - Full + Interpreted — medium, mining fallback only.
  - Light + JIT — slow, verification (uses only the 256 MiB cache,
    generates dataset items on the fly).
  - Light + Interpreted — slowest, verification fallback only.

Design rule: **the app must log and display which mode is actually in
use.** A silent fallback to light or interpreted mode must be treated
as a bug, not a configuration choice, because the user would see
"mining" while earning at a fraction of the rate.

## 3. JIT / VM on ARM64 — the largest algorithm lever

### 3.1 What JIT does, and why it is 2–3× in upstream docs

Upstream RandomX documentation lists `RANDOMX_FLAG_JIT` as "use Just-In-Time
compilation" with a 2–3× speedup on supported platforms versus interpreted
execution. That figure is an upstream documentation figure for JIT vs
interpreter, **not** a measured Dimensity 7050 gain, and not a gain over
an already-JIT build. It matters because it sets the cost of getting
JIT wrong: if JIT is off, broken, or falls back on this phone, the loss
is on that order, not a few percent.

Mechanism, in builder terms:
- The JIT translates each randomly generated 256-instruction program
  into native AArch64 code at runtime, with architecture-specific
  register allocation (RandomX registers → hardware registers),
  instruction fusion where possible, efficient addressing modes for
  scratchpad/dataset access, and optimised branches.
- XMRig ships separate JIT compilers for x86-64, ARM64, and RISC-V.
  The ARM64 JIT is a real, maintained path — not a stub — with recent
  upstream work including: ARM64 JIT not using the `x18` register
  (v6.21.0, platform-reserved register fix), minor AArch64 JIT changes
  in v6.25.0 (better instruction selection, not emitting instructions
  that add 0), and ARM64 fixes in v6.26.0.
- The JIT must be verified on-device, not assumed from the build flag.
  A build that sets `asm: true` in config but ships a binary without a
  working ARM64 JIT, or that hits an Android executable-memory restriction
  and silently falls back, would look identical in the UI and mine slowly.

### 3.2 What to implement / verify (builder checklist)

1. **Keep `asm: true` in the config template.** Do not turn it off to
   "save battery" — that trades a large speed loss for a small power
   saving and is the wrong trade on a charging-only miner.
2. **Log JIT status at startup, verbatim from XMRig output.** Required
   log lines to check before any release claim: JIT enabled, hardware
   AES detected or not (see §5), dataset built in full mode, and the
   thread count actually used. If any of those is missing or shows a
   fallback, stop and fix it before benchmarking.
3. **Do not fork or rewrite the JIT.** The upstream ARM64 JIT is the
   asset. The builder's job is to build the pinned MoneroOcean fork
   correctly (v6.25.0-mo1 in the tree, or a newer -mo fork only after
   re-verifying donate 0% + TLS + pool behaviour), with the same JIT
   code, and to verify it on-device. A hand-written "faster JIT" with
   no differential test against upstream is a correctness risk: a wrong
   JIT produces invalid shares, which pay nothing no matter how fast
   they appear.
4. **Differential test before release:** run the same `--bench` workload
   (or the app's equivalent) and confirm accepted shares rise on
   MoneroOcean, not just that H/s rises in-app. H/s without accepted
   shares is not mining.
5. **Pause, not stop, for thermal/network pauses.** Stopping the miner
   process destroys the JIT-compiled programs, VMs, and dataset state
   and forces a rebuild on resume. Anvil's existing pause (stdin `p`,
   process alive) is algorithmically correct for this reason; keep it.
   A stop should only happen on explicit user Stop (long-press /
   notification), as in the current design.

### 3.3 What NOT to do at JIT level

- Do not disable JIT, do not use light mode, do not use interpreted mode
  for mining.
- Do not hand-edit generated code, do not spoof a benchmark, do not
  edit `algo-perf` to fake a faster algorithm (see §6).
- Do not claim the upstream 2–3× JIT-vs-interpreter figure as a gain
  over the current Anvil build — the current build already uses JIT
  when it works. The gain from this workstream is *preserving and
  verifying* JIT, not inventing it.

## 4. Scratchpad and L2/L3 cache — why this phone is cache-bound

### 4.1 The numbers

- Scratchpad per thread: 2 MiB total (L1 16 KiB, L2 256 KiB, L3 2 MiB).
- XMRig requirement for full speed: 256 KiB L2 + 2 MiB L3 per thread.
- Dimensity 7050 shared L3: a third-party comparison table lists
  1 MB L2 / 2 MB L3 for this SoC. **Treat as indicative, not a MediaTek
  datasheet quote.** It has not been verified from a MediaTek datasheet
  in this workstream. The builder must verify the real topology on the
  phone (sysfs cache sizes, or at minimum the sustained ladder in §7)
  before quoting it as fact.

Implication, even if the indicative L3 figure is only approximately
right: **one thread's scratchpad already equals the whole shared L3.**
Two threads cannot both keep their full scratchpads in L3. Every
additional thread therefore increases the fraction of scratchpad
accesses that miss to LPDDR5 RAM, which is far slower than cache.
This is the physical mechanism behind the G99 ladder in the existing
config doc (2 threads 328 H/s, 4 threads 385 H/s, 8 threads 424 H/s —
diminishing returns, and on Snapdragon 865, 8 threads actually slower
than 4 big-only threads).

### 4.2 Scratchpad prefetch — a real, testable knob

XMRig has a `scratchpad_prefetch_mode` option (added in v6.3.4, with
later work including a BMI2 version for x86 prefetch and an optimised
ARMv8 dataset read). Prefetch is the attempt to load the next dataset /
scratchpad data into cache *before* the VM needs it, hiding DRAM latency.

- **Why it matters on a phone:** RandomX's dataset accesses are
  pseudo-random, so the prefetcher cannot predict them perfectly, but
  a correct prefetch distance can still hide part of the LPDDR5 latency.
  RandomX v2's design note makes the same point explicitly: v2 moves
  dataset prefetch 2 iterations ahead instead of 1, precisely to match
  how real CPUs and DRAM behave.
- **What to do:** expose prefetch mode as a *measured* test variable,
  not a fixed "best" value. Run the same thread ladder with each
  available prefetch mode (as exposed by the pinned XMRig fork) and
  keep only the mode that wins on **30-minute sustained 60s H/s**,
  not on a 60-second burst. If modes tie within measurement noise,
  keep the upstream default — do not ship a non-default mode on a tie.
- **What not to do:** do not claim a prefetch gain from desktop
  (x86 BMI2 / VAES) results. Those instructions do not exist on ARM64.
  The ARM64-relevant prefetch path is the ARMv8 dataset read and the
  fork's own prefetch code, measured on this phone.

### 4.3 Cache QoS, huge pages, and other desktop knobs — phone reality

- **Huge pages (`RANDOMX_FLAG_LARGE_PAGES`, 5–10% in upstream docs
  on desktop, plus 1–3% for 1 GiB pages on Linux): DO NOT APPLY ON THIS
  PHONE.** Anvil's template already sets `"huge-pages": false`, and
  the existing config doc states Android does not grant them. On
  Android the flag will simply fall back. Do not root to force it.
  The upstream % figures are desktop figures and must not be quoted
  as phone gains.
- **Cache QoS (XMRig 6.3.1+, experimental):** an x86 feature to stop
  non-mining cores accessing cache used by mining threads. It is not
  an ARM64 phone feature to rely on. Note it as "not applicable" in
  the builder's checklist, do not chase it.
- **MSR mod, 1 GiB pages, Zen VAES-512:** x86/desktop only. The
  VAES-512 work in XMRig 6.26 is for Zen 5, not for Cortex-A78/A55.
  Listing them here only to prevent a builder from importing a
  desktop tuning guide into an ARM64 phone build.

### 4.4 Design consequence for thread count (algorithm view)

The algorithm-level conclusion matches the thread-level conclusion
in the existing config doc, but for a different reason: it is not
"little cores are slow" in isolation, it is "each extra thread adds
a full 2 MiB scratchpad that must compete for a ~2 MiB shared L3
and for LPDDR5 bandwidth." Therefore:

- The ladder (2 big / 2+1 / 2+2 / 2+4 / all 8) must be run at the
  algorithm level too: same prefetch mode, same dataset, same pool
  algo, only thread count/affinity changing.
- The decision metric is sustained H/s *and* rejected-share % *and*
  temperature. A rung that raises burst H/s but raises rejected
  shares or forces repeated thermal pauses has lower *paid* H/s.

## 5. Hardware AES and dataset read on ARM64

- RandomX uses AES heavily in three places: scratchpad initialisation
  (AesGenerator1R, >20 GB/s on modern CPUs in upstream notes;
  AesGenerator4R for program-buffer initialisation), scratchpad
  fingerprinting (AesHash1R), and — in RandomX v2 — register mixing
  in the main loop (see §7).
- On ARM64 the relevant acceleration is **ARM Crypto Extensions
  (hardware AES + NEON)**, the ARM equivalent of x86 AES-NI. Upstream
  notes list "hardware AES (AES-NI on x86-64, Crypto Extensions on
  ARM64)" and "NEON and crypto extensions" for ARM64, with a fallback
  to software AES if the CPU lacks them.
- **Verification required on this phone, not assumed:** check
  `/proc/cpuinfo` Features for `aes` (and `sha2`, `crc32` as context)
  on the A78 and A55 cores separately. If hardware AES is present,
  the build must use it (`RANDOMX_FLAG_HARD_AES` path in XMRig);
  if it is absent on any core, that core's threads will run software
  AES and will be disproportionately slower — another reason to
  prefer big cores, to be confirmed by measurement, not asserted.
- **ARMv8 dataset read:** XMRig upstream optimised the ARMv8 dataset
  read path (PR #2398 in the changelog history). The builder should
  confirm the pinned MoneroOcean fork contains that path (it should,
  as a descendant of upstream), and must not replace it with a generic
  dataset reader.
- **Argon2 for cache initialisation:** upstream flags for SSSE3/AVX2
  Argon2 are x86-only. On ARM64, cache initialisation cost is a
  one-off per key, not a per-hash cost, so it affects startup time
  (first start, minutes) far more than sustained H/s. Do not optimise
  Argon2 at the expense of the main loop.

## 6. Dataset (2,080 MiB) handling and reuse — the startup trap

### 6.1 Lifecycle

1. **Cache build (once per key):** 256 MiB, Argon2d from the key.
2. **Dataset build (once per key):** 2,080 MiB derived from the cache
   via SuperscalarHash. This is the slow, memory-heavy step on first
   start. It is shared by all threads — it is **not** 2,080 MiB per
   thread. Total resident for mining is therefore ~2.3 GiB (dataset +
   cache), plus 2 MiB scratchpad per thread, plus the app itself.
3. **Mining:** VMs read the shared dataset read-only, each thread
   with its own VM and scratchpad. Dataset items are 64 bytes; a hash
   touches many pseudo-random dataset locations, which is the
   memory-hard property.
4. **Key change:** when the RandomX key (seed) changes — on Monero,
   every 2,048 blocks in the reference design — the cache and dataset
   must be rebuilt. That is a normal, infrequent cost, not a bug.

### 6.2 Design rules for the builder

1. **Build once, reuse across jobs.** The dataset must not be rebuilt
   per job, per share, or per reconnect. A reconnect must reuse the
   existing dataset and VMs.
2. **Build once, reuse across pause/resume.** Thermal and network
   pauses must not destroy the dataset. Only an explicit Stop, a
   process kill by Android, or a key change should force a rebuild.
   If Android kills the process (low memory, battery optimisation),
   the next start will pay the rebuild cost again — which is why
   battery optimisation must be OFF / Unrestricted for this app,
   and why other heavy apps must be closed during a test.
3. **Do not cache the dataset to disk as a shortcut.** VoltMiner's
   browser experiment caches a ~2 GB dataset in browser storage for
   reuse across visits, but that is a browser-experiment trade-off,
   not a phone-app design to copy without a security review. On the
   Android app, keep the dataset in RAM as XMRig does. A disk-cached
   dataset would be stale on key change and would add a large,
   unnecessary write to phone storage.
4. **Startup UX must explain the first-start cost honestly.** First
   start benchmarks algorithms (MoneroOcean calibration, a few
   minutes) *and* builds the dataset. The UI must say "first start
   takes a few minutes, later starts are faster" — it must not show
   a fake hashrate or "mining" state while the dataset is still
   building.
5. **Memory pressure check:** on a 6–8 GiB phone, ~2.3 GiB resident
   is fine if other apps are closed. If the phone is low on RAM,
   Android may kill the miner or thrash. The test protocol must
   record free RAM / other apps open at each rung, or the ladder
   results will not be comparable.

### 6.3 What dataset work does NOT buy

- A "faster dataset" does not raise sustained H/s after the first
  minutes, because the dataset is read-only during mining. Optimising
  dataset *build* only shortens startup.
- Light mode (256 MiB cache only, dataset items generated on the fly)
  is ~8× slower in upstream's fast-vs-light comparison table and is
  for verification, not mining. Never ship light mode as a "low-memory
  mining mode" — it would look like mining and pay almost nothing.

## 7. MoneroOcean algo switching and price/difficulty weighting

### 7.1 Mechanism (MoneroOcean XMRig fork)

1. On first start, the fork runs a **full algorithm calibration**:
   it benchmarks every algorithm the hardware can run and reports
   those numbers to the pool as `algo-perf`.
2. The pool weights each algorithm by **`price ÷ difficulty`** for
   its coin, picks the most profitable job, and sends that job to
   the miner.
3. If the winning coin is not Monero, the pool auto-converts its
   proceeds to XMR for payout to the miner's wallet. The miner's
   wallet address is the pool login; there is no separate account.
4. `algo-perf` is cached on disk across restarts in Anvil's design,
   so later starts skip the full calibration and resume in seconds.
   This cache must be preserved across pause/resume and across
   app updates, or every restart pays the calibration cost again.

### 7.2 Why plain rx/0 was assigned on phones — VoltMiner, measured

VoltMiner (June 22, 2026, MoneroOcean XMRig fork v6.25.0-mo1, ARM64,
same binary family as Anvil) ran full calibration on a Redmi Pad
(Helio G99) and an LG V60 (Snapdragon 865). Raw hashrates:

| Algorithm | Coin | H/s | vs RandomX |
|---|---|---|---|
| `rx/arq` | ArQmA | 3,031 | 9× |
| `cn-pico` | TurtleCoin | 1,802 | 5× |
| `panthera` | Scala | 689 | 2× |
| `rx/0` | Monero | 339 | 1× (baseline, Redmi Pad) |

After price/difficulty weighting, **MoneroOcean assigned `rx/0`
on both phones and held it stable**, with the log line:

```
ALGO PERFORMANCE CALIBRATION COMPLETE
new job from gulf.moneroocean.stream  algo rx/0
```

VoltMiner's own summary: "raw hashrate isn't profit. Those coins
are worth a tiny fraction of Monero per hash" and "the most
profitable thing a phone can mine on MoneroOcean is — perhaps
surprisingly — plain Monero (RandomX) itself."

**Implication for this phone:** expect `rx/0` on the Dimensity 7050
as the normal case. The Dimensity 7050 has stronger big cores than
the G99, so its absolute `rx/0` number should be higher than 339 H/s,
but the *assignment* logic is the same: a 9× raw hashrate on a coin
worth far less than 1/9th of Monero per hash still loses.

### 7.3 What algo switching is, and is not

- **Is:** a free, automatic hedge. If an altcoin spikes in
  price/difficulty terms, the pool can switch the phone to it
  temporarily and convert back to XMR. No app change is needed.
- **Is not:** a phone speedup, a way to "mine ArQmA at 3,000 H/s
  and get paid as if it were Monero," or a reason to force a light
  algo by hand. Forcing `rx/arq` by hand, against the pool's
  weighting, was worse $/day on the measured economics in the
  existing config doc, despite the bigger H/s number.
- **Is not:** a substitute for a RandomX-only pool on this phone.
  VoltMiner's corollary, verified on phones: "a standard RandomX-only
  pool mines exactly what MoneroOcean already mines here, so falling
  back to one costs essentially nothing in yield" in the normal case.
  MoneroOcean is still preferred as the default because the switch
  is free if a spike happens, and because its 0% direct-to-MoneroOcean
  fee and direct-wallet payout are already verified in the tree.

### 7.4 Builder rules for algo switching

1. **Keep benchmarking ON on first start.** Do not skip calibration
   to "start faster" — skipping it means the pool has no `algo-perf`
   and cannot assign correctly.
2. **Keep the `algo-perf` cache on disk.** Do not delete it on pause,
   update, or restart. Log when it is reused vs rebuilt.
3. **Never hand-edit `algo-perf`.** Editing it to fake a faster
   algorithm misdirects the pool, risks invalid or mispriced work,
   and is not a legitimate optimisation. Treat any such edit as a
   bug / spoofing, not a tuning knob.
4. **Display the pool-assigned algo, not the fastest algo.** The UI
   and any report must quote the log's `new job ... algo XYZ` line
   and the MoneroOcean dashboard pending XMR. A UI that shows
   "3,031 H/s" from a calibration table while the pool is paying
   `rx/0` work would mislead the user about earnings.
5. **Re-calibrate only on a real trigger:** new binary, new device,
   or a pool instruction — not on every start, and not on a timer
   that interrupts mining.

## 8. Recent upstream optimisations — what applies to this phone, what does not

| Upstream change | Phone relevance | Action |
|---|---|---|
| XMRig 6.25.0: minor AArch64 JIT changes (better instruction selection, no +0 instructions) | Direct — ARM64 JIT | Already in the fork family; verify on-device, do not regress |
| XMRig 6.26.0: ARM64 fixes (#3778) | Direct — ARM64 correctness | Note for a future -mo fork upgrade; do NOT upgrade the pinned fork mid-build without re-verifying donate 0% + TLS + pool behaviour |
| XMRig 6.26.0: RandomX v2 support (#3769–#3783) | **Not Monero `rx/0`** — see §7.4 / §1.4 | Do not mine Monero as `rx/2`. Borrow only the mechanism ideas (deeper prefetch, AES register mixing) as research, not the variant |
| RandomX v2 mechanism: program 256 → 384, ~1.5× work/hash, dataset prefetch 2 ahead instead of 1, 16 AES ops for F/E register mix, CFROUND tweaked | Mechanism research only | Prefetch distance is the testable idea on v1; AES-in-main-loop is a v2 design point, not a v1 phone gain to claim |
| RISC-V vectorised RandomX main loop, vector hardware AES, VAES-512 for Zen 5 (6.26) | **Not ARM64 phone** | Ignore for this build; do not import RISC-V / x86 code paths |
| XMRig 6.23: tweaked auto-config for AMD CPUs with <2 MB L3/thread; 6.22.3: optimised auto-config for <2 MB L3/thread | Analogy only — the *principle* (fewer threads when L3/thread is low) matches this phone, the AMD code does not | Apply the principle via the thread ladder, not the AMD code |
| ARMv8 dataset read optimisation (historic PR #2398), scratchpad prefetch mode (v6.3.4), BMI2 prefetch (x86) | ARMv8 read: direct. BMI2: x86 only | Keep ARMv8 read path; test prefetch modes on-device; ignore BMI2 |
| Hardware AES: x86 AES-NI, ARM Crypto Extensions, RISC-V vector AES | ARM Crypto Extensions: direct, if present on A78/A55 | Verify via `/proc/cpuinfo` Features, log it, use hard-AES path if present |

**No peer-reviewed paper was found in this workstream that proves a
new RandomX algorithm variant beats upstream XMRig on a Dimensity 7050.**
The sources above are upstream XMRig / RandomX documentation and
VoltMiner's measured phone benchmarks, not peer-reviewed papers.
That gap must be stated plainly to the parent: algorithm-level
"new research" for this phone is currently upstream engineering +
measured phone benchmarks, not a peer-reviewed breakthrough.

## 9. What to measure — algorithm-level test protocol

Run this **in addition to, not instead of**, the thread ladder in
`DIMENSITY7050-CONFIG.md`. Same conditions for every run: same
charger, same network (no Wi-Fi/Jio switch mid-run), other heavy
apps closed, charging-only ON, CPU 65 °C / battery 40 °C.

### 9.1 Per-run log checklist (must be recorded, not assumed)

- [ ] XMRig startup: JIT enabled? Hardware AES detected? Full mode
      (not light)? Dataset built (2,080 MiB) or reused?
- [ ] First-start only: algo calibration complete? `algo-perf`
      written / reused?
- [ ] Pool job line: `new job from gulf.moneroocean.stream  algo ___`
      — record the algo at 5, 15, and 30 minutes. If it changes,
      record when and why (pool switch vs reconnect).
- [ ] 60s hashrate (not 10s) at 5, 15, 30 minutes, and 30-minute
      sustained average.
- [ ] Accepted shares, rejected shares, reject % at 30 minutes.
- [ ] CPU temp max, battery temp max, battery % change while charging,
      pause count, throttled? Y/N.
- [ ] MoneroOcean dashboard, by wallet search: worker present?
      Hashrate >0? Accepted shares increasing? Pending XMR increasing?
      (Dashboard is the proof; app H/s alone is not.)

### 9.2 Variables to vary, one at a time

| Variable | Values to test | Keep if |
|---|---|---|
| Thread count / affinity | B0 (2 big), B1 (2+1), B2 (2+2), B3 (2+4), B4 (all 8) | Highest 30-min sustained paid H/s (accepted, not just raw) at ≤65 °C / ≤40 °C, no repeated pauses |
| Scratchpad prefetch mode | Each mode exposed by the pinned fork, default first | Only if it beats default by a clear margin on sustained H/s; otherwise keep default |
| Pool | MoneroOcean (default) vs RandomX-only fallback | MoneroOcean unless it is down; fallback yield is ~equal in the normal case, so do not chase a pool switch as a gain |
| Dataset / VM lifecycle | Pause-resume vs Stop-restart | Pause-resume must be faster (no rebuild); if Stop-restart is used, record the rebuild time as a cost |

### 9.3 Decision rules

- Keep the rung/mode with the highest **30-minute sustained,
  accepted-share-backed** H/s at safe temperatures. Burst H/s does
  not count.
- If a higher rung beats a lower rung by <10% sustained, keep the
  lower rung (less heat, less wear, same money).
- If rejected shares rise above ~2–3%, or pauses repeat, step down
  one rung regardless of raw H/s.
- If the pool-assigned algo is not `rx/0`, do not celebrate a
  higher H/s number — record the algo, record dashboard pending
  XMR over days, and only call it a gain if pending XMR rises
  faster than on `rx/0` over the same period.

## 10. Explicit exclusions — algorithm-level

- No fake hashrate, no spoofed `algo-perf`, no spoofed benchmarks.
- No forcing a light algo by hand to inflate the displayed H/s.
- No light mode, no interpreted mode for mining.
- No rooting, no forced huge pages, no disabling charging-only /
  CPU 65 °C / battery 40 °C pauses to chase H/s. Throttling already
  cuts sustained H/s if limits are removed, and battery damage on a
  primary phone is not worth cents per month.
- No seed / private key / password handling at any layer. Only the
  public receiving address goes to the pool, as in the existing
  wallet design. Algorithm code must never log, store, or transmit
  a seed.
- No RandomX v2 (`rx/2`) mining presented as Monero mining.
- No desktop tuning (MSR, 1 GiB pages, VAES, Cache QoS, BMI2)
  imported into the ARM64 phone build as if it applied.

## 11. Prioritised handover to the builder (in order)

1. **Verify JIT + hard AES + full mode on-device** from XMRig logs
   before any benchmark claim. Fix any silent fallback first.
2. **Preserve dataset + VM + `algo-perf` across pause/resume and
   restart.** Pause, not stop, for thermal/network pauses.
3. **Ship the thread ladder with true affinity** (big-core discovery,
   `affine_to_cpu` per thread) — algorithm tuning is meaningless if
   threads land on random cores.
4. **Test scratchpad prefetch modes** one at a time on the ladder;
   keep default on a tie.
5. **Keep MoneroOcean algo switching on, display the assigned algo,**
   and verify on the MoneroOcean dashboard, not in-app alone.
6. **Record the full §9 checklist per rung** and replace every
   "expected" number in this report and in
   `DIMENSITY7050-CONFIG.md` with measured numbers before any
   $/month figure is quoted to the user.
7. **Do not upgrade the pinned fork to 6.26 / RandomX v2 mid-build**
   without a separate verification pass (donate 0%, TLS, pool
   behaviour, accepted shares). The v2 mechanisms are research
   notes, not a drop-in Monero upgrade.

## Sources used

- XMRig RandomX optimisation guide (xmrig.com/docs/miner/randomx-optimization-guide):
  2,080 MiB dataset, 256 MiB cache, 256 KiB L2 + 2 MiB L3 per thread,
  light mode 256 MiB and very slow.
- tevador/RandomX documentation (DeepWiki summaries of the upstream
  repo): cache 256 MiB / Argon2d, dataset 2,080 MiB (2,048 + 32 MiB,
  64-byte items), scratchpad L1 16 KiB / L2 256 KiB / L3 2 MiB,
  256-instruction programs, 8 programs per hash, Full+JIT fastest,
  Light+JIT slow, JIT 2–3× vs interpreter in upstream docs,
  hardware AES (AES-NI / ARM Crypto Extensions), large-pages flags.
- XMRig CHANGELOG (upstream, v6.21–v6.26): ARM64 JIT `x18` fix,
  AArch64 JIT instruction selection (6.25), ARM64 fixes (6.26),
  RandomX v2 support (6.26), RISC-V vectorisation and VAES-512
  (x86/RISC-V only, not phone), ARMv8 dataset read and
  scratchpad prefetch mode (historic).
- VoltMiner, "How Much Monero Can a Phone Actually Mine?"
  (voltminer.net, June 22, 2026): MoneroOcean XMRig fork v6.25.0-mo1,
  Redmi Pad G99 / LG V60 SD865, raw `rx/arq` 3,031 H/s (9×),
  `cn-pico` 1,802 H/s (5×), `rx/0` 339 H/s (1×), pool assigned
  `rx/0` on both phones and held it, price÷difficulty weighting.
- Existing tree: `~/workspace/anvil-miner/docs/DIMENSITY7050-CONFIG.md`
  (thread ladder, thermal limits, MoneroOcean 0.003 XMR threshold
  context, G99/SD865 benchmarks), Anvil config template
  (`asm: true`, `huge-pages: false`, `donate-level: 0`).

## Caveats for the parent to relay plainly

- Dimensity 7050 L3 size (indicative 2 MB) is from a third-party
  comparison table, not a MediaTek datasheet — verify on the phone
  before quoting it as fact.
- Hardware AES presence on A78/A55 on this specific phone is
  unverified in this workstream — check `/proc/cpuinfo` Features.
- No Dimensity 7050 measurement was made in this workstream.
  All hashrate ranges remain extrapolations from G99/SD865
  benchmarks until the §9 ladder is run on the NARZO 70 Pro 5G.
- No % improvement is claimed in this report. The upstream JIT
  2–3× figure is JIT-vs-interpreter, not a gain over an already-JIT
  Anvil build, and desktop huge-page / VAES figures do not apply
  to this phone.
