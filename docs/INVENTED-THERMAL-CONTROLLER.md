# Invented: A Governor-Cooperative Sustained-Performance Controller for Phone Mining

**Title proposed:** GCSPC — A Governor-Cooperative, Thermal-Aware Sustained-Performance Controller for Memory-Hard Mining on Heterogeneous Android Phones

**Scope:** Phone mining only. Target phone: NARZO 70 Pro 5G, Dimensity 7050 (2× Cortex-A78 @ 2.6 GHz + 6× Cortex-A55 @ 2.0 GHz, ARM64, Android, unrooted).
**Workload:** XMRig RandomX (rx/0) via the Anvil fork, direct to MoneroOcean, TLS on, donate-level 0.
**Status:** Invention and paper draft only. No code was shipped in this document, and no hashrate, temperature, or earnings figure is claimed for this phone. Every gain claim is a hypothesis to be falsified by the experiment in §7.

---

## Abstract

Phone miners fail not because their peak hashrate is low, but because their sustained hashrate collapses. A phone starts cool, bursts at a high rate, heats, throttles under the Android governor and thermal HAL, pauses at a hard limit, cools, restarts, and repeats — a sawtooth whose 30-minute average is well below its first-minute peak.

Existing phone miners use one of two crude controls: (a) a fixed thread count, or (b) a hard pause at a temperature threshold with a fixed resume point. Neither cooperates with the governor that actually sets frequency. The hard pause fights the governor: it lets heat build until a cliff, then removes all load, then re-applies full load into a still-warm phone.

We invent GCSPC, a governor-cooperative controller that works *with* the governor, without root, without setting frequencies, without disabling any safety pause. GCSPC treats sustained hashrate, not peak hashrate, as the objective. It combines four mechanisms that, to our knowledge, have not been combined for phone mining before:

1. **A joint thermal–hashrate state estimator** — temperature, temperature derivative, 60-second hashrate, hashrate slope, and battery temperature are estimated together, not thresholded separately.
2. **Predictive step-down, not reactive pause** — the controller steps down one thread rung *before* the hard pause point, when the joint state predicts the pause will fire within the next dwell window, and holds the lower rung for a fixed dwell.
3. **Online per-rung thermal calibration** — the controller learns, from the phone's own trace, each rung's thermal equilibrium and time constant, and uses that learned model to choose the rung, rather than a copied 65 °C / 55 °C rule as a tuning knob (those limits stay as hard safety, see §8).
4. **Governor-probe integration** — the controller probes, logs, and if available uses Android Sustained Performance Mode, and probes uclamp once and logs EPERM vs success, rather than assuming either works on this handset.

GCSPC is falsifiable by design (§7.6). If it does not beat a fixed-rung control and a naive threshold-pause control on 30-minute sustained, accepted-share-backed hashrate under identical conditions, the hypothesis is rejected, not rescued by a peak figure.

## 1. Problem

### 1.1 The sawtooth

Let H(t) be instantaneous hashrate, T_cpu(t) CPU temperature, T_bat(t) battery temperature, and f(t) the governor-set frequency. On a heterogeneous phone:

- RandomX is memory-bound: dataset 2,080 MiB shared, scratchpad 2 MiB per thread, shared L3 of only a few MiB. Extra little-core threads add heat and bus contention faster than they add hashes.
- The governor (schedutil, driven by PELT with ~32 ms halflife, or vendor WALT with ~20 ms windows) reacts to load, but its frequency decision is filtered and delayed relative to the miner's load step.
- The thermal HAL / skin-temperature cap (~43 °C class on many phones) and the app's own pauses (Anvil defaults: CPU pause 65 °C / resume 55 °C, battery pause 40 °C / resume 37 °C) are cliffs, not gradients.

Result: H(t) is high at t = 0, then falls as f(t) is clamped, then drops to zero at a pause, then recovers. The decision metric used in most miner UIs — instantaneous or 10-second hashrate — rewards the burst and hides the sawtooth.

### 1.2 Why existing controls fail

- **Fixed thread count.** Chooses a rung once. If the rung is too high for the room, charger, and case, the phone throttles and pauses repeatedly. If too low, it leaves sustained capacity unused. It cannot adapt to a warm room, a fast charger, or a case.
- **Hard threshold pause.** Waits until T_cpu ≥ 65 °C, then stops all work. During cooling, the governor has already clamped frequency, the battery is still hot, and the restart re-applies full load. This creates a limit cycle: heat → pause → cool → full load → heat, with period set by thermal mass, not by mining efficiency.
- **Governor-forcing (root).** Setting `performance` governor, forcing max frequency, or disabling thermal limits is excluded here (see §8): it is unavailable on a stock phone, it removes protection, and on measured bursty workloads it gave only a modest gain while throttling remained. For a continuous workload it heats fastest and hits the skin cap hardest.

The gap is a controller that *anticipates* the governor's clamp and steps down smoothly, holding a rung the governor can sustain, rather than oscillating between full load and zero load.

## 2. Hypothesis

**H1 (main).** A controller that (i) estimates joint thermal–hashrate state, (ii) steps down one rung predictively before a hard pause, (iii) holds the lower rung for a dwell, and (iv) learns per-rung thermal equilibria from the phone's own trace, will achieve a higher 30-minute sustained, accepted-share-backed hashrate, with fewer pauses, than (a) the best fixed rung and (b) naive threshold-pause control, under identical charger, network, room, and phone-state conditions.

**H2 (mechanism).** The gain, if any, comes from *avoided pauses and avoided governor clamping*, not from a higher peak. Therefore GCSPC's peak 60-second hashrate will be no higher than the fixed control's peak; its sustained average will be higher because its time at zero or clamped hashrate is lower.

**H3 (calibration).** Per-rung thermal equilibria learned on this phone will differ from copied defaults and from other phones' ladders (Helio G99 vs Snapdragon 865 bracket opposite outcomes for little-core addition). Therefore a controller that copies another phone's rung choice will underperform a controller calibrated on this phone, even with identical code.

All three are stated *before* measurement, so a null result is informative, not a failure to be hidden.

## 3. Related work

Related work is cited for mechanism transfer, not for mining gains. No paper below measured RandomX on a Dimensity 7050, and none of their percentages are transferred to this phone.

- **HARP (Smejkal et al., ACM Middleware '25).** Linux-integrated heterogeneous resource management: applications describe behaviour or it is learned online, the manager places threads and tells applications to adapt. Measured −12% execution time, −28% energy on two heterogeneous systems (not phones, not mining). Transfer: the *pattern* — placement + adaptation + online learning — not the numbers. GCSPC is the in-app analogue an unrooted Android app can actually implement: affinity + ladder + monitoring + adaptation, without installing a kernel manager.
- **FBHS (Liang et al., IEEE Trans. Sustainable Computing, vol. 11, pp. 516–529, Sept–Oct 2026).** Federated bundled hybrid scheduling for big.LITTLE real-time DAG tasks, with DVFS. Large energy/time reductions vs baselines on DAG workloads. Transfer: do not treat all threads alike on big.LITTLE; high- vs low-utilisation work wants different placement. A miner has no deadlines or DAG, so most of FBHS does not map; GCSPC borrows only the placement asymmetry.
- **Phoenix (Jeon et al., ACM Trans. Embedded Comput. Syst., vol. 25, no. 2, 2026).** Thermal-aware DNN placement on mobile, postponing throttling via placement and learned thermal dynamics. Transfer: place work where heat builds slowest, pause *before* the governor throttles you. Non-transferable: multi-exit early exits — a RandomX hash must run fully or the share is invalid. GCSPC therefore has no reduced-quality mode; its only thermal levers are step-down and pause.
- **zTT (Kim et al., ACM MobiSys 2021, Best Paper).** Learning-based DVFS with zero thermal throttling, Pixel 3a + Jetson TX2, −23.9% power in high-temperature rendering while holding frame rate. Transfer: learned, proactive control beats reactive thresholding. GCSPC's predictive step-down is the unrooted-app analogue: it cannot set DVFS, so it reduces load before the governor is forced to.
- **iDEAS (Samadi et al., IEEE Access, 2025, vol. 13, pp. 200711–200724).** Deep-reinforcement-learning DVFS/scheduling/offloading on big.LITTLE mobile. Large energy reductions on task sets, not a continuous hash loop. Transfer: joint placement + frequency + pause decisions learned from the device's own trace, rather than a fixed rule. GCSPC deliberately uses a *transparent* learned model (per-rung equilibrium + time constant, §5.3) instead of a black-box RL policy, so its decisions are auditable and its failure modes are diagnosable.
- **Marasini & Zhou, calibrated DVFS (arXiv:2609.04705, preprint).** Fanless Pi 5, YOLOv8n: reactive baseline throttled 1,823 times in 30 minutes; calibrated proactive run eliminated observed throttling in 30-minute runs. Transfer: the *protocol* — derive thresholds from measured sensor noise and step response on the actual device, with dwell/time guards — not the frame-rate numbers. GCSPC adopts dwell guards and on-device calibration as first-class design, not as tuning afterthoughts.
- **Anvil / XMRig / VoltMiner (engineering, not papers).** Anvil's defaults (charging-only ON, CPU 65 °C / 55 °C, battery 40 °C / 37 °C, 60 s no-internet grace, MoneroOcean algo-perf caching) are the safety baseline GCSPC keeps. VoltMiner's phone ladders (G99: 2 threads 328 H/s, 4 threads 385, 8 threads 424; SD865: 4 big 748 peak vs 8 threads 684, −9%) bracket the possible outcomes and motivate why the ladder must be measured on this phone. XMRig's ARM64 JIT (v6.21 x18 fix, v6.25 A64 instruction selection, v6.26 ARM64 fixes) is the execution baseline; GCSPC does not modify the JIT, it controls the load the JIT is asked to sustain.

**Novelty claim, stated narrowly.** No prior work found combines, for phone mining: (i) joint thermal–hashrate state estimation, (ii) predictive one-rung step-down with a dwell guard, (iii) online per-rung thermal-equilibrium learning, and (iv) governor-probe integration (sustained mode + uclamp probe), under unrooted, safety-pause-preserving constraints, with sustained accepted-share hashrate as the sole decision metric. If a reader finds a prior system with all four, GCSPC's novelty claim should be narrowed to the combination's application to RandomX on big.LITTLE Android, and the paper should cite it.

## 4. Definitions and notation

- **Rung.** An ordered thread/affinity setting. Ladder L, lowest first: B0 = 2 threads, 2 big only (affine, after the affinity patch); B1 = 2 big + 1 little; B2 = 2 big + 2 little; B3 = 2 big + 4 little; B4 = all 8 threads. Until the affinity patch lands, percentage fallbacks (25% / 50% / 75% / 100%) are counts only and must be labelled "unpinned, not big-only".
- **Sustained hashrate H_sust.** Mean 60-second hashrate over the last 10 minutes of a 30-minute run, plus the 30-minute session average if the app reports it (state which is used). 10-second hashrate is never a decision metric.
- **Accepted-share-backed hashrate.** H_sust is only valid if accepted shares increased over the run and reject % is reported. A high H_sust with zero accepted shares is a bug, not a result.
- **Pause.** An automatic stop of mining by the controller or by a hard safety limit, with reason logged (heat, battery, charging, network).
- **Dwell.** A minimum hold time at a rung after a step-down (default 30 minutes in the experiment, §7; the controller's dwell is a parameter to be reported, not hidden).
- **Governor clamp.** A sustained fall in 60-second hashrate of ≥20% below the rung's cool peak for ≥10 continuous minutes, without a pause firing — evidence the governor, not the app, has reduced frequency.

## 5. Method: GCSPC

### 5.1 Architecture (app-level only, no root, no kernel module)

```
Sensors (10 s poll)          Estimator                Controller
  CPU temp, battery temp  →  joint state (T, dT/dt,  →  step-down / hold /
  60 s hashrate, accepted /     H60, dH/dt, reject %,     step-up / pause
  rejected, charging,         charging, pause count)      with dwell guard
  scaling_cur_freq (read-only)
        ↓                              ↓
  Per-rung thermal model     Governor probes
  (equilibrium, tau, learned)  (sustained mode?, uclamp?)
        ↓
  Actuators (app-level only):
  thread count + affinity, pause (alive, not Stop),
  resume, dataset/VM preserved across pause
```

All sensing is read-only sysfs / battery API / XMRig log parsing for the app's own process. No sysfs writes, no governor writes, no cgroup writes from the app. If a probe requires privilege, it is logged as blocked and the controller proceeds without it.

### 5.2 Joint state estimator

Every 10 seconds, GCSPC updates:

- T_cpu, smoothed (exponential moving average, α reported in implementation; default 0.3, to be sensitivity-tested, not tuned silently).
- dT_cpu/dt, estimated over a 60-second window (least-squares slope, not a single-sample difference, to suppress sensor noise).
- H60, the 60-second hashrate from XMRig.
- dH/dt, slope of H60 over 10 minutes.
- T_bat, smoothed, and its slope.
- Reject % over the run, and pause count.

The estimator outputs a discrete state, not a single threshold crossing:

- **COOL:** T_cpu well below pause, dT/dt ≈ 0 or negative, H60 stable (within 10% of cool peak).
- **WARMING:** dT/dt positive and sustained, H60 still near peak — the governor has not yet clamped, but heat is accumulating.
- **CLAMPING:** H60 fallen ≥20% below cool peak for ≥10 minutes, with T_cpu high — the governor has already reduced frequency.
- **NEAR-PAUSE:** T_cpu or T_bat within a learned margin of the hard pause point (see §5.3), or dT/dt projects crossing within the dwell window.
- **PAUSED:** hard safety pause fired (see §8); GCSPC does not override it.

Single-sample spikes do not change state. A state change requires the condition to hold for two consecutive estimator windows (20 seconds minimum), to avoid flapping on sensor noise.

### 5.3 Predictive step-down with learned per-rung thermal model

**Thermal model (first-order, per rung, learned).** For each rung r, GCSPC fits, from the phone's own trace:

```
T_cpu(t) ≈ T_eq(r) + (T_start − T_eq(r)) · exp(−t / τ(r))
```

where T_eq(r) is the rung's thermal equilibrium temperature under current room/charger/case conditions, and τ(r) is its time constant. Fitting is by least squares on the rung's temperature trace after the first 5 minutes (transient excluded), with at least 15 minutes of data per rung before the model is trusted. If a rung has insufficient data, GCSPC marks its model UNKNOWN and does not use it to decide — it falls back to the reactive rule below.

This model is deliberately simple and auditable. It does not claim to capture governor non-linearity, skin-temperature feedback, or charger heating exactly; it captures the dominant first-order behaviour that determines whether a rung can be sustained, and its residuals are reported (see §7).

**Predictive rule.** GCSPC steps down one rung when *either*:

(a) **Reactive trigger (fallback, always active):** CPU pause fired twice in 30 minutes, or H60 fell ≥20% below cool peak for 10 minutes, or battery repeatedly hits 40 °C, or battery % falls while charging, or reject % > ~2–3% and rising — step down one rung, hold 30 minutes.

(b) **Predictive trigger (the invention):** the learned model projects T_cpu will reach the hard pause point (65 °C) within the next dwell window (30 minutes) *at the current rung*, while a lower rung's learned T_eq is below the pause point with margin. Then step down *now*, before the pause fires, and hold the lower rung for the dwell.

The predictive trigger is the difference from naive threshold control: it trades a small, controlled hashrate reduction now for avoided zero-hashrate pause time and avoided governor clamping later. If the projection is wrong (model error), the cost is bounded: at most one rung for one dwell, after which GCSPC re-evaluates with new data.

**Step-up rule (conservative, symmetric).** Step up one rung only after a full 30 minutes stable at the current rung: CPU ≤65 °C without pauses, battery ≤40 °C, H60 within 10% for the last 10 minutes, no battery % fall while charging. Step up at most one rung per dwell. Never jump two rungs, never step up immediately after a pause.

**Dwell guard (anti-flapping).** After any step-down, GCSPC holds the new rung for 30 minutes regardless of temperature falling quickly, unless a hard safety pause fires. This prevents the limit cycle the naive controller exhibits (pause → cool → full load → pause). The dwell value is a reported parameter; sensitivity to 15 vs 30 vs 60 minutes is a pre-registered secondary analysis (§7.5), not a post-hoc tuning exercise.

### 5.4 Governor-probe integration

GCSPC probes, once per session, and logs the result:

1. **Sustained Performance Mode.** Call `isSustainedPerformanceModeSupported()`; if true, A/B test a run with it on vs off (see §7) and, if it wins sustained hashrate, enable it for subsequent runs and report that it was on. If false/unsupported on the NARZO, log "unsupported" and proceed without it. Android's own documentation motivates this probe: normal max clocks may hold high performance for minutes then throttle by 30 minutes, while sustained mode may hold a lower but stable level for the whole period. GCSPC does not assume this holds for mining; it tests it.
2. **uclamp probe.** Attempt `sched_setattr()` with a uclamp hint once, log EPERM vs success. Ordinary apps are expected to lack CAP_SYS_NICE; if blocked, GCSPC closes the uclamp line and does not retry it every run. If, unexpectedly, it succeeds, its effect is A/B tested as a separate variant and reported as such, not folded silently into the main result.
3. **Frequency observation.** Read `scaling_cur_freq` per cluster (read-only) during runs to corroborate governor clamping vs app pausing. This is diagnostic, not control: GCSPC never writes a frequency.

These probes are what "works with the governor" means operationally: use the governor's own sustained API if the platform offers it, test privileged hints once and respect a denial, and observe frequency rather than fighting it.

### 5.5 Affinity and dataset discipline (prerequisites, not the invention)

GCSPC assumes, and the experiment verifies, two prerequisites already in the Anvil fork design:

- **Dynamic big-core discovery + `affine_to_cpu`.** Enumerate cpu0..cpu7 from sysfs, group by `cpuinfo_max_freq` (2.6 GHz = A78 big, 2.0 GHz = A55 little), emit affinity big-first. If discovery fails, fall back to unpinned and log "affinity OFF" — never guess indices, never claim big-core mining in that build.
- **Dataset/VM preservation across pause.** Pause keeps the process alive, preserving the 2,080 MiB dataset, JIT programs, VMs, and cached algo-perf. Stop destroys them and forces a multi-minute rebuild. GCSPC therefore pauses, never stops, on thermal step-down; Stop is reserved for user action or a safety stop (§8).

Without these, GCSPC's step-down would be confounded by dataset rebuild time and scheduler migration. The experiment (§7) therefore includes a prerequisite check: affinity verified per thread (field 39 of `/proc/<pid>/task/<tid>/stat`), dataset built once, JIT on, hardware AES on, full mode on — or the run is invalid.

### 5.6 Control pseudocode (for implementation and review)

```
state = COOL; rung = B0; dwell_until = now
loop every 10 s:
  update estimator (T_cpu, dT/dt, H60, dH/dt, T_bat, reject %, pauses)
  if hard safety pause fired: state = PAUSED; log reason; continue  # never override
  if now < dwell_until and state != NEAR-PAUSE-critical: hold rung; continue
  if reactive trigger fires: rung -= 1; dwell_until = now + 30 min; log trigger; continue
  if predictive trigger fires (model projects pause within dwell, lower rung T_eq safe):
      rung -= 1; dwell_until = now + 30 min; log projection + model params; continue
  if stable 30 min at rung and rung < max: rung += 1; dwell_until = now + 30 min; log
  else: hold rung
```

Every decision is logged with timestamp, state, model parameters, and trigger type, so the experiment can be audited and the controller's decisions can be replayed from the log.

## 6. Theoretical justification

### 6.1 Why sustained, not peak, is the right objective

Total accepted work over a session is ∫ H(t) dt over time when mining, plus zero during pauses, minus rejected shares. A controller that maximises peak H(t) at t = 0 but spends 30% of the session paused or clamped has lower ∫ H(t) dt than a controller with a lower peak and no pauses. This is arithmetic, not a tuning preference. Any paper or UI that reports only peak or first-minute hashrate is therefore measuring the wrong objective for earnings.

### 6.2 Why predictive step-down can beat reactive pause (sketch)

Consider a two-rung system: high rung H_high with equilibrium T_eq(high) above the pause point, low rung H_low with T_eq(low) below it. Reactive control runs high until pause, then zero, then high again: its average is a time-weighted mix of H_high, clamped H, and zero, with the mix set by thermal mass. Predictive control runs high until the model projects a pause, then runs low continuously: its average is a mix of H_high (brief) and H_low (sustained), with no zero time and no clamped time. Predictive wins iff the zero + clamped time it avoids outweighs (H_high − H_low) over the dwell. This condition is testable from the trace: it holds when pause/clamp time under reactive control exceeds (H_high − H_low)/H_high × dwell, approximately. GCSPC does not assume this condition holds; the experiment measures both sides.

### 6.3 Why dwell guards prevent limit cycles (stability sketch)

Without a dwell, a controller that steps down when hot and steps up when cool will oscillate with period set by sensor delay + thermal time constant, spending much of its time in transition. With a dwell longer than the thermal time constant τ(r) (measured, typically minutes, not seconds), the system reaches near-equilibrium at the new rung before re-evaluating, so the decision is based on steady state, not transient. This is the standard hysteresis + dwell argument from control theory, applied here with a learned τ(r) rather than a guessed constant. Formal stability proof is out of scope for this draft; the experiment's pause count and rung-transition count are the empirical stability metrics (see §7).

### 6.4 Why the governor cannot be bypassed, and why that is fine

An unrooted app cannot set DVFS, cannot write cgroups, and cannot load a scheduler. GCSPC therefore treats the governor as an environment, not an actuator: it observes frequency, it uses the platform's sustained-performance API if offered, and it controls the only actuator it has — load (thread count + affinity) and pause. This is a constraint that makes the result *more* general, not less: if GCSPC works on a stock phone, it works on any stock phone with the same API surface, without root, warranty, or banking breakage.

## 7. Experiment design

### 7.1 Pre-registration

Before any GCSPC run, pre-register in a dated file: ladder, dwell (30 min), estimator parameters (smoothing α, slope windows), decision metric (30-minute sustained 60-second hashrate, accepted-share-backed), primary comparison (GCSPC vs best fixed rung vs naive threshold-pause), and falsification criteria (§7.6). Any change after seeing data is reported as a post-hoc change, not as a pre-registered result.

### 7.2 Conditions (identical for every arm)

- Phone: NARZO 70 Pro 5G, Dimensity 7050, same charger and cable for all runs, plugged in throughout, charging-only ON.
- Network: one network for the whole experiment (Jio mobile data *or* Wi-Fi, recorded at the top; never switch mid-run or between arms in the same session).
- Room: same room, similar temperature (recorded per run; "cool / warm" if no thermometer, consistently).
- Phone state: case off, hard ventilated surface, not sun/bed/pillow/pocket, screen off except for readings, other heavy apps closed, no VPN, no second miner, no heavy use during a run. If a run is interrupted by a call or heavy use, it is invalid (§7.5).
- Safety: CPU pause 65 °C / resume 55 °C, battery pause 40 °C / resume 37 °C, unchanged for all arms. First-start algo calibration done once before the experiment; algo-perf cached and reused, not cleared between arms.
- Cool-down: ≥10 minutes stopped between runs, until CPU temperature returns near idle baseline; longer if still hot, with extra wait recorded.

### 7.3 Arms (three, plus prerequisite check)

1. **Prerequisite check (once):** affinity verified per thread, JIT on, hardware AES on, full mode on, dataset built once, worker present on MoneroOcean. If any fails, fix it before the experiment; do not run arms on a broken baseline.
2. **Arm A — Best fixed rung.** Run each ladder rung (B0–B4) for 30 minutes, cool-down between, pick the rung with highest sustained hashrate under §7.4 rules. That rung is Arm A's setting for the comparison. (If the ladder itself is the experiment, Arm A is the ladder winner, reported as such.)
3. **Arm B — Naive threshold-pause.** Fixed at the Arm A rung, with only hard pauses (65 °C / 40 °C) and resume at 55 °C / 37 °C, no predictive step-down, no dwell beyond resume hysteresis. This is the current Anvil behaviour.
4. **Arm C — GCSPC.** Same starting rung as Arm A, with predictive step-down, dwell guard, learned per-rung model, and governor probes as in §5. Sustained-mode on/off and uclamp success/failure are reported as variants, not hidden: if sustained mode is on for Arm C, it is also tested on Arm A/B as a separate factor, or the comparison is labelled "GCSPC + sustained mode vs fixed without it" — never a confounded comparison presented as a pure GCSPC effect.

Each arm is run at least 3 times (3 × 30 minutes + cool-downs), on different days if possible, to capture room/charger variation. Order is randomised or counterbalanced across days to avoid a morning-cool vs evening-hot confound. Total time is therefore ~3 arms × 3 repeats × 30 minutes ≈ 4.5 hours of mining plus cool-downs; it may be split across sessions only if conditions are identical and the split is recorded.

### 7.4 Measurements (every run)

- 60-second hashrate at 5, 15, 30 minutes; 30-minute sustained average (decision metric); peak 60-second hashrate (reported, never used as decision).
- Accepted shares start → end (difference), rejected start → end, reject %.
- CPU temp max, battery temp max, battery % start → end (change while charging; a fall is a flag).
- Pause count + reasons, rung-transition count, time at zero hashrate, time clamped (H60 ≥20% below cool peak for ≥10 min).
- Pool algo in log per run (expected rx/0; record exactly as shown).
- MoneroOcean dashboard: worker present, speed >0, accepted shares increasing, pending XMR start → end (session-level check, not a per-run decision metric).
- GCSPC log (Arm C only): every state change, trigger type (reactive vs predictive), model parameters (T_eq, τ per rung), dwell holds.
- Room temp, charger, network, case on/off, sustained-mode on/off, uclamp probe result.

A run missing any field is incomplete and is reported as such, not averaged in silently.

### 7.5 Analysis

- Primary outcome: 30-minute sustained, accepted-share-backed hashrate, mean across repeats per arm, with standard deviation and range. Report all repeats, not just the best.
- Secondary outcomes: pause count, time at zero, reject %, max temps, battery % change, rung-transition count.
- Comparison: GCSPC vs best fixed vs naive pause, pairwise, with the *same* starting rung and conditions. If GCSPC's starting rung differs, the comparison is confounded and must be labelled as such.
- Sensitivity (pre-registered secondary): dwell 15 vs 30 vs 60 minutes; smoothing α variations; sustained-mode on vs off. Each sensitivity is a separate, labelled analysis, not a search for a winning parameter to report as primary.
- Invalid runs (§7.5 in the test protocol, adapted here): network switch mid-run, unplug, heavy app/call, covered/hot placement, wallet change, missing accepted/rejected totals — discard and redo, with reason recorded. Do not silently drop a bad run that makes a setting look good or bad.

### 7.6 Falsification criteria (decide before seeing data)

GCSPC's hypothesis H1 is **rejected** if any of the following holds on the primary outcome, across repeats:

- (F1) GCSPC's mean sustained hashrate is not higher than both Arm A and Arm B by more than run-to-run noise (defined pre-registration as: difference smaller than the pooled standard deviation across repeats, or overlapping ranges with no consistent direction across repeats).
- (F2) GCSPC has more pauses, or more time at zero hashrate, than Arm B.
- (F3) GCSPC's reject % exceeds Arm A/B by >2 percentage points consistently, suggesting its step-down/step-up transitions lose shares.
- (F4) GCSPC's per-rung thermal model residuals are so large that its projections are no better than chance (projected pause within dwell vs actual pause: accuracy not better than the base rate of pauses under Arm B).

If H1 is rejected, the paper must report it as rejected, with the data, and must not rescue it by reporting peak hashrate, first-minute hashrate, or a single best run. A null result is a valid contribution: it shows that, on this phone, predictive step-down does not beat a well-chosen fixed rung, and future work should look elsewhere (JIT, memory path, or a different coin — not another threshold tweak).

H2 is rejected if GCSPC's peak exceeds the fixed control's peak (suggesting a different mechanism) or if its time at zero/clamped is not lower despite a higher sustained average (suggesting the gain came from elsewhere, e.g. a different starting rung or sustained mode).

H3 is rejected if learned T_eq(r) values are indistinguishable across rungs (within measurement noise) or if they do not transfer even across days on the same phone under similar conditions (suggesting the model is fitting noise, not thermal physics).

### 7.7 What this experiment does NOT prove

- It does not prove a $/month figure. A $/month figure needs live price, network difficulty, and pending XMR over days, not a 30-minute hashrate, and must be labelled as an estimate at calculation time, not a promise.
- It does not prove a payout. Final payout proof is separate: an on-chain payment into the user's own wallet at pool threshold, with txid, amount, and date from the wallet itself — not an app balance.
- It does not prove GCSPC generalises to other phones, other SoCs, or other workloads. Generalisation requires the same experiment on other handsets, reported separately.

## 8. Limitations and safety

**Limitations.**
- Single phone, single SoC, single workload (RandomX rx/0), single pool (MoneroOcean). No claim to other hardware or coins.
- First-order thermal model: it ignores governor non-linearity, skin-temperature feedback, charger heating, and ambient drift. Its residuals are part of the result, not hidden.
- Estimator parameters (smoothing, slope windows, dwell) are chosen before the experiment but are not proven optimal; sensitivity analysis (§7.5) bounds, but does not eliminate, this choice.
- Sustained-mode and uclamp availability are handset- and vendor-dependent; a "not supported" result on the NARZO is a valid finding, not a failure to implement.
- No formal stability proof is offered in this draft; stability is assessed empirically via pause count, transition count, and sustained average.
- RandomX parameters (dataset, scratchpad) are consensus-fixed; GCSPC does not and must not change them, shrink them, or substitute a different hash. Any such change would mine a different algorithm and be rejected by the pool.

**Safety (non-negotiable, for any implementation).**
- Charging-only ON throughout. Mining on battery is excluded from this experiment.
- Hard pauses unchanged: CPU 65 °C / resume 55 °C, battery 40 °C / resume 37 °C. GCSPC never raises, disables, or overrides them. Its predictive step-down is *additional* control *below* those limits, not a replacement for them.
- No root, no sysfs writes, no governor writes, no cgroup writes, no thermal-HAL disabling, no overclocking, no undervolting.
- No seed, private key, mnemonic, or password handling at any layer. Only the public receiving address is sent to the pool. Wallet UI rules from the Anvil wallet design apply unchanged (masked display, fill-only paste/scan, validation in Wallet.isValid()).
- Stop immediately, regardless of arm, if: battery repeatedly >40 °C, CPU repeatedly pausing at 65 °C with no stable run, phone too hot to hold, battery % falling while charging on consecutive runs, battery swelling, unusual smell, or any physical concern. Record the stop, cool fully, do not restart at a higher rung to "push through".
- Battery wear: even within limits, sustained mining accelerates battery ageing. The experiment's battery % and temperature data are part of the cost accounting, not externalised. A controller that wins sustained hashrate but destroys battery life faster is not a net win for a primary phone; that trade-off must be stated in any future $/month or net-value discussion.

## 9. Future work (if H1 is not rejected)

1. Learned dwell: adapt dwell to measured τ(r) per rung, rather than a fixed 30 minutes, with the same falsification criteria.
2. Multi-objective control: add battery-health cost (temperature × time at high charge) to the objective, so the controller can trade a small sustained loss for a large battery-life gain, with the trade-off made explicit.
3. Cross-phone validation: same protocol on at least two other big.LITTLE handsets, with per-phone calibration, to test whether GCSPC's *method* generalises even when its *parameters* do not.
4. JIT-aware control: if future work shows JIT compile time per hash is a measurable share of total time, add compile-pipeline state to the estimator (see the JIT frontier report) — only with the same validity proof (hash-sum + multi-threaded pool acceptance).
5. Pool-aware control: if MoneroOcean assigns a non-rx/0 algo on a future run, test whether GCSPC's rung choice should change by algo (different memory/compute mix), with dashboard XMR/day as the metric, not calibration H/s.

If H1 *is* rejected, future work should pivot, in order: (a) verify JIT + hardware AES + full mode on-device (a silent fallback costs far more than any controller), (b) test the memory path (prefetch, THP probe, dataset persistence), (c) test compiler A/B (target flags, ThinLTO, PGO) — each with the same sustained, accepted-share-backed protocol — before inventing another controller.

## 10. References (transfer sources, not mining results)

- Smejkal, Khasanov, Castrillon, Härtig, HARP, ACM Middleware '25, pp. 270–284, DOI 10.1145/3721462.3770774.
- Liang, Wu, Wang, Xie, FBHS, IEEE Trans. Sustainable Computing, vol. 11, pp. 516–529, Sept–Oct 2026.
- Jeon, Kim, Lee, Cha, Phoenix, ACM Trans. Embedded Comput. Syst., vol. 25, no. 2, 2026, DOI 10.1145/3793860.
- Kim, Bin, Ha, Lee, Chong, zTT, ACM MobiSys 2021 (Best Paper).
- Samadi, Yadollahi, Shah-Mansouri, iDEAS, IEEE Access, vol. 13, pp. 200711–200724, 2025, DOI 10.1109/access.2025.3636995.
- Marasini & Zhou, Sustainable Edge Vision via Empirically Calibrated DVFS, arXiv:2609.04705 (preprint).
- tevador RandomX documentation: configuration, architecture, performance optimisation (project docs, not peer-reviewed).
- XMRig changelog, v6.21–v6.26 (ARM64 JIT, A64 instruction selection, ARM64 fixes).
- Anvil fork tree, ~/workspace/anvil-miner: README, docs/BUILD-XMRIG.md, docs/DIMENSITY7050-CONFIG.md, docs/WALLET-CONNECTION-UI.md, docs/THERMAL-POWER-SUSTAINED.md, docs/ALGORITHM-LEVEL-DESIGN.md, docs/FRONTIER-LOWLEVEL-A.md, docs/FRONTIER-JIT-ARM64.md, docs/FRONTIER-CACHE-MEMORY-DATASET.md (design reports, not measured Dimensity results).
- VoltMiner phone benchmarks, June 2026 (blog, not peer-reviewed): Helio G99 and Snapdragon 865 ladders, MoneroOcean rx/0 assignment.

---

**Author note for the parent agent:** This is an invention paper draft, not a measured result. Do not quote any gain from it to the user as a fact. The next step is implementation of GCSPC in the Anvil fork (affinity + estimator + dwell + probes + logging, safety pauses unchanged) followed by the §7 experiment on the NARZO 70 Pro 5G, with pre-registration before the first GCSPC run. If the experiment rejects H1, report the rejection plainly.
