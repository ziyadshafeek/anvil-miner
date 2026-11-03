package io.github.j3r0nim0.anvil

import android.util.Log

/**
 * GCSPC (APK2 only) — Governor-Cooperative Sustained-Performance Controller.
 *
 * Invented in docs/INVENTED-THERMAL-CONTROLLER.md. This is a deliberately
 * small, auditable implementation of that paper, NOT the full paper:
 *
 * - Predictive step-down: step down ONE thread rung BEFORE the hard
 *   safety pause fires, when the joint state predicts it.
 * - 30-minute dwell: after a step-down, no further step-down for 30 min.
 * - Safety pauses are UNCHANGED and never overridden: CPU 65C/55C and
 *   battery 40C/37C in MiningService/ThermalMonitor still fire first
 *   if reached. GCSPC only acts below those limits.
 * - Every decision is logged (Log + MiningState log) so an A/B run can
 *   be replayed from the log.
 *
 * LIMITATIONS, stated plainly:
 * - Step-down here is done via XMRigManager.reconfigure(), which stops
 *   and restarts the XMRig process. The RandomX dataset is therefore
 *   rebuilt after a step-down. That restart cost is real and is part
 *   of what the A/B test measures; a live thread-count change without
 *   restart is NOT implemented (XMRig has no such API here).
 * - Drop persistence is 60 seconds in this v1, not the paper's 10
 *   minutes — a shorter trigger, chosen so a test run can actually
 *   observe a step-down. Tune only with a new pre-registered run.
 * - Sustained Performance Mode is probed via the public PowerManager
 *   API. uclamp has NO public Android API for an unprivileged app, so
 *   it is logged as "unavailable via public API" — not probed via
 *   sched_setattr, and never assumed to work.
 */
class Gcspc(
    var hardCpuThresholdC: Int,
    private val onStepDown: (newPercent: Int, reason: String) -> Unit,
) {
    companion object {
        private const val TAG = "Gcspc"
        const val DWELL_MS = 30L * 60L * 1000L
        const val CPU_NEAR_MARGIN_C = 5      // act at threshold-5, e.g. 60C
        const val BATTERY_NEAR_C = 38        // act at 38C, pause is at 40C
        const val DROP_FRACTION = 0.20       // 60s hashrate 20% below cool peak
        const val DROP_PERSIST_MS = 60_000L
        const val REJECT_PCT_LIMIT = 3.0
    }

    var enabled: Boolean = true
    private var coolPeak60s: Double = 0.0
    private var dropSinceMs: Long = -1L
    private var lastStepDownMs: Long = -1L
    private var lastRejected: Int = -1
    private var sessionStartMs: Long = System.currentTimeMillis()

    fun reset() {
        coolPeak60s = 0.0
        dropSinceMs = -1L
        lastStepDownMs = -1L
        lastRejected = -1
        sessionStartMs = System.currentTimeMillis()
    }

    /** One evaluation, called from the 10-second thermal poll. */
    fun evaluate(
        cpuTempC: Int,
        batteryTempC: Int,
        hashrate60s: Double,
        accepted: Int,
        rejected: Int,
        currentPercent: Int,
        bigOnly: Boolean,
    ) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (lastStepDownMs > 0 && now - lastStepDownMs < DWELL_MS) return
        if (accepted < 0 || rejected < 0) return

        // Cool peak: only update while comfortably below the hard limit,
        // so a throttled reading never becomes the new "peak".
        if (cpuTempC in 1 until (hardCpuThresholdC - 10) && hashrate60s > coolPeak60s) {
            coolPeak60s = hashrate60s
        }

        val rejectPct = if (accepted + rejected > 0) {
            100.0 * rejected / (accepted + rejected)
        } else 0.0

        var reason: String? = null
        if (cpuTempC >= hardCpuThresholdC - CPU_NEAR_MARGIN_C && cpuTempC > 0) {
            reason = "GCSPC predictive: CPU ${cpuTempC}C within ${CPU_NEAR_MARGIN_C}C of hard pause ${hardCpuThresholdC}C"
        } else if (batteryTempC >= BATTERY_NEAR_C && batteryTempC > 0) {
            reason = "GCSPC predictive: battery ${batteryTempC}C near 40C pause"
        } else if (coolPeak60s > 0 && hashrate60s > 0 && hashrate60s < coolPeak60s * (1 - DROP_FRACTION)) {
            if (dropSinceMs < 0) dropSinceMs = now
            if (now - dropSinceMs >= DROP_PERSIST_MS) {
                reason = "GCSPC predictive: 60s hashrate ${hashrate60s.toInt()} < 80% of cool peak ${coolPeak60s.toInt()} for >=60s (clamping)"
            }
        } else {
            dropSinceMs = -1L
        }
        if (reason == null && rejectPct > REJECT_PCT_LIMIT && lastRejected >= 0 && rejected > lastRejected) {
            reason = "GCSPC predictive: reject rate ${"%.1f".format(rejectPct)}% and rising"
        }
        lastRejected = rejected
        if (reason == null) return

        val newPercent = when (currentPercent) {
            100 -> 75
            75 -> 50
            50 -> 25
            else -> -1
        }
        if (bigOnly || newPercent < 0) {
            val msg = "GCSPC: trigger fired but already at lowest rung (big-only or 25%) — no step-down, hard safety pause still applies: $reason"
            Log.i(TAG, msg)
            MiningState.appendLog(msg)
            lastStepDownMs = now // dwell still applies: do not spam the log
            return
        }
        lastStepDownMs = now
        dropSinceMs = -1L
        val msg = "GCSPC DECISION: step down $currentPercent% -> $newPercent% : $reason"
        Log.i(TAG, msg)
        MiningState.appendLog(msg)
        onStepDown(newPercent, reason)
    }
}
