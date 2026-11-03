package io.github.j3r0nim0.anvil

import android.util.Log

/**
 * Full-7050 native bridge. All methods are safe fallbacks: if the native
 * library is absent (e.g. an older APK), they return a plain "native lib
 * unavailable" string instead of crashing. Nothing here mines Monero,
 * submits pool shares, or changes XMRig settings.
 *
 * - cpuSelfTest(): dual-stream AES-128 + FP64 + PMULL code path, checked
 *   against the FIPS-197 AES test vector in native code. PASS/FAIL is a
 *   correctness check only, never a hashrate.
 * - runGpuBenchOnce(count): Vulkan SHA-256 benchmark kernel only.
 *   It hashes count 64-byte blocks with a nonce in the first word and
 *   returns the first hash in hex plus the count. It does NOT parse
 *   stratum jobs, check a pool difficulty target, or submit shares,
 *   so it must never be quoted as GPU mining earnings.
 */
object NativeBench {
    private const val TAG = "NativeBench"

    private val loaded: Boolean = runCatching {
        System.loadLibrary("anvil_native")
        true
    }.getOrDefault(false)

    @JvmStatic private external fun nativeCpuSelfTest(): String
    @JvmStatic private external fun nativeBlackholeSelfTest(): String
    @JvmStatic private external fun nativeVulkanSha256(count: Int): String

    fun blackholeSelfTest(): String {
        if (!loaded) return "native lib unavailable"
        return runCatching { nativeBlackholeSelfTest() }.getOrElse { "blackhole self-test error: ${it.message}" }.also {
            Log.i(TAG, "Blackhole self-test: $it")
            MiningState.appendLog("Blackhole self-test: $it")
        }
    }

    fun cpuSelfTest(): String {
        if (!loaded) return "native lib unavailable"
        return runCatching { nativeCpuSelfTest() }.getOrElse { "cpu self-test error: ${it.message}" }.also {
            Log.i(TAG, "CPU self-test: $it")
        }
    }

    fun runGpuBenchOnce(count: Int): String {
        if (!loaded) return "native lib unavailable (Vulkan bench not built into this APK)"
        val safeCount = count.coerceIn(1, 65536)
        return runCatching { nativeVulkanSha256(safeCount) }.getOrElse { "GPU bench error: ${it.message}" }.also {
            Log.i(TAG, "GPU bench: $it")
            MiningState.appendLog("GPU bench: $it")
        }
    }
}
