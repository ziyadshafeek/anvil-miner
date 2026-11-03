# FRONTIER, LOW LEVEL, OPEN-ENDED — Workstream E
## OS, scheduler, DVFS and thermal frontier at low level
### Phone mining only, low level only. No laptop mining, no other earning.

Target: NARZO 70 Pro 5G, Dimensity 7050 — 2× Cortex-A78 @ 2.6 GHz + 6× Cortex-A55 @ 2.0 GHz.
No number in this report is a Dimensity 7050 mining measurement unless it says so.
Every technique is marked: **PROVEN** (mechanism works, in the Android/Linux stack),
**PROMISING** (research, not yet proven for this phone/miner),
**REJECTED** (tested, harmful, or blocked), or **ROOT-ONLY — RESEARCH ONLY**.

**Do NOT root a main phone to chase these ideas.** Root ideas are listed so the
frontier is complete, not as a recommendation. Rooting can wipe data on bootloader
unlock, break banking / SafetyNet, brick the phone, misreport sensors, and void
warranty.

## Verdict first

There is no OS-level bypass that turns a 2-big-core phone into a sustained
desktop miner. The governor and the thermal system are not a bug to defeat;
they are the physics of a passively cooled phone. The frontier win is not
"force maximum frequency". It is: **give the governor a steady, well-placed
load, step down before it is forced to throttle you hard, and measure the
30-minute sustained average, not the first-minute burst.**

That is the opposite of the usual root-guide advice, and the evidence below
supports it: forcing `performance` gave only +20% in one on-device LLM test
and still left throttling, while proactive lowering before the limit held
100% of target frame rate in the zTT Pixel 3a work (different workload, see below).

## 1. The stack a miner actually runs under

```
Miner threads (XMRig, native)
  -> pthread_setaffinity_np / sched_setaffinity   [app can do this]
  -> CFS / EEVDF (mainline) or vendor CFS+WALT   [kernel, app cannot replace it]
  -> EAS: Energy-Aware Scheduling, Energy Model  [kernel, places waking tasks]
  -> schedutil governor: PELT or WALT load signal -> cpufreq OPP choice
  -> vendor thermal HAL: skin temperature (~43 °C class limit in Android docs)
       + SoC / battery sensors -> frequency caps, then hard throttle
  -> Android framework: foreground service / cpuset / battery optimisation
       (background apps are parked on little cores and killed)
```

Key facts that shape everything below:

- **PELT** is a geometric moving average, halflife ~32 ms. It is slow to react
  to a 50–100 ms load jump. **WALT** (Qualcomm / vendor Android kernels) tracks
  demand in fixed windows, typically 20 ms, and forgets a task quickly when it
  leaves the runqueue. It is more responsive than PELT for bursty mobile work.
- **schedutil** does not set a frequency from a timer. It reads the scheduler's
  utilisation signal. A continuous, steady load is therefore the easiest load
  for schedutil to serve correctly. A bursty, pause/resume load is the hardest.
  RandomX mining, run continuously, is already the friendly shape. This is a
  real advantage over bursty LLM inference, where schedutil hunting was a
  measured problem.
- **EAS** (Linux 5.0+, with schedutil) compares the energy cost of placing a
  waking task on a little core vs a big core, using the Energy Model, and
  picks the cheaper placement that still meets demand. It has one operating
  point: on or off. It cannot be tuned by an app to "prefer speed at any cost".
- **Thermal limits are layered, not one number.** CPU junction, battery, and
  skin temperature each have their own sensor and cap. Android's own sustained
  performance documentation is explicit: a device at normal max clocks may run
  60 fps for a few minutes then throttle to 30 fps by 30 minutes, while a
  capped sustained mode can hold ~45 fps for the whole 30 minutes. Lower peak,
  higher sustained average. That sentence is the whole strategy.

## 2. PROVEN — no root, usable in the Anvil fork today

### 2.1 Thread affinity via `sched_setaffinity` / `pthread_setaffinity_np`
**PROVEN mechanism. Phone gain UNKNOWN, must be measured.**

- XMRig already supports it: each thread entry in the `rx/0` array can carry
  `affine_to_cpu`. Anvil today sets `affine_to_cpu: false` for every thread,
  so "25% = 2 threads" is a count, not a placement.
- Discovery must be dynamic, never hardcoded: read
  `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq` and
  `related_cpus` (cores sharing a frequency domain = a cluster), group by
  frequency, big = 2.6 GHz group, little = 2.0 GHz group. Record indices.
  Android convention (little = low indices, big = high) is convention, not a
  guarantee.
- Verification, also no root for your own process: field 39 of
  `/proc/<pid>/task/<tid>/stat` is the CPU a thread is currently running on.
  Log it per thread during the test ladder. If threads are not on the intended
  cores, the affinity claim is false, however good the config looks.
- Why it matters for mining specifically: RandomX is memory-bandwidth bound,
  and little cores share the same bus and small shared L3. Earlier phone
  brackets show both directions, which is why measurement is mandatory:
  Helio G99, 2 big-only 328 H/s (77%), 4 threads 385 H/s, 8 threads 424 H/s
  (little cores helped); Snapdragon 865, 4 big-only 748 H/s peak, 8 threads
  684 H/s (−9%, little cores hurt). Dimensity 7050 (2 big + 6 little) is
  UNKNOWN until its own ladder runs.
- Cost of affinity, honestly stated: Android's own docs warn that forced
  affinity removes the platform's ability to adjust for load and thermal
  throttling and can increase battery drain. Pair affinity with thermal
  pauses and adaptive step-down, never use it to bypass them.

### 2.2 Stay in the foreground, stay continuous
**PROVEN Android mechanism.**

- Foreground service + wake lock + battery optimisation Unrestricted is what
  keeps a miner out of the background cpuset. A background / JobScheduler /
  WorkManager miner is parked on little cores, throttled, and killed. That
  route is **REJECTED** for mining, however convenient it looks.
- Do NOT set `THREAD_PRIORITY_BACKGROUND` or any background priority on
  mining threads. That is a direct hint to the scheduler to deprioritise them.
- Do NOT pause/resume rapidly to "trick" WALT/PELT. WALT forgets quickly,
  PELT decays in ~32 ms, and every process restart rebuilds the 2,080 MiB
  RandomX dataset and loses JIT/VM state. Pause (process alive) is correct;
  Stop (process killed) is expensive. The existing Anvil 60-second
  no-internet grace, rather than killing on a drop, is the right shape.
- Keep other heavy apps closed. They share the same LPDDR5 bus, cache, and
  thermal budget, and they give EAS/schedutil competing load to place.

### 2.3 Android Sustained Performance Mode — the official frontier
**PROVEN API. Availability on NARZO UNKNOWN — probe it, do not assume it.**

- Android 7.0+: `Window.setSustainedPerformanceMode()` + power HAL hint
  `POWER_HINT_SUSTAINED_PERFORMANCE`, gated by
  `isSustainedPerformanceModeSupported()`. In the Nexus reference
  implementation the power HAL caps maximum CPU/GPU frequencies at the
  highest sustainable levels, precisely to avoid the burst-then-throttle
  sawtooth.
- For a miner this is the correct framing: ask the OEM's own sustained
  envelope, then mine inside it, rather than fighting the governor at max
  clocks and being throttled below the sustained envelope anyway.
- Test: call the API, log supported true/false, and compare 30-minute
  sustained 60-second hashrate with and without it, same charger/network/
  room. If unsupported on this handset, the app-level analogue is the
  adaptive step-down in §5 — it is not a reason to root for the same effect.

### 2.4 Read your own sensors, no root
**PROVEN for your own process.**

- Per-cluster current frequency: `/sys/devices/system/cpu/cpu<N>/cpufreq/scaling_cur_freq`
- Per-thread current CPU: `/proc/<pid>/task/<tid>/stat` field 39
- Thermal zones: `/sys/class/thermal/`, battery temperature via the app's
  existing ThermalMonitor path
- Profiling: Perfetto / Systrace for scheduler migrations and frequency
  hunting, if a deeper diagnosis is ever needed
- These are diagnostic, not control. Reading them does not change the
  governor, and that is fine: the control loop is thread count + pause,
  driven by these readings.

## 3. PROMISING — research, not yet proven for this phone/miner

### 3.1 HARP — two-way app ↔ resource-manager communication
**PROMISING. Peer-reviewed, not mining, not a phone.**

- Smejkal, Khasanov, Castrillon, Härtig, "HARP: Energy-Aware and Adaptive
  Management of Heterogeneous Processors", Proc. 26th ACM/IFIP Middleware
  '25, pp. 270–284, Dec 2025, DOI 10.1145/3721462.3770774, Best Paper
  Honorable Mention.
- What it is: a Linux-integrated manager. Apps describe behaviour or it is
  learned online; the manager places threads and tells apps to adapt in
  return. Supports OpenMP, TBB, custom adaptive models.
- Measured: on two representative heterogeneous systems, average execution
  time −12%, energy −28% vs existing methods. Test systems are not named in
  the abstract; do not assume a phone.
- Transfer to Anvil: **design pattern only**. An Android app cannot install
  a Linux resource manager. The implementable analogue is exactly what the
  fork already plans: fixed affinity + thread-count ladder + online
  monitoring (temperature, sustained hashrate, accepted shares) + adaptation
  (drop a rung, pause). That is HARP's loop, shrunk to app level. Its −12% /
  −28% must NOT be quoted as a mining gain.

### 3.2 FBHS — heterogeneous real-time scheduling
**PROMISING. Peer-reviewed, not mining.**

- Liang, Wu, Wang, Xie, "FBHS: A High-Performance Framework for Real-Time
  Task Scheduling on big.LITTLE Architectures", IEEE Trans. Sustainable
  Computing, vol. 11, pp. 516–529, Sept–Oct 2026.
- Measured on 20–70 real-time DAG tasks vs four baselines: energy −18.3% vs
  pure federated, −26.1% vs pure bundled, −31.7% vs CFS, −10.1% vs E-HEFT;
  execution time −45.8%, −50.95%, −56.2%, −21.18% in the same comparisons.
- Transfer: **weak**. A miner has no deadlines, no DAG, no low-utilisation
  thread to bundle. The usable lesson is only: do not treat all threads
  alike on big.LITTLE; test big-only vs mixed placements separately. The
  percentages do not transfer.

### 3.3 Proactive / predictive thermal control — zTT, Phoenix, ThermoGuard, calibrated DVFS
**PROMISING. The most directly useful research family, with a clear limit.**

- **zTT** (Kim et al., ACM MobiSys 2021, Best Paper; foundation, not
  2024–2026): learned DVFS jointly adjusting big.LITTLE CPU + GPU frequencies
  on a Pixel 3a under a 45 °C skin limit. Sustained gaming/vision: 100% of
  target frame rate with zero thermal-induced throttling, vs default
  governor 20–30% FPS drops after overheating. Power −23.9% in the
  high-temperature rendering case while holding frame rate (Pixel 3a +
  Jetson TX2). Lesson: lower frequency *before* the limit, do not wait for
  the governor to cut you.
- **Phoenix** (Jeon et al., ACM Trans. Embedded Comput. Syst., vol. 25, no. 2,
  2026, article 31, DOI 10.1145/3793860, peer-reviewed): thermal-aware
  placement of multiple DNNs, reinforcement learning of thermal dynamics,
  postpones throttling, holds frame rate. No single % quoted in the abstract,
  none invented here. Non-transferable part: multi-exit early exits do NOT
  apply to RandomX — a hash must run its full random program or the share
  is invalid. Transferable part only: place work where heat builds slowest,
  pause before forced throttling.
- **ThermoGuard** (AR/RF, Jetson Xavier NX + Pixel 8): couples DVFS with
  overlay throttling, thermal feedback loop. Reported +27% end-to-end utility
  under sustained high load vs baseline governors, CPU held below 70 °C in
  its tests. Different workload (AR overlays can be degraded; hashes cannot).
- **Calibrated DVFS** (Marasini & Zhou, arXiv:2609.04705, preprint, NOT
  peer-reviewed): fanless Raspberry Pi 5, YOLOv8n. Baseline throttled 1,823
  times in 30 minutes; calibrated state-aware DVFS eliminated observed
  throttling in 30-minute runs, +6.8% frame rate, −1.9% energy/frame vs a
  temperature-only reactive baseline. Transferable method only: derive
  thresholds from measured sensor noise and step response on the actual
  device, with dwell/time guards — do not copy 65 °C / 55 °C as magic numbers
  without phone data, but do not change Anvil's defaults until phone data
  justifies it.
- **App-level translation of all four:** step down one rung when CPU pause
  fires twice in 30 minutes, or 60-second hashrate falls 20% below cool peak
  for 10 minutes, or battery repeatedly hits 40 °C; hold the lower rung
  30 minutes before stepping back up. This is proactive control an app can
  actually do. It is a stability gain, not a hashrate miracle.

### 3.4 DREUS — a tunable operating point instead of EAS's single point
**PROMISING. Thesis, not a shipped kernel, not mining.**

- Bras, "DREUS: Precomputed Workload Splitting for Tunable Performance-Energy
  Balancing in Online Heterogeneous Multicore", LIACS Leiden, Aug 2026.
- Idea: EAS enforces one energy/performance trade-off. DREUS precomputes a
  family of Pareto-optimal splits of utilisation and frequency, selected by
  a single scalar, and dispatches at task wake-up.
- Reported: all tested configurations improved sojourn time vs EAS but used
  more energy; the strictest tested improved average sojourn time by 20.8%
  vs EAS while using 13.9% more energy. That is a trade-off, not free speed:
  you pay energy (heat, battery) for time.
- For a miner, the interesting direction is the opposite end of the same
  curve: a *sustained* operating point (slightly lower frequency, much lower
  heat, higher 30-minute average) chosen deliberately, rather than EAS's
  default point. DREUS itself cannot be installed by an app; the idea can
  inform the step-down ladder's choice of "which rung is the sustained point".

### 3.5 uclamp — the hint that should work, but probably cannot, from an app
**PROMISING IN THEORY. Likely BLOCKED in practice on stock Android. Test, do not assume.**

- Utilisation clamping, Linux 5.3 (task) / 5.4 (cgroup): `UCLAMP_MIN`
  (boost / lower bound) and `UCLAMP_MAX` (cap / upper bound), 0–1024.
  Only schedutil understands the hint for frequency selection. On
  heterogeneous systems, a `uclamp_min` above little-core capacity biases
  placement to big cores; a `uclamp_max` at/below little capacity biases
  to little cores.
- Interfaces: per-task `sched_setattr()` (`sched_util_min/max`), per-cgroup
  `cpu.uclamp.min/max`, system-wide sysctl. Effective value is the
  intersection of all three.
- Blocker: changing a process's scheduling attributes via `sched_setattr`
  requires `CAP_SYS_NICE`. An ordinary Android app does not have it, and
  Android's cgroup task profiles (`cgroups.json` / `task_profiles.json`,
  including the `schedtune` / `top-app` profiles) are system-image policy,
  not app policy. A 2025 uclamp sum-aggregation patch series (LKML, Arm Juno
  r2, 4 little + 2 big) shows the mechanism works when the caller is
  privileged — it does not prove an app can call it.
- Test protocol: attempt `sched_setattr` with `SCHED_FLAG_UTIL_CLAMP` from
  the native side, log success vs EPERM, and log whether placement/frequency
  actually changed. If EPERM, close this line for the stock app and do not
  revisit it without a system-level change. Do NOT work around it with root
  on a main phone (see §4).

### 3.6 sched_ext — the real frontier, for a different device
**PROMISING FOR FUTURE KERNELS. REJECTED for a stock Android app today.**

- sched_ext, merged Linux 6.12, lets a BPF program act as the CPU scheduler
  (dispatch queues, `struct sched_ext_ops`, only `ops.name` mandatory).
  Jan 2026 LWN: sub-schedulers (multiple schedulers on one system) are in
  development. Desktop schedulers exist: `scx_lavd`, `scx_bpfland`,
  `scx_rusty`, `scx_layered`, `scx_simple`. Terminating the loader reverts
  to the default scheduler, so experiments are cheap — on a machine that
  has it.
- Requirements: `CONFIG_SCHED_CLASS_EXT=y`, `CONFIG_BPF_SYSCALL=y`,
  `CONFIG_BPF_JIT=y`, `CONFIG_DEBUG_INFO_BTF=y` (BTF is the most common
  blocker on embedded kernels), plus privilege to load BPF.
- Android reality: Android's BPF loader loads *system* programs from
  `/system/etc/bpf/` at boot, built into the system image. It is not an
  app API for loading a scheduler. Stock vendor GKI kernels do not ship a
  loadable sched_ext scheduler for apps, and an app cannot load one without
  system privilege. A custom kernel / custom ROM could, which puts it in
  §4, not in the shipped APK.
- Watch it: if a future Android kernel ever exposes a sched_ext path, a
  miner-specific scheduler (big cores for hashing threads, little cores for
  UI/network, no migration during a hash batch) is a genuine research
  direction. Today it is a watch item, not a build item.

### 3.7 EEVDF, GreenMix, and other 2025–2026 scheduler work
**PROMISING IN GENERAL. No expected miner gain.**

- **EEVDF** (Earliest Eligible Virtual Deadline First), fully integrated in
  Linux 6.13 CFS: better fairness and lower latency for short tasks, virtual
  deadlines, proxy execution in 6.17. A continuous RandomX thread is not a
  short task and does not benefit from fairer short-task scheduling. No
  miner gain expected; do not chase it.
- **GreenMix** (Roy et al., SC '25, ACM, Nov 2025, DOI 10.1145/3712285.3759861):
  energy-efficient serverless on asymmetric multi-cores, randomised sketching,
  vs Linux EAS as state-of-practice baseline. Serverless functions on
  E-cores, keep-alive cost, not a phone miner. Lesson only: EAS is the
  baseline everyone still compares against; beating it for a *steady*
  workload is harder than beating it for bursty serverless work.
- **2026 edge LLM paper** (arXiv:2603.23640): iPhone 16 Pro loses ~41.5% of
  peak throughput over 20 iterations to a hot-state plateau; Galaxy S24 Ultra
  loses ~15% to a governor-regulated plateau. General lesson, already known:
  thermal behaviour supersedes peak compute on phones. Not a miner number.

## 4. ROOT-ONLY — RESEARCH ONLY, DO NOT RECOMMEND ON A MAIN PHONE

These are listed for completeness of the frontier. **Do not do them on the
user's only phone.**

| Idea | What it would do | Why it is root-only / risky |
|---|---|---|
| `scaling_governor=performance`, `scaling_max_freq` forced | Hold max clocks | Needs sysfs write, blocked by SELinux Enforcing even with root unless permissions are changed. One on-device LLM test: pinning + performance governor gave only +20% (0.5 → 0.6 tok/s), first-token −13%, throttling remained dominant factor elsewhere. On mining it heats fastest and then hits skin-temp caps harder. |
| SchedTune / schedtune cgroup boost, `top-app` cpuset moves | Boost a task group, force big-core placement | schedtune is a kernel cgroup controller (CONFIG_CGROUP_SCHEDTUNE, EXPERIMENTAL), configured at boot / by init. App cannot move itself into a privileged cpuset. |
| Disabling the thermal engine / thermal HAL limits | Remove the throttle | Removes protection. Heat accumulates, battery ages fastest at high charge + heat, eventual hard shutdown or damage. Root guides claiming "91% fewer throttling events" via kernel managers are unverified marketing on low-quality pages, not peer-reviewed data. |
| Custom kernel, KernelSU / Magisk / APatch modules, undervolting, overclocking | New governors, voltage tables, CPU isolation | Bootloader unlock wipes data, warranty loss, banking breakage, bootloop risk, mismatched DVFS tables misreport sensors (one forum analysis cited 68% of random reboots tracing to mismatched tables — indicative, not proof). Undervolting can crash under load; overclocking a phone SoC has almost no headroom. |
| SCHED_FIFO / SCHED_DEADLINE / RT for miner threads | Force max frequency (mainline schedutil sets max for RT/DEADLINE) + priority | Requires CAP_SYS_NICE. An RT miner would starve UI/network threads, raise heat, and risk watchdog / ANR behaviour. It is also the wrong tool: mining needs throughput, not deadline latency. |
| CPU isolation (`isolcpus`), `nohz_full`, custom EAS Energy Model | Reserve cores, change EAS cost model | Kernel command line / kernel rebuild only. No app path. |
| BORE burst patch, custom sched_ext on a custom kernel | Favour short bursts / custom placement | BORE helps interactive feel under load (compositor, input), not a steady hash loop. sched_ext on a custom kernel is §3.6, just with a rebuilt kernel. |

A note on the "spice up your phone" genre: pages claiming >87% of mods add
latency, 3.8× kernel-panic risk, 37% more ANRs with ≥15 modules, etc., are
low-quality marketing pages, not peer-reviewed studies. They are cited here
only to show the *direction* of risk, not as precise numbers. The precise
risk on any one handset is unknowable without testing that handset, which is
itself a reason not to do it on a main phone.

## 5. What the miner should actually do — working WITH the governor

In order, no root, in the Anvil fork:

1. **Foreground service, wake lock, battery Unrestricted.** Stay out of the
   background cpuset. This is step zero; without it every other optimisation
   is measured on the wrong cores.
2. **Dynamic big-core discovery + affinity.** Sysfs frequency groups, not
   hardcoded indices. Emit `affine_to_cpu` big-first. Log actual core per
   thread (field 39) to prove it.
3. **Try Sustained Performance Mode, log supported or not.** If supported,
   compare sustained hashrate with/without it. If not, skip it cleanly.
4. **Try uclamp, log EPERM or success.** If blocked, close it. Do not fake
   a uclamp effect with thread priority tricks.
5. **Continuous load, no flapping.** Keep the native process alive across
   network drops (60 s grace), reuse dataset + JIT + algo-perf cache.
   Pause, do not Stop, on heat/battery/charging loss.
6. **Adaptive step-down, with dwell.** Ladder: 2 big-only → 2+1 → 2+2 → 2+4
   → all 8. Step down one rung if: CPU pause twice in 30 min, or 60-second
   hashrate falls 20% below cool peak for 10 min, or battery repeatedly
   hits 40 °C, or battery % falls while charging, or reject % rises above
   ~2–3% on a rung that was clean lower down. Hold the lower rung 30 min
   before stepping back up. This is the app-level version of zTT/Phoenix
   proactive control.
7. **Fixed safety pauses, unchanged.** Charging-only ON, CPU pause 65 °C /
   resume 55 °C, battery pause 40 °C / resume 37 °C. These are protection
   and also performance: a phone that pauses less because it is cooler
   out-earns a phone that pauses in a loop because it is hotter, even if
   the hotter phone's burst is higher.
8. **Physical setup, stated plainly.** Slow charger (5–10 W preferred over
   30 W+ fast charging, which adds its own heat), case off, hard ventilated
   surface, not bed/pillow/pocket/sun, ambient ideally <30 °C, screen off,
   other heavy apps closed, one network for the whole rung. Pre-cool the
   phone 15 min at room temperature before a test rung. Below ~20% battery,
   Android throttles clocks even when plugged in on some handsets — do not
   benchmark from a low battery.
9. **Measure sustained, not burst.** 60-second hashrate at 5, 15, 30 min,
   30-minute sustained average, accepted/rejected, reject %, CPU max,
   battery max, battery % change while charging, pause count, pool algo
   in log, MoneroOcean dashboard pending XMR. Decision: highest sustained
   rung without repeated pauses; if a higher rung beats a lower rung by
   <10% sustained, keep the lower rung.

## 6. Ranked summary

| Rank | Idea | Status | Miner action |
|---|---|---|---|
| 1 | Big-core affinity + dynamic discovery | PROVEN mechanism, gain UNKNOWN on this phone | Build it, measure ladder |
| 2 | Foreground service + continuous load + dataset reuse | PROVEN | Already in Anvil shape, keep it |
| 3 | Sustained Performance Mode | PROVEN API, availability UNKNOWN | Probe, A/B test |
| 4 | Adaptive step-down before forced throttle (zTT/Phoenix lesson) | PROMISING, app-level analogue | Build it |
| 5 | HARP-style two-way adaptivity (monitor → adapt) | PROMISING, pattern only | Implement as §5 loop |
| 6 | uclamp hint | PROMISING in theory, likely BLOCKED for apps | Try once, log EPERM, close if blocked |
| 7 | DREUS tunable operating point | PROMISING, thesis only | Inform choice of sustained rung, do not implement dispatcher |
| 8 | sched_ext custom scheduler | PROMISING for future kernels, REJECTED for stock app | Watch only |
| 9 | EEVDF, GreenMix, FBHS percentages | PROMISING in general, no miner transfer | Do not quote, do not build for |
| 10 | Performance governor, schedtune boost, thermal-engine off, custom kernel, RT threads, under/overclock | ROOT-ONLY, RESEARCH ONLY | Do NOT do on main phone |
| 11 | Disabling thermal protection, background JobScheduler mining, bursty trick loads, more-threads-equals-more-speed | REJECTED | Do not do |

## 7. Frontier watchlist — what would change this verdict

- A future Android kernel / GKI that exposes a sched_ext path or a
  documented app-level sustained-performance API on MediaTek handsets.
- A peer-reviewed paper that actually measures a continuous, memory-hard
  workload (not DNN inference, not DAG tasks) on a big.LITTLE phone under
  a proactive thermal controller, with sustained throughput numbers.
- A vendor thermal HAL / power HAL change that raises the skin-temperature
  envelope or exposes a miner-safe sustained cap — OEM-controlled, not
  app-controlled.
- A measured Dimensity 7050 ladder (affinity on/off, sustained mode
  on/off, uclamp try, step-down on/off) with 30-minute sustained numbers.
  Until that ladder exists, every "frontier gain" above is a hypothesis,
  and the honest frontier statement is: **the governor can be worked with
  for a higher sustained average, but it cannot be bypassed for a higher
  peak that lasts.**

## Sources — ranked, with status

- Smejkal et al., HARP, Middleware '25, ACM, DOI 10.1145/3721462.3770774
  (peer-reviewed) — https://cfaed.tu-dresden.de/publications?pubId=3858
- Liang et al., FBHS, IEEE Trans. Sustainable Computing, vol. 11, pp. 516–529,
  Sept–Oct 2026 (peer-reviewed)
- Jeon et al., Phoenix, ACM Trans. Embedded Comput. Syst., vol. 25, no. 2,
  2026, DOI 10.1145/3793860 (peer-reviewed)
- Kim et al., zTT, ACM MobiSys 2021, Best Paper (foundation, not 2024–2026)
- Marasini & Zhou, calibrated DVFS, arXiv:2609.04705 (preprint, NOT peer-reviewed)
- Bras, DREUS, LIACS Leiden thesis, Aug 2026 — https://theses.liacs.nl/pdf/2025-2026-BrasMMerijn.pdf
- Roy et al., GreenMix, SC '25, ACM, DOI 10.1145/3712285.3759861 (peer-reviewed, serverless)
- Linux sched-util-clamp documentation — https://git.zx2c4.com/linux-rng/tree/Documentation/scheduler/sched-util-clamp.rst
- Android cgroup abstraction / task profiles — https://source.android.com/docs/core/perf/cgroups
- Android sustained performance — https://source.android.com/docs/core/power/performance
- Android BPF loader (system programs only) — https://source.android.com/docs/core/architecture/kernel/bpf
- sched_ext sub-schedulers, LWN via summary, Jan 2026 — http://en.zicos.com/tech/i32472397-Sub-schedulers-for-sched-ext.html
- On-device inference debugging, schedutil hunting + performance-governor test
  (blog, one Pixel 4, LLM workload, NOT mining) — http://dev.to/pingredsai/on-device-inference-debugging-part-2-threads-on-big-cores-cpu-at-full-clock-still-slow-6hm
- WALT / PELT / schedutil mechanism — vendor kernel docs summarised via
  DeepWiki (OnePlus 9, Xiaomi SM8250, Realme GT7 Pro kernels), not primary
  vendor datasheets; treat kernel-variant details as indicative.
- Thermal throttling sustained-performance survey, IJARCCE, vol. 15, issue 7,
  July 2026, DOI 10.17148/IJARCCE.2026.15711 (journal quality weak, figures
  indicative only: up to 35% drop, SD 7 Gen 3 22.5%, SD 8s Gen 3 35.0%)
- Edge LLM sustained-load paper, arXiv:2603.23640 (iPhone −41.5%, S24 Ultra
  −15% over iterations; general thermal lesson, not mining)
