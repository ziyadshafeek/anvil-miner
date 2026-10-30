package io.github.j3r0nim0.anvil

import android.util.Log
import java.io.File

/**
 * PALM read-only probe (APK2 only). From docs/PALM-PAPER.md.
 *
 * Logs SYSTEM-WIDE /proc/meminfo huge-page fields only. This is NOT
 * the XMRig dataset's AnonHugePages (that lives in the native process
 * and is not readable from the app process without a native probe),
 * and it is NOT evidence that THP helped mining. If system
 * AnonHugePages is 0, the PALM THP component is dead for this phone
 * and must not be claimed. No MADV_COLLAPSE, no sysfs writes, no root,
 * no dataset-on-disk persistence is added here.
 */
object ThpProbe {
    private const val TAG = "ThpProbe"

    fun logSystemThp(): String {
        val out = runCatching {
            File("/proc/meminfo").readLines()
                .filter { it.startsWith("AnonHugePages") || it.startsWith("Hugepagesize") || it.startsWith("HugePages_") }
                .joinToString("; ")
        }.getOrDefault("meminfo unreadable")
        val msg = "PALM read-only THP probe (system-wide, NOT XMRig dataset): $out"
        Log.i(TAG, msg)
        MiningState.appendLog(msg)
        return msg
    }
}
