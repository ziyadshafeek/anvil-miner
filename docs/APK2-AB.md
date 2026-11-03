# APK2 A/B variant — what differs from APK1, and what does NOT

APK1 (tag v0.2.1-fork1, fork main): baseline fork — masked wallet,
6-step wallet flow, monero: fill-only, fork update check, big-core
affinity, native MoneroOcean XMRig v6.25.0-mo1, NDK 28.2, platform 28,
TLS ON, OpenSSL 3.4, -O3 -march=armv8.2-a+crypto, LTO attempted.
Version 0.2.0 / code 2.

APK2 (tag v0.2.2-fork2-ab, branch apk2): APK1 + ONLY the following.
Version 0.2.2-ab / code 3. Same package, same release keystore as APK1
in this A/B pair, so APK2 installs over APK1 as an upgrade. If an
upstream Anvil build is installed, uninstall that first (different
signature) — do not install over it.

## Implemented in APK2
1. GCSPC predictive step-down (docs/INVENTED-THERMAL-CONTROLLER.md,
   Gcspc.kt, Settings switch "APK2: predictive step-down", default ON;
   switch takes effect on next mining start). Steps down ONE rung
   (100->75->50->25) before the hard pause when: CPU within 5C of the
   hard threshold, OR battery >=38C, OR 60s hashrate <80% of cool peak
   for >=60s, OR reject rate >3% and rising. Then holds 30 minutes
   (dwell). Every decision is logged ("GCSPC DECISION:" / "GCSPC:").
   Safety pauses are UNCHANGED and never overridden (CPU 65/55C,
   battery 40/37C, charging-only). At big-only or 25% there is no
   lower rung: it logs and does nothing.
   LIMIT: step-down uses XMRigManager.reconfigure(), which restarts
   the XMRig process, so the RandomX dataset is rebuilt after a
   step-down. That cost is part of the A/B measurement.
   LIMIT: drop persistence is 60s in this v1, not the paper's 10 min.
   LIMIT: Sustained Performance Mode is probed via the public
   PowerManager API; uclamp has no public API for an unprivileged app
   and is logged as unavailable, not assumed.
2. VSWL local share ledger (ShareLedger.kt, from
   INVENTED-LOW-HASHRATE-POOL-METHOD.md): hash-chained entries in
   app-private filesDir/vswl_ledger.txt on each accepted-share
   increase, masked wallet only (first 6 + last 4). Never exported,
   never sent anywhere. This is a consistency check, NOT proof the
   pool owes money. Dashboard + on-chain payout are still the proof.
3. SEHO build-flag variant ONLY (release.yml): native flags add
   -mcpu=cortex-a78 -mtune=cortex-a78 on top of APK1's flags.
   The full SEHO tier-split rewrite was NOT done — it would risk the
   build for an unmeasured gain. If the native build fails on these
   flags, the fallback is APK1's flags, documented in the CI run.
4. PALM read-only probe (ThpProbe.kt): logs system-wide
   /proc/meminfo AnonHugePages at mining start. That is NOT the
   XMRig dataset's huge pages, and a 0 there kills the THP claim for
   this phone. No MADV_COLLAPSE, no sysfs writes, no root, no
   dataset-on-disk persistence. Pause-preserving resume (stdin "p",
   process alive) is already stock Anvil behaviour and unchanged.
5. Sustained 60s logging (MiningService sustainedTick): a
   "SUSTAINED60:" log line every 60 seconds
   while mining, with 60s hashrate, accepted/rejected, CPU/battery
   temps, thread %. 10-second burst figures are never the decision
   number.

## NOT implemented in either APK (do not claim)
- RDDB-JIT native emitter rewrite (invalid-share risk, needs
  offline hash-sum gate first).
- CPCCS full footprint-sampling scheduler (needs pilot calibration).
- IPSD/BMOS pool changes (need pool adoption), RandomX v2, huge
  pages, root, disabling pauses, any hash/dataset/scratchpad change,
  any seed/private-key field, any prebuilt libxmrig.so from internet.
- No other miner engine. No $/month or % figure is claimed for APK2
  until the phone A/B test below is actually run.

## A/B test (same phone, same conditions)
Same charger/cable, plugged in, charging-only ON, one network for the
whole test (no switch), same room, case off, cool-down >=10 min
between runs. Per rung (big-only, 25/50/75/100%): 30 minutes, record
60s hashrate at 5/15/30 min, sustained average (last 10 min),
accepted/rejected + reject %, CPU/battery max, battery % change,
pauses, GCSPC decisions (APK2 log), MoneroOcean dashboard worker +
accepted + pending XMR at session start/end. Winner = highest
eligible sustained average with no repeated pauses. If APK2 does not
beat APK1 by more than run-to-run noise, it is a tie and APK1 stays.
