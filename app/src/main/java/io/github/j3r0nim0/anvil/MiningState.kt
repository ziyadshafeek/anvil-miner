package io.github.j3r0nim0.anvil

/**
 * Process-level stats the service writes and [MainActivity] reads.
 * No bound service — the UI polls every second while visible.
 */
object MiningState {
    @Volatile var hashrate10s: Double = 0.0
    @Volatile var hashrate60s: Double = 0.0
    @Volatile var accepted: Int = 0
    @Volatile var rejected: Int = 0
    @Volatile var pool: String = ""
    @Volatile var uptimeSecs: Int = 0
    @Volatile var tempCelsius: Int = 0
    @Volatile var tempSource: String = "" // "cpu" | "battery" | ""
    @Volatile var pauseReason: String = ""
    @Volatile var userPaused: Boolean = false
    @Volatile var lastLogLine: String = ""
    /** Sticky until the pool connects. Speed lines must not clear this. */
    @Volatile var poolError: String = ""
    /** Rolling XMRig stdout shown on the Mine tab. */
    @Volatile var logText: String = ""

    fun appendLog(line: String) {
        lastLogLine = line
        val next = if (logText.isEmpty()) line else "$logText\n$line"
        logText = if (next.length > 12_000) next.takeLast(8_000) else next
    }
    /** -1 = no countdown. Otherwise seconds until no-internet pause. */
    @Volatile var noInternetSecondsLeft: Int = -1
    @Volatile var cpuThermalReadable: Boolean = true
    @Volatile var batteryTempC: Int = 0

    fun reset() {
        hashrate10s = 0.0
        hashrate60s = 0.0
        accepted = 0
        rejected = 0
        pool = ""
        uptimeSecs = 0
        tempCelsius = 0
        tempSource = ""
        pauseReason = ""
        userPaused = false
        lastLogLine = ""
        poolError = ""
        logText = ""
        noInternetSecondsLeft = -1
        batteryTempC = 0
    }

    fun formatHs(v: Double): String {
        return when {
            v >= 1_000_000 -> String.format("%.2f MH/s", v / 1_000_000)
            v >= 1_000 -> String.format("%.1f kH/s", v / 1_000)
            v > 0 -> String.format("%.0f H/s", v)
            else -> "— H/s"
        }
    }
}
