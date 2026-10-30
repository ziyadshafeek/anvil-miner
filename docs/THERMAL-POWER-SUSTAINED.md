# Thermal, Power and Sustained Speed — Dimensity 7050 Phone Mining Only

Design report only. No source code was changed for this workstream.

Target phone: NARZO 70 Pro 5G, Dimensity 7050.
SoC layout: 2× Cortex-A78 big cores at 2.6 GHz + 6× Cortex-A55 little
cores at 2.0 GHz, 8 cores total. RandomX is memory-bandwidth bound,
not core-count bound, so heat rises faster than useful work when
weak little cores are woken.

Goal: maximum **sustained** speed over 30 minutes and over hours,
not the highest number seen in the first 10 seconds.

---

## 1. Why burst speed lies, and sustained speed is the only money

A phone miner starts cool. In minute one the big cores can run at
full frequency, the cache is cold in a good way, and the battery is
not yet warm. A 10-second hashrate from that minute is a burst.

Then three heat sources add up at the same time:

1. SoC power from RandomX itself — continuous, high, all-core work.
2. Charger power — charging a lithium battery always makes heat,
   and fast charging makes much more than slow charging.
3. Battery internal heat — the battery warms as it charges and as
   the phone draws power, and a warm battery holds charge less well
   and ages faster.

The phone protects itself by throttling: it lowers CPU frequency,
then, in Anvil, it pauses mining entirely at a temperature limit
and resumes only after cooling. That is not a failure of the design.
It is the phone avoiding damage.

The result is a sawtooth: high burst, throttle, pause, cool,
restart, high burst again. The average over an hour of a sawtooth
can be **lower** than a lower, steady run that never throttles.

Illustrative arithmetic only — not a measured result, not a promise:
a run that bursts at 550 H/s for 10 minutes, then throttles to
300 H/s for 20 minutes, averages 383 H/s over 30 minutes.
A steady run at 420 H/s that never throttles averages 420 H/s.
The burst run looked faster in minute one and earned less over
the half hour. That is why every test in this design uses a
30-minute sustained average, not a peak.

## 2. Fixed safety defaults — do not loosen them

These are Anvil defaults already verified in the source audit.
Keep them exactly. They are protection, not a cap to be removed
to chase speed.

| Setting | Value | Why |
|---|---|---|
| Charging-only | ON, non-negotiable on a primary phone | Mining on battery drains fast, heats the battery, and Android will kill the service anyway. Plugged in is the only repeatable test condition. |
| CPU pause | 65 °C, resume 55 °C (10 °C hysteresis) | Hysteresis stops rapid pause/resume cycling. Resuming at 55 °C, not 64 °C, gives the SoC a real cooling window. |
| Battery pause | 40 °C, resume 37 °C | Battery heat is the long-term damage route. 40 °C is already warm for a battery held there for hours. |
| Huge pages | false | Android does not grant them to an app. Do not root to force them. |
| Screen | Off during tests and during real runs | The display is a major heat and power source, and it is not needed while mining. |
| Case | Off, if it traps heat | A thick case insulates the back, which is the main heat path out of the phone. |

### Why removing the pauses would hurt, not help

1. **Sustained speed falls.** Without a pause, the phone throttles
   harder and longer, to a lower frequency, and stays there. The
   average over an hour drops below a paused, cooled, steady run.
2. **Battery life falls faster.** Heat + high state of charge is
   the worst combination for lithium ageing. Hours at a hot battery
   cost far more in eventual replacement than a few cents of
   mining gross can repay. Phone mining gross is already in the
   tens of cents per month range; one battery replacement wipes
   out years of it.
3. **Stability falls.** A very hot phone is more likely to have
   Android kill the foreground service, drop the pool connection,
   or shut down. Rejected shares rise when the device has no
   headroom for Android and networking.
4. **Safety falls.** A phone too hot to hold, under a pillow, in
   sun, or charging at full fast-charge power while mining at
   100% is a heat risk, not an optimisation.

Removing pauses does not remove heat. It only removes the
mechanism that lets the phone recover from heat.

## 3. Power setup for maximum sustained speed

### Charger: slow is faster, over an hour

Use the slowest charger that still keeps battery percentage
stable or rising while mining. A 5 W or 10 W charger is preferable
to a 30 W+ fast charger for a sustained run, for two reasons:

- Fast charging adds its own heat on top of mining heat, so the
  CPU pause triggers sooner and more often.
- If the charger cannot keep up, battery percentage falls while
  plugged in. That is a stop signal, not a setting to ignore:
  it means the charger + heat load combination is unsustainable.
  Step down one thread rung, or use a cooler room, before the
  next test.

Record, for every rung: battery percentage change while charging.
A rung that loses battery while plugged in is not a sustainable
rung, even if its first-minute hashrate was highest.

### The 100% charge tension, stated plainly

Charging-only mining keeps the phone plugged in, which tends to
hold it at 100%. Holding lithium at 100% and hot accelerates
ageing more than holding it at, say, 80%. If the phone offers an
80% charge cap, use it for mining sessions. If it does not, do
not try to hack one in with root or a third-party battery tool.
The practical compromise is: mine in sessions, not 24 hours
unattended at 100% and hot, and let the phone cool between
sessions. This design does not pretend that tension away.

### Phone state during a test or a real run

- Plugged in, slow charger, back uncovered, case off.
- On a hard, ventilated surface — table or stand, not bed,
  pillow, blanket, pocket, or direct sun.
- Ambient ideally below 30 °C. A hot room alone can be the
  difference between a steady run and a throttling run.
- Screen off, brightness irrelevant once off.
- Other heavy apps closed: no game, video, browser with many
  tabs, or second miner running. RandomX dataset is about
  2,080 MiB shared plus 256 MiB cache; other heavy apps compete
  for the same RAM and memory bus.
- One network for the whole rung. Do not switch Wi-Fi / Jio
  mid-rung. A network switch drops the pool connection, loses
  in-flight work, and pollutes the temperature and hashrate
  readings for that rung.
- Do not cover the phone, do not put it in a closed drawer or
  under anything to “hide” it. Ventilation is part of the cooling
  system.

## 4. Adaptive thread step-down — the core technique

Do not pick a thread count once and hope. Let the miner step
down when throttling is detected, and only step back up after
a long stable period. This keeps the run near the highest
**sustainable** rung instead of oscillating at an unsustainable
one.

### Ladder, in order

| Rung | Threads | Notes |
|---|---|---|
| B0 | 2, big cores only (affine, after the affinity patch) | Expected best H/s per watt, safe default. |
| B1 | 3, 2 big + 1 little | Small step up, test only if B0 is stable. |
| B2 | 4, 2 big + 2 little (Anvil 50% count, big-first) | Sweet-spot candidate, if thermals allow. |
| B3 | 6, 2 big + 4 little (Anvil 75% count) | Comparison only. |
| B4 | 8, all cores (Anvil 100% count) | Comparison only. Expect throttle first. |

Until the affinity patch lands, Anvil percentages are counts
only, not core placement: 25% = 2 threads, 50% = 4, 75% = 6,
100% = 8, with the OS scheduler free to place them. Do not call
an unpatched 25% run “big-only”.

### Step-down triggers — any one fires a step down

- CPU pause triggered twice in 30 minutes, **or**
- 60-second hashrate falls 20% below the cool peak for that rung
  for 10 continuous minutes, **or**
- Battery temperature repeatedly hits 40 °C, **or**
- Battery percentage falls while plugged in, **or**
- Rejected share rate rises above about 2–3% on a rung that was
  clean at a lower rung.

On trigger: step down **one** rung, hold that rung for at least
30 minutes, and do not step back up in the same session unless
the lower rung has been stable — CPU at or below 65 °C without
pauses, battery at or below 40 °C, 60-second hashrate within
10% — for a full 30 minutes. Then step up one rung only, and
re-test for 30 minutes.

Never step down two rungs at once to “be safe”, and never step
up because the first minute after a step-down looked fast.
Both mistakes re-create the sawtooth.

### Decision rule for the final setting

Keep the rung with the highest **30-minute sustained** 60-second
hashrate at CPU ≤ 65 °C and battery ≤ 40 °C without repeated
pauses. If a higher rung beats a lower rung by less than 10%
sustained, keep the lower rung: less heat, less wear, same
money for practical purposes. If any rung pauses repeatedly,
drops battery percentage while charging, or shows rising
rejected shares, step down one rung and re-test.

## 5. How to measure sustained speed — protocol, not a peak

### What to record, and what not to record

Record **60-second hashrate**, never 10-second hashrate, for
decisions. 10-second numbers jump with pool difficulty changes,
network jitter, and short thermal bursts. 60-second numbers
smooth those out and are what MoneroOcean itself will see as
stable work.

For every rung, same charger, same network, same room, same
phone state as in section 3, record:

- 60-second hashrate at 5, 15 and 30 minutes.
- 30-minute sustained average of the 60-second readings.
- “Stable” definition: 60-second hashrate within 10% for the
  last 10 minutes of the rung.
- Accepted shares, rejected shares, reject percentage.
- CPU temperature maximum, battery temperature maximum.
- Battery percentage change while charging.
- Pause count in the 30 minutes, and whether throttling was
  visible (hashrate falling while temperature stayed high).
- Pool algo in the log (expected: rx/0, plain Monero, on
  MoneroOcean for a phone — do not quote a light-algo headline
  number as earnings).
- MoneroOcean dashboard pending XMR, checked separately from
  the app. App numbers alone are never proof.

A rung is not “done” after 5 minutes. A rung that looks best
at 5 minutes and throttles at 20 minutes is a worse rung than
a steady rung that looked unremarkable at 5 minutes.

### Test table to fill in on the phone

| Rung | Threads / affinity | Pool algo | 5-min 60s H/s | 15-min 60s H/s | 30-min sustained 60s H/s | Accepted | Rejected | Reject % | CPU max °C | Battery max °C | Battery % change charging | Pauses | Throttled? | MoneroOcean pending XMR |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Idle baseline | 0 | — | — | — | — | — | — | — | | | | — | — | — |
| 25% / B0 | 2 | | | | | | | | | | | | | |
| B1 | 3 | | | | | | | | | | | | | |
| 50% / B2 | 4 | | | | | | | | | | | | | |
| 75% / B3 | 6 | | | | | | | | | | | | | |
| 100% / B4 | 8 | | | | | | | | | | | | | |

Fill big-core indices from the actual phone first
(`cpuinfo_max_freq`: the 2 cores at 2.6 GHz are the A78s).
Do not assume cpu6/cpu7.

### Stop rules — stop the test immediately if:

- Battery stays above 40 °C, or CPU stays above 65 °C with
  repeated pausing.
- The phone is too hot to hold comfortably.
- Battery percentage falls while plugged in on two consecutive
  rungs.
- The phone shuts down, restarts, or Android kills the service
  repeatedly.
- Rejected shares climb sharply on a rung.

Stopping is not failing. It is the measurement telling you that
rung is unsustainable.

## 6. Simple user checklist (for the final APK steps)

1. Plug in with a slow charger. Keep it plugged in the whole run.
2. Take the case off. Put the phone on a table, back up, nothing
   covering it. Not in sun, not on a bed.
3. Close heavy apps. Turn the screen off.
4. Start on big cores only, 2 threads. Run 30 minutes.
5. Check: speed steady? Temperature OK? Battery % not falling?
   Pauses: none or almost none? If yes, try one rung up.
6. If it pauses a lot, gets very hot, or battery falls while
   charging, step down one rung and stay there.
7. Check MoneroOcean by your wallet address, not just the app,
   to confirm accepted shares are rising.

## 7. What this design does not claim

- No hashrate or $/month figure in this report is a measured
  Dimensity 7050 result. Ranges from earlier work (about
  400–600 H/s class, about $0.50–$1/month gross before wear, at
  older XMR/network snapshots) are hypotheses to replace with
  the 30-minute sustained numbers from the table above.
- No percentage improvement is claimed for any technique here
  until it is measured on this phone under this protocol.
- Thermal optimisation cannot turn a 2-big-core phone into a
  desktop-class miner. What it can do is keep the phone at its
  own highest sustainable speed, for longer, with less battery
  damage, instead of wasting time and heat on burst-and-throttle
  cycles.

---

Design only. Implementation (affinity patch, adaptive step-down
in code, session totals) belongs to the APK build workstream,
using the triggers, hold times and decision rule above.
