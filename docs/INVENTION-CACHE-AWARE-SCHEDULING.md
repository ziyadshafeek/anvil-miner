# Complement-Paired Cache-Cohort Scheduling (CPCCS) for RandomX on a Dimensity 7050 Phone

## A new, cache-aware thread-scheduling method — invention paper, not a result

**Scope:** Phone mining only. Low level only. Target device: NARZO 70 Pro 5G,
MediaTek Dimensity 7050 — 2× Cortex-A78 at 2.6 GHz + 6× Cortex-A55 at
2.0 GHz, ARM64, Android, unrooted. Miner: XMRig RandomX, Anvil fork,
direct to MoneroOcean, pool `gulf.moneroocean.stream:20032`, TLS on,
donate-level 0.

**Correctness invariant, non-negotiable:** CPCCS changes *only* which
virtual machines (VMs) run, on which cores, and in which order. It does
not change the hash function, the RandomX program, the 2,080 MiB dataset,
the 256 MiB cache, the 2 MiB scratchpad per thread, the JIT-generated
code semantics, floating-point rounding, or the pool protocol. A hash
produced under CPCCS must be bit-identical to the same hash produced
under static scheduling. If any variant of CPCCS changes a hash bit, it
is rejected, however fast it looks.

**No result is claimed in this paper.** Every expected effect is stated
as a hypothesis to test. No percentage improvement and no $/month figure
is claimed for this phone. The only numbers that will count are
measured, sustained, pool-accepted numbers from the experiment in
Section 8.

---

## 1. Abstract

RandomX on a phone is memory-bound, not arithmetic-bound. Each thread
owns a 2 MiB scratchpad, while the phone's shared L3 cache is only a
few MiB in total. Running many threads at once therefore does not add
proportional work: the threads evict each other's scratchpads to slow
DRAM, raise heat, and trigger throttling, so sustained hashrate can
fall even as thread count rises.

Existing phone practice is static: pick a thread count (2, 4, or 8)
and leave it there, with or without core affinity. That practice
ignores two facts that change during a run: (a) a VM's *hot working
set* — the scratchpad blocks it actually touches in a short window —
is usually much smaller than its full 2 MiB scratchpad, and it drifts
as the random program changes; (b) two VMs whose hot sets overlap in
cache compete far more than two VMs whose hot sets are complementary.

CPCCS is a scheduler that measures each VM's hot working set by light
sampling, forms *cohorts* of VMs whose combined hot working set fits an
estimated L3 budget and whose hot-set overlap is low, runs one cohort
at a time on the big cores with true core affinity, parks the other
VMs, and rotates cohorts on a dwell-guarded interval. Parked VMs do
not mine on little cores; little cores are used only for cache-friendly
auxiliary work (next-job preparation and sequential dataset page
fault-in) in an ablation-tested assist mode, or left idle in the core
mode, so they do not add random-access contention to the shared bus.

The contribution is not a new hash, a new dataset size, or a new
pool. It is a cache-aware admission and pairing rule, with a
pre-registered experiment and explicit falsification criteria, that
can be tested on one phone without root, without changing consensus
parameters, and without disabling any thermal or charging protection.

## 2. Problem

### 2.1 The hardware constraint

- Dimensity 7050: 2 big Cortex-A78 cores + 6 little Cortex-A55 cores.
- RandomX per-thread scratchpad: L1 16 KiB, L2 256 KiB, L3 2 MiB.
  XMRig's own full-speed rule is 256 KiB L2 + 2 MiB L3 per thread.
- Shared L3 on this class of phone cluster is, by A78 architecture,
  at most a few MiB, and third-party tables suggest about 2 MiB for
  this 2+6 topology. **The exact Dimensity 7050 L3 size is not verified
  from a MediaTek datasheet and must be measured on the phone before
  it is quoted as fact.** CPCCS therefore treats L3 capacity as an
  *estimated budget to be calibrated*, not as a known constant.
- Dataset: 2,080 MiB, shared by all threads, accessed pseudo-randomly
  and therefore essentially uncacheable. Scratchpad access is the
  cacheable part, and it is the part that thrashes.

Arithmetic of the constraint, independent of the exact L3 figure:

- 1 thread needs up to 2 MiB of L3 working set.
- 2 threads need up to 4 MiB.
- 8 threads need up to 16 MiB.

Any phone in this class therefore cannot hold all threads' full
scratchpads in L3 at once. Static thread counts hide this by
averaging: a burst at minute one looks fast, then throttling and
eviction pull the 30-minute average down.

### 2.2 Why static scheduling fails

Static schedules make three errors:

1. **They count threads, not working sets.** Two threads with narrow
   hot sets can coexist in L3; two threads with wide hot sets cannot,
   yet a static count treats both pairs identically.
2. **They ignore drift.** A RandomX program is random per hash. A VM
   that was narrow in one hash can be wide in the next. A placement
   that was good at minute one can be bad at minute ten, and static
   scheduling never notices.
3. **They waste little cores on the wrong work.** Running independent
   VMs on little cores adds random-access scratchpad traffic on the
   same bus and the same L3, for about one-tenth the per-core speed
   of a big core on this class of chip (as seen in earlier G99
   benchmarks: 2 big cores ≈ 328 H/s, 6 little cores together added
   only ≈ 96 H/s). Little cores are better used, if at all, for
   sequential, cache-friendly auxiliary work — or not used at all.

### 2.3 What is already known, and what is not

- Earlier phone benchmarks (Helio G99, Snapdragon 865, VoltMiner,
  June 2026, blog, not peer-reviewed) bracket both outcomes: on G99,
  little cores *added* (2 threads 328 H/s, 8 threads 424 H/s); on
  Snapdragon 865, little cores *hurt* (4 big-only 748 H/s peak,
  8 threads 684 H/s, −9%). Dimensity 7050 could land either side.
  That contradiction is the reason a fixed answer ("always big-only"
  or "always all cores") is not credible without measurement.
- Peer-reviewed heterogeneous scheduling work (HARP, Middleware '25;
  FBHS, IEEE Transactions on Sustainable Computing, Sept–Oct 2026;
  Phoenix, ACM Transactions on Embedded Computing Systems, 2026)
  justifies adaptive placement and thermal-aware step-down in
  general, but none of it measures RandomX, hashrate, or this phone.
  Its percentages must not be transferred to mining.
- No prior work found in this invention's survey pairs RandomX VMs
  by measured hot-set overlap, or admits VMs to big cores by a
  working-set budget that is re-estimated during the run. That gap
  is CPCCS's claimed novelty. Novelty is a claim about the
  literature as searched, not a proof of speed.

## 3. Hypothesis

**H1 (primary):** Under identical charger, network, room, thermal,
and charging-only conditions, CPCCS will achieve a higher 30-minute
sustained 60-second hashrate, with no worse accepted-share rate and
no more thermal pauses, than the best static baseline on the same
phone (static big-only 2 threads, and static 4 threads, tested
separately).

**H2 (mechanism):** The gain, if any, will come through a higher
effective L3 hit proxy — measured indirectly as lower time per hash
iteration at stable temperature, and as fewer scratchpad-related
stalls — not through higher peak frequency or higher thread count.

**H3 (ablation):** Disabling footprint pairing while keeping cohort
rotation (ablation A) will remove most of any CPCCS gain; disabling
little-core assist while keeping pairing (ablation B) will remove
little or none of it. If ablation A does *not* remove the gain, the
gain was rotation or affinity, not pairing, and the pairing claim
must be withdrawn.

**Null hypothesis H0:** CPCCS sustained hashrate is equal to or
lower than the best static baseline, once run-to-run variance,
temperature, and measurement noise are accounted for. H0 is the
default. CPCCS is adopted only if H0 is rejected under the
pre-registered criteria in Section 8.5.

**Expected effect, stated as a hypothesis, not a result:** Direction
is predicted positive for sustained hashrate; magnitude is
*unknown*. It could be zero, small, or negative if sampling overhead,
migration cost, or footprint-estimation error dominates. Any
magnitude quoted before the experiment is speculation and must be
labelled as such.

## 4. Related work — and how CPCCS differs

| Work | What it does | Why CPCCS is different |
|---|---|---|
| Static affinity, XMRig `affine_to_cpu` | Pins threads to cores, fixed | CPCCS re-decides placement during the run, by measured working set, not once at start |
| HARP (2025, ACM) | Linux resource manager, app–manager co-adaptation, −12% time / −28% energy on its test systems | HARP cannot be installed by an unrooted Android app; CPCCS is an in-app analogue, and its numbers are not mining numbers |
| FBHS (2026, IEEE) | Federated + bundled scheduling for real-time DAG tasks on big.LITTLE | A miner has no deadlines or DAG; CPCCS borrows only "do not treat all threads alike", and adds footprint pairing, which FBHS does not have |
| Phoenix (2026, ACM) | Thermal-aware DNN placement, multi-exit networks to hold frame rate | A hash cannot be early-exited; CPCCS's thermal lever is pause / rotate / step down, not reduced-quality work |
| iDEAS (2025, IEEE Access) | Deep-reinforcement-learning DVFS + scheduling + offloading on big.LITTLE | An unrooted app cannot set DVFS governors; CPCCS controls only thread placement, cohort, and pause, which an app can control |
| XMRig prefetch modes, dataset prefetch (v1: 1 ahead; v2: 2 ahead) | Prefetch dataset items ahead of use | Prefetch hides latency for one VM; CPCCS reduces *contention between* VMs. The two are complementary, not substitutes. CPCCS tests prefetch as a controlled variable, not as its mechanism |
| Cache QoS / MPAM / DSU way partitioning | Hardware reservation of cache ways for a core group | Requires privileged firmware/kernel support not present on a stock phone; CPCCS is a software admission rule that approximates the same goal without hardware partitioning |
| RandomX v2 (XMRig 6.26) | Heavier program, deeper prefetch, work-per-Joule focus on desktop | v2 is not mainnet Monero mining and is heavier per hash; CPCCS works within v1 / rx/0 and must not be confused with a v2 claim |

## 5. Method: Complement-Paired Cache-Cohort Scheduling (CPCCS)

### 5.1 Definitions

- **VM:** one RandomX virtual machine, one thread, one 2 MiB scratchpad.
- **Block:** a 64 KiB slice of a scratchpad. A scratchpad therefore has
  32 blocks (2 MiB / 64 KiB). Block size is a tuning parameter, not a
  consensus parameter; 64 KiB is chosen to match a plausible L2-sized
  granularity, and must be sensitivity-tested (32 KiB and 128 KiB
  variants in a pilot, before the main experiment).
- **Hot set, Wᵢ(t):** the set of blocks accessed by VM *i* in the last
  sliding window (default: last 4,096 sampled accesses, see 5.2).
  Its size |Wᵢ(t)| is the VM's estimated working-set size.
- **Overlap, Oᵢⱼ(t):** Jaccard overlap of two VMs' hot sets,
  |Wᵢ ∩ Wⱼ| / |Wᵢ ∪ Wⱼ|, in [0, 1]. Low overlap is complementary;
  high overlap is contending.
- **Cohort:** a set of VMs scheduled concurrently on big cores.
- **L3 budget, B:** an estimated shared-L3 capacity for admission,
  calibrated on the phone (see 5.4). B is *not* assumed to be 2 MiB;
  it is a parameter fitted in a pilot and then frozen for the main
  experiment.
- **Parked VM:** a VM not currently in a cohort. Its scratchpad and
  JIT state are preserved in RAM. It does not mine, and it does not
  rebuild its dataset on resume.

### 5.2 Footprint sampling — light, sampled, never per-access

Full per-access tracing would cost more than it saves. CPCCS samples:

1. In the VM's scratchpad access path, sample every *N*th access
   (default N = 1,024; pilot-test N = 256, 1,024, 4,096 for overhead
   vs accuracy).
2. Record only the block index: `block = scratchpad_offset >> 16`
   (for 64 KiB blocks). No address, no hash data, no pool data is
   recorded.
3. Maintain, per VM, a sliding window of the last 4,096 samples and
   a count per block. Hot set Wᵢ(t) = blocks with count above a
   threshold (default: at least 1% of window samples; pilot-test).
4. Recompute Wᵢ(t) and |Wᵢ(t)| every 5 seconds (the scheduler tick),
   not on every access.

**Overhead budget, pre-registered:** sampling + recomputation must
cost < 1% of one big core's time, measured by CPU time in the
scheduler path. If it exceeds 1% in the pilot, increase N or reduce
tick frequency until it is under budget, or abandon CPCCS. Overhead
is reported as a metric, not hidden.

**Why sampling is valid here:** CPCCS does not need an exact cache
simulation. It needs a *ranking* — which VMs are wide vs narrow, and
which pairs overlap most — that is stable over a 5-second tick.
Sampling error that preserves ranking is acceptable; sampling error
that flips ranking is measured in the pilot by comparing sampled
hot sets against a full-trace ground truth on a short, offline
benchmark run (not on the live pool).

### 5.3 Cohort formation — admission by budget, pairing by complement

At each scheduler tick (5 s), and on cohort rotation (see 5.5):

1. Compute |Wᵢ| and Oᵢⱼ for all VMs.
2. Sort VMs by |Wᵢ| descending (widest first — hardest to fit).
3. Greedily form a cohort for the 2 big cores:
   - Start with the widest VM.
   - Add a second VM only if:
     - (a) |W_first| + |W_second| ≤ B × safety_margin
       (default safety_margin = 0.85, to leave room for system /
       pool / UI cache use), **and**
     - (b) O_first,second is the lowest overlap available among
       candidates that satisfy (a).
   - If no second VM satisfies (a), run a single-VM cohort (1 big
     core active, second big core idle or on auxiliary work — see
     5.6). Running 1 VM well is better than running 2 VMs that
     thrash each other; this is a deliberate, testable choice.
4. If more than 2 VMs exist in total (the test uses up to 4 VMs in
   the CPCCS condition, to match the static 4-thread baseline's VM
   count while varying *concurrency*, not VM count), the VMs not in
   the cohort are parked.
5. Assign the cohort to big cores by true affinity
   (`affine_to_cpu` with dynamically discovered big-core indices,
   never hardcoded indices; discovery via sysfs frequency groups,
   as in the existing affinity design). If big-core discovery fails,
   CPCCS aborts to static unpinned scheduling and logs that
   affinity is OFF — it must not guess indices and claim big-core
   scheduling.

**Worked logic, in words, not numbers:** a narrow + wide pair whose
combined hot set fits the budget is preferred over a wide + wide
pair that exceeds it, even if the wide + wide pair looked faster in
a 10-second burst. The budget rule is what makes CPCCS
cache-aware; the overlap rule is what makes it complement-paired.
Either rule alone is a weaker invention; the paper claims the
combination, and ablation A (Section 8.3) tests whether pairing
adds anything over budget-only admission.

### 5.4 Calibrating the L3 budget B — pilot, then freeze

B is not taken from a blog table. Pilot procedure, before the main
experiment:

1. Measure the phone's cache topology from sysfs
   (`/sys/devices/system/cpu/cpu*/cache/index*/size`) and from
   XMRig's cache report, if available. Record what is found, and
   what is not found.
2. Run a short, offline benchmark (not on the live pool) with
   1, 2, and 4 VMs, and with hot-set sizes artificially varied by
   program selection if possible, otherwise by natural variation
   across hashes. Record sustained hashrate vs estimated combined
   working set.
3. Fit B as the combined working-set size above which sustained
   hashrate per VM falls sharply (a knee, not a single point).
   If no knee is visible, B is set to the largest combined size
   tested, and the budget rule is reported as non-binding in that
   pilot — a result that weakens, not strengthens, the CPCCS claim.
4. Freeze B, safety_margin, block size, N, and tick interval in a
   pre-registration note *before* the main experiment. Changing
   them after seeing main results is forbidden; it would be
   p-hacking, not science.

### 5.5 Cohort rotation — dwell-guarded, starvation-free

- **Rotation interval:** default 30 seconds, dwell-guarded: a cohort
  is not rotated out before 30 seconds have passed, even if a
  better pairing appears, to avoid migration thrash and WALT/PELT
  governor punishment for flapping.
- **Rotation rule:** at interval end, recompute hot sets and reform
  cohorts from scratch (Section 5.3). A VM that was parked is
  eligible again; a VM that was active can be parked. No VM may be
  parked for two consecutive intervals unless its hot set is the
  widest and no cohort can fit it — in that case it runs alone in
  the next interval (starvation guard).
- **Migration cost:** rotating a cohort does *not* rebuild dataset,
  JIT code, or scratchpad. The parked VM's scratchpad stays in RAM;
  on resume, its first accesses will miss L3 (cold), which is a
  measured cost in the experiment (first-minute hashrate after
  rotation vs steady hashrate). If cold-start cost dominates, the
  rotation interval is lengthened in a pilot, not hidden.
- **Thermal interaction:** rotation does not override thermal
  pauses. If CPU ≥ 65 °C or battery ≥ 40 °C, mining pauses
  regardless of cohort. On resume, cohorts are reformed from
  current hot sets.

### 5.6 Little cores — auxiliary work only, ablation-tested

**Core mode (default for the primary test):** little cores run no
VMs. They run system, UI, and pool I/O only. This is the cleanest
test of pairing: any gain cannot be attributed to little-core
mining.

**Assist mode (ablation B only):** little cores perform, for parked
VMs, (a) sequential fault-in of dataset pages for the next epoch
or next job (streaming, cache-friendly, using streaming prefetch
hints where available), and (b) preparation of the next job's
metadata (not its hash). They must not perform random-access
scratchpad work, and must not run a VM. Assist is tested separately
because sequential prefetch can itself contend for memory
bandwidth; if assist hurts sustained hashrate, it is dropped, and
the core mode stands alone.

Little cores are never used to bypass thermal limits, and never
used to run a second, hidden miner.

### 5.7 Pseudocode

```
every 5 seconds (scheduler tick):
    for each VM i:
        W[i] = hot_set(i)          # from sampled blocks, last 4096 samples
        size[i] = |W[i]|
    for each pair (i, j):
        overlap[i][j] = |W[i] ∩ W[j]| / |W[i] ∪ W[j]|

every 30 seconds (rotation, dwell-guarded):
    if thermal_pause or not_charging: pause; skip rotation
    sort VMs by size descending
    cohort = [widest VM]
    candidates = VMs satisfying size[widest] + size[cand] <= B * 0.85
    if candidates non-empty:
        cohort.append(argmin overlap[widest][cand] over candidates)
    # else: single-VM cohort
    assign cohort to big cores via affine_to_cpu (discovered indices)
    park other VMs (preserve scratchpad + JIT state in RAM)
    if assist_mode:
        assign parked VMs' sequential fault-in to little cores
    else:
        leave little cores idle except system/UI/pool I/O
```

**Implementation notes for the builder (not part of the claim):**
CPCCS lives in the scheduler layer (Kotlin + XMRig config
generation), not in the RandomX VM. It needs a sampling hook in
the scratchpad access path and a config-generation change to emit
per-thread affinity per cohort. It must log, per tick: cohort
members, sizes, overlaps, B, and scheduler CPU time. Without those
logs, the experiment cannot be audited.

## 6. Theoretical justification — why it *should* help, if it does

Let a VM's hash time be approximated, for intuition only, as:

    T_hash ≈ T_compute + (miss_rate × T_DRAM) + T_stall_other

where T_compute is register / L1 / L2 work, miss_rate is the
fraction of scratchpad accesses that miss L3, and T_DRAM is DRAM
latency (order 200–400 cycles, vs ~4–5 cycles for L1, ~12 cycles
for L2 — orders of magnitude, not exact phone numbers).

Two mechanisms follow from this model, and both are testable:

1. **Capacity mechanism:** If combined hot sets exceed L3, miss_rate
   rises sharply for all active VMs (capacity misses). Admission
   by budget keeps combined hot sets near or below L3, so miss_rate
   stays in the low regime. This predicts a knee in hashrate vs
   combined working set (pilot, Section 5.4), and a sustained
   advantage for CPCCS over all-cores static scheduling, where
   combined working set routinely exceeds L3.
2. **Conflict mechanism:** Even when combined size fits L3 in total,
   two VMs whose hot sets map to the same cache sets evict each
   other (conflict misses). Pairing by low overlap reduces
   conflict misses at fixed total size. This predicts that, at
   fixed cohort size (2 VMs) and fixed total working-set size,
   low-overlap pairs sustain higher hashrate than high-overlap
   pairs — a prediction that can be tested directly by forcing
   pairings in a pilot, independent of the full CPCCS loop.

**Why this does not predict a large gain:** The model also bounds
the gain. If miss_rate is already low (narrow hot sets, or a
larger-than-assumed L3), or if T_compute dominates, or if DRAM
bandwidth (not latency) is the bottleneck, then reducing misses
helps little. If sampling + migration overhead exceeds the miss
saving, CPCCS hurts. The theory therefore predicts *direction*
(positive, if the phone is in the capacity/conflict regime) and
*conditions* (gain largest when hot sets are wide and overlapping,
smallest when they are narrow), but not magnitude. A paper that
quoted a magnitude from this model alone would be inventing a
result.

**Why little-core assist is secondary:** Sequential prefetch hides
DRAM latency for *future* dataset accesses, but RandomX dataset
access is pseudo-random, so prefetch distance is limited (1–2
iterations ahead, as in v2's mechanism). Assist therefore cannot
fix random-access latency; it can only warm pages and prepare
jobs. That is why assist is an ablation, not the core claim.

## 7. What CPCCS is not

- Not a new hash, dataset, scratchpad size, or consensus change.
- Not a huge-page, MSR, root, or kernel change. It runs unrooted,
  with the existing thermal (65 °C / 40 °C) and charging-only
  protections unchanged.
- Not a JIT rewrite, a NEON rewrite, or a compiler flag. Those are
  separate, complementary workstreams; CPCCS must be tested with
  the JIT, compiler, and prefetch settings held constant, or its
  effect cannot be isolated.
- Not a pool change. Pool-assigned algorithm, fee, and payout
  threshold are held constant (MoneroOcean, rx/0 expected, 0%
  direct). CPCCS is judged on hashrate and accepted shares, not
  on pool choice.
- Not a claim that more threads are always worse, or always better.
  CPCCS's claim is conditional: *concurrency should follow measured
  working sets, not a fixed count*.

## 8. Experiment design

### 8.1 Conditions (all on the same phone, same build except scheduler)

- **A — Static big-only (baseline 1):** 2 threads, affine to 2 big
  cores, fixed.
- **B — Static 4 threads (baseline 2):** 2 big + 2 little, affine,
  fixed. Matches CPCCS's VM count, differs in concurrency rule.
- **C — Static all cores (baseline 3):** 8 threads, affine, fixed.
  Comparison only; expected to throttle, included to show the
  full ladder, not as a contender.
- **D — CPCCS core:** up to 4 VMs, cohort size ≤ 2 on big cores,
  pairing + budget admission, rotation 30 s, little cores idle
  except system. **This is the primary test condition.**
- **E — Ablation A (budget only, no pairing):** same as D, but
  second cohort member chosen by smallest working set, ignoring
  overlap. Isolates pairing.
- **F — Ablation B (CPCCS + assist):** same as D, plus little-core
  sequential fault-in / next-job preparation. Isolates assist.

All conditions share: same charger and cable, same network (one
network for the whole experiment, recorded), same room conditions
as far as practicable, charging-only ON, CPU pause 65 °C /
resume 55 °C, battery pause 40 °C / resume 37 °C, JIT on,
hardware AES on, full-memory mode, prefetch mode held constant,
MoneroOcean pool and donate 0% held constant, other heavy apps
closed, screen off except for readings.

### 8.2 Procedure per run

1. Cool-down: phone idle, charger on, until CPU temperature is
   back near idle baseline (recorded). Minimum 10 minutes
   between runs; longer if still hot.
2. First-start calibration (algo-perf) done once, before the
   experiment, and reused — do not clear it between runs.
3. Run duration: 30 minutes mining, after a 5-minute warm-up
   that is *not* counted in the sustained average (warm-up
   prevents first-minute burst from contaminating the result).
4. Record 60-second hashrate at 5, 15, and 30 minutes *after*
   warm-up (i.e. at 10, 20, 35 minutes from start, if warm-up is
   counted separately — the exact clock must be fixed in the
   pre-registration and used consistently). Primary metric is
   the 30-minute sustained average of 60-second readings in the
   last 10 minutes, plus the session average if the app provides
   one (record which one is used, and use it consistently).
5. Record at run start and end: accepted shares, rejected shares,
   reject %, CPU temp max, battery temp max, battery % change
   while charging, pause count and reasons, pool algo in log,
   MoneroOcean dashboard worker present / hashrate > 0 /
   accepted shares increasing / pending XMR (session-level
   check, not a per-run decision metric).
6. Record scheduler overhead: CPU time in sampling + tick +
   rotation, as a % of one big core, and cohort log (members,
   sizes, overlaps, B) per tick, for audit.

### 8.3 Randomisation and blocking

- **Order:** conditions run in randomised order, generated before
  the experiment, with no condition run twice in a row if avoidable.
  Randomisation prevents time-of-day, ambient temperature, and
  battery-age drift from masquerading as a condition effect.
- **Blocks:** runs are grouped into blocks of one run per condition
  (A–F), in randomised order within the block. At least 5 blocks
  (5 repeats per condition, 30 runs total) are planned; see
  power note in 8.4. If a run is invalid (network switch, unplug,
  heavy app use, covering, wallet change, missing start/end
  totals), it is discarded *and* re-run in a later block, with
  the reason recorded. Invalid runs are never silently dropped
  to favour a condition.
- **Cool-down and washout:** between runs, cool-down as in 8.2.
  Between blocks, a longer cool-down (at least 30 minutes idle)
  is recommended, to prevent heat carry-over from biasing the
  next block's first run.

### 8.4 Metrics and statistical rigour

**Primary metric (one, pre-registered):** 30-minute sustained
60-second hashrate (H/s), as defined in 8.2. All success criteria
are judged on this metric first. No other metric can rescue a
failed primary metric.

**Secondary metrics:** accepted shares per run, reject %,
CPU temp max, battery temp max, battery % change while charging,
pause count, scheduler overhead %, MoneroOcean dashboard
confirmation (binary: worker present with rising accepted shares,
yes/no).

**Descriptive statistics:** for each condition, report mean,
median, standard deviation, min, max, and 95% confidence interval
for the primary metric, across repeats. Report run-to-run
variance explicitly — a method with high variance is less useful
even if its mean is higher.

**Inferential statistics (pre-registered):**
- Primary comparison: D vs A, and D vs B, as paired comparisons
  within blocks (same block, same day conditions as far as
  possible). Use a paired test: paired t-test if differences are
  approximately normal (Shapiro–Wilk, reported), otherwise
  Wilcoxon signed-rank test. Report effect size (Cohen's d_z for
  paired data, or matched-pairs rank-biserial for Wilcoxon) and
  95% CI for the mean difference, not just a p-value.
- Multiple comparisons: D vs A, D vs B, E vs D (ablation A),
  F vs D (ablation B) — four primary-family comparisons.
  Control family-wise error with Holm–Bonferroni correction.
  Uncorrected p-values are reported alongside corrected ones,
  and a claim requires significance *after* correction.
- **Power / sample size:** 5 repeats per condition is a *minimum
  pilot scale*, not a powered confirmatory scale. Before the
  main experiment, estimate within-block standard deviation from
  a pilot (at least 2 blocks), and compute the repeats needed to
  detect a 10% sustained difference with 80% power at α = 0.05
  (after correction). If the required repeats exceed what is
  feasible in the time available, the experiment is reported as
  *pilot / exploratory*, and no confirmatory claim is made —
  regardless of p-values. This rule prevents a small, noisy
  experiment from being dressed up as proof.
- **No post-hoc condition changes:** B, block size, N, tick,
  rotation interval, and safety_margin are frozen after the
  pilot (Section 5.4). Any change after seeing main results
  invalidates the confirmatory interpretation and must be
  reported as a new, exploratory experiment.

**Validity checks (must pass, or the run is invalid):**
- JIT on, hardware AES on, full-memory mode on, in logs.
- Pool algo in log recorded; if the pool assigns a non-rx/0
  algo for a run, that run is flagged and analysed separately
  (algo switching changes the workload).
- Accepted shares rise during the run; a run with zero accepted
  shares is a fault, not a data point.
- MoneroOcean dashboard confirms worker presence; app H/s
  alone is never proof.

### 8.5 Falsification criteria — what would kill CPCCS

CPCCS is **rejected** (not adopted, not "promising with more work"
unless a specific, testable fix is proposed and re-tested) if
*any* of the following holds in the main experiment:

1. **Primary failure:** Mean D sustained hashrate is not higher
   than both A and B, with 95% CI for the difference excluding
   zero after Holm correction, and with no thermal/reject
   degradation (see 2–3). A higher mean with a CI including zero
   is *inconclusive*, not a success.
2. **Thermal / reject degradation:** D has more pauses, higher
   CPU/battery temp maxima, or higher reject % than A/B by a
   margin that matters in practice (pre-registered: reject %
   above ~2–3% and rising, or pauses on most runs, or battery %
   falling while charging), even if hashrate is nominally higher.
   A faster method that overheats or loses shares is not better.
3. **Overhead failure:** Scheduler overhead exceeds 1% of one big
   core on average, or migration / cold-start cost after rotation
   erases the sustained gain (first-minute-after-rotation
   hashrate systematically below steady hashrate by a large
   margin, reported).
4. **Ablation failure:** E (budget only, no pairing) performs
   statistically indistinguishably from D. Then pairing adds
   nothing, and the pairing claim is withdrawn; budget-only
   admission may still be reported as a separate, weaker claim,
   only if it itself passes criteria 1–3 vs baselines.
5. **Mechanism failure:** D's gain, if any, does not correlate
   with the mechanism — e.g. gain appears even when hot-set
   overlap is high, or when B is non-binding, or when footprint
   sampling is disabled in a control run. Then the gain is
   attributed to an uncontrolled variable (affinity, rotation,
   or chance), not to CPCCS.
6. **Validity failure:** Any JIT, AES, full-mode, or hash-sum
   check fails, or pool-accepted shares do not rise. Then the
   experiment is invalid, not a CPCCS failure or success.

If CPCCS is rejected, the paper's conclusion must say so plainly,
and the builder must not ship CPCCS. A rejected invention is a
successful experiment — it prevents shipping a useless scheduler.

### 8.6 Reporting

The final report, after the experiment, must contain, in short
form:

1. Conditions, charger, network, room conditions, and B / block /
   N / tick / rotation values as frozen.
2. Per-condition mean, median, SD, and 95% CI for sustained
   hashrate, plus secondary metrics.
3. Paired differences, effect sizes, corrected and uncorrected
   p-values, and the power calculation.
4. Which falsification criteria passed or failed, and the
   adoption decision (adopt / reject / inconclusive-pilot).
5. Discarded runs and reasons, and any safety stops.
6. No $/month figure derived from a 30-minute run alone. A $/month
   figure needs live price, network difficulty, and pending XMR
   over days, and must be labelled as an estimate at the time
   of calculation, not as a promise.

## 9. Limitations

- **Single device, single chip.** Results on one Dimensity 7050
  phone do not generalise to other SoCs, other Android versions,
  or other RandomX implementations without re-testing.
- **L3 size and mapping unknown.** B is calibrated, not measured
  from a datasheet. If the phone's actual cache topology differs
  from the model (e.g. per-cluster L3, or a different replacement
  policy), the budget rule may be mis-calibrated. The pilot's
  knee-finding step is the mitigation, and its absence must be
  reported.
- **Sampling is a proxy.** Hot-set size and overlap are estimated
  from sampled block indices, not from hardware performance
  counters, which an unrooted app cannot reliably read. Ranking
  error is possible, and is measured in the pilot against
  full-trace ground truth on a short offline run.
- **Pool variance.** MoneroOcean dashboard pending XMR moves
  slowly at phone speed and is noisy over 30 minutes. It is a
  session-level sanity check, not a per-run metric. Accepted
  shares are the per-run proof, and they too are stochastic at
  low hashrate.
- **Thermal and battery confounds.** Ambient temperature, charger
  heat, case, and battery age all affect sustained hashrate.
  Randomised blocks and cool-downs mitigate, but do not eliminate,
  these confounds. Ambient temperature must be recorded per run.
- **No root, no perf counters, no cache partitioning.** CPCCS
  cannot enforce cache reservation; it can only influence it
  through admission and pairing. That is a fundamental limit of
  the approach on a stock phone, and it bounds the plausible
  gain.
- **Algorithm scope.** CPCCS is for RandomX rx/0 on this phone.
  It does not transfer automatically to other algorithms, other
  pools, or RandomX v2, whose program and prefetch behaviour
  differ.

## 10. Risks and safety

- **Scheduler overhead and migration cost** could outweigh any
  cache gain. Mitigation: overhead budget (Section 5.2),
  dwell-guarded rotation, and falsification criterion 3.
- **Incorrect affinity** (guessed big-core indices) could place
  VMs on little cores and be reported as "big-core" mining.
  Mitigation: dynamic discovery, abort-to-unpinned on failure,
  and per-thread CPU verification in logs.
- **Thermal and battery risk** if rotation or assist is
  mis-implemented to bypass pauses. Mitigation: pauses are
  unchanged and non-overridable by the scheduler; safety stops
  (battery > 40 °C repeatedly, CPU pausing repeatedly, phone
  too hot to hold, battery % falling while charging, any
  swelling or smell) stop the experiment immediately.
- **Validity risk** if any scheduler change alters hash semantics.
  Mitigation: hash-sum verification against a reference for
  the same seed, single-threaded then multi-threaded pool
  acceptance, and rejection of any change that alters a hash bit.
- **Privacy / security risk:** footprint sampling records only
  block indices, never hash data, wallet data, or pool data.
  No seed, private key, or password is ever handled by CPCCS,
  and no full wallet address is ever displayed by it (masked
  display only, as in the wallet UI design).
- **Over-claim risk:** the largest risk in this kind of work is
  not a slow scheduler, but a fast-sounding claim from a noisy
  experiment. Mitigation: pre-registration, frozen parameters,
  Holm correction, power reporting, and the falsification
  criteria in Section 8.5. If the experiment is underpowered,
  it is labelled pilot, not proof.

## 11. Conclusion — invention, not verdict

CPCCS is a testable invention: measure hot working sets by light
sampling, admit VMs to big cores only when their combined hot
set fits a calibrated L3 budget, pair VMs with low hot-set
overlap, rotate cohorts on a dwell-guarded interval, and park
the rest without rebuilding their state. It preserves hash
correctness exactly, runs unrooted, and keeps every thermal and
charging protection in place.

Whether it helps, hurts, or does nothing on a Dimensity 7050 is
unknown until the experiment in Section 8 is run, with its
parameters frozen, its repeats powered, and its falsification
criteria applied. That unknown is not a weakness of the paper;
it is the paper's point. An invention that cannot be killed by
a stated experiment is not science, it is marketing.

**Next step, in order:** (1) pilot to calibrate B, block size, N,
and tick, and to measure sampling overhead and ranking accuracy;
(2) freeze parameters in a pre-registration note; (3) run the
main experiment (Section 8); (4) adopt CPCCS only if it passes
Section 8.5, otherwise reject it plainly and report why.

---

### Sources and prior work cited

- tevador RandomX: dataset 2,080 MiB, cache 256 MiB, scratchpad
  L1 16 KiB / L2 256 KiB / L3 2 MiB, JIT on ARM64, hardware AES.
- XMRig CPU config and RandomX optimisation guide: thread
  affinity, prefetch modes, huge-pages, full vs light mode.
- Anvil fork audit, wallet-UI design, Dimensity 7050 config, and
  factory workstreams in `~/workspace/anvil-miner/docs/`:
  audit, wallet UI, Dimensity config, thermal/power, algorithm
  level, lowest level, GitHub frontier, peer-reviewed frontier.
- Smejkal et al., HARP, ACM Middleware '25, DOI
  10.1145/3721462.3770774.
- Liang et al., FBHS, IEEE Transactions on Sustainable Computing,
  vol. 11, pp. 516–529, Sept–Oct 2026.
- Jeon et al., Phoenix, ACM Transactions on Embedded Computing
  Systems, vol. 25, no. 2, 2026, DOI 10.1145/3793860.
- Samadi et al., iDEAS, IEEE Access, vol. 13, pp. 200711–200724, 2025,
  DOI 10.1109/access.2025.3636995.
- VoltMiner phone benchmarks, June 2026 (blog, not peer-reviewed):
  Helio G99 and Snapdragon 865 thread ladders, MoneroOcean rx/0
  assignment.
- SChernykh RandomX v2 gist (desktop, not peer-reviewed):
  program 256 → 384, ops +52.9%, prefetch 2 ahead.

*No percentage in this paper is a Dimensity 7050 result. All
percentages cited are from other hardware/workloads and are
labelled as such. The Dimensity 7050 result, if any, will come
only from Section 8.*
