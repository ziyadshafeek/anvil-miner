# FRONTIER H — Academic and preprint low-level mining frontier

Scope: mining only, low level only, open-ended. Target for transfer: NARZO 70 Pro 5G,
Dimensity 7050, 2x Cortex-A78 @ 2.6 GHz + 6x Cortex-A55 @ 2.0 GHz, XMRig RandomX,
Anvil fork, direct to MoneroOcean.

## Verdict first

No 2024-2026 peer-reviewed paper, and no arXiv preprint found in this hunt,
measures a RandomX speedup on an ARM phone, let alone on a Dimensity 7050.
That gap is still the frontier's main fact.

What the frontier DOES offer, ranked by transferability, is:

1. Heterogeneous scheduling + DVFS ideas that justify the big-core ladder
   and adaptive step-down already in the build — not a new hashrate number.
2. A JIT-gap warning: a simple single-pass JIT can run ~2x slower than native
   code on ARM64, which makes RandomX's maintained A64 JIT, and logging that
   it is actually on, a first-order check, not a detail.
3. Memory-hardness diminishing returns: bigger memory does not linearly buy
   security or speed, which reinforces that RandomX's fixed 2,080 MiB dataset
   and 2 MiB/thread scratchpad cannot be "tuned" into a speedup without
   mining a different algorithm.
4. Proof-of-Useful-Work theory (matrix multiplication, optimisation problems)
   that is mathematically promising and GPU-first — it does NOT give a
   Dimensity 7050 Monero miner a faster route today, and its first production
   attempt (Pearl, 2026) is GPU-only and already shows the "useful work
   nobody bought" problem.
5. Decentralised-pool theory (FIBERPOOL) that improves pool fairness and
   security on paper, but does not change a phone's hashrate or its
   share-window problem.

**Rule applied throughout: no paper % below is a Dimensity 7050 mining gain.
Every % is labelled with its own hardware and workload. Transfer is marked
PROVEN / PROMISING / UNKNOWN. UNKNOWN is the default until measured on this phone.**

---

## Ranked papers

### 1. FBHS — PEER-REVIEWED, most directly transferable scheduling idea
- Liang, Wu, Wang, Xie, "FBHS: A High-Performance Framework for Real-Time
  Task Scheduling on big.LITTLE Architectures", IEEE Transactions on
  Sustainable Computing, Sept-Oct 2026, vol. 11, pp. 516-529.
  https://www.computer.org/csdl/journal/su/2026/05/11617339/2ikHxCVfPO0
- Status: peer-reviewed IEEE journal.
- What it measured: real-time DAG tasks, 20-70 tasks, vs four baselines.
  Energy reductions: -18.3% vs pure federated, -26.1% vs pure bundled,
  -31.7% vs completely fair scheduler, -10.10% vs E-HEFT. Execution-time
  reductions in the same comparisons: -45.8%, -50.95%, -56.2%, -21.18%.
  Also verified on Montage and CyberShake real data streams.
- What it did NOT measure: no mining, no hashrate, no phone, no RandomX.
- Transfer to Dimensity 7050 RandomX: PROMISING as a design pattern only.
  Lesson: do not treat all threads alike on big.LITTLE; high- vs
  low-utilisation work wants different placement. For RandomX that means
  test big-only vs mixed placements separately. A miner has no deadlines
  and no DAG, so most of FBHS's mechanism does not map across.
  Its % must NOT be quoted as a mining gain.

### 2. iDEAS — PEER-REVIEWED, DVFS + scheduling + offloading, mobile
- Samadi, Yadollahi, Shah-Mansouri, "iDEAS: Intelligent DVFS for
  Energy-Efficient Task Scheduling in Mobile Devices with big.LITTLE
  Computing Architecture", IEEE Access, 2025, vol. 13, pp. 200711-200724.
  DOI: 10.1109/access.2025.3636995.
- Status: peer-reviewed IEEE Access.
- What it measured: deep-reinforcement-learning DVFS/scheduling/offloading
  policy on two task sets, vs an energy-first baseline and a
  drop-minimising baseline. Energy -69% and -75% for lighter tasks,
  -54% and -72% for more demanding tasks, with task drops -18% for
  intensive workloads and almost eliminated for lighter workloads.
- What it did NOT measure: no mining, no hashrate, no RandomX, no
  Dimensity 7050. Task sets are not a continuous memory-hard hash loop.
- Transfer: PROMISING for sustained-speed design, UNKNOWN for hashrate.
  Usable idea: joint placement + frequency + pause decisions learned
  from the phone's own temperature/has/hrate trace, rather than a fixed
  thread count. An unrooted Android app cannot set DVFS governors
  directly; the implementable analogue is pause / step-down / resume
  with a dwell guard, which is already in the thermal design.
  Its % must NOT be quoted as a mining gain.

### 3. HARP — PEER-REVIEWED, heterogeneous resource management (prior hunt, confirmed)
- Smejkal, Khasanov, Castrillon, Hartig, "HARP: Energy-Aware and
  Adaptive Management of Heterogeneous Processors", ACM Middleware '25,
  pp. 270-284, Dec 2025, DOI: 10.1145/3721462.3770774.
- Status: peer-reviewed ACM conference, Best Paper Honorable Mention.
- What it measured: on two representative heterogeneous systems, in
  multi-application scenarios, average execution time -12% and energy
  -28% vs existing methods. Test systems are not named in the abstract
  snippet, so they are not assumed to be phones.
- Transfer: PROMISING as a pattern, UNKNOWN for RandomX. Android will
  not let an app install a Linux resource manager like HARP. The
  in-app analogue is fixed affinity + ladder + online monitoring +
  adaptation. Not a phone mining result.

### 4. Kops — PREPRINT / forthcoming, JIT-gap warning, ARM64
- Zheng, Ji, Tao, Sun, Zhang, Williams, Quinn, "Kops: Safely Extending
  the eBPF Compilation Pipeline with Native Operations", arXiv:2606.24213,
  listed for ACM SIGOPS ATC 2026 (Nov 2026, i.e. forthcoming at the
  Oct 2026 search date — treat as not yet presented, not as a
  published result).
- Status: PREPRINT, not peer-reviewed as of this hunt.
- What it measured: a simple single-pass kernel JIT, translating one
  bytecode instruction at a time with no cross-instruction optimisation,
  ran the same program 1.57x slower on x86-64 and 1.98x slower on ARM64
  than direct native compilation, in the authors' characterisation.
- Transfer: PROMISING and urgent as a CHECK, not as a gain. RandomX's
  value on ARM64 depends entirely on its maintained A64 JIT being on
  and well-selected. If a build ever falls back to interpreter, or to
  a naive JIT, a ~2x-style penalty is the right order of magnitude
  to suspect. Builder action: log JIT on/off, hardware AES, full-mem
  mode at startup, and treat a silent fallback as a bug. Kops does
  NOT prove RandomX's JIT is slow — RandomX's A64 JIT is a different,
  maintained compiler with register allocation and instruction
  selection. Do not quote 1.98x as a RandomX number.

### 5. Argon2 adoption and diminishing returns — PREPRINT, directly on RandomX's base
- "Evaluating Argon2 Adoption and Effectiveness in Real-World Software",
  arXiv:2504.17121v1, Apr 2025.
- Status: PREPRINT, not peer-reviewed.
- What it measured: economic model validated against cryptocurrency
  mining benchmarks. OWASP 46 MiB Argon2 config reduced compromise
  rates by 42.5% vs SHA-256 at $1/account for strong passwords.
  Increasing to RFC 9106's 2,048 MiB gave only +23.3% ($1) and
  +17.7% ($20) additional protection despite 44.5x greater memory
  demand. Repository study: 46.6% of deployments used weaker-than-OWASP
  parameters. Model explicitly uses Bitcoin SHA-256 as baseline and
  Monero RandomX (Argon2d-based, ~2 GiB) as the memory-hard analogue,
  with RandomX overhead conservatively estimated at 100x Argon2 base.
- Transfer: PROMISING as a constraint, UNKNOWN as a speedup. Lesson:
  memory-hardness has diminishing returns, and RandomX's parameters
  are consensus-fixed. You cannot "turn up memory" on a phone to get
  more Monero hashrate — you would mine a different function, and the
  pool would reject it. The paper's cost model also confirms why
  dataset/cache handling, not raw arithmetic, dominates phone cost.

### 6. FIBERPOOL — PREPRINT, decentralised pools, not hashrate
- Sakurai, Shudo, "FIBERPOOL: Leveraging Multiple Blockchains for
  Decentralized Pooled Mining", arXiv:2501.15459, 26 Jan 2025.
  https://arxiv.org/abs/2501.15459
- Status: PREPRINT, not peer-reviewed.
- What it measured: no hashrate, no phone benchmark. A formal /
  design validation of mining fairness, budget balance, reward
  stability, and incentive compatibility for FIBERPOOL Proportional.
  Design: smart contract on main chain + storage chain for share
  verification + child chain for cheap reward withdrawal, to fix
  P2Pool's early-stage scalability/security problem (share-chain
  capacity, security depending on participating hashrate) and
  SmartPool's non-budget-balance / heavy smart-contract fees.
- Transfer: UNKNOWN for phone earnings, PROMISING for pool design
  only. It does not solve the 500 H/s phone's share-window problem:
  a phone still needs a sidechain share inside a finite PPLNS window
  when a block is found. FIBERPOOL is not implemented for Monero,
  not available to an Anvil phone miner today, and must not be
  presented as a faster payout route. Watch only.

### 7. Komargodski, Schen, Weinstein — PREPRINT, PoUW from matrix multiplication
- "Proofs of Useful Work from Arbitrary Matrix Multiplication",
  arXiv:2504.09971 (v1 Apr 2025, v4 Nov 2025), also IACR ePrint 2025/685.
  https://arxiv.org/abs/2504.09971v1
- Status: PREPRINT, not peer-reviewed as of this hunt. Security is
  conjectured, based on reducing to solving a batch of low-rank
  random linear equations.
- What it measured: no hashrate, no phone, no implementation benchmark
  in the abstract. Main result is theoretical: PoUW for arbitrary
  MatMul with 1+o(1) multiplicative overhead over naive MatMul,
  even against fast-MatMul-style algorithms, in a permissionless
  setting where the miner chooses its own input.
- Transfer: PROMISING in theory, UNKNOWN and GPU-FIRST in practice.
  MatMul is the bottleneck of AI compute, so the "2-for-1" idea is
  real in principle. It does not help a Dimensity 7050 RandomX miner:
  it is a different consensus, needs GPU MatMul + zero-knowledge
  proving, and its first production attempt, Pearl (mainnet Apr 2026,
  GPU miners, ~194 s blocks), already shows the central failure mode
  in independent reporting: most "useful" work was matrices nobody
  bought, and real inference carried a 10-15% overhead in early tests.
  Do NOT present PoUW as a phone mining upgrade. Frontier watch only.

### 8. Cao et al. — PREPRINT, optimisation-based PoUW framework
- Cao, Ling, Wang, Gao, Ding, "Optimization-based Proof of Useful Work:
  Framework, Modeling, and Security Analysis", arXiv:2405.19027v2, Mar 2025.
- Status: PREPRINT, not peer-reviewed as of this hunt.
- What it measured: modelling and security analysis, not a phone
  benchmark. Identifies security conditions against selfish and
  malicious miners, a lower bound for security overhead, and the
  trade-off between useful-work efficiency and PoW safeguard, plus
  reward-function design guidelines. Simulations are 10M rounds of
  strategy games, not hashrate measurements.
- Transfer: UNKNOWN for phone mining. Useful only as a warning:
  any "useful work" pool must pay a security overhead, and that
  overhead is the price of replacing pure hashing. No phone route
  follows from this paper today.

### 9. Vavilis et al. — PEER-REVIEWED CONFERENCE, lightweight IoT consensus
- Vavilis, Niavis, "A Fair and Lightweight Consensus Algorithm for IoT",
  Global Internet of Things and Edge Computing Summit (GIECS 2025),
  Springer CCIS vol. 2719, first online 13 Nov 2025.
  https://link.springer.com/chapter/10.1007/978-3-032-09555-8_14
- Status: peer-reviewed conference paper.
- What it measured: a distributed-lottery + reputation-voting hybrid
  for IoT, designed to minimise resource demands and give fair block
  proposals without dedicated hardware. Abstract gives no single
  hashrate, energy %, or phone benchmark to quote — none is invented here.
- Transfer: UNKNOWN for Monero mining. It is a different consensus
  (lottery + voting, not PoW), so it cannot be bolted onto RandomX.
  Its transferable idea is only the general one: on constrained
  devices, lottery/fairness mechanisms beat brute-force hashing for
  energy — which is why PoW phone mining is structurally expensive,
  not a reason to expect a RandomX speedup from this paper.

### 10. REDC / Dynamic Lightweight Hashing — PEER-REVIEWED JOURNAL, IoT, not PoW
- Jain, Kumar, "Resource Efficiency-Driven Consensus (REDC): A Machine
  Learning-Based Blockchain Framework for Healthcare IoT Systems",
  Int. J. Math. Eng. Manag. Sci., vol. 10, no. 6, pp. 2061-2082, 2025.
  DOI: 10.33889/IJMEMS.2025.10.6.096.
- Status: peer-reviewed journal.
- What it measured: REDC + Dynamic Lightweight Hashing vs Proof of
  Elapsed Work and Luck, networks up to 100 nodes: up to +70%
  throughput, +43% energy efficiency, -25% latency.
- Transfer: UNKNOWN for RandomX, and the % must NOT be transferred.
  The baseline is not PoW, the workload is healthcare IoT consensus,
  and Dynamic Lightweight Hashing is a different hash, not RandomX.
  No Monero pool would accept it. Included only because it is the
  kind of "lightweight hashing" claim that is often misread as a
  mining gain — it is not.

### 11. RandomX upstream docs — NOT A PAPER, but the low-level ground truth
- tevador/RandomX configuration and performance documentation.
  https://deepwiki.com/tevador/RandomX/6.2-performance-optimization
- Status: project documentation, not peer-reviewed.
- Documented figures, for context only: JIT 2-3x vs interpreted,
  hardware AES 30-50% vs software AES (x86 AES-NI framing),
  large pages 5-10%, full-mem mode 2,080 MiB, light mode 256 MiB.
  These are upstream documentation figures, not Dimensity 7050
  measurements, and the JIT figure is JIT-vs-interpreter, NOT a gain
  over an already-JIT Anvil build.
- Transfer: PROVEN as the build checklist, UNKNOWN as a gain number.
  Builder must verify on-device: JIT on, hardware AES on (ARM Crypto
  Extensions), full-mem on, large pages off on unrooted Android.
  XMRig changelog (v6.21.0: ARM64 JIT do not use x18, sse2neon update;
  v6.25.0: A64 JIT instruction selection; v6.26.0: ARM64 fixes +
  RandomX v2) is the only "new" low-level RandomX work verified in
  this hunt, and v2 is heavier per hash, not a Monero speedup.

---

## Foundation only (outside 2024-2026, NOT counted in the ranking)

- Biryukov, Dinu, Khovratovich, Argon2 (2016, Password Hashing
  Competition winner): memory-hard, 0.6 cycles/byte memory filling,
  explicitly optimised for x86 cache/memory organisation. Foundation
  for why RandomX's Argon2d cache is x86-favoured and why ARM phone
  cache behaviour must be measured, not assumed.
- Van der Linde, "Parallel SHA-256 in NEON" (Radboud bachelor thesis,
  2016): 4-way NEON SHA-256, +33.5% for 256-bit and +27.5% for 512-bit
  inputs on Cortex-A8 vs C. Old hardware, old thesis, NOT a 2024-2026
  result, and NOT a RandomX result — RandomX hashes are sequential
  VM programs, not 4 parallel SHA-256 lanes, so NEON parallelism
  inside one hash does not transfer. Included only to prevent
  mis-citation of a NEON % as a mining gain.

---

## Rejected / not credible for this workstream

- Freederia-hosted "papers" (lattice SIMD signatures, HF-SMPC hash,
  zero-knowledge edge ledger): no verifiable venue, no peer-review
  evidence, and claims (e.g. 35% fewer ops vs SHA-256 on Cortex-M4,
  3.5x signature speed) are not independently checkable from the
  search snippets. Excluded, not ranked.
- johal.in "Proof-of-Capacity" article claiming Loihi 3 neuromorphic
  58% power reduction, 8.2M verifications/watt, quantum annealing
  3.8x: no verifiable IEEE 2025 source in the snippet, marketing
  framing, neuromorphic hardware not present in a Dimensity phone.
  Excluded.
- SiliconHealth, arXiv:2601.09557 (Jan 2026 preprint): repurposed
  Bitcoin ASICs for healthcare, Lucky Miner LV06 2.93 MH/W. Interesting
  as ASIC repurposing, irrelevant to a phone CPU miner — a phone
  cannot run SHA-256 ASIC work, and RandomX is ASIC-resistant by
  design. Excluded from the phone ranking.
- E-PoW / AI-learning mining marketing pages: no peer-reviewed
  throughput study, no phone benchmark. Excluded.
- A 2026 journal article claiming "more efficient hashing saves
  28.9% energy" and "layer-2 cuts block energy 70.4%": a consensus /
  layer-2 result, not a low-level hashing optimisation for RandomX,
  and its methodology was not verifiable from the snippet. Not ranked.

---

## What to test next on Dimensity 7050, in priority order

1. Log JIT + hardware AES + full-mem on the actual phone. If any is
   off, fix detection first — that is the only low-level failure that
   can cost a large fraction of speed, per the Kops JIT-gap warning.
2. Big-core affinity ladder, sustained 30-minute 60s hashrate, as in
   the existing test protocol. FBHS/iDEAS/HARP justify the ladder;
   they do not predict its winner.
3. Compiler A/B, one change at a time: ARMv8.2-A + crypto vs generic,
   -O2 vs -O3, LTO on/off. No % claimed until measured.
4. Dataset/cache lifecycle: build once, reuse across pause/resume,
   do not rebuild per job or per restart. Measure dataset build time
   and RAM, and confirm full-mem mode is actually possible.
5. MoneroOcean calibration on this chip: which algo is assigned,
   dashboard XMR/day over days. Do not quote calibration H/s as earnings.
6. Do NOT test: RandomX v2 as Monero, light/interpreted mining,
   forced huge pages, rooting, SVE, GPU paths, or any "lightweight
   hash" substituted for RandomX — all would be rejected by the pool
   or unavailable on this phone.

## Bottom line for the parent

The academic frontier is NOT empty, but it is NOT a phone mining
breakthrough either. It gives the builder a justified test list
(affinity, JIT verification, DVFS-aware step-down, dataset reuse)
and two warnings (JIT fallback cost, memory-diminishing returns).
It gives no paper, preprint, or upstream change that, on its own
evidence, beats the current base — Anvil + MoneroOcean XMRig
v6.25.0-mo1, direct to wallet, 0% donate, TLS on — on a Dimensity
7050. The only number that can change that verdict is a measured,
sustained, on-device benchmark with MoneroOcean dashboard verification.
