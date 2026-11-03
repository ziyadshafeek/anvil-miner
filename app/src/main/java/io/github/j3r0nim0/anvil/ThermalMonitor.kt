package io.github.j3r0nim0.anvil

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File

/**
 * Polls /sys/class/thermal/thermal_zoneN/temp (millidegrees C) and takes the max.
 *
 * NORMAL → OVERHEATED when temp ≥ thresholdCelsius
 * OVERHEATED → NORMAL when temp ≤ (threshold − 10°C)
 */
class ThermalMonitor(private var thresholdCelsius: Int = 65) {

    companion object {
        private const val TAG = "ThermalMonitor"
        private const val POLL_INTERVAL_MS = 10_000L
        private const val HYSTERESIS_GAP_C = 10
        private val THERMAL_ROOT = File("/sys/class/thermal")

        fun isCpuThermalReadable(): Boolean {
            if (!THERMAL_ROOT.exists()) return false
            return THERMAL_ROOT
                .listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
                ?.any { zone ->
                    runCatching { File(zone, "temp").readText().trim().toIntOrNull() }
                        .getOrNull() != null
                } ?: false
        }
    }

    var onOverheat: (() -> Unit)? = null
    var onCooledDown: (() -> Unit)? = null
    var onTemperature: ((Int) -> Unit)? = null

    var lastTempCelsius: Int = 0
        private set

    private var isOverheated = false
    private var pollThread: HandlerThread? = null
    private var pollHandler: Handler? = null

    fun start() {
        val ht = HandlerThread("thermal-poll").also { it.start() }
        pollThread = ht
        pollHandler = Handler(ht.looper).also { it.post(pollRunnable) }
    }

    fun stop() {
        pollHandler?.removeCallbacksAndMessages(null)
        pollThread?.quitSafely()
        pollHandler = null
        pollThread = null
        isOverheated = false
    }

    fun updateThreshold(newThresholdCelsius: Int) {
        thresholdCelsius = newThresholdCelsius
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            val temp = readMaxCelsius()
            lastTempCelsius = temp
            onTemperature?.invoke(temp)
            evaluate(temp)
            pollHandler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun evaluate(tempC: Int) {
        when {
            !isOverheated && tempC >= thresholdCelsius -> {
                isOverheated = true
                Log.w(TAG, "Overheating: ${tempC}°C ≥ ${thresholdCelsius}°C")
                onOverheat?.invoke()
            }
            isOverheated && tempC <= (thresholdCelsius - HYSTERESIS_GAP_C) -> {
                isOverheated = false
                Log.i(TAG, "Cooled: ${tempC}°C ≤ ${thresholdCelsius - HYSTERESIS_GAP_C}°C")
                onCooledDown?.invoke()
            }
        }
    }

    private fun readMaxCelsius(): Int {
        if (!THERMAL_ROOT.exists()) return 0
        return THERMAL_ROOT
            .listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
            ?.mapNotNull { zone ->
                try {
                    File(zone, "temp").readText().trim().toIntOrNull()
                } catch (_: Exception) {
                    null
                }
            }
            ?.maxOrNull()
            ?.div(1000)
            ?: 0
    }
}
