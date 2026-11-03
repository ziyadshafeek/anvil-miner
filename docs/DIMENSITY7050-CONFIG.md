# Dimensity 7050 (NARZO 70 Pro 5G) — best, safest XMRig RandomX config for Anvil

Target phone: 2x Cortex-A78 @ 2.6 GHz (big) + 6x Cortex-A55 @ 2.0 GHz (little),
8 threads total in Android, ARM64, 6 nm, LPDDR5.
Anvil in this tree: v0.2.0, MoneroOcean XMRig fork v6.25.0-mo1,
default pool `gulf.moneroocean.stream:20032` (TLS), `donate-level: 0` on MoneroOcean,
charging-only ON by default, CPU pause 65 C, battery pause 40 C.

## Verdict first — is $10/month reachable on this phone alone?

**No. Not by any legitimate config change.**

Using the pasted research's own assumptions (network ~6.3 GH/s,
432 XMR/day emission, $545/XMR — variable, not a guarantee):

- $/H/s/month = 432 / 6,300,000,000 x 545 x 30 = **$0.00112**
- 400 H/s = $0.45/month, 500 H/s = $0.56, 600 H/s = $0.67, 750 H/s = $0.84
- $1/month needs ~892 H/s sustained. $10/month needs **~8,919 H/s sustained**.

That is roughly 15x to 22x this phone's realistic sustained rate, and
roughly 12x even the Snapdragon 865's 750 H/s in the Anvil benchmarks.
No thread count, affinity trick, algo switch, or 0% fee can manufacture
that. A 20% optimisation on 500 H/s is $0.56 -> $0.67, not $10.
Aim honestly at **$0.45-$0.70/month gross, before battery wear and power**,
with first MoneroOcean payout (0.003 XMR) in roughly 73-109 days at
400-600 H/s. If the phone sustains over 600 H/s cool, re-run the maths —
it still does not reach $10.

Do not budget on $10, do not run the phone at 100% on battery to chase it,
and do not trust any app or config that promises $10 from one phone.

## 1. Big-core-first thread scheduling

### Why 2 big cores alone gave 328 H/s on Helio G99, and 8 threads only 424 H/s

Helio G99 in `docs/BENCHMARKS.md`: 2x Cortex-A76 + 6x Cortex-A55.

| Threads | H/s | % of max |
|---|---|---|
| 2 (big only) | 328 | 77% |
| 4 | 385 | 91% |
| 8 (all) | 424 | 100% |

The 2 big cores do 164 H/s each. The other 6 cores together add only
96 H/s, about 16 H/s each — roughly 10x less per core. That is not a bug.
RandomX is memory-bandwidth-bound, not core-count-bound (see section 2),
and a little A55 at 2.0 GHz with a small private cache is a weak RandomX
core. Waking it also:

- shares the same memory bus and shared L3, so it steals bandwidth from
  the big cores;
- raises total power and heat, so the big cores throttle sooner;
- adds scheduler, dataset-access and TLB contention.

Snapdragon 865 proves the point the other way: 4 big cores gave 748 H/s,
8 threads gave 684 H/s, **-9%**. More threads can be slower.

For Dimensity 7050, which has only **2** big cores (not 4), the sweet spot
is therefore even narrower: 2 threads, possibly 2 big + 1-2 little if the
test ladder shows a real, sustained gain — never 8 threads by default.

### The current Anvil code cannot guarantee big-core-first

In this tree, `XMRigManager.writeConfig()` does:

- `threadCount = max(1, (totalCores * threadsPercent / 100).toInt())`
- every thread entry is `{"low_power_mode":false,"affine_to_cpu":false}`

So the UI steps map to thread *counts*, not to *which* cores:

| Anvil UI | Threads (8-core phone) | What the OS may do |
|---|---|---|
| 25% | 2 | any 2 cores — big, little, or mixed |
| 50% | 4 | any 4 cores |
| 75% | 6 | any 6 cores |
| 100% | 8 | all 8 cores |

"25% = big cores only" is **not** true in the current build. The OS
scheduler, thermal state, and other apps decide placement.

### Required code change for a true big-core config (do this before claiming it)

1. Discover the big cores on the actual phone first — do not hardcode
   CPU numbers from a blog. On the phone (or via adb):
   `cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`
   The 2 cores at 2.6 GHz are the A78s; the 6 at 2.0 GHz are the A55s.
   Record their `cpu0..cpu7` indices in the test table below.
2. Change the config generator to emit, for each thread,
   `{"low_power_mode":false,"affine_to_cpu":<index>}` with the big-core
   indices first, instead of `affine_to_cpu:false` for all threads.
3. Add a "Big cores only (2 threads, affine)" preset alongside the
   25/50/75/100% steps. Do not relabel 25% as big-only until affinity is
   actually on.

### Proposed thread ladder to test (in this order)

| Test | Threads | Affinity (once patched) | Purpose |
|---|---|---|---|
| B0 | 2 | 2 big cores only | **Expected best H/s per watt, and the safe default** |
| B1 | 3 | 2 big + 1 little | Does one little add net H/s after heat? |
| B2 | 4 | 2 big + 2 little | Anvil 50% count, but with big-first affinity |
| B3 | 6 | 2 big + 4 little | Anvil 75% count, for comparison only |
| B4 | 8 | all cores | Anvil 100% count, for comparison only — expect throttle |

Prediction, to be replaced by measured numbers:
B0 ~340-420 H/s cool, B2 ~400-500 H/s cool, B4 ~430-550 H/s cool,
all 10-30% lower sustained once throttled. If B1/B2 do not beat B0 by
at least 10% sustained over 30 minutes (not a 60-second burst), stay on B0.
The extra little-core heat is not worth a 3-5% burst gain.

## 2. RandomX dataset, huge pages, cache — why 100% CPU is usually NOT best

- **Dataset: 2,080 MiB, shared by all threads** (not 2 GiB per thread).
  Plus a 256 MiB cache. Full-mode mining needs that ~2.3 GiB resident.
  On a 6-8 GiB phone that is fine if other apps are closed, and a reason
  to mine charging-only with nothing else heavy running.
- **Scratchpad: 2 MiB per thread** (L1 16 KiB, L2 256 KiB, L3 2 MiB in
  RandomX's own hierarchy). XMRig's optimisation guide states the same
  rule: 256 KiB L2 and 2 MiB L3 per mining thread for full speed.
- **Dimensity 7050 shared L3 is small.** A third-party comparison table
  lists 1 MB L2 and 2 MB L3 for this SoC (treat as indicative, not a
  MediaTek datasheet quote). If that is right, even **one** thread's
  2 MiB scratchpad equals the whole shared L3, and 2 threads cannot both
  fit. Every extra thread then spills to LPDDR5 RAM, which is far slower
  than cache. That is the physical reason little cores add so little and
  why 8 threads can be slower than 4 on a bigger phone.
- **Huge pages: already off, and keep them off on this phone.**
  Anvil's template sets `"huge-pages": false`, and `docs/BENCHMARKS.md`
  states Android does not grant them. Do not root the phone, do not
  claim a huge-page gain, and do not set `huge-pages: true` expecting
  a desktop-style 1-50% boost — on Android it will simply not be granted
  and XMRig will fall back. The 1 GiB huge-page path is Linux-desktop only.
- **100% CPU fails three ways on a phone:**
  1. It wakes 6 weak little cores that add ~16 H/s each on the G99
     pattern while sharing the same bus and L3.
  2. It pushes CPU and battery past Anvil's pause points (65 C / 40 C)
     faster, so the miner pauses, cools, resumes, and pauses again —
     average H/s over an hour can be *lower* than a steady 2-thread run.
  3. It leaves no headroom for Android, the pool connection, or thermal
     management, so accepted-share latency and rejected shares rise.

Rule: pick the thread count with the best **sustained 60-second
hashrate over 30 minutes at a stable temperature**, not the best
10-second burst in the first minute.

## 3. MoneroOcean algorithm switching — does it actually help?

Yes, it exists, and no, it does not turn a phone into a $10 miner.

How it works: the MoneroOcean XMRig fork benchmarks every algorithm
the hardware can run, reports those `algo-perf` numbers to the pool,
the pool multiplies each algorithm's hashrate by that coin's
`price / difficulty`, sends the most profitable job, and converts any
altcoin proceeds to XMR for payout. Anvil preserves this: its config
keeps the algo-perf cache on disk across restarts, and a fresh start
benchmarks for a few minutes before settling.

What actually happened on phones, in Anvil's own benchmarks:

- Raw hashrate: `rx/arq` (ArQmA) ~9x RandomX, `cn-pico` (TurtleCoin)
  ~5x RandomX on the G99.
- After price/difficulty weighting, **both test phones were assigned
  `rx/0` (plain Monero) and held it**. Raw speed is not profit; those
  coins are worth a tiny fraction of Monero per hash.

Expected effect on $/day vs plain XMR on this phone:

| Case | Effect |
|---|---|
| Normal case (as measured) | ~0% — pool assigns rx/0, same as a RandomX-only pool |
| Altcoin spike case | Possibly a small, temporary uplift — unquantified, not budgetable |
| If you forced a light algo by hand | Worse $/day on the measured economics, despite a bigger H/s number |

Do not quote a light-algo H/s (e.g. 3,000 H/s on rx/arq) as earnings.
Always quote the pool-assigned algo (watch the log for
`new job ... algo XYZ`) and the MoneroOcean dashboard's pending XMR,
not the app's H/s alone. Keep algo-perf benchmarking enabled on first
start; do not hand-edit it to fake a faster algorithm — that would
misdirect the pool and is not a legitimate optimisation.

## 4. Thermal throttling, battery degradation, charging-only

Anvil defaults already in this tree — keep them, do not loosen them
to chase H/s:

- CPU pause: **65 C**, resume at 55 C (10 C hysteresis in ThermalMonitor).
- Battery pause: **40 C** (400 tenths in MiningService).
- Charging-only: **ON by default**. Unplugged = paused.
- 60-second no-internet grace, optional Wi-Fi-only and quiet hours.

Why these limits matter on this phone:

- Sustained CPU load on a sealed phone heats the battery as well as
  the SoC. Heat + high state of charge accelerates battery wear far
  more than the ~$0.50/month gross is worth.
- Throttling is not a cliff, it is a slowdown: as the SoC passes its
  thermal limit it drops big-core frequency, so a run that starts at
  500 H/s can settle at 350 H/s. A test that only records the first
  minute overstates the real rate.
- Mining on battery (not charging) drains the phone, heats it, and
  then Android will kill the foreground service anyway. Charging-only
  is a safety feature, not a limitation to bypass.

Safe operating rules for every test:

1. Phone on charger, back uncovered, case off if it traps heat,
   ambient ideally below 30 C, phone not in direct sun or under a pillow.
2. Stop the test immediately if battery stays above 40 C, CPU stays
   above 65 C with repeated pausing, the phone is too hot to hold
   comfortably, or battery % falls while plugged in (charger cannot
   keep up — that means the load is too high for that charger).
3. Never mine on the main phone's battery while travelling, and never
   disable the 65 C / 40 C pauses to "earn more". The pause *is* the
   protection.
4. Expect battery health cost even within limits. Treat $0.50/month
   gross as before wear — net value after a shortened battery life
   can easily be negative on a primary phone.

## 5. Legitimate "ingenious" techniques only

Allowed, and what each actually buys:

- **Big-core affinity** (section 1): puts work where it pays, avoids
  wasting bus bandwidth on little cores. Gain: best H/s per watt, maybe
  0-15% sustained vs unpinned 50%, not 10x.
- **Adaptive threads**: drop from B2 to B0 automatically when CPU temp
  approaches 65 C or 60s H/s falls 20% below the cool peak for 10 minutes,
  instead of letting the service pause/resume in a loop. This is a
  *stability* gain, not a hashrate miracle. Implement as: if throttled,
  step down one ladder rung and hold it for 30 minutes before stepping
  back up.
- **Restart on network drop**: Anvil already pauses on no internet
  (60s grace) and logs DNS/disconnect in XMRigManager. On resume, do
  not re-benchmark algos — reuse the on-disk algo-perf cache, so
  restart is seconds, not minutes. Do not hammer the pool with
  reconnects; one reconnect, then back off.
- **Dev fee 0%**: already in the template (`"donate-level": 0`) and
  waived by the MoneroOcean fork when mining direct to MoneroOcean.
  Keep it. It saves at most 1% vs a stock build — $0.006/month at
  500 H/s — and is not a route to $10.
- **Avoid duplicate work**: one worker, one pool, one wallet, no
  second copy of the app, no second account, no emulator. Duplicate
  instances split the same bus, heat, and IP, and risk pool bans.
- **Dataset hygiene**: close other heavy apps, mine on Wi-Fi or stable
  mobile data without switching networks mid-test (switching pauses
  online time and resets the route), and verify on MoneroOcean, not
  only in the app.

Explicitly NOT used: fake hashrate, spoofed benchmarks, spoofed
location/IP, VPN/datacentre masking, multi-account or cloned apps,
bypassing pool vardiff or payout rules, rooting to force huge pages,
or disabling thermal/battery pauses. Any of those risks a ban, a
wrong payout, or a damaged phone for no real gain.

## Concrete config proposal (to implement, then test)

### A. Ship / test config — Anvil settings

| Setting | Value | Why |
|---|---|---|
| Pool | `gulf.moneroocean.stream:20032`, TLS on | Anvil default, direct to wallet |
| Coin | `monero` in config, algo-perf benchmarking ON | Let MoneroOcean assign the profitable algo (expected rx/0) |
| Donate | `donate-level: 0` | Already in template, MoneroOcean direct waiver |
| Huge pages | `false` | Android does not grant them |
| ASM | `true` | ARM64 JIT, already in template |
| Charging-only | ON | Non-negotiable on a primary phone |
| Wi-Fi-only | OFF for the Jio test, ON only if the user commits to Wi-Fi for a full day | Switching networks mid-test invalidates the test |
| CPU pause | 65 C, resume 55 C | Anvil default, do not raise |
| Battery pause | 40 C | Anvil default, do not raise |
| Start difficulty | 30000 (existing compile constant) | Pool vardiff start, leave alone unless the pool dashboard shows a problem |
| Default threads for first real run | **B0: 2 threads, affine to the 2 big cores** (after the affinity patch) | Best H/s per watt on a 2-big-core phone |
| Fallback if affinity patch is not yet in | Anvil 25% (2 threads, unpinned) | Same count, scheduler may place them anywhere — record placement if possible, do not call it big-only |

### B. XMRig thread array (illustrative — fill CPU indices from the phone)

Discover big-core indices first:
`cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`
Suppose the 2.6 GHz cores are `cpu6` and `cpu7` (verify — do not assume):

```json
"cpu": {
  "enabled": true,
  "asm": true,
  "huge-pages": false,
  "rx/0": [
    {"low_power_mode": false, "affine_to_cpu": 6},
    {"low_power_mode": false, "affine_to_cpu": 7}
  ]
}
```

For B2 (2 big + 2 little), append the two little indices with the
highest little-core frequency, in the same array format. Keep
`max-threads-hint` consistent with the thread count in the array —
do not set a hint of 100% while the array has 2 entries.

### C. Expected ranges (replace with measured numbers, do not budget on them)

| Scenario | Sustained 60s H/s (30 min, throttled) | Gross $/month at pasted assumptions |
|---|---|---|
| B0, 2 big, cool start | 340-420 peak, ~300-380 sustained | $0.34-$0.43 |
| B2, 2 big + 2 little, cool start | 400-500 peak, ~360-450 sustained | $0.40-$0.50 |
| B4, 8 threads, cool start | 430-550 peak, ~350-450 sustained (throttle eats the gain) | $0.39-$0.50 |
| Optimistic ceiling (cool, best case, algo switch helps a little) | 600 peak | $0.67 |
| $10/month target | 8,919 sustained | — not reachable |

First MoneroOcean payout at 0.003 XMR: ~109 days at 400 H/s,
~88 days at 500 H/s, ~73 days at 600 H/s (check the live threshold
on moneroocean.stream before starting — it can change).

## Test ladder — fill this in on the phone, one rung per day if possible

Conditions for every rung: same charger, same network (do not switch
Wi-Fi/Jio mid-rung), same room temperature, other heavy apps closed,
charging-only ON, CPU pause 65 C, battery pause 40 C.
Record the **60s** hashrate, not the jumpy 10s number. Record at
5, 15 and 30 minutes, then the 30-minute sustained average.
"Stable" = 60s H/s within 10% for the last 10 minutes.

Big-core indices found on this phone (fill in):
- 2.6 GHz A78 cores: cpu___ , cpu___
- 2.0 GHz A55 cores: cpu___ to cpu___

| Rung | Anvil / config | Threads | Pool algo shown in log | 5-min 60s H/s | 15-min 60s H/s | 30-min sustained 60s H/s | Accepted | Rejected | Reject % | CPU temp max (C) | Battery temp max (C) | Battery % change while charging | Pauses? (count) | Throttled? Y/N | MoneroOcean pending XMR |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Idle baseline | App closed, charger on | 0 | — | — | — | — | — | — | — |  |  |  | — | — | — |
| 25% | Anvil 25% (unpinned) | 2 |  |  |  |  |  |  |  |  |  |  |  |  |  |
| B0 | Big-only affine (patch) | 2 big |  |  |  |  |  |  |  |  |  |  |  |  |  |
| B1 | Big + 1 little affine | 3 |  |  |  |  |  |  |  |  |  |  |  |  |  |
| 50% / B2 | Anvil 50% / big+2 little affine | 4 |  |  |  |  |  |  |  |  |  |  |  |  |  |
| 75% / B3 | Anvil 75% / big+4 little affine | 6 |  |  |  |  |  |  |  |  |  |  |  |  |  |
| 100% / B4 | Anvil 100% / all affine | 8 |  |  |  |  |  |  |  |  |  |  |  |  |  |

Decision rule (apply after the ladder, not during a burst):

- Keep the rung with the highest **30-minute sustained** 60s H/s at
  CPU <= 65 C and battery <= 40 C without repeated pauses.
- If a higher rung beats the lower rung by less than 10% sustained,
  keep the lower rung — less heat, less wear, same money.
- If any rung pauses repeatedly, drops battery % while charging, or
  shows rejected shares rising above ~2-3%, step down one rung.
- Stop the whole experiment if battery temp exceeds 40 C repeatedly
  or the phone is uncomfortable to hold — the $0.50/month is not worth
  a swollen or degraded battery on a primary phone.

## What to build next (code, in order)

1. Affinity patch in `XMRigManager.writeConfig()`: big-core discovery
   + `affine_to_cpu` per thread + a "Big cores only" preset. Without
   this, every "big-core" claim is scheduler luck.
2. Adaptive step-down: on sustained throttle (CPU pause triggered twice
   in 30 minutes, or 60s H/s down 20% from cool peak for 10 minutes),
   step down one rung and hold 30 minutes.
3. Session totals on-device (already in Anvil TODO 21): hashes,
   accepted/rejected, duration, per-rung — so the test table above can
   be filled from the app instead of by hand.
4. Wallet UI already specified by the parent: separate wallet, paste
   public address only, never seed, MoneroOcean dashboard check.
   Do not add any feature that asks for a seed or private key.

## Sources used from this tree and the pasted research

- `~/workspace/anvil-miner/README.md`, `docs/BENCHMARKS.md`,
  `docs/BUILD-XMRIG.md`, `app/src/main/assets/xmrig_config_template.json`,
  `app/src/main/java/io/github/j3r0nim0/anvil/XMRigManager.kt`,
  `MiningService.kt`, `ThermalMonitor.kt`, `MainActivity.kt`
  (threadSteps 25/50/75/100, CPU pause 65 C, battery pause 40 C,
  charging-only default ON, donate 0%, huge-pages false).
- Pasted research benchmarks: G99 328 / 385 / 424 H/s at 2 / 4 / 8
  threads, SD865 748 H/s at 4 threads vs 684 H/s at 8, MoneroOcean
  assigning rx/0 on both phones, $0.22-$1.01/month at 200-900 H/s
  on 6.3 GH/s / 432 XMR/day / $545 assumptions, 0.003 XMR payout
  threshold, P2Pool Nano share-window caveat (not used here — a
  vardiff pool is the right choice for a few-hundred-H/s phone).
