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

    /**
     * 2026-11-03: read-only /proc/self/smaps_rollup for THIS app process only.
     * Returns the AnonHugePages line verbatim, or "unreadable". This is NOT
     * the XMRig child process's AnonHugePages, and a non-zero value here
     * does NOT prove the 2,080 MiB RandomX dataset is on huge pages.
     * Android does not grant THP to that dataset in practice (see
     * docs/DIMENSITY7050-CONFIG.md: huge-pages stays false).
     */
    fun ownSmapsAnonHugePages(): String {
        return runCatching {
            File("/proc/self/smaps_rollup").readLines()
                .firstOrNull { it.startsWith("AnonHugePages") }?.trim()
                ?: "AnonHugePages: not present"
        }.getOrDefault("smaps_rollup unreadable")
    }

    /**
     * 2026-11-03 (full-7050): read-only scan for the XMRig child process.
     * The child runs as the same app UID, so /proc/<pid>/smaps_rollup is
     * attempted for any process whose cmdline contains "libxmrig".
     * Returns "xmrig:<AnonHugePages line>" or a plain not-found/unreadable
     * message. Even a non-zero value here is only that child process's
     * total AnonHugePages; it does NOT by itself prove the 2,080 MiB
     * RandomX dataset is on huge pages, and it must never be quoted
     * as a dataset THP figure without that caveat. If the scan finds
     * nothing (e.g. /proc visibility rules on this device), that is
     * reported as not-found, not as zero.
     */
    fun xmrigSmapsAnonHugePages(): String {
        return runCatching {
            val procDir = File("/proc")
            val pids = procDir.listFiles()?.mapNotNull { it.name.toIntOrNull() } ?: return "xmrig smaps: /proc unreadable"
            for (pid in pids) {
                val cmdline = runCatching {
                    File("/proc/$pid/cmdline").readBytes().decodeToString()
                }.getOrDefault("")
                if (!cmdline.contains("libxmrig")) continue
                val line = runCatching {
                    File("/proc/$pid/smaps_rollup").readLines()
                        .firstOrNull { it.startsWith("AnonHugePages") }?.trim()
                }.getOrNull()
                return when {
                    line != null -> "xmrig pid=$pid $line (child-process total, NOT dataset-only proof)"
                    else -> "xmrig pid=$pid smaps_rollup unreadable"
                }
            }
            "xmrig smaps: process not found (not mining, or /proc hidden)"
        }.getOrDefault("xmrig smaps: scan failed")
    }
}
