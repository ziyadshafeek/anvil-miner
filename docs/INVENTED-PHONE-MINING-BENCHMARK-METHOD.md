# A Reproducible Benchmark Method for Phone CPU Mining at Low Level

## A Method Invention, Not a Result Claim

**Scope:** Phone mining only. Low level only.
**Target device class:** One Android phone, ARM64, exemplified by the NARZO 70 Pro 5G,
MediaTek Dimensity 7050 — 2× Cortex-A78 at 2.6 GHz + 6× Cortex-A55 at 2.0 GHz.
**Miner class:** Anvil fork of XMRig, RandomX, direct to MoneroOcean,
pool `gulf.moneroocean.stream:20032`, TLS on, donate-level 0.
**Status of this document:** This is a *method*. It contains no measured hashrate,
no earnings figure, and no claim that any technique works on this phone.
Every number that would normally appear in a results table is left as a blank
to be filled only after the protocol below is run. Any paper, report, or
message that fills those blanks without running this protocol is not using
this method.

---

## Abstract

Phone mining results in blogs, app stores, and vendor pages are usually
unreproducible. They report a single burst hashrate, from one run, with no
controls for heat, thread placement, execution mode, or pool validity.
A phone that bursts at a high rate for one minute and then throttles for
twenty minutes can look faster than a slower, steady phone, while earning
less. A thread *count* can be mistaken for thread *placement*. An
application-reported hashrate can be mistaken for work a pool actually
accepted.

This paper invents a single, pre-registered, low-level benchmark method
for phone CPU mining that separates those four confusions by design:

1. **Burst vs sustained** — by defining sustained speed before data collection,
   and by measuring it over a fixed 30-minute window, not by taking a peak.
2. **Affinity vs count** — by treating thread count and core placement as
   two separate factors in a factorial design, with an explicit unpinned
   control for every pinned condition.
3. **JIT on vs off** — by requiring a log-verified execution-mode check and
   a hash-sum verification before any speed figure is accepted.
4. **App hashrate vs pool-accepted work** — by requiring a five-level
   validity hierarchy, ending in pool-dashboard evidence, before any
   hashrate is called mining.

The method adds the missing academic controls: pre-registration of the
decision rule, repeat counts justified by a power calculation, variance
and confidence-interval reporting, a falsification list, and a threats-to-
validity section. It is designed so that a negative result — "no difference
found" — is a publishable, useful result, not a failed experiment.

**Keywords:** RandomX, ARM64, phone mining, benchmarking, reproducibility,
big.LITTLE, JIT, thermal throttling, pre-registration.

---

## 1. Problem

### 1.1 What is broken in current phone-mining evidence

Four recurring defects, each observed in prior phone-mining material:

- **Peak reporting.** A 10-second hashrate is reported as "the hashrate".
  On a phone, the first minute is cool. The thirtieth minute is not.
- **Count/placement confusion.** "25% = 2 threads" is reported as
  "big cores only", when the operating system may place those two threads
  anywhere. Count and placement are different variables.
- **Mode silence.** A miner can silently fall back from JIT + full-memory
  mode to interpreter or light mode. Speed then collapses, but the app
  still shows a number. Without a mode check, the number is uninterpretable.
- **App-only proof.** An application can report hashes that a pool never
  accepts — wrong rounding, wrong algorithm, wrong wallet, dead pool.
  App hashrate alone is not mining.

### 1.2 Research questions

- **RQ1 (sustained):** Which thread configuration gives the highest
  *sustained* 30-minute hashrate on this phone, at safe temperatures,
  without repeated pauses?
- **RQ2 (placement):** Does core affinity change sustained hashrate
  independently of thread count?
- **RQ3 (mode):** Is JIT + full-memory mode actually active in the build
  under test, and does a mode fallback change accepted shares?
- **RQ4 (validity):** Does application-reported hashrate correspond to
  pool-accepted shares and to pool-dashboard evidence?

### 1.3 Hypotheses, stated to be falsified

Pre-register these before any run. Do not rewrite them after seeing data.

- **H1:** Sustained hashrate differs by thread configuration by more than
  the minimum meaningful difference (see §5.3).
- **H2:** At the same thread count, pinned big-core placement differs
  from unpinned placement in sustained hashrate.
- **H3:** JIT + full-memory mode is active in the test build, verified by
  log and hash sum.
- **H4:** Application hashrate, accepted shares, and dashboard hashrate
  agree within pre-set tolerance (see §4.4); disagreement falsifies
  the run, not the pool.

The null hypothesis for H1 and H2 is: no difference beyond measurement
noise. The method is designed to *accept* the null cleanly if that is
what the data show.

---

## 2. Method Overview

### 2.1 Design type

A **within-device, repeated-measures, factorial experiment with a
pre-registered decision rule**. "Within-device" is essential: phones differ
in silicon, battery age, and thermal paste. Comparing one phone's big-core
run to another phone's all-core run proves nothing. Every comparison in
this method is the *same phone, same day conditions, same build*,
differing only in the factor under test.

### 2.2 Independent variables

| Factor | Levels | Notes |
|---|---|---|
| Thread count | 2, 3, 4, 6, 8 | 3 is only meaningful with affinity; see §3.2 |
| Core placement | Unpinned; big-only; big-first | Unpinned is the control. Big-only exists only for 2 threads |
| Execution mode | JIT + full; interpreter (diagnostic only); light (diagnostic only) | Diagnostic modes are for RQ3, never for mining claims |
| Prefetch mode | As exposed by the pinned fork; default first | One variable at a time; see §3.4 |
| Compiler variant | Baseline; one flag change at a time | See §3.4; never combine untested flags |

### 2.3 Dependent variables (primary in bold)

- **Sustained 60-second hashrate, 30-minute average (PRIMARY).**
  Defined in §4.1. This, and only this, decides the winner.
- 60-second hashrate at 5, 15, and 30 minutes.
- Accepted shares, rejected shares, reject percentage per rung.
- CPU temperature maximum, battery temperature maximum.
- Battery percentage change while charging.
- Pause count and pause reasons.
- Pool-assigned algorithm from the log.
- MoneroOcean dashboard: worker present, dashboard hashrate, pending XMR
  change over the session.

### 2.4 Confounders and how each is controlled

| Confounder | Control in this method |
|---|---|
| Heat carry-over | 10-minute minimum cool-down between rungs, to near idle temperature, recorded |
| Charger | Same charger and cable for every rung, recorded |
| Network | Same network for every rung; no Wi-Fi/mobile switch mid-session, recorded |
| Ambient temperature | Recorded per rung; rungs compared only within similar ambient bands; a hot-room rung is not compared to a cool-room rung without noting it |
| Other apps | All heavy apps closed; no video, game, download, call, or second miner during a rung |
| Screen | Screen off except for brief readings |
| Battery state | Charging-only ON throughout; a rung that starts below 20% is invalid |
| Dataset rebuild | Dataset built once per key; pause (process alive) preferred over Stop; rebuilds recorded |
| Algorithm calibration | First-start calibration once, cached; never cleared between rungs |
| Order effect | Rungs run lowest first, then a repeat of the lowest rung at the end as a drift control (see §3.3) |
| Operator bias | Decision rule pre-registered (§5); operator does not choose the winner by eye |

### 2.5 What is NOT varied

Safety limits are fixed, not factors. Charging-only ON. CPU pause 65 °C,
resume 55 °C. Battery pause 40 °C, resume 37 °C. No rooting. No forced
huge pages. No disabling of pauses. No seed, private key, or password
is ever entered, stored, logged, or transmitted. Only the public
receiving address is sent to the pool. These are ethical and safety
constraints, not tuning knobs. An experiment that disables them to
"see the true maximum" is outside this method.

---

## 3. Experiment Design

### 3.1 Session structure

One session = one build + one charger + one network + one ambient band.

1. **Setup (once per session).** Record the full environment (§6).
   Verify wallet (public address only), worker name, charging-only ON,
   safety limits ON, build commit and binary SHA.
2. **Calibration (once).** First-start algorithm calibration, if not
   already cached. Record duration and pool-assigned algorithm.
3. **Idle baseline (5 minutes).** App closed, charger on. Record CPU
   temperature, battery temperature, battery percentage.
4. **Rungs (30 minutes each).** In the order in §3.3, with cool-downs.
5. **Drift control.** Repeat the lowest rung after the highest rung.
6. **Dashboard check.** Record dashboard evidence for the session.
7. **Data freeze.** Raw data are copied to the archive format in §6
   before any analysis. Analysis after freezing may not add, drop, or
   re-label a rung without a written amendment.

Total time per full session, including cool-downs: about 3.5–4 hours.
A session may be split across days only if charger, network, build,
and wallet are identical and the split is recorded. Otherwise it is a
new session, not a continuation.

### 3.2 Factorial design: affinity vs count

This is the method's central invention. Do not collapse it into a
single "threads" slider.

**Design matrix (affinity patch present):**

| Rung | Threads | Placement |
|---|---|---|
| B0 | 2 | Big cores only, affine |
| B1 | 3 | 2 big + 1 little, affine |
| B2 | 4 | 2 big + 2 little, affine |
| B3 | 6 | 2 big + 4 little, affine |
| B4 | 8 | All cores, affine |

**Control matrix (same counts, unpinned):**

| Control | Threads | Placement |
|---|---|---|
| C2 | 2 | Unpinned |
| C4 | 4 | Unpinned |
| C8 | 8 | Unpinned |

**Comparison that answers RQ2:** B0 vs C2, B2 vs C4, B4 vs C8.
If B0 and C2 are equal within tolerance, affinity adds nothing at
2 threads on this phone. That is a valid, publishable finding.
If B4 is worse than C8, or better, that too is a finding — but only
if both were run under identical conditions in the same session.

**If the affinity patch is absent:** the affine matrix cannot be run.
Run only C2, C4, C6, C8, label every one "unpinned, not big-only",
and do not answer RQ2. Do not rename an unpinned run as a placement
result. The absence of the patch is itself a reported limitation.

**Big-core discovery (must precede any affine run):**
Do not hardcode CPU indices. On the phone, record:

- `cpuinfo_max_freq` per CPU, and the frequency groups.
- Cache sizes per CPU, if readable.
- `Features` per core, especially `aes`, from `/proc/cpuinfo`.

The 2.6 GHz group is the big group; the 2.0 GHz group is the little
group, on this device class. If frequencies are equal, or sysfs is
unreadable, affinity is OFF for that session and the fact is recorded.

### 3.3 Order and repeats

**Order:** B0, B1, B2, B3, B4, then controls C2, C4, C8 in a second
session or after a full cool-down, then repeat B0 as drift control.
Lowest first prevents heat from a high rung unfairly penalising a
later low rung.

**Repeats (pre-registered):**
- Minimum: **3 repeats per rung** for the primary decision.
- Preferred: **5 repeats per rung** if time allows.
- A rung with fewer than 3 valid repeats cannot win. It is reported
  as "insufficient repeats", not as a winner or loser.

**Why 3–5, not 1:** A single run cannot separate a real 10% difference
from thermal drift, network jitter, or a single throttling event.
Section 5 gives the power calculation that justifies 3 as a floor and
5 as the preferred number for the effect size this method targets.
If a pilot shows higher variance than assumed, the repeat count must
rise — see §5.2. Do not keep the repeat count low because it is
convenient.

**Invalid runs:** A rung is discarded and repeated (not averaged in)
if any of §7.1 applies. Every discarded rung is listed with its reason.
Silent dropping is a protocol violation.

### 3.4 One variable at a time

After the thread/placement decision, test other low-level variables
in separate sessions, one change per session:

- Prefetch mode: default, then each exposed mode, same ladder rung
  (the winner from §3.2) only.
- Compiler variant: baseline, then one flag change, same rung only.
- Sustained Performance Mode, if supported: on vs off, same rung only.
- uclamp attempt: once, log success or permission error, close if
  blocked. Do not treat a blocked call as a result.

Never combine a new prefetch mode, a new compiler flag, and a new
thread count in one run and attribute the result to one of them.

### 3.5 Execution-mode verification (RQ3)

Before any speed run in a session, verify and record:

1. **JIT status** from the native log: on or off.
2. **Hardware AES** from the log and from `/proc/cpuinfo` Features.
3. **Memory mode:** full (dataset built, ~2,080 MiB) or light (cache
   only, ~256 MiB).
4. **Hash-sum verification:** the benchmark hash sum for a fixed seed
   matches the reference for the pinned fork. A mismatch stops the
   session. Speed without a matching hash sum is not mining.
5. **Huge pages:** granted or not, from `/proc/<pid>/smaps`
   `AnonHugePages`, if readable. Expected on unrooted Android: not
   granted. Record the fact, do not force it.

Diagnostic interpreter/light runs, if done, are labelled diagnostic
in every table. They answer "what does fallback cost?", never
"what does this miner earn?".

---

## 4. Metrics

### 4.1 Primary metric: sustained hashrate

**Definition (pre-registered, do not change after seeing data):**

> Sustained hashrate for a rung = the mean of the 60-second hashrate
> readings taken in the last 10 minutes of the 30-minute rung,
> provided the rung had no repeated pauses (see §5.4).

- Use 60-second readings only. Never use a 10-second reading for a
  decision. A 10-second reading may be recorded for burst analysis,
  labelled "burst, not decision".
- If the app provides a session average, record it separately and
  state which figure was used for the decision. Do not mix them.
- **Burst metric (secondary, for RQ1 contrast):** the highest
  10-second reading in the first 5 minutes. Report burst and sustained
  side by side. The gap between them *is* a finding: it measures
  throttling.

### 4.2 Secondary metrics

- **Accepted shares:** difference between rung end and rung start.
- **Rejected shares:** same difference.
- **Reject percentage:** rejected difference ÷ (accepted + rejected
  difference) × 100. If both differences are zero, record "no shares",
  not 0%.
- **Temperature maxima:** CPU and battery, per rung.
- **Battery change:** end percentage minus start percentage, while
  charging. A fall while plugged in is a sustainability flag.
- **Pause count and reasons:** heat, battery, charging, network.
- **Pool algorithm:** exact log line, e.g. `new job … algo ___`.
- **Dashboard evidence:** worker present (yes/no), dashboard hashrate
  > 0 (yes/no), accepted shares increasing (yes/no), pending XMR
  change over the session.

### 4.3 Derived metrics (never primary)

- **Efficiency proxy:** sustained hashrate per thread, and sustained
  hashrate per pause-free minute. These describe, they do not decide.
- **Thermal cost:** degrees risen per 100 H/s sustained, for
  description only.
- **Earnings estimate:** only after a full session, only from
  dashboard pending XMR over days, at live price and network
  difficulty at the time of calculation, labelled as an estimate at
  that date, never as a promise. A 30-minute hashrate alone must never
  be converted to a monthly figure in a results table.

### 4.4 Validity hierarchy (RQ4)

A rung's hashrate is accepted as mining evidence only if it passes
these levels in order. Failure at any level invalidates the rung's
speed claim, even if the app number looks good.

| Level | Check | Pass condition |
|---|---|---|
| 0 | App hashrate | A 60-second figure is shown |
| 1 | Hash sum | Benchmark hash sum matches reference for the pinned fork |
| 2 | Live shares | Accepted shares increase during the rung; reject % recorded |
| 3 | Dashboard worker | Worker with the recorded name appears, hashrate > 0, shares increasing |
| 4 | Dashboard pending | Pending XMR increases over the session (slow; session-level, not per-rung) |
| 5 | On-chain payout | A payment into the user's own wallet at pool threshold, with transaction ID, amount, and date from the wallet itself — long-term only, not required for a 30-minute rung |

Levels 0–3 are required for any rung claim. Level 4 is required for
any session claim. Level 5 is required for any payout claim. App
balance alone is never Level 5.

**Tolerance for RQ4:** App 60-second hashrate and dashboard hashrate
are expected to differ — different averaging windows. Record both,
do not force agreement. A disagreement is falsification only if
dashboard shares do not increase while app hashrate is high, or if
reject percentage is high while app hashrate is high. See §8.

---

## 5. Statistical Analysis Plan (Pre-registered)

### 5.1 Descriptive reporting (mandatory for every rung)

For every rung and control, report, across its valid repeats:

- n (number of valid repeats)
- Mean sustained hashrate
- Standard deviation
- Coefficient of variation (SD ÷ mean × 100%)
- Median, minimum, maximum
- 95% confidence interval for the mean
- Mean accepted shares, mean reject %, mean temperature maxima,
  mean pause count

A single mean with no spread is not a result under this method.
A rung with a high mean and a wide interval has not beaten a rung
with a slightly lower mean and a narrow interval — see §5.4.

### 5.2 Sample size and power (how 3–5 was chosen)

**Minimum meaningful difference (MMD):** 10% of sustained hashrate.
A difference smaller than 10% is treated as a tie for decision
purposes (§5.4), because it is smaller than typical thermal and
network variation on a phone, and because it does not change the
practical conclusion for the user.

**Pilot step (mandatory before the full run):**
Run the winner-candidate rung and one comparator rung 3 times each.
Estimate the within-rung standard deviation, s, of sustained hashrate
from those pilot repeats.

**Power formula (paired, same phone):**
For a paired comparison with target power 80% and two-sided
alpha 0.05, the approximate required number of pairs is:

> n ≈ ( (z_{0.975} + z_{0.80}) × s_d ÷ MMD )²

where s_d is the standard deviation of the *differences* between
paired repeats, z_{0.975} = 1.96, z_{0.80} = 0.84.

Worked form (no result claimed, formula only): if s_d were, for
illustration, 5% of mean hashrate and MMD is 10%, then
n ≈ ((1.96 + 0.84) × 5 ÷ 10)² ≈ 2.0, so 3 pairs is the floor.
If s_d were 10%, n ≈ 7.8, so 8 pairs would be required.
**Rule:** compute n from the pilot. If the computed n exceeds the
repeats already planned, add repeats until n is met, or report the
comparison as underpowered and do not claim a win. Do not lower
the MMD after seeing the pilot to make n smaller.

If repeats are not paired by session order (e.g. controls run on a
different day), use the unpaired form with pooled variance and
report that the pairing was lost. Paired is preferred and is the
default in §3.3, because order and cool-down are controlled within
a session.

### 5.3 Tests

- **Primary comparison (two rungs):** paired t-test if differences
  are approximately normal (Shapiro–Wilk, reported), otherwise
  Wilcoxon signed-rank test. Report test statistic, p-value,
  mean difference, and 95% confidence interval for the difference.
  A p-value alone, without the interval and effect size, is
  insufficient.
- **Multiple rungs:** repeated-measures ANOVA, or Friedman test if
  non-normal, followed by pairwise comparisons with Bonferroni
  correction. Report the correction. Uncorrected pairwise p-values
  across five rungs are not accepted.
- **Effect size:** Cohen's d_z for paired comparisons (mean
  difference ÷ SD of differences). Report it alongside every p-value.
- **Affinity question (RQ2):** the B-vs-C pairs in §3.2 are the
  pre-specified contrasts. Do not hunt for a significant pair
  after the fact among all possible pairs and report only that one.
- **Significance level:** alpha = 0.05, two-sided, pre-registered.
  "Trending towards significance" is not a finding.

### 5.4 Pre-registered decision rule (the winner rule)

Apply in this order, after data freeze, with no discretion:

1. **Eligibility:** Only rungs with ≥3 valid repeats, no repeated
   pauses (see below), and Levels 0–3 validity (§4.4) are candidates.
2. **Repeated pauses:** A rung that paused repeatedly — defined as
   ≥2 automatic pauses in 30 minutes, or a safety stop (§7.2) —
   is out, even if its early hashrate was highest.
3. **Highest sustained mean:** Among eligible rungs, the highest
   mean sustained hashrate is the provisional winner.
4. **Tie rule:** If the provisional winner beats the next rung by
   less than 10% sustained, and the confidence interval for the
   difference includes zero or the tie band, keep the *lower* rung
   (fewer threads, less heat, less wear). The lower rung is then
   the winner. This rule is pre-registered precisely because a
   small, noisy gain is not worth extra heat.
5. **Step-down triggers (override):** Step down one rung regardless
   of hashrate if reject % exceeds ~2–3% and is rising, battery
   percentage falls while charging, or pauses become frequent.
   Record which trigger fired.
6. **No winner:** If no rung is eligible, the result is
   "no stable rung found under these conditions". That is the
   result. Do not guess a winner.

### 5.5 Variance and uncertainty reporting

- Report run-to-run variance, not just mean. A method that hides
  variance hides throttling.
- Report ambient temperature and battery age band alongside
  variance, as possible variance sources.
- If the drift-control repeat (final B0) differs from the first
  B0 by more than the MMD, report session drift and do not trust
  single-session comparisons without a second session. Drift is
  data, not an excuse to drop the inconvenient repeat.

---

## 6. Reproducibility Checklist (Record Before Any Run)

Copy this checklist into the session header. A session without it
is not reproducible and its results are not accepted under this method.

- [ ] Device model, Android version, kernel version, security patch
  level (as shown on the phone, not assumed).
- [ ] App: fork repository, commit hash, build type (debug/release),
  native binary SHA-256, config JSON (pool, TLS, donate-level,
  huge-pages, asm, thread array template).
- [ ] Wallet: worker name (`AX-…` prefix only in reports; full
  address never printed in a report), pool, dashboard link method.
- [ ] Charger: wattage, cable type, same for all rungs (yes/no).
- [ ] Network: Jio mobile or Wi-Fi, same for all rungs (yes/no),
  VPN off (yes/no).
- [ ] Ambient temperature band per rung.
- [ ] Case on/off, surface type, screen-off rule followed (yes/no).
- [ ] Charging-only ON, CPU pause 65 °C / resume 55 °C,
  battery pause 40 °C / resume 37 °C (all confirmed, not assumed).
- [ ] Big-core indices found (or "affinity OFF, reason: ___").
- [ ] JIT on, hardware AES on, full mode, hash sum match (all
  confirmed, with log excerpts in the archive).
- [ ] Calibration: done once, cached, not cleared between rungs.
- [ ] Pre-registration: decision rule (§5.4), MMD (10%), alpha
  (0.05), repeat target, and session date recorded *before* the
  first rung. Ideally, hash the pre-registration text and record
  the hash in the session header.

**Raw data archive format (CSV, one row per reading):**

> session_id, rung, repeat, placement, threads, timestamp,
> hashrate_60s, hashrate_10s_burst, accepted_total, rejected_total,
> cpu_temp_c, battery_temp_c, battery_pct, pause_count,
> pool_algo, dashboard_worker_present, notes

Keep raw CSVs. Summary tables alone are not an archive. Analysis
code or spreadsheet formulas, if used, are archived with the CSVs.

---

## 7. Safety, Ethics, and Invalid Runs

### 7.1 Invalid runs — discard and repeat, never average in

Discard and repeat a rung if:

- Network switched mid-rung, or dropped beyond the app's grace
  period for a long stretch.
- Phone unplugged, or charging-only paused the run for most of it.
- A heavy app was opened, a long call happened, or the phone was
  used heavily during the rung.
- The phone was covered, in sun, or in a hot place for that rung only.
- The wallet address was changed mid-test.
- Accepted/rejected totals were not recorded at start and end.
- Execution mode fell back silently (JIT off, light mode) without
  the operator noticing until after the rung — the rung is invalid,
  and the fallback is reported as a build issue.

### 7.2 Safety stops — stop immediately, no matter which rung

Stop the test, unplug if needed, and let the phone cool if:

- Battery temperature repeatedly above 40 °C, or CPU repeatedly
  pausing at 65 °C with no stable run.
- Phone too hot to hold comfortably.
- Battery percentage falling while plugged in, rung after rung.
- Battery swelling, unusual smell, or any physical concern —
  stop, unplug, do not continue.
- Repeated pauses on every rung, even the lowest rung.

After a safety stop, record it, cool fully, and do not restart at
a higher rung to "push through". A safety stop is a result about
the phone's limit, not a failed test.

### 7.3 Ethics and privacy

- No seed phrase, private key, mnemonic, or wallet password is
  ever requested, entered, stored, logged, photographed, or shared.
  Only the public receiving address is used, and it is masked
  (first 6 + last 4) in every report.
- No other person's device, account, or network is used.
- No pool rule is bypassed, no hashrate is faked, no benchmark
  is spoofed, no multiple accounts or devices are used to inflate
  a result.
- Battery and thermal limits are safety constraints, not obstacles
  to be removed for a higher number.

---

## 8. Threats to Validity

### 8.1 Internal validity

- **Thermal history:** a rung after a hot rung starts hotter.
  Mitigation: cool-down to near idle, recorded; order lowest first.
  Residual risk: imperfect cool-down. Reported via drift control.
- **Network variation:** pool latency and job rate vary by time of
  day. Mitigation: same network, same session, accepted-share
  counts as a second check. Residual risk: acknowledged; a second
  session on a different day is the check.
- **Operator reading error:** brief screen-on readings can be
  misread. Mitigation: 60-second figure only, recorded at fixed
  times (5, 15, 30 minutes), with a second reading if ambiguous.
- **Dataset state:** a fresh dataset build vs a reused dataset
  changes early-run speed. Mitigation: build once, reuse, record
  rebuilds.

### 8.2 Construct validity

- **Does "hashrate" mean "mining"?** Only if Levels 1–3 (§4.4) pass.
  App hashrate without accepted shares is not the construct.
- **Does "big-core" mean "big-core"?** Only if indices were
  discovered from sysfs in that session. A label without discovery
  is not the construct.
- **Does "sustained" mean "sustained"?** Only under the §4.1
  definition (last-10-minutes mean of 60-second readings).
  A 5-minute mean is not sustained under this method.

### 8.3 External validity

- **One phone, one build, one pool.** Results generalise only to
  the same device class, same build, same pool, under similar
  thermal conditions. They do not generalise to other phones,
  other chips, desktop CPUs, or other coins, without a new run.
- **Season and room:** a winter cool-room result does not predict
  a summer hot-room result. Ambient band is part of the result.
- **Pool change:** if MoneroOcean changes difficulty, algorithm
  assignment, or payout rules, pending-XMR and payout conclusions
  must be re-measured. Hashrate conclusions are less affected,
  but dashboard conclusions are not.

### 8.4 Conclusion validity

- **Low power:** with only 3 repeats and high variance, a real
  10% difference may be missed. Mitigation: power calculation
  (§5.2), confidence intervals, and the explicit "underpowered,
  no claim" outcome.
- **Multiple comparisons:** five rungs invite false positives.
  Mitigation: pre-specified contrasts, Bonferroni correction,
  and the tie rule.
- **Fishing:** trying prefetch modes, compiler flags, and thread
  counts until one looks significant, then reporting only that
  one, is forbidden by pre-registration. Every tested variant is
  listed, winners and losers alike.

### 8.5 Reliability

- **Same operator, same phone, same protocol, different day:**
  expected to give the same winner within the tie band, if ambient
  and network are similar. A different winner on a repeat session
  is reported as instability, not hidden.
- **Different operator:** the test sheet in §9 is designed so a
  second operator can repeat the session from the sheet alone,
  without asking the first operator what they did.

---

## 9. Falsification Criteria

A claim made under this method is falsified — not merely weakened —
if any of the following occur. Falsification is a success of the
method, not a failure of the experimenter.

| Claim | Falsified if |
|---|---|
| "Big-core affinity improves sustained hashrate" | B-vs-C pairs (§3.2) show a mean difference < MMD, or the 95% CI for the difference includes zero, in ≥3 valid repeats per pair |
| "A higher rung is faster sustained" | The higher rung's sustained mean is lower, or it is ineligible due to repeated pauses, in the full session |
| "JIT is on in this build" | Log shows JIT off, or hash sum mismatches, or interpreter mode is detected in any run |
| "App hashrate equals mining" | App hashrate is high while accepted shares do not increase, or reject % is high, or dashboard worker is absent |
| "This configuration is stable" | Drift-control repeat differs from the first repeat of the same rung by more than MMD, with no recorded condition change to explain it |
| "Prefetch mode X is better" | Mode X does not beat default by ≥ MMD sustained, with a CI excluding zero, in ≥3 valid repeats |
| "Compiler flag Y is better" | Flag Y does not beat baseline by ≥ MMD sustained, with a CI excluding zero, in ≥3 valid repeats, or it breaks JIT, AES, TLS, or donate-level checks |
| "This phone earns $X/month" | Dashboard pending XMR over days does not support the figure at live price/difficulty, or the figure was derived from a 30-minute hashrate alone |
| "A light-algo hashrate is an earnings gain" | Pool-assigned algorithm is rx/0, or dashboard pending XMR does not rise faster on the light algo over days than on rx/0 |

If a claim is falsified, the report must say so in its abstract,
not only in a footnote. A falsified claim must not be re-presented
in a later report without new data from a new, pre-registered run.

---

## 10. Ready-to-Use Test Sheet

Copy this sheet for every session. Fill it during the run, not
afterwards from memory.

### 10.1 Session header

| Field | Value |
|---|---|
| Session ID / date | |
| Device / Android / kernel | |
| App commit / binary SHA-256 | |
| Config: pool / TLS / donate / huge-pages / asm | |
| Worker name (AX-… prefix only) | |
| Charger / cable | |
| Network (Jio / Wi-Fi, VPN off?) | |
| Ambient band | |
| Case on/off, surface | |
| Charging-only ON? CPU 65/55? Battery 40/37? | |
| Big-core indices found (or affinity OFF + reason) | |
| JIT on? Hardware AES on? Full mode? Hash sum match? | |
| Calibration done / cached? Pool algo after calibration? | |
| Pre-registration hash / date | |
| MMD / alpha / repeat target | |

### 10.2 Idle baseline

| Field | Value |
|---|---|
| 5 min idle, charger on, app closed | |
| CPU temp (°C) | |
| Battery temp (°C) | |
| Battery % | |

### 10.3 Per-rung sheet (copy one per rung × repeat)

| Field | Value |
|---|---|
| Rung / repeat | |
| Threads / placement (affine / unpinned) | |
| Prefetch / compiler variant (if any) | |
| Charger / network / room temp | |
| Start: accepted / rejected / battery % / CPU temp / battery temp | |
| 60s hashrate, 5 min | |
| 60s hashrate, 15 min | |
| 60s hashrate, 30 min | |
| 10s burst hashrate, first 5 min (for contrast only) | |
| Sustained average (last-10-min mean of 60s readings) | |
| End: accepted / rejected / battery % / CPU temp / battery temp | |
| Accepted difference / rejected difference / reject % | |
| CPU temp max / battery temp max | |
| Battery % change while charging | |
| Pauses, count + reasons | |
| Throttled? Yes / No, and how you knew | |
| Pool algo in log | |
| Dashboard: worker present? Dashboard hashrate > 0? Shares increasing? | |
| Valid? Yes / No, and reason if No | |

### 10.4 Summary table (fill only after data freeze)

| Rung | n | Mean sustained | SD | CV % | Median | Min | Max | 95% CI | Mean reject % | Mean pauses | Eligible? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| B0 | | | | | | | | | | | |
| B1 | | | | | | | | | | | |
| B2 | | | | | | | | | | | |
| B3 | | | | | | | | | | | |
| B4 | | | | | | | | | | | |
| C2 | | | | | | | | | | | |
| C4 | | | | | | | | | | | |
| C8 | | | | | | | | | | | |

### 10.5 Decision record (fill only after §5.4 is applied)

- Provisional winner (highest eligible sustained mean):
- Tie check: difference to next rung, CI, tie rule applied?
- Step-down triggers fired, if any:
- Final winner, in one sentence, with reason:
- Falsified claims, if any:
- Discarded rungs, with reasons:
- Safety stops, if any:
- Session drift (final B0 vs first B0):
- Dashboard session check (pending XMR start → end):
- Limitations (ambient, network, repeats, build):

### 10.6 Final short report template (use this, not a long essay)

1. Which network, charger, build, and room conditions were used.
2. Which rung won under §5.4, and why, in one or two sentences.
3. Winning rung: sustained hashrate (mean, SD, 95% CI, n),
   accepted/rejected and reject %, temperature maxima,
   battery change, pause count.
4. Pool algo in the log, and whether the worker appeared on
   MoneroOcean with speed above zero and accepted shares increasing.
5. Any rungs discarded, and why. Any safety stop, and what happened.
6. Any claim falsified under §9.
7. No monthly earnings figure, unless it comes from dashboard
   pending XMR over days at live price/difficulty, labelled as an
   estimate at that date.

---

## 11. What This Method Invents, in One Paragraph

Prior phone-mining evidence conflated four different things: a burst
with a sustained rate, a thread count with a core placement, a JIT
build with a fallback build, and an app number with pool-accepted
work. This method invents a single protocol that separates all four
by design — a pre-registered sustained metric, a factorial
affinity-vs-count design with an unpinned control, a log-and-hash-sum
mode gate, and a five-level validity hierarchy — plus a power-based
repeat rule, a tie rule that favours the cooler rung, and a
falsification list that makes a negative result count. It does not
invent a faster miner. It invents the test that would prove, or
disprove, that a miner is faster, on this phone, under stated
conditions, with stated uncertainty.

## 12. Limitations of This Method Itself

- It measures one phone well. It does not, by itself, generalise to
  other phones. A multi-phone version would need the same protocol
  run on each phone, with phone as a random effect in the analysis.
- It does not measure long-term earnings. Payout proof (Level 5)
  needs weeks to months at phone speed, and is outside a 30-minute
  rung by design.
- It cannot control pool-side changes (difficulty, algorithm
  assignment, payout rules) except by recording them.
- It relies on operator discipline for readings, cool-downs, and
  data freezing. Automation of readings (on-device logging to CSV,
  with timestamps) is recommended as a future improvement, and
  would strengthen, not replace, this method.
- It assumes the app under test honestly reports its own 60-second
  hashrate and share counts. If an app falsifies its own numbers,
  only Levels 3–5 (dashboard and on-chain) can catch it — which is
  why those levels are mandatory, not optional.

---

*No results are claimed in this document. All tables are blank by
design. Fill them only by running the protocol above, under the
safety limits in §2.5 and §7, with only a public receiving address,
never a seed, private key, or password.*
