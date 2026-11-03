# PALM: A Persistent Anchored Lifecycle for Memory-Hard Mining on Android

## A root-free dataset, TLB and prefetch method for RandomX that does not change the hash

**Scope.** Phone mining only. Low level only. Target device class: Android ARM64 phone,
exemplified by NARZO 70 Pro 5G, Dimensity 7050 (2× Cortex-A78 + 6× Cortex-A55).
Base miner: Anvil fork of XMRig, MoneroOcean pool, direct to the user's own
Monero wallet. No root. No sysfs writes. No seed, private key or password
handling at any layer. Only the public receiving address is sent to the pool.

**Status of this document.** An invention proposal with a pre-registered
experiment design. It is not a result. No hashrate gain, no percentage
improvement, and no dollar figure is claimed for this method anywhere in
this paper. Every number that would be needed to judge it must come from
the experiment in §8, run on the target phone.

---

## Abstract

RandomX mining on a phone is limited less by arithmetic than by memory
management. The full dataset is 2,080 MiB, shared by all threads; the
per-thread scratchpad is 2 MiB; the shared L3 cache of a phone cluster is
of the same order as a single scratchpad. On a stock Android phone the
dataset is also rebuilt on every process restart, its page tables are
re-faulted after every pause, and its huge-page state depends on
fragmentation at the moment of allocation — which is worst exactly when
the phone has been used normally before mining, i.e. the warm-phone case.

PALM (Persistent Anchored Lifecycle for Memory-hard mining) treats
dataset, page tables, scratchpad allocation, warm-up and pause/resume as
one lifecycle instead of five independent settings. Its components are:

1. **Epoch Anchor:** build the dataset once per epoch key, verify it on
   resume with a cheap sampled checksum, and reuse it — never rebuild it
   on a pause, a transient pool disconnect, or a thread-rung change.
2. **Tiered THP probing, corrected:** use `MADV_HUGEPAGE` fault-time
   multi-size THP (mTHP) first, and PMD-only `MADV_COLLAPSE` second,
   measuring the result from `/proc/<pid>/smaps` (`AnonHugePages`) and
   keeping a tier only if it is actually granted and does not hurt
   sustained speed. If nothing is granted, fall back to base pages and
   stop retrying.
3. **Cluster-local scratchpad fault-in:** allocate each thread's
   scratchpad in its own arena, set affinity first, then fault the pages
   in from the owning thread, so that faulting, TLB population and
   first-touch locality follow the thread that will use the memory.
4. **TLB-only warm-up:** warm page tables, not cache. A 2,080 MiB
   dataset cannot fit in a phone cache, so cache warm-up is futile;
   sequential, streaming-hinted fault-in of the dataset populates page
   tables without pretending to keep the dataset resident.
5. **Pause-preserving resume:** pause keeps the process, dataset, JIT
   code and `algo-perf` cache alive; only a true process death, an
   epoch change, or a sampled-checksum failure forces a rebuild.

PALM changes no hash, no dataset size, no scratchpad size, no cache
size, no RandomX program, and no pool protocol. A hash produced under
PALM is bit-identical to a hash produced by stock XMRig for the same
seed, program and dataset. Validity is therefore testable by the
existing proof — hash-sum verification plus pool-accepted shares —
not by a new correctness argument.

---

## 1. Problem

### 1.1 What RandomX actually costs on a phone

RandomX full mode uses, per the project specification and XMRig
documentation:

- Cache: 256 MiB, initialised with Argon2d from the epoch key.
- Dataset: 2,080 MiB (2,048 + 32 MiB), 64-byte items, derived from the
  cache with SuperscalarHash, shared by all threads, not per thread.
- Scratchpad per thread: L1 16 KiB, L2 256 KiB, L3 2 MiB.
- Full-mode rule in XMRig guidance: 256 KiB of L2 and 2 MiB of L3 per
  mining thread for full speed.

On a phone cluster whose shared L3 is of the order of a few MiB,
one thread's 2 MiB scratchpad already fills the shared cache. Two
threads need roughly twice that; eight threads need roughly eight
times that. Threads beyond the first one or two therefore spill to
LPDDR5 DRAM. DRAM latency is two orders of magnitude above L1 latency
in round terms (tens of cycles for L1/L2, hundreds of cycles for DRAM
in the standard textbook ordering; exact cycle counts are
device-specific and are not asserted here). Dataset accesses are
pseudo-random across 2,080 MiB, so they cannot be cached in any case:
only the scratchpad is cacheable, and only if other threads and other
apps do not evict it.

### 1.2 Five separate taxes, currently paid independently

1. **Rebuild tax.** Every process restart rebuilds the cache and the
   dataset. That is minutes of zero hashing, plus re-benchmarking if
   `algo-perf` is not cached. A miner that pauses and resumes often —
   charging pauses, thermal pauses, network drops — pays this tax
   repeatedly if pause is implemented as stop.
2. **TLB tax.** At a 4 KiB base page, a 2,080 MiB dataset needs
   2,080 MiB / 4 KiB = 532,480 page-table entries. At a 16 KiB base
   page (Android 15, ARM64) it needs 133,120 entries. At a 2 MiB PMD
   huge page it needs 1,040 entries. At a 512 KiB mTHP folio it needs
   4,160 entries. The TLB is finite; a working set that needs hundreds
   of thousands of entries will miss, and each miss is a page-table
   walk before the DRAM access even starts. The exact TLB capacity of
   the target SoC is not asserted in this paper — it must be measured
   or treated as an unknown — but the arithmetic above is exact, and
   it is the reason page size matters at all for this workload.
3. **Fragmentation tax.** Huge-page promotion depends on contiguous
   free memory at allocation time. A freshly booted phone has large
   contiguous regions; a warm phone, after hours of normal use, does
   not. A method that asks for a single 2 MiB page or nothing therefore
   fails exactly in the warm-phone case, which is the normal case.
4. **Eviction tax.** Scratchpad pages faulted in by one thread, or
   allocated interleaved across threads, are evicted by little-core
   threads, by other apps, and by the dataset's own streaming reads
   if warm-up is done naively.
5. **Prefetch confusion tax.** Dataset reads are pseudo-random and
   unpredictable, so prefetching dataset items ahead of a hash is
   futile. Prefetching the *scratchpad* and warming *page tables* is
   not futile, but the two are routinely conflated, and a warm-up
   that tries to cache the dataset wastes time and evicts the
   scratchpad it should be protecting.

Existing practice treats these as five knobs: `huge-pages` on/off,
thread count, pause behaviour, warm-up, and dataset rebuild. PALM's
claim is that they are one lifecycle, and that the measurable
quantities that decide between settings — `AnonHugePages`,
time-to-first-accepted-share, time-to-resume after pause, and
30-minute sustained hashrate — should be measured together, in the
same run, with the same controls.

---

## 2. Hypothesis

**H0 (null).** On the target phone, PALM produces no difference in
30-minute sustained hashrate, time-to-first-accepted-share, or
time-to-resume after pause, beyond run-to-run noise measured by
repeated baseline runs, in either the fresh-boot or the warm-phone
condition.

**H1 (PALM).** PALM reduces the non-compute share of mining time —
rebuild time, page-fault / TLB-miss time, and pause-resume time —
without changing compute time (the hash itself). Concretely, PALM is
predicted to:

- (a) reduce time-to-first-accepted-share after a pause or a
  thread-rung change to near zero (dataset already resident), versus
  a full rebuild in the baseline;
- (b) reduce time-to-first-accepted-share on a cold start only by
  the warm-up ordering, not by skipping the mandatory first build;
- (c) increase sustained hashrate, if at all, more in the warm-phone
  condition than in the fresh-boot condition, because fragmentation
  is the variable that tiered THP probing is designed to adapt to;
- (d) show a measurable `AnonHugePages` increase in at least some
  runs when THP probing succeeds, and show no sustained-hastate
  regression when it fails and falls back to base pages.

**Direction, not magnitude.** H1 predicts the *sign* of (a) and the
*ordering* fresh-boot vs warm-phone in (c). It predicts no magnitude.
Any magnitude quoted before the experiment is a fabrication, and
this paper quotes none. If (a) is true but sustained hashrate is
unchanged, PALM is still a real lifecycle improvement (less dead
time), but it is not a hashrate improvement, and it must be reported
that way. If (d) shows `AnonHugePages = 0` on the target phone,
the THP component is falsified for that phone, whatever desktop
documentation says about huge pages.

---

## 3. Related work

Status is labelled for every source. Peer-reviewed papers are not
mining papers; documentation and blogs are not peer-reviewed. No
source below is claimed to measure RandomX on a Dimensity 7050,
because none does, as far as this proposal can establish.

- **RandomX specification and XMRig documentation (project docs,
  not peer-reviewed).** Dataset, cache and scratchpad sizes; full vs
  light mode (light mode is ~8× slower in upstream's fast-vs-light
  framing and is a verification mode, not a mining optimisation);
  JIT vs interpreter; hardware AES; huge-page guidance. The
  specification is the ground truth for what PALM must not change.
- **XMRig 6.25 transparent huge pages on Linux (upstream changelog,
  not a phone measurement).** A THP path exists in the miner on
  Linux. That is the starting point for PALM's probing, not evidence
  that it works on unrooted Android.
- **Linux kernel THP / mTHP documentation (kernel docs).**
  Correction that matters for this proposal: `MADV_COLLAPSE`
  supports PMD-sized collapse only, requires a PMD-aligned range
  with at least one faulted page, and ignores sysfs settings; mTHP
  (multi-size THP, e.g. 16–512 KiB and 2 MiB folios for anonymous
  memory) is granted on the fault path and by khugepaged, not by
  `MADV_COLLAPSE`. An earlier frontier note that described
  `MADV_COLLAPSE` for sub-PMD sizes was wrong on this point, and
  PALM is designed around the corrected semantics: fault-time
  `MADV_HUGEPAGE` for mTHP tiers, `MADV_COLLAPSE` only for
  PMD-aligned 2 MiB chunks. Order-1 (8 KiB) anonymous mTHP is not
  supported; that tier is excluded.
- **Android 16 KiB page sizes (Android Developers documentation).**
  From Android 15, AOSP supports devices configured with 16 KiB
  pages on ARM64; NDK r28+ builds 16 KiB-aligned ELF by default;
  `getconf PAGE_SIZE` reports 4096 or 16384. PALM must query page
  size at runtime and must never assume 4 KiB.
- **HARP (Smejkal et al., ACM Middleware '25, peer-reviewed).**
  Heterogeneous resource management: place threads and adapt the
  application in return. Measured gains are on other heterogeneous
  systems, for other workloads, not mining. PALM borrows only the
  pattern — placement + monitoring + adaptation — implemented
  inside an app, not as a kernel resource manager.
- **FBHS (Liang et al., IEEE Trans. Sustainable Computing, 2026,
  peer-reviewed).** big.LITTLE scheduling for real-time DAG tasks.
  Transferable lesson only: do not treat all threads alike on
  big.LITTLE. A miner has no deadlines or DAG, so most of the
  mechanism does not transfer.
- **Phoenix (Jeon et al., ACM TECS, 2026, peer-reviewed) and
  Marasini & Zhou (arXiv preprint, not peer-reviewed).** Thermal-
  aware placement and calibrated DVFS. Transferable lesson only:
  step down before the governor throttles you hard, with a dwell
  guard against flapping. Multi-exit early exits do not transfer:
  a RandomX hash must run fully or the share is invalid.
- **VoltMiner phone benchmarks (blog, June 2026, not peer-reviewed).**
  The only hard phone ladder in the prior work: Helio G99 2 threads
  328 H/s, 4 threads 385 H/s, 8 threads 424 H/s; Snapdragon 865
  4 big-only 748 H/s vs 8 threads 684 H/s. These bracket the
  Dimensity 7050 outcome in both directions and are the reason
  PALM's test ladder must include big-only, mixed and all-core
  rungs rather than assuming one.
- **Prior Anvil audit, Dimensity config, thermal design, algorithm
  design (internal factory reports, not publications).** Charging-
  only ON, CPU pause 65 °C / resume 55 °C, battery pause 40 °C /
  resume 37 °C, pool `gulf.moneroocean.stream:20032` TLS on,
  donate-level 0, `huge-pages: false` as shipped default. PALM
  keeps all of these unchanged; it is a lifecycle around them,
  not a replacement for them.

**Novelty claim, precisely bounded.** No single component of PALM
is claimed as unprecedented in isolation: persistent datasets,
`MADV_HUGEPAGE`, `MADV_COLLAPSE`, per-thread arenas, streaming
warm-up and pause-preserving services all exist elsewhere. What is
claimed as original is the *combination as a single measured
lifecycle for RandomX on unrooted Android*, with three details
that, to the author's knowledge, have not been published together
for this workload: (i) tiered THP probing with the corrected
PMD-only collapse semantics and a measured fallback, (ii) a
TLB-only warm-up that explicitly refuses to cache the dataset,
and (iii) an anchor record that lets a pause, a rung change or a
transient disconnect resume without a rebuild, verified by a
sampled checksum plus pool acceptance rather than by trust.
If a reviewer finds prior art for the full combination, the
novelty claim falls to the combination's measurement protocol
(§8), and the method must still stand or fall on that protocol.

---

## 4. Method — PALM, in full

PALM is implemented as a thin lifecycle layer around stock XMRig.
It does not modify the RandomX VM, the JIT emitter, the dataset
generation code, or the hash function. All PALM code lives in:
(a) a native allocation / lifecycle wrapper around the existing
dataset, cache and VM allocation calls; (b) the Kotlin foreground
service lifecycle; (c) a small native probe that reads back
`/proc/<pid>/smaps`. No other XMRig code is touched.

### 4.1 State machine

```
COLD ──build──► BUILDING ──ready──► ANCHORED ──pause──► PAUSED
  ▲                  │                    │  resume   │
  │                  │ epoch change /     └───────────┘
  │                  │ checksum failure
  └──────────────────┘
INVALIDATED ──rebuild──► BUILDING
```

- **COLD:** no dataset in this process.
- **BUILDING:** cache and dataset are being built, once, on the
  big cores if affinity is available (init is regular, parallel
  work; using big cores for init is a scheduling choice, not a
  hash change).
- **ANCHORED:** dataset resident, anchor record valid, mining or
  ready to mine.
- **PAUSED:** VM execution stopped, process and dataset kept
  alive by the foreground service. No dataset free, no JIT free,
  no `algo-perf` clear.
- **INVALIDATED:** epoch key changed, sampled checksum failed, or
  process died. Only this state rebuilds.

A transient pool disconnect does not leave ANCHORED: it stays
ANCHORED, reconnects, and resumes with the existing dataset if
the epoch key is unchanged. A thread-rung change does not leave
ANCHORED: it reallocates only scratchpad arenas (§4.4), not the
dataset. A calibration re-run does not leave ANCHORED unless
`algo-perf` is missing.

### 4.2 Epoch Anchor Record

One record per epoch key, held in process memory and, for metadata
only, in private app storage (never the dataset itself, never any
key material beyond the public epoch seed that the pool already
publishes):

```
Anchor {
  epoch_key_id:      hash of the epoch key (public, pool-visible)
  page_size:         runtime query, 4096 or 16384 or other
  dataset_bytes:     2080 MiB (fixed, asserted, never tuned)
  cache_bytes:       256 MiB (fixed)
  thp_map:           per-region granted folio size, or NONE
  thread_rung:       current rung label (B0/B1/B2/B3/B4)
  algo_perf_version: version of the cached algo-perf file, if any
  sampled_checksum:  hash of dataset items at deterministic indices
  built_at:          monotonic timestamp of the build
}
```

**Sampled checksum — what it is and is not.** On build, PALM
hashes dataset items at deterministic, strided indices (for
example every 2^20th item, plus the first and last items — the
exact stride is an implementation parameter, recorded in the
log). On resume, it re-hashes the same indices and compares.
This is O(dataset / stride), not O(dataset). It is *not* a proof
that the whole dataset is intact: a corrupt item between sampled
indices would pass. That is acceptable only because the sampled
check is not the validity proof. The validity proofs, in order,
are: (1) benchmark hash-sum verification for the seed, (2)
single-threaded live accepted shares, (3) multi-threaded live
accepted shares with reject rate near zero, (4) MoneroOcean
dashboard pending balance increasing. If any of those fails,
the anchor is invalidated and the dataset is rebuilt, regardless
of the sampled check. A method that trusted the sampled check
alone would be unsound; PALM does not.

**What PALM never stores.** No seed phrase, no private key, no
wallet password, no pool password. The anchor holds only the
public epoch key identifier and dataset metadata. The wallet
address handling is unchanged from the audited Anvil design:
public address only, masked in UI, validated by shape, block
Base58 and Keccak-256 checksum.

### 4.3 Tiered THP probing (corrected semantics)

Order matters, and the order is forced by the kernel semantics
in §3:

**Step T0 — query, never assume.** Read runtime page size
(`getconf PAGE_SIZE` equivalent in-process), read the list of
mTHP sizes the kernel reports as enabled, if readable, and read
`AnonHugePages` for the process before any hint. Log all three.
If the kernel reports no mTHP sizes, skip to Step T3 (base pages).

**Step T1 — fault-time mTHP (the mTHP tier).** For the dataset
and for each scratchpad arena, call `madvise(ptr, len,
MADV_HUGEPAGE)` *before* faulting the pages in, then fault them
in sequentially (§4.5). The kernel may then grant folios at any
enabled mTHP size (16, 32, 64, 128, 256, 512 KiB, or 2 MiB,
subject to the kernel's enabled list and to contiguity). PALM
does not choose a size; it asks, faults, and measures. This is
the tier that can succeed on a fragmented warm phone, because a
64 KiB or 256 KiB folio needs far less contiguity than a 2 MiB
PMD.

**Step T2 — PMD collapse (the 2 MiB tier, PMD-aligned only).**
For each dataset chunk that is fully faulted, PMD-aligned, and
at least 2 MiB long, attempt `madvise(ptr, len, MADV_COLLAPSE)`
once. This ignores sysfs, but it is best-effort and PMD-only:
it will fail on unaligned ranges, on ranges with no faulted
page, and on fragmented ranges, and that failure is data, not
an error to retry in a loop. Do not call `MADV_COLLAPSE` on
sub-PMD ranges expecting mTHP collapse — the kernel does not
provide that.

**Step T3 — measure and decide.** Read `AnonHugePages` from
`/proc/<pid>/smaps` for the dataset and scratchpad VMAs, and,
if readable, the process's `thp_*` counters. Record:
granted folio sizes (inferred from the `AnonHugePages` delta
and, where available, per-VMA `FilePmdMapped` / anon THP
accounting — exact fields are kernel-version dependent and
must be recorded as observed, not assumed), split count, and
sustained hashrate in the same run (§8). Keep the granted
tier. If `AnonHugePages` is unchanged, record THP = NONE for
this run, fall back to base pages, and do not retry collapse
again in the same session: repeated collapse attempts on a
fragmented phone waste CPU that could be hashing, and
khugepaged churn is itself a cost.

**Step T4 — no sysfs, no root.** PALM never writes
`/sys/kernel/mm/transparent_hugepage/*`, never requests
hugetlbfs, never sets `vm.nr_hugepages`, never changes the
governor. If a future Android kernel exposes a documented
unprivileged API for mTHP size selection, that API can be
added as a new tier; until then, hint + measure is the whole
method.

**Why tiering, not on/off.** The prior shipped default is
`huge-pages: false` — a single binary choice. On a fresh-boot
phone that choice forfeits any granted folio; on a warm phone
a single 2 MiB request fails and is reported as "THP does not
work". Tiering turns a binary into a measurement: the phone
grants what it can, PALM records what it granted, and the
experiment (§8) can then say, per condition, whether granted
folios correlate with sustained speed at all.

### 4.4 Cluster-local scratchpad fault-in

RandomX allocates a scratchpad per thread. PALM does not change
its size or layout. It changes *where and by whom* it is
allocated and faulted:

1. Set thread affinity first, from dynamic discovery:
   read `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`
   and `related_cpus`, group by frequency (2.6 GHz group = big,
   2.0 GHz group = little on the target class), record indices,
   never hardcode them. If discovery fails, fall back to
   unpinned threads and log affinity = OFF.
2. Allocate each thread's scratchpad in its own anonymous
   `mmap` arena, aligned to the runtime page size and, where
   the arena size permits, to a 2 MiB boundary (alignment is
   not a huge-page guarantee; it only makes PMD collapse
   *possible* for that arena).
3. Fault the arena in from the owning thread, after affinity
   is set, by a sequential touch of its pages. Do not fault
   all arenas from the main thread and then hand them to
   worker threads: first-touch locality, TLB population and
   any NUMA-like cluster locality the kernel provides all
   follow the faulting thread, not the allocating thread.
4. Big-core threads' arenas are faulted first and are never
   shared with little-core threads. Little-core threads, when
   used, get their own arenas; they do not share a scratchpad
   with a big-core thread, because sharing is exactly the
   eviction path PALM is trying to avoid.

This component is deliberately modest: it is an allocation
discipline, not a new allocator. Its falsification is clean:
if per-thread fault-in shows no difference from main-thread
fault-in under the protocol in §8, the component is rejected
and removed, and the rest of PALM stands or falls without it.

### 4.5 TLB-only warm-up (staged, streaming-hinted)

Warm-up is staged, and each stage has a different goal.
Conflating them is the error PALM is designed to prevent:

- **Stage W0 — page-table warm-up (dataset).** Sequentially
  touch the dataset in large chunks (implementation parameter,
  e.g. 64 MiB chunks, recorded in the log), with a streaming
  prefetch hint (`__builtin_prefetch` with low temporal
  locality, mapping to `PRFM PLDL1STRM` on AArch64 where the
  compiler provides it). The hint is a hint: the CPU may ignore
  it. The goal is to fault pages and populate page tables, not
  to keep the dataset in cache — it cannot be kept there, at
  2,080 MiB against a few MiB of cache, and trying to do so
  evicts the scratchpad. After W0, the dataset is *mapped*,
  not *cached*. That distinction is the whole point of this
  stage, and it is why W0 is measured by page-fault count and
  time-to-first-hash, not by cache-hit rate.
- **Stage W1 — scratchpad priming.** Touch each thread's
  scratchpad arena from its owning thread, sequentially. The
  scratchpad *can* fit in cache at 2 MiB per thread for one
  or two threads, so priming here is a genuine cache effect,
  unlike W0. Priming touches pages to fault them; it does not
  write hash data into them — RandomX initialises the
  scratchpad per hash from the input, and PALM must not
  pre-write it.
- **Stage W2 — JIT and validity warm-up.** Run benchmark
  hashes, verify the hash sum for the seed, confirm JIT is
  on, hardware AES is on, and full mode (not light, not
  interpreter) is active, from the log. Only then start live
  mining. A silent fallback to light or interpreted mode is
  a build bug, not a warm-up result, and the run is invalid
  if it occurs (§8, invalid runs).

**What warm-up does not do.** It does not prefetch dataset
items for the next hash: dataset accesses are pseudo-random,
so any such prefetch would be a guess with a hit rate near
zero and a cache-pollution cost near certain. It does not
reorder dataset items, compress them, or cache them on disk:
all of those would change the hash, break pool verification,
or both. Disk caching of the dataset across process death is
explicitly rejected: it would require writing 2,080 MiB to
flash on every epoch, wear the storage, and still need a
full integrity check on reload — which is the rebuild cost
again, plus I/O.

### 4.6 Pause-preserving resume, in code terms

- On pause (charging lost, thermal pause, user pause, pool
  unreachable with grace): stop VM execution, keep the
  process, dataset, cache, JIT code, thread arenas and
  `algo-perf` file. Set state PAUSED. Do not free.
- On resume: check charging, CPU ≤ resume threshold,
  battery ≤ resume threshold, epoch key unchanged, sampled
  checksum passes, JIT / AES / full-mode flags still on.
  If all pass, resume mining at the previous rung. If the
  epoch key changed or the checksum fails, go to
  INVALIDATED and rebuild.
- On process death (Android low-memory kill, user swipes
  away, crash): the anchor is lost by definition. The next
  start is COLD, builds once, and records "anchor lost:
  process death" as a reason — it does not pretend the
  anchor survived. The frequency of anchor loss is itself
  a measured outcome (§8): a method whose anchor is lost
  on every pause is not a pause-preserving method, whatever
  its code claims.
- On rung change: keep ANCHORED, free and reallocate only
  scratchpad arenas for the new rung, re-run W1 for the new
  arenas, do not rebuild the dataset, do not re-benchmark
  `algo-perf` unless it is missing.

### 4.7 What PALM explicitly does not do

- No change to RandomX, XMRig's JIT emitter, or the hash.
- No change to dataset, cache or scratchpad sizes.
- No huge-page reservation, no hugetlbfs, no sysfs writes,
  no governor change, no root.
- No SVE/SVE2 (not present on ARMv8.2-A), no GPU path, no
  x86 flags on ARM, no `-Ofast` / fast-math (RandomX needs
  IEEE-754-compliant floating point, including rounding-mode
  handling).
- No seed, private key or password handling. No wallet
  address change. No pool change. No `algo-perf` spoofing:
  a benchmark or calibration result must never be hand-
  edited to fake a faster algorithm.
- No RandomX v2 as Monero: v2 is heavier per hash and is
  not mainnet `rx/0`. Its prefetch-distance idea may be
  tested as a *scheduling* experiment inside v1 (see §8,
  secondary arm), never as a hash change.

---

## 5. Theoretical justification

PALM's justification is a cost decomposition, not a speedup
claim. Let, for a mining session:

```
T_total = T_compute + T_tlb + T_dram + T_rebuild + T_pause + T_other
```

where `T_compute` is the hash arithmetic and VM execution,
`T_tlb` is page-table walk time on TLB misses,
`T_dram` is DRAM latency on cache misses (unavoidable for
random dataset reads, reducible only for scratchpad),
`T_rebuild` is dataset/cache build time amortised over the
session, `T_pause` is dead time on pause/resume, and
`T_other` is pool I/O, scheduling and app overhead.

PALM's components map to terms, one to one:

- Anchor (§4.2) + pause-preserving resume (§4.6) reduce
  `T_rebuild` and `T_pause` toward zero for pause/resume
  and rung changes, and to exactly one build per epoch for
  cold starts. They do not touch `T_compute`.
- Tiered THP (§4.3) reduces `T_tlb` by the ratio of page-table
  entries: from 532,480 entries at 4 KiB to 133,120 at
  16 KiB, 4,160 at 512 KiB mTHP, or 1,040 at 2 MiB PMD,
  *if* the folio is actually granted. If it is not granted,
  `T_tlb` is unchanged, and PALM must report that, not
  assume it. TLB miss cost is also bounded by TLB capacity,
  which is why the paper does not convert entry counts into
  a hashrate percentage: without the device's TLB size and
  miss penalty, that conversion would be invented.
- Cluster-local fault-in (§4.4) reduces the eviction part of
  `T_dram` for scratchpad only. It cannot reduce the random
  dataset part of `T_dram`, which is inherent to RandomX.
  Any claim that it reduces dataset DRAM time would be
  false, and PALM does not make it.
- TLB-only warm-up (§4.5) reduces the first-hash page-fault
  burst, which is part of `T_tlb` on cold start and on
  resume-after-fault, but does not reduce steady-state
  `T_dram`, because the dataset cannot stay cached. A
  warm-up that claimed steady-state cache gains for the
  dataset would be contradicted by its own size arithmetic.

Two consequences follow, and both are testable predictions
rather than promises:

1. **PALM's gain, if any, is bounded by the non-compute
   share.** If a session is long, steady, fresh-boot, and
   never pauses, `T_rebuild` and `T_pause` are already
   amortised to near zero, and PALM can only act through
   `T_tlb` and scratchpad eviction. If a session is short,
   restart-heavy, or warm-phone fragmented, the non-compute
   share is larger, and PALM has more room to act. The
   experiment must therefore compare session shapes, not
   just headline hashrate.
2. **PALM cannot raise the compute ceiling.** No lifecycle
   change makes a 2-big-core phone compute more hashes per
   second than its cores and memory system allow. If the
   experiment shows a large sustained-hastate gain, the
   first suspect is not PALM but a confound — a different
   thread rung, a different thermal state, a light-mode
   fallback in the baseline, or pool variance — and the
   controls in §8 exist to catch exactly that.

---

## 6. Implementation sketch (for the builder, not a result)

```
build_or_resume(epoch_key):
  if anchor.valid and anchor.epoch == epoch_key
     and sampled_checksum(dataset) == anchor.checksum:
       return ANCHORED          # no rebuild, no re-benchmark
  build_cache(epoch_key)         # Argon2d, 256 MiB
  build_dataset(cache)           # SuperscalarHash, 2080 MiB
  anchor = {epoch_key, page_size(), thp_map = probe_thp(),
            checksum = sampled_checksum(dataset)}
  return ANCHORED

probe_thp():
  log(page_size(), mthp_enabled_list(), anon_hugepages_before())
  madvise(dataset, MADV_HUGEPAGE)
  madvise_each_scratchpad(MADV_HUGEPAGE)
  fault_in_sequentially(dataset, chunk = 64 MiB, streaming_hint)
  for each PMD-aligned 2 MiB chunk in dataset:
      madvise(chunk, MADV_COLLAPSE)   # once, best-effort, PMD only
  return {anon_hugepages_after(), thp_counters_if_readable()}

pause():
  stop_vm_execution()
  keep(dataset, cache, jit_code, algo_perf)
  state = PAUSED

resume():
  if not charging or cpu_temp > resume_cpu or batt_temp > resume_batt:
      stay PAUSED
  if epoch_changed() or sampled_checksum() fails:
      state = INVALIDATED; return build_or_resume(new_key)
  verify_flags(jit_on, hard_aes_on, full_mode_on)
  state = ANCHORED; start_mining(previous_rung)
```

Log lines PALM must emit (exact text is an implementation
choice, the fields are not): page size, mTHP enabled list,
`AnonHugePages` before/after probing, anchor valid/invalid
and reason, dataset build time or "reused", sampled checksum
pass/fail, JIT/AES/full-mode flags, thread affinity indices
found, rung, pause/resume timestamps and reasons. A run
whose log lacks any of these fields is incomplete (§8).

---

## 7. Safety, privacy and validity guards

- Thermal and charging guards are unchanged and non-negotiable:
  charging-only ON, CPU pause 65 °C / resume 55 °C, battery
  pause 40 °C / resume 37 °C. PALM must never raise them,
  disable them, or use affinity to bypass them. A run that
  pauses repeatedly is a result (the rung is unsustainable),
  not a failure to be tuned away.
- Battery-health note: holding a phone at 100% charge while
  hot accelerates ageing. If the phone offers an 80% charge
  cap, use it. Do not hack one with root.
- Privacy: only the public receiving address is sent to the
  pool. The anchor, the dataset and the checksum contain no
  wallet secret. The MoneroOcean dashboard check uses the
  address the user pasted; the full address must never appear
  in app UI, toasts, logs or chat — only the masked form
  (first 6 + last 4) in UI, and the internal pool config /
  dashboard link.
- Validity: any PALM change must pass, in order: (1) benchmark
  hash-sum match for the seed, (2) single-threaded live
  accepted shares with reject rate near zero, (3) multi-
  threaded live accepted shares with reject rate near zero
  (the multi-threaded test is essential: shared rounding
  state or shared VM state that passes single-threaded can
  still produce invalid shares multi-threaded), (4)
  MoneroOcean dashboard worker present, hashrate > 0,
  accepted shares increasing, pending balance increasing.
  App hashrate alone is never proof.

---

## 8. Experiment design

### 8.1 Design: factorial, pre-registered, with a baseline noise floor

**Factors:**

- **A — Method (3 levels):** Baseline (stock Anvil: dataset
  rebuilt on restart, `huge-pages: false`, main-thread
  fault-in, no anchor), PALM-noTHP (anchor + cluster-local
  fault-in + TLB warm-up + pause-preserving resume, THP
  hints disabled), PALM-THP (full PALM, §4).
  PALM-noTHP exists so that a THP effect and a lifecycle
  effect are not conflated: if PALM-noTHP and PALM-THP tie,
  THP added nothing on that phone; if Baseline and
  PALM-noTHP tie but PALM-THP wins, the win is THP, not
  lifecycle; if PALM-noTHP wins over Baseline, the win is
  lifecycle, not THP.
- **B — Phone state (2 levels):** Fresh-boot (reboot, wait
  10 minutes idle, no other apps opened) vs Warm-phone
  (at least 2 hours of normal use — browsing, messaging,
  video — immediately before the run, no reboot, no
  memory cleaner run). Warm-phone is the ecologically
  normal condition and the one PALM is hypothesised to
  help most (§2, H1c).
- **C — Thread rung (blocking factor, not a treatment):**
  B0 (2 big-only, affine), B2 (2 big + 2 little, affine),
  B4 (8 threads, all cores). Primary analysis is B0;
  B2 and B4 are secondary and are reported, not pooled
  into a single hashrate, because rung changes the cache
  and thermal regime itself.
- **Secondary arm (prefetch, exploratory only):** within
  PALM-THP at B0, test each prefetch mode the ARM build
  exposes, one at a time, plus the shipped default. This
  arm is exploratory: it does not change the primary
  conclusion, and a prefetch win here must be replicated
  before it is adopted.

**Cells:** 3 methods × 2 phone states = 6 primary cells at
B0, plus B2/B4 repeats for the winning method(s) only, to
limit total time. Each cell is replicated 3 times. Order
of cells is randomised per replicate set, with a full
cool-down between runs (§8.4).

**Session shape per run (all cells):**

1. Cold-start phase: record dataset build time (or
   "reused" for PALM resume arms), time-to-first-hash,
   time-to-first-accepted-share.
2. Steady phase: 30 minutes mining, 60-second hashrate
   recorded at 5, 15 and 30 minutes, plus the 30-minute
   sustained average (the decision number, not the peak).
3. Pause phase: pause for 5 minutes (charging kept on,
   process kept alive for PALM, process kept alive for
   Baseline too — the difference is what PALM *does*
   with the kept process, not whether the process is
   kept), then resume and record time-to-first-accepted-
   share after resume and 10-minute sustained hashrate
   after resume.
4. THP snapshot: `AnonHugePages` and, if readable, THP
   counters, before probing, after probing, and at
   30 minutes.

Total time per run is therefore about 45–50 minutes plus
cool-down. A full B0 factorial (6 cells × 3 replicates)
is about 14–15 hours of phone time, split across days
only if charger, network, room and wallet are identical
and the split is recorded. That time cost is real and is
stated here so that a shortened protocol is recognised
as a deviation, not as the same experiment.

### 8.2 Primary and secondary outcomes

**Primary outcomes (pre-registered, B0 only):**

- P1: 30-minute sustained 60-second hashrate (H/s).
- P2: time-to-first-accepted-share after cold start (s).
- P3: time-to-first-accepted-share after 5-minute pause (s).
- P4: `AnonHugePages` for dataset + scratchpad VMAs
  (KiB), after probing and at 30 minutes.

**Secondary outcomes (all rungs):**

- Accepted / rejected shares and reject % per phase.
- CPU temperature maximum, battery temperature maximum,
  battery % change while charging, pause count and
  reasons, throttling visible (repeated pauses, falling
  60-second hashrate, phone uncomfortable to hold).
- Pool algo in the log per run (expected `rx/0` on
  MoneroOcean for a phone; a non-`rx/0` assignment is
  recorded, not assumed to be a gain).
- MoneroOcean dashboard: worker present, hashrate > 0,
  accepted shares increasing, pending balance direction
  over the session (not a per-run $/month figure).
- Anchor loss count: number of runs in which PALM fell
  back to a rebuild, and the reason (process death,
  epoch change, checksum failure).

**Explicitly not an outcome.** No $/month figure is computed
from a 30-minute run. A $/month figure needs live price,
network difficulty and pending balance over days, and
must be labelled as an estimate at the time of calculation,
not as a promise. No payout is expected or required by
this experiment: the pool threshold (0.003 XMR on
MoneroOcean in prior work) takes weeks to months at phone
speed, and this experiment proves lifecycle and sustained
speed, not payout.

### 8.3 Controls (identical in every run, or the run is invalid)

- Same charger and cable, phone plugged in throughout,
  charging-only ON.
- Same network for the whole experiment (either Jio
  mobile data for every run, or Wi-Fi for every run;
  recorded at the top of the sheet; never switched mid-
  run or between runs in the same replicate set).
- Same room, similar ambient temperature (recorded per
  run; "cool room / warm room" consistently if a
  thermometer is unavailable).
- Phone on a ventilated surface, case off, not in sun,
  not on a bed, not under a pillow or blanket, screen
  off except when taking a reading.
- All other heavy apps closed. No video, game, download,
  second miner, VPN, or heavy phone use during a run.
- Same wallet address, same worker name (`AX-…`), same
  pool, TLS on, donate-level 0, JIT on, hardware AES
  on, full mode on — verified from the log per run.
- Same thread rung within a cell; same cool-down
  (§8.4) before each run; same 60-second (not 10-second)
  hashrate definition.
- Baseline noise floor: before the factorial, run the
  Baseline at B0, fresh-boot, 3 times in identical
  conditions. The spread of those 3 runs (range and
  coefficient of variation) is the noise floor. A PALM
  difference smaller than that floor is a tie, however
  large it looks in isolation.

### 8.4 Cool-down, invalid runs, safety stops

- **Cool-down:** after stopping a run, wait at least
  10 minutes with the app stopped, until CPU temperature
  is back near the idle baseline, before the next run.
  If still hot, wait longer and record the extra wait.
- **Invalid runs — discard and redo, do not average in:**
  network switched mid-run or dropped beyond the app's
  grace period; phone unplugged; heavy app opened or
  long call during the run; phone covered, in sun, or
  in a hot place for that run only; wallet address
  changed mid-experiment; accepted/rejected totals not
  recorded at both start and end; JIT, hardware AES or
  full mode silently off; sampled checksum not recorded.
  Record the reason for every discarded run.
- **Safety stops — stop immediately, no matter the cell:**
  battery temperature repeatedly above 40 °C, CPU
  repeatedly pausing at 65 °C with no stable run, phone
  too hot to hold, battery % falling while plugged in
  on consecutive runs, battery swelling, unusual smell,
  or any physical concern — stop, unplug, do not
  continue, do not restart at a higher rung to "push
  through". A safety stop is a result, not a failure
  to be hidden.

### 8.5 Analysis plan (pre-registered)

- Report every cell's raw values (all replicates), plus
  median and range per cell. With n = 3 per cell, do not
  present a p-value as if it were a large-sample result;
  report the median difference and whether it exceeds
  the baseline noise floor (§8.3). If a larger sample
  is later collected, a pre-specified non-parametric
  comparison (e.g. Mann–Whitney U, two-sided, per
  primary outcome, with the number of comparisons
  stated) may be added, but it must not be added
  retroactively to a small sample to manufacture
  significance.
- Primary comparison order: (1) PALM-THP vs Baseline
  at B0, fresh-boot; (2) PALM-THP vs Baseline at B0,
  warm-phone; (3) PALM-noTHP vs Baseline at B0, both
  phone states (to separate lifecycle from THP);
  (4) PALM-THP vs PALM-noTHP at B0, both phone states
  (to isolate THP). Secondary rungs are descriptive.
- Decision rule for adoption, pre-registered: adopt a
  PALM component only if it wins on a primary outcome
  by more than the noise floor, in at least two of
  three replicates, in the same direction, without a
  safety stop, without a reject-rate increase above
  ~2–3%, and without a battery-% fall while charging.
  A component that wins on time-to-resume but ties on
  sustained hashrate is adopted as a lifecycle
  improvement, labelled as such, not as a hashrate
  improvement.

### 8.6 Falsification criteria

PALM, or a component of it, is falsified if any of the
following holds under the protocol above. Falsification
of a component does not automatically falsify the rest;
falsification of all primary outcomes falsifies PALM as
a hashrate method, even if it remains a lifecycle method.

- **F1 — THP not granted.** `AnonHugePages` is unchanged
  (within measurement error) after T1+T2 probing in
  all fresh-boot and warm-phone runs on the target
  phone. Then the THP component is falsified for that
  phone and kernel, and PALM-THP collapses to
  PALM-noTHP. Desktop THP documentation does not
  override a zero on the phone.
- **F2 — THP granted, no sustained effect.** `AnonHugePages`
  increases, but 30-minute sustained hashrate for
  PALM-THP vs PALM-noTHP differs by less than the
  noise floor in both phone states. Then THP is granted
  but irrelevant for this workload on this phone, and
  the THP component is rejected as a hashrate lever
  (it may still be retained only if it does not hurt,
  and that retention must be justified, not assumed).
- **F3 — Lifecycle does not save time.** Time-to-first-
  accepted-share after pause for PALM vs Baseline
  differs by less than the noise floor, or PALM's
  anchor is lost (process death, checksum failure) in
  a majority of pause runs. Then the anchor /
  pause-preserving component is falsified as
  implemented, whatever its code claims.
- **F4 — Cluster-local fault-in does nothing.** Per-
  thread fault-in vs main-thread fault-in (a sub-arm
  within PALM-noTHP, one variable changed) differs by
  less than the noise floor on sustained hashrate and
  on time-to-first-hash. Then §4.4 is rejected.
- **F5 — TLB warm-up does nothing, or hurts.** W0
  warm-up vs no warm-up (cold start, same method)
  differs by less than the noise floor on time-to-
  first-hash, or W0 *increases* time-to-first-hash
  (e.g. by evicting scratchpad or by faulting cost
  exceeding its benefit). Then §4.5 is rejected or
  revised to scratchpad-only priming.
- **F6 — Validity failure.** Any PALM run produces a
  benchmark hash-sum mismatch, a multi-threaded
  reject rate materially above the baseline, or a
  MoneroOcean dashboard worker that does not show
  accepted shares increasing while the app claims
  hashrate. Then PALM as implemented is invalid, not
  merely ineffective, and no hashrate from that build
  may be reported until the cause is found and fixed.
- **F7 — Thermal confound.** A PALM win disappears
  when temperature, charger and cool-down are held
  strictly identical, or appears only in runs that
  violated a control. Then the win was thermal, not
  PALM, and the protocol, not the method, must be
  fixed and the experiment re-run.

---

## 9. Limitations

1. **Single device class.** Results, if any, are for the
   target phone class and its Android build and kernel.
   They do not transfer to other SoCs, other OEM thermal
   policies, other Android versions, or 16 KiB-page
   devices without re-running §8. In particular, a
   4 KiB-page phone and a 16 KiB-page phone differ by
   4× in dataset page-table entries before any THP
   effect, and must not be pooled.
2. **Kernel opacity.** mTHP enabled sizes, khugepaged
   behaviour, THP sysfs values and `smaps` fields vary
   by kernel version and OEM build, and some may be
   unreadable from an unprivileged app. PALM logs what
   is readable and treats unreadable fields as missing
   data, not as zero.
3. **Fragmentation is stochastic.** Warm-phone
   fragmentation depends on the exact prior use, which
   cannot be reproduced exactly. The warm-phone cell
   is therefore an ecological condition, not a
   controlled fragmentation level, and its variance
   will be larger than fresh-boot variance. That
   variance is part of the result.
4. **Sampled checksum is not a full proof.** See §4.2.
   A full-dataset rehash on every resume would defeat
   the purpose of the anchor; the protocol therefore
   relies on sampled check + benchmark hash-sum +
   pool acceptance, in that order, and a failure at
   any later stage invalidates the anchor.
5. **Pool variance.** MoneroOcean assignment, difficulty
   and pending balance move with network conditions.
   That is why the experiment's decision number is
   sustained hashrate and accepted shares, not pending
   balance or $/month, and why a 30-minute run cannot
   prove a payout.
6. **Small sample.** Three replicates per cell is a
   pilot, not a confirmatory trial. It can falsify
   large claims and can justify a larger trial; it
   cannot prove a small effect. Any adoption decision
   from this protocol alone must be labelled pilot-
   grade.
7. **No root, by design.** PALM cannot test the
   counterfactual "what if THP were forced system-
   wide" or "what if the governor were changed",
   because it refuses those interventions. That is a
   deliberate scope limit, not an oversight: the
   method is for a stock phone its owner actually
   uses, not for a rooted test bench.
8. **Battery wear is not measured by this protocol.**
   Sustained hashrate and temperature are measured;
   long-term battery capacity loss is not, and must
   not be inferred from a 30-minute run. A method
   that wins on hashrate but accelerates wear is not
   a net win on a primary phone, and that trade-off
   must be stated, not hidden, in any adoption report.

---

## 10. Conclusion (as a proposal, not a result)

PALM does not promise a faster phone. It promises a
cleaner question: how much of a phone miner's time is
spent not hashing — rebuilding, faulting, waiting for
page tables, resuming — and can that time be removed
without touching the hash, without root, and without
pretending that a desktop huge-page number applies to
a phone? If the experiment in §8 answers "little" or
"none", PALM is falsified in the precise ways §8.6
states, and the builder should keep only the parts
that survived (most plausibly: pause-preserving resume
as a lifecycle hygiene, even if hashrate ties). If it
answers "a measurable, replicated amount, larger on a
warm phone than on a fresh boot", PALM is a genuine,
if modest, low-level contribution to phone RandomX
mining — and its magnitude will be whatever the
experiment measured, not whatever this proposal
hoped for.

---

## References (status labelled)

- tevador, RandomX specification and documentation
  (project docs, not peer-reviewed).
- XMRig documentation: RandomX optimisation guide,
  CPU configuration, changelog 6.21–6.26 (project docs).
- Linux kernel documentation: Transparent Huge Pages,
  multi-size THP, `MADV_COLLAPSE` semantics (kernel docs).
- Android Developers: Support 16 KB page sizes (docs).
- Smejkal et al., HARP, ACM Middleware '25
  (peer-reviewed).
- Liang et al., FBHS, IEEE Trans. Sustainable
  Computing, 2026 (peer-reviewed).
- Jeon et al., Phoenix, ACM TECS, 2026 (peer-reviewed).
- Marasini & Zhou, calibrated DVFS, arXiv preprint
  (not peer-reviewed).
- VoltMiner phone benchmarks, June 2026 (blog, not
  peer-reviewed).
- Prior Anvil audit, Dimensity 7050 config, thermal
  and algorithm factory reports (internal, not
  publications).

*No reference above is cited as measuring PALM, or as
measuring RandomX on a Dimensity 7050, because none does.
That absence is the reason §8 exists.*
