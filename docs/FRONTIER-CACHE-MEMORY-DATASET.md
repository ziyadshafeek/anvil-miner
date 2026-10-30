# FRONTIER, LOW LEVEL, OPEN-ENDED — Workstream C: Cache, Memory and Dataset Frontier for RandomX

Scope: mining only, low level only. Target phone in the parent project: NARZO 70 Pro 5G,
Dimensity 7050 (2× Cortex-A78 @ 2.6 GHz + 6× Cortex-A55 @ 2.0 GHz, LPDDR5, ARM64).
Baseline to beat, from the existing Anvil config in this tree:
dataset 2,080 MiB shared by all threads (not per thread) + 256 MiB cache,
scratchpad per thread L1 16 KiB / L2 256 KiB / L3 2 MiB, huge-pages false on Android,
pool gulf.moneroocean.stream:20032, donate 0%.

## Verdict first — no cache breakthrough changes the phone ceiling

There is no new paper, preprint or GitHub patch found in this hunt that lets a
2-big-core phone with a ~2 MiB shared L3 hold a 2 MiB scratchpad per thread for
many threads, or that shrinks RandomX's fixed 2,080 MiB dataset / 2 MiB scratchpad
for Monero. Those sizes are consensus parameters for rx/0. You cannot change
them and still mine Monero.

What the frontier actually offers is:
1. **Fitting the fixed algorithm to the small cache** — fewer threads, affinity,
   prefetch, persistent dataset, TLB reduction — worth single-digit to low-teen
   percent at best on a phone, and only if measured, not assumed.
2. **A genuinely new TLB / page-size lever on Android that was not in the old
   Anvil docs** — XMRig 6.25 Linux THP support, mTHP, and Android 15 16 KiB base
   pages. This is the only "new" item in this workstream that is testable from
   an unrooted app without changing the algorithm.
3. **Proof that cache pressure can be designed away — in other algorithms, not
   in Monero.** New 2025-2026 PoW designs use 544 KiB to 36 MiB working sets
   instead of 2 GiB. That is a lesson for a future coin, not a patch for rx/0.
   Porting a smaller scratchpad to Monero would produce invalid shares.

The honest frontier ranking for this phone is therefore: THP/mTHP probe first,
prefetch + persistent-dataset A/B second, cluster-local scratchpad third,
everything requiring root / kernel / hardware co-design last (research only).

## 1. The hard constraint, in numbers

- L1 access ~4-5 cycles, L2 ~12 cycles, DRAM ~200-400 cycles (general memory-wall
  figures, repeated in the LPDDR5X / memory-wall literature found here).
- RandomX scratchpad: 2 MiB per thread. One thread already equals the whole
  indicative Dimensity 7050 shared L3 (~2 MiB, third-party table, not a MediaTek
  datasheet — verify on the phone via sysfs / hwloc, do not quote as fact).
- 2 threads need 4 MiB effective L3, 8 threads need 16 MiB. A phone cluster
  offers at most ~4 MiB by A78 architecture. Every thread past the first one or
  two therefore spills to LPDDR5.
- LPDDR5: up to 6,400 Mbps per pin in the JEDEC spec; a flagship example found
  here is Snapdragon 8 Gen 3 + LPDDR5X at ~77 GB/s. Dimensity 7050 is LPDDR5,
  not LPDDR5X, and its exact phone bandwidth was not found — measure spill cost
  by sustained hashrate, not by spec sheet.
- Dataset reads are pseudo-random across 2,080 MiB. They cannot be prefetched
  perfectly, and they cannot be cached. Only the 2 MiB scratchpad is cacheable,
  and only if it is not evicted by other threads, other apps, or the dataset
  stream itself.

## 2. Ranked techniques — proven / promising / rejected

### PROVEN — already in XMRig / RandomX, keep and verify on phone

| Technique | Evidence / hardware | Phone verdict |
|---|---|---|
| One shared dataset + shared cache, VMs per thread, parallel dataset init, barrier on epoch change | RandomX spec; cpuminer-scash integration (RandomX 1.2.1, one shared 2 GiB dataset, helper threads = thread count, pthread barrier on key change) | PROVEN, ship. Never duplicate the dataset per thread. Never rebuild on every reconnect. |
| Thread count ≤ L3 / 2 MiB | XMRig optimisation guide (256 KiB L2 + 2 MiB L3 per thread); G99 phone pattern in Anvil tree: 2 threads 328 H/s, 4 threads 385, 8 threads 424; SD865: 4 big 748 vs 8 threads 684 (-9%) | PROVEN as a rule, phone rung still to measure. This is why B0/B2, not B4, is the default hypothesis. |
| Persistent memory pool across restarts / algo switches | XMRig `memory-pool`: continuous persistent block, avoids losing huge-page allocation when dataset updates and threads restart after 2-3 days | PROVEN on desktop, PROMISING on Android as dataset-reuse discipline. Implement as: keep VM + dataset alive across pause, do not re-benchmark algo-perf on every restart. |
| Scratchpad prefetch, modes 0-3 | XMRig 6.3.4, PR #1823: 0 off, 1 `prefetcht0` (default), 2 `prefetchnta` (faster on Coffee Lake and a few CPUs), 3 `mov`. DragonX xmrig-hac notes the same 4 values. | PROVEN on x86, UNMEASURED on ARM64. Test all modes the ARM build exposes, one at a time, on the same ladder. Do not assume mode 1 is best on A78. |
| Dataset prefetch 2 iterations ahead (RandomX v2 mechanism) | SChernykh RandomX v2 gist, desktop: v2 = 6,815,744 ops vs v1 4,456,448 (+52.9%), program 256→384, prefetch 2 ahead instead of 1. Ryzen AI 9 365: v1 7,739.7 H/s, v2 7,931.5 H/s (+2.5% H/s) but 156.73% work/Joule on the gist's metric — i.e. v2 is heavier per hash, its metric is work, not raw speed. | PROVEN as a mechanism on desktop, REJECTED as a Monero shipment: do not mine rx/0 as rx/2. PROMISING only as an idea to backport: test a deeper prefetch distance in the v1 JIT, if the fork exposes it, and keep it only on a sustained win. |
| Hardware AES + JIT, full mode | RandomX docs, XMRig changelog: ARM64 JIT fixes 6.21 (no x18), 6.25 Aarch64 JIT selection, 6.26 ARM64 fixes | PROVEN, verify on device: JIT on, hard AES on, full-mem on, or treat silent fallback to light/interpreter as a bug. Light mode is ~8× slower in upstream's fast-vs-light table — never ship it as a mode. |

### PROMISING — new in 2024-2026, testable from an unrooted Android app

| Technique | Evidence | Why it matters for this phone / test |
|---|---|---|
| Transparent Huge Pages (THP) in XMRig 6.25, Linux | XMRig 6.25 changelog PR #3743 "Linux: added support for transparent huge pages", plus "improved compatibility for automatically enabling huge pages on Linux systems without NUMA". XMRig huge-pages doc: typical +20-30%, RandomX up to +50% on desktop with huge pages, 1 GiB pages +1-3% on top — desktop figures, with root / sysctl. | PROMISING, not proven on Android. The new part is that the miner now has a THP path at all. On Android, test it without root (see §3). Do not quote desktop % for a phone. |
| mTHP (multi-size THP) + MADV_COLLAPSE | Linux THP docs found here: mTHP allows huge pages below PMD size, e.g. 16/32/64/128/256/512 KiB and 1,024/2,048 KiB in sysfs listings; `madvise(MADV_COLLAPSE)` collapses a PMD-aligned range, ignores sysfs settings, needs at least one page faulted in. THP only works for anonymous memory and tmpfs/shmem. | PROMISING. mTHP sizes below 2 MiB are a better fit for a 2 MiB scratchpad and a fragmented phone than a single 2 MiB PMD. Test 64 KiB / 256 KiB / 512 KiB collapse on scratchpad and dataset regions and read back `AnonHugePages` from `/proc/<pid>/smaps`. If 0, close the line. |
| Android 15 16 KiB base pages | Android 15 (API 35) supports 16 KiB pages on ARM64; Play requires 16 KiB support for new/updated apps targeting Android 15+ from 1 Nov 2025. 64 MiB mapping: 16,384 pages at 4 KiB vs 4,096 at 16 KiB — 4× fewer TLB entries. AGP 8.5.1+ aligns ELF/ZIP to 16 KiB. | PROMISING and free if the device ships it. For a 2,080 MiB dataset: 532,480 pages at 4 KiB vs 133,120 at 16 KiB. Check `adb shell getconf PAGE_SIZE` on the NARZO. If 4,096, this is a future-device gain, not a current-phone gain. Build the .so 16 KiB-aligned anyway (no downside found, required for Play on API 35+). |
| xmrig-hac scratchpad interleaving, closest L3 slice | DragonX xmrig-hac: "Uses scratchpad interleaving to access only the closest L3 slice from each CPU core", claimed 7-8% speedup on Zen3, plus MSR mod for cn-heavy because prefetchers get confused with interleaving. Hardware: Zen3 desktop, CryptoNight-heavy lineage, not RandomX ARM. | PROMISING as a concept, UNPROVEN on ARM. Dimensity DSU clusters also have slice locality. Test analogue: place each thread's scratchpad on memory local to its cluster / core group, vs one interleaved allocation, and measure sustained H/s. Do not port Zen3 MSR code to ARM. |
| Cache-QoS idea, software version | XMRig `cache_qos`: experimental, disallow non-mining cores from L3, to reduce interference. Desktop Intel RDT lineage. | PROMISING only as hygiene on a phone: close heavy apps, pin mining threads, leave UI/system threads on little cores. There is no Android RDT API for an app to enforce this. Do not claim a Cache-QoS gain. |
| Dataset init parallelism + warm-up | cpuminer-scash: helper threads parallelise dataset generation; RandomX: cache built once per key, dataset once per key, reused for many hashes. | PROMISING for startup, not for steady H/s. Use big cores only for init, touch/fault dataset pages sequentially before mining (warm TLB), and measure init time saved. Steady-state H/s is unaffected once the dataset is built. |

### REJECTED for shipping on an unrooted phone — research only, or wrong layer

| Technique | Evidence | Why rejected here |
|---|---|---|
| hugetlbfs / 1 GiB huge pages / sysctl vm.nr_hugepages=1280 | XMRig huge-pages doc, Linux desktop, root required. | REJECTED for Android app: needs root / sysctl, 1 GiB pages are Linux-only. The old Anvil rule (huge-pages false) stands until the THP probe in §3 proves otherwise. |
| MSR mod / disabling hardware prefetchers | XMRig optimisation guide: "You must disable hardware prefetchers to get optimal RandomX performance" — via MSR 0x1a4 on Intel, root. | REJECTED on ARM phone: no MSR interface from an app, no Intel prefetcher to disable. Hardware prefetch on A78 is not app-controllable. |
| Cache colouring / Colored Huge Pages (CHP) | DATE 2026 paper, Univ. of Athens: CHP, hardware-software co-design, distributes a huge page across physical frames in a strided pattern, +33.7% vs 4 KiB pages without LLC partitioning on memory-intensive workloads, in a modified Linux kernel + minimal architectural changes. Older HPCA'08 page-colouring work: OS allocates pages by cache location to partition LLC. | REJECTED for shipping: needs kernel + hardware changes. PROMISING as the direction of travel — it proves colouring + huge pages are not inherently incompatible, which is the exact combination a phone miner wants. Do not attempt it in an app. |
| Arm MPAM / DSU way partitioning via resctrl | Arm MPAM guide + DSU paper (arXiv:2503.17038): DSU implements per-way L3 partitioning in groups of 4 ways, evaluated on RK3568/RK3588/Orin with ATF + kernel driver changes, CLUSTERPMUEN/SMEN bits enabled in ATF. Linux MPAM resctrl patches (Nov 2025, v5) are still landing upstream. | REJECTED for shipping: privileged, firmware + kernel, not present on a stock NARZO. If a future phone exposes DSU partitioning, this becomes the correct way to reserve L3 for the 2 big cores — watch, do not build for it now. |
| Shrinking the working set by changing the algorithm | New designs below (§4) | REJECTED for Monero: scratchpad/dataset sizes are consensus. A smaller scratchpad mines a different coin, not XMR. |
| KV-cache compression (TurboQuant, joint KV encoding) | Google TurboQuant: KV cache to ~3.5 bits, ~2.7× from Shannon bound, up to 8× attention speedup on H100 in its own metric; joint encoding: up to 4.38× KV compression, ~40% token throughput on vLLM. Hardware: GPUs, LLM inference. | REJECTED: LLM inference, not PoW. RandomX dataset must be bit-exact; lossy compression would produce invalid shares. No transfer. |
| Equihash List Item Reduction (LIR) as a mining speedup | Ding et al., ePrint 2025/2141: LIR systematises memory-time trade-offs for Wagner/Equihash, peak memory >2nN → nN bits (>50% reduction), with ~2× time penalty; Equihash(144,5) 700 MB vs ~2.5 GB in prior implementations. | REJECTED as a RandomX speedup: a 2× time penalty for a 2× memory saving is a loss for H/s, and RandomX's dataset is not an Equihash list. PROMISING only as theory: memory-time trade-offs are near-linear in practice, not free. |
| COLOSSUS-X 32 GiB dataset | Cypherium, Oct 2026 announcement: 32 GiB dataset growing ~10%/year, high-memory AI accelerators, no performance data vs ASIC fleets published in the source found. | REJECTED for a phone: opposite direction — more memory, not less, and no phone data. Noted only to show the frontier is splitting: small-cache CPU coins vs huge-memory accelerator coins. |

## 3. The THP / mTHP probe — the one new experiment worth running first

No root is needed to *ask* for THP. Whether the kernel grants it is a different
question, and it must be measured, not assumed.

Steps, in order, on the NARZO (native side, not Kotlin alone):
1. Check base page: `adb shell getconf PAGE_SIZE` (4,096 vs 16,384).
2. Allocate dataset and scratchpad as anonymous `mmap` (THP only works for
   anonymous memory and tmpfs/shmem, per Linux docs).
3. `madvise(ptr, len, MADV_HUGEPAGE)` on the dataset region and on each
   scratchpad region. This is a hint, not a guarantee; it is ignored if the
   kernel has THP disabled for that size.
4. Fault pages in (touch sequentially), then try `madvise(ptr, len,
   MADV_COLLAPSE)` on a PMD-aligned range. Collapse ignores sysfs, but
   requires PMD alignment and at least one faulted page.
5. Read back `/proc/<pid>/smaps` and check `AnonHugePages` for those VMAs.
   Also check `/sys/kernel/mm/transparent_hugepage/enabled` and the
   `hugepages-*kB` entries if readable (they may not be from an app).
6. Run the same 30-minute sustained ladder with THP hint on vs off.
   Decision: keep THP hint only on a sustained, accepted-share-backed win
   with no stability regression. If `AnonHugePages` is 0 on this phone,
   close the huge-page line permanently and do not revisit it.

Caveats: THP promotion on a fragmented phone after hours of use is worse
than on a fresh boot — test both fresh-boot and warm-phone conditions.
THP can increase memory use (a 2 MiB page for a partially-used range) and
can be split back by the kernel under pressure; log splits if visible.

## 4. New memory-hard designs — what they prove, and why they do not help rx/0

These are the frontier's answer to "reduce cache pressure", and they are
all *different algorithms*. Their value here is as a benchmark for what a
phone-friendly PoW looks like, and as a warning that Monero's design is
deliberately phone-unfriendly at scale.

| Design | Working set / mechanism | Hardware / date | Transfer to Monero? |
|---|---|---|---|
| Xelis xelishashv3 (live Dec 2025) | 544 KiB per-hash scratchpad, BLAKE3 + ChaCha8 + AES, random access. Top desktop CPU ~12 kH/s class (v3; old v2 18-19 kH/s figures are not comparable). Large-L3 X3D chips (96 MiB L3) win because many 544 KiB scratchpads stay in cache. | Desktop CPUs, Dec 2025 guide | NO for rx/0. YES as a design lesson: a 544 KiB scratchpad fits in a phone's L2/L3 in a way a 2 MiB scratchpad does not. If the parent ever evaluates a non-Monero CPU coin, this is the first cache-fit candidate to benchmark on the NARZO. |
| Dpowcoin Argon2id (June 2026 whitepaper v1.0) | Dual-round Argon2id, 4 MiB + 32 MiB = 36 MiB total per attempt, sequential data dependencies, NEON on AArch64 via ISA dispatcher. | Paper, June 2026, no phone benchmark found | NO for rx/0. YES as a lesson: 36 MiB is phone-RAM-feasible and ASIC-hostile by SRAM cost, without a 2 GiB dataset. |
| AstroBWT (DERO) | 1-1.2 MiB working set, Salsa20 + Burrows-Wheeler + SHA3, prime-length 147,253-byte arrays, sequential, data-dependent. | DERO suite, no phone benchmark found | NO for rx/0. Closest in size to a phone cache, but still a different coin. |
| Vecno MemHash (whitepaper Dec 2025) | 16-23 rounds, 8 state words, BLAKE3 + S-box lookups, data-dependent memory accesses. | Whitepaper, no benchmark found | NO — whitepaper only, no phone data, no pool proof. Watch only. |
| RandomX v2 | Heavier, not lighter: +52.9% ops, prefetch 2 ahead. | Desktop Zen 5 gist | NO as a cache reduction. Its only cache lesson is the deeper prefetch. |

Conclusion from this section: if the goal were "any CPU coin that fits a phone
cache", the frontier answer would be a 544 KiB-36 MiB design, not RandomX.
Since the parent goal is Monero on this phone, the frontier answer stays
inside §2-§3: fit RandomX to the cache you have, do not redesign RandomX.

## 5. Ordered experiments for the builder (phone only, no root)

1. **THP/mTHP probe (§3)** — 1 day of work, binary answer (AnonHugePages >0 or 0).
   If 0, stop.
2. **16 KiB alignment + page-size check** — build .so 16 KiB-aligned, test on
   4 KiB and (emulator) 16 KiB, measure TLB / init time. No downside found.
3. **Prefetch A/B** — modes the ARM build exposes, plus, if accessible, a
   2-ahead variant. Same ladder, sustained 60 s H/s, accepted shares.
4. **Persistent dataset + warm TLB** — keep dataset/VM across pause, sequential
   fault-in on init, big-cores-only init. Measure restart-to-first-share time,
   not just steady H/s.
5. **Cluster-local scratchpad** — scratchpad allocation per DSU cluster vs
   interleaved, if the 7050's DSU topology is discoverable from sysfs.
   Expect small or zero on a single-cluster phone; do not spend a week on it.
6. **Do not do:** hugetlbfs, sysctl, MSR, root, Cache-QoS/MPAM enforcement,
   CHP kernel work, or a custom RandomX variant. Those are desktop / kernel /
   future-hardware workstreams, not an Android app workstream.

## 6. What would actually move the number

- A THP hit on scratchpad + dataset: plausible single-digit % on a phone,
  desktop analogue is larger, phone fragmentation will eat part of it.
  UNKNOWN until §3 runs.
- Prefetch tuning: low single-digit %, x86 analogue is mode-dependent and
  sometimes negative. UNKNOWN until A/B runs.
- Persistent dataset: saves minutes per restart, ~0% steady H/s, but
  prevents the "rebuild on every reconnect" tax that kills a session's
  average. Worth doing regardless.
- Fewer threads: already proven by the G99/SD865 pattern — this is the
  largest *known* cache effect on a phone, and it is a subtraction, not
  an addition: removing little-core contention lets the big cores keep
  their scratchpad in cache.
- Nothing in this workstream, alone or combined, credibly takes a
  ~400-600 H/s phone to ~8,900 H/s. Cache work is about getting close
  to the phone's own ceiling, not raising the ceiling. Any claim otherwise
  should be treated as unmeasured until a sustained, accepted-share-backed
  benchmark on the NARZO proves it.

## Sources (verbatim URLs from search, no invented links)

- https://xmrig.com/docs/miner/randomx-optimization-guide
- https://xmrig.com/docs/miner/hugepages
- https://xmrig.com/docs/miner/config/cpu
- https://raw.githubusercontent.com/xmrig/xmrig/master/CHANGELOG.md
- https://gist.github.com/SChernykh/6058ecf01c929883b9d19c7eeadc8809
- https://deepwiki.com/tevador/RandomX
- https://deepwiki.com/tevador/RandomX/3.2-configuration-options
- https://deepwiki.com/scashnetwork/cpuminer-scash/2.3-randomx-integration
- https://android.googlesource.com/kernel/common/+/refs/tags/android15-6.6-2024-07_r57/Documentation/admin-guide/mm/transhuge.rst
- https://publish.obsidian.md/mm/Transparent+Huge+Pages+(THP)
- https://git.dragonx.is/DragonX/xmrig-hac/commits/commit/bcd943b907c08a77ef9012108dc2b31ea6b323fc/src/crypto/rx/Rx.cpp
- https://date26.date-conference.com/proceedings-archive/2026/DATA/1222.pdf
- https://arxiv.org/pdf/2503.17038
- https://eprint.iacr.org/2025/2141
- https://hydrax.gg/blog/how-to-mine-xelis-xel.html
- https://dpowcore.org/static/whitepaper.pdf
- https://deepwiki.com/deroproject/derosuite/6-astrobwt-proof-of-work-algorithm
