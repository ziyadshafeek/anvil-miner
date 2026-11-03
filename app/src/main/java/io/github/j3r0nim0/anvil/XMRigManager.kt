package io.github.j3r0nim0.anvil

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled MoneroOcean XMRig (`libxmrig.so`) as a process.
 * Connects straight to the pool over TLS — no on-device stratum proxy.
 */
class XMRigManager(
    private val context: Context,
    private val poolUrl: String,
    private val poolTls: Boolean,
    private val workerName: String,
    private val difficulty: Int,
) {

    companion object {
        private const val TAG = "XMRigManager"
        private const val BINARY_SO = "libxmrig.so"
        private const val CONFIG_NAME = "xmrig_config.json"
        private const val CONFIG_META = "xmrig_config_meta.txt"
        const val DEBUG_LOG_NAME = "debug_xmrig.log"
        private const val DEBUG_LOG_MAX_BYTES = 400_000L

        fun clearAlgoCache(context: Context) {
            File(context.filesDir, CONFIG_NAME).delete()
            File(context.filesDir, CONFIG_META).delete()
        }
    }

    private var process: Process? = null

    @Volatile private var statsPool = ""
    @Volatile private var statsAccepted = 0
    @Volatile private var statsRejected = 0
    @Volatile private var statsHashrate10s = 0.0
    @Volatile private var statsHashrate60s = 0.0
    @Volatile private var connectionStartMs = 0L

    private val reSpeed = Regex(
        """10s/60s/15m[:\s]+([\d.]+|n/a)\s+([\d.]+|n/a)\s+([\d.]+|n/a)\s+([KMG]?)H/s""",
        RegexOption.IGNORE_CASE,
    )
    private val rePool = Regex("""(?:use pool|new job from)\s+(\S+)""")
    private val reAccepted = Regex("""accepted\s+\((\d+)/(\d+)\)""")
    private val reDisconnect = Regex("""net\s+.*(disconnect|offline|read error)""", RegexOption.IGNORE_CASE)
    private val reDns = Regex("""DNS error""", RegexOption.IGNORE_CASE)

    private val debugLogFile by lazy { File(context.filesDir, DEBUG_LOG_NAME) }
    private val logFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private fun appendDebugLog(line: String) {
        try {
            debugLogFile.appendText("${logFmt.format(Date())}  $line\n")
            if (debugLogFile.length() > DEBUG_LOG_MAX_BYTES) {
                val lines = debugLogFile.readLines()
                debugLogFile.writeText(lines.takeLast(lines.size / 2).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun start(wallet: String, threadsPercent: Int) {
        if (process?.isAlive == true) {
            Log.w(TAG, "start() while alive — stopping first")
            stopLocked()
        }

        statsPool = ""
        statsAccepted = 0
        statsRejected = 0
        statsHashrate10s = 0.0
        statsHashrate60s = 0.0
        connectionStartMs = 0L
        MiningState.poolError = ""

        val binary = getBinary()
        val config = writeConfig(wallet, threadsPercent)

        appendDebugLog("=== XMRig starting (wallet=${wallet.take(8)}… threads=$threadsPercent%) ===")
        Log.i(TAG, "Launching: ${binary.absolutePath} --config=${config.absolutePath} --no-color")

        process = ProcessBuilder(binary.absolutePath, "--config=${config.absolutePath}", "--no-color")
            .redirectErrorStream(true)
            .directory(context.filesDir)
            .start()
            .also { proc ->
                Thread({
                    try {
                        proc.inputStream.bufferedReader().use { reader ->
                            reader.forEachLine { line ->
                                Log.i(TAG, "xmrig: $line")
                                appendDebugLog(line)
                                MiningState.appendLog(line)
                                parseStatsLine(line)
                            }
                        }
                    } catch (e: Exception) {
                        Log.i(TAG, "xmrig drain ended: ${e.message}")
                        appendDebugLog("=== drain ended: ${e.message} ===")
                    }
                    val code = runCatching { proc.waitFor() }.getOrDefault(-1)
                    Log.w(TAG, "XMRig exited with code $code")
                    appendDebugLog("=== XMRig exited with code $code ===")
                }, "xmrig-drain").apply { isDaemon = true; start() }
            }

        Log.i(TAG, "XMRig started (wallet=${wallet.take(8)}…, threads=$threadsPercent%)")
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    private fun stopLocked() {
        val proc = process ?: return
        process = null
        appendDebugLog("=== XMRig stopped ===")
        Log.i(TAG, "XMRig stopping")
        try {
            proc.destroy()
            if (!proc.waitFor(2, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(2, TimeUnit.SECONDS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stop: ${e.message}")
            runCatching { proc.destroyForcibly() }
        }
        Log.i(TAG, "XMRig stopped")
    }

    @Synchronized
    fun pause() {
        sendStdin("p")
        Log.i(TAG, "XMRig paused (stdin p)")
    }

    @Synchronized
    fun resumeHashing() {
        sendStdin("r")
        Log.i(TAG, "XMRig resumed (stdin r)")
    }

    private fun sendStdin(cmd: String) {
        val proc = process ?: return
        if (!proc.isAlive) {
            Log.w(TAG, "stdin write skipped — process not alive")
            return
        }
        try {
            proc.outputStream.let { out ->
                out.write("$cmd\n".toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "stdin write failed: ${e.message}")
        }
    }

    fun reconfigure(wallet: String, threadsPercent: Int) {
        stop()
        start(wallet, threadsPercent)
    }

    val isRunning: Boolean get() = process?.isAlive == true

    private fun getBinary(): File {
        val binary = File(context.applicationInfo.nativeLibraryDir, BINARY_SO)
        check(binary.exists()) { "XMRig binary not found at ${binary.absolutePath}." }
        Log.i(TAG, "Binary: ${binary.absolutePath} (${binary.length()} bytes)")
        return binary
    }

    /**
     * CPU topology from sysfs, on the device itself — never hardcoded.
     * Reads /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq for each
     * online CPU and returns indices sorted by max frequency, highest first.
     * On Dimensity 7050 that puts the 2.6 GHz A78 cores first and the
     * 2.0 GHz A55 cores after them. Falls back to 0..N-1 if sysfs is unreadable.
     */
    private fun coresByMaxFreqDesc(): List<Int> {
        return try {
            val dir = File("/sys/devices/system/cpu")
            val found = dir.listFiles()
                ?.filter { it.name.matches(Regex("cpu\\d+")) }
                ?.mapNotNull { f ->
                    val idx = f.name.removePrefix("cpu").toIntOrNull() ?: return@mapNotNull null
                    val freq = runCatching {
                        File(f, "cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                    }.getOrDefault(0L)
                    idx to freq
                }
                ?.sortedWith(compareByDescending<Pair<Int, Long>> { it.second }.thenBy { it.first })
                ?.map { it.first }
            if (found.isNullOrEmpty()) {
                (0 until Runtime.getRuntime().availableProcessors()).toList()
            } else {
                found
            }
        } catch (_: Exception) {
            (0 until Runtime.getRuntime().availableProcessors()).toList()
        }
    }

    private fun writeConfig(wallet: String, threadsPercent: Int): File {
        val configFile = File(context.filesDir, CONFIG_NAME)
        val metaFile = File(context.filesDir, CONFIG_META)
        val poolUser = if (difficulty > 0) "$wallet+$difficulty" else wallet
        val userJson = jsonString(poolUser)
        // Big-only mode is read from the same SharedPreferences the UI writes.
        // It is part of the meta key so switching mode forces a full config
        // rewrite (and a fresh thread array) instead of a user-only patch,
        // which would otherwise keep the old, unpinned thread array.
        val prefs = context.getSharedPreferences(Prefs.NAME, android.content.Context.MODE_PRIVATE)
        val threadMode = prefs.getInt(Prefs.THREAD_MODE, 0)
        val bigOnly = threadMode == Prefs.THREAD_MODE_BIG_ONLY
        val asymmetric = threadMode == Prefs.THREAD_MODE_ASYMMETRIC
        val l3Isolated = threadMode == Prefs.THREAD_MODE_L3_ISOLATED
        val mask4 = threadMode == Prefs.THREAD_MODE_MASK4
        val mask8 = threadMode == Prefs.THREAD_MODE_MASK8
        val orderedCores = coresByMaxFreqDesc()
        val totalCores = orderedCores.size.coerceAtLeast(1)
        val bigCores = orderedCores.take(2)
        val littleCores = orderedCores.drop(2)
        // Main array, used for the percent modes and as the fallback.
        val threadIndices: List<Int> = when {
            bigOnly -> bigCores
            asymmetric -> (bigCores + littleCores.take(2)).distinct()
            mask4 -> (bigCores + littleCores.takeLast(2)).distinct()
            mask8 -> orderedCores
            // In L3-isolated mode the "main" array is big-only; light algos
            // get their own array below. The template's rx entries use
            // __THREAD_ARRAY_RX__ and its light entries use
            // __THREAD_ARRAY_LIGHT__, so this fallback is only for keys
            // not covered by either (none in the current template).
            l3Isolated -> bigCores
            else -> {
                val threadCount = maxOf(1, (totalCores * threadsPercent / 100.0).toInt())
                orderedCores.take(threadCount)
            }
        }
        // Per-algo arrays (full-7050). Verified against XMRig v6.25.0-mo1:
        // CpuConfig::get(miner, algorithm) returns Threads::get(algorithm),
        // and CpuBackend::setJob stops and restarts workers on an algo
        // change. So these arrays are NOT simultaneous: rxArray runs only
        // while the pool job is rx/0 (etc.), lightArray only while the
        // pool job is a light algo. In L3-isolated mode, rx threads never
        // include a little core, so a little core can never be scheduled
        // onto an rx/0 thread and evict the 2 MB scratchpad's L3 lines;
        // conversely, when a light algo is assigned, big cores idle rather
        // than contending. That idle-big cost is the trade-off, and the
        // sustained 60s log is the judge, not a pre-declared win.
        val rxIndices: List<Int> = if (l3Isolated) bigCores else threadIndices
        val lightIndices: List<Int> = if (l3Isolated) littleCores else threadIndices
        val metaKey = "v4:mode=$threadMode:threads=$threadsPercent:rx=${rxIndices.joinToString(",")}:light=${lightIndices.joinToString(",")}:$poolUrl:$poolTls"

        if (configFile.exists() && metaFile.exists() && metaFile.readText().trim() == metaKey) {
            val existing = configFile.readText()
            val patched = existing.replace(
                Regex(""""user"\s*:\s*"[^"]*""""),
                """"user": $userJson""",
            )
            if (patched != existing) {
                configFile.writeText(patched)
                Log.i(TAG, "Config: user patched (algo-perf preserved)")
            }
            return configFile
        }

        val template = context.assets.open("xmrig_config_template.json").bufferedReader().readText()
        // Affinity at CPU level, big-first. Every rung (25/50/75/100% and
        // big-only) is tested on the phone; the winner is the highest
        // SUSTAINED 60s hashrate over 30 minutes, not the coolest or the
        // highest 10s burst. No rung is pre-declared the winner here.
        fun arrayFor(indices: List<Int>): String = "[" + indices.joinToString(",") {
            // CORRECTION 2026-11-03 (full-7050, after direct source read):
            // XMRig v6.25.0-mo1 CpuThread parses ONLY [intensity, affinity]
            // arrays or plain integer affinities. It does NOT parse
            // {"low_power_mode":…, "affine_to_cpu":…} objects — src/
            // contains zero occurrences of either key — so the old
            // object format silently produced unpinned threads
            // (affinity -1, intensity 1) regardless of the log line.
            // Emit [1, cpu] pairs, which the parser actually honours.
            """[1,$it]"""
        } + "]"
        val threadArray = arrayFor(threadIndices)
        val rxArray = arrayFor(rxIndices)
        val lightArray = arrayFor(lightIndices)
        Log.i(TAG, "Thread config: main=$threadIndices rx=$rxIndices light=$lightIndices, mode=$threadMode ($threadsPercent%)")

        val config = template
            .replace("\"__WALLET_ADDRESS__\"", userJson)
            .replace("\"__POOL_URL__\"", jsonString(poolUrl))
            .replace("\"__POOL_TLS__\"", "$poolTls")
            .replace("\"__RIG_ID__\"", jsonString(workerName))
            .replace("\"__THREADS_PERCENT__\"", "$threadsPercent")
            .replace("\"__THREAD_ARRAY_RX__\"", rxArray)
            .replace("\"__THREAD_ARRAY_LIGHT__\"", lightArray)
            .replace("\"__THREAD_ARRAY__\"", threadArray)
        configFile.writeText(config)
        metaFile.writeText(metaKey)
        Log.i(TAG, "Config: written (pool=$poolUrl tls=$poolTls rig=$workerName threads=$threadsPercent%)")
        return configFile
    }

    /** JSON string literal, quotes included. */
    private fun jsonString(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) {
                    sb.append("\\u%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun parseStatsLine(line: String) {
        var changed = false

        reSpeed.find(line)?.let { m ->
            val unitMult = when (m.groupValues[4].uppercase()) {
                "K" -> 1_000.0
                "M" -> 1_000_000.0
                "G" -> 1_000_000_000.0
                else -> 1.0
            }
            statsHashrate10s = (m.groupValues[1].toDoubleOrNull() ?: 0.0) * unitMult
            statsHashrate60s = (m.groupValues[2].toDoubleOrNull() ?: 0.0) * unitMult
            changed = true
        }

        rePool.find(line)?.let { m ->
            val newPool = m.groupValues[1]
            if (statsPool.isEmpty() && newPool.isNotEmpty()) {
                connectionStartMs = System.currentTimeMillis()
            }
            statsPool = newPool
            MiningState.poolError = ""
            changed = true
        }

        if (reDns.containsMatchIn(line)) {
            statsPool = ""
            connectionStartMs = 0L
            MiningState.poolError =
                "Can't reach pool (DNS). Check network / VPN."
            changed = true
        }

        if (reDisconnect.containsMatchIn(line)) {
            statsPool = ""
            connectionStartMs = 0L
            changed = true
        }

        reAccepted.find(line)?.let { m ->
            val newAccepted = m.groupValues[1].toIntOrNull() ?: statsAccepted
            val newRejected = m.groupValues[2].toIntOrNull() ?: statsRejected
            // APK2 VSWL: hash-chained local ledger, masked wallet only, on accepted increase.
            if (newAccepted > statsAccepted) {
                ShareLedger.recordAccepted(context, newAccepted, newRejected, statsPool, workerName)
            }
            statsAccepted = newAccepted
            statsRejected = newRejected
            changed = true
        }

        if (changed) emitStats()
    }

    fun emitStats() {
        val uptimeSecs = if (connectionStartMs > 0) {
            ((System.currentTimeMillis() - connectionStartMs) / 1000).toInt()
        } else {
            0
        }
        MiningState.hashrate10s = statsHashrate10s
        MiningState.hashrate60s = statsHashrate60s
        MiningState.accepted = statsAccepted
        MiningState.rejected = statsRejected
        MiningState.pool = statsPool
        MiningState.uptimeSecs = uptimeSecs
    }
}
