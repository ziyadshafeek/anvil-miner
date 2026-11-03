package io.github.j3r0nim0.anvil

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val NAME = "settings"

    const val WALLET = "wallet"
    const val THREADS = "threads"
    const val THERMAL = "thermal"
    const val CHARGING_ONLY = "charging_only"
    const val WIFI_ONLY = "wifi_only"
    const val QUIET_ON = "quiet_on"
    const val QUIET_START = "quiet_start" // minutes from midnight
    const val QUIET_END = "quiet_end"
    const val DEVICE_HARM = "device_harm"
    const val CPU100 = "cpu100_count"
    const val UPDATE_CHECK = "update_check"
    // Thread mode: 0 = percent steps (25/50/75/100), 1 = big cores only (2 threads, affine)
    const val THREAD_MODE = "thread_mode"
    const val THREAD_MODE_BIG_ONLY = 1
    // 2026-11-03, app-layer only: 2 = asymmetric (2 big + little, all affine, same algo).
    // This is NOT two algos at once: one XMRig process runs one pool-assigned algo.
    // Little threads run the same algo and will contend for shared L3/bus; the
    // 60s sustained log is the test, not a pre-declared win.
    const val THREAD_MODE_ASYMMETRIC = 2
    // 2026-11-03 (full-7050): 3 = L3-isolated per-algo.
    // rx/0, rx/wow, cn-heavy and cn/ccx use big cores only; cn-pico, cn-lite,
    // cn and cn/r use little cores only. This is STILL one algo at a time:
    // XMRig CpuBackend::setJob stops and restarts workers when the pool
    // assigns a different algo, so the per-algo array only selects which
    // cores run WHEN that algo is assigned. It does not run rx/0 and
    // cn-pico simultaneously. Simultaneous dual-algo would need two XMRig
    // processes, two pool connections and two datasets, and is NOT done
    // here (duplicate worker, double memory, thermal cost, pool risk).
    const val THREAD_MODE_L3_ISOLATED = 3
    // 2026-11-03 (pool-presets-7050): 4 = 4-thread mask, 5 = 8-thread mask.
    // Mask 4 is big cores + the last 2 little cores by sysfs order
    // (cpu4, cpu5 on the expected Dimensity 7050 layout), leaving the
    // first little cores for Android itself. Mask 8 is all cores.
    // Both are discovered by max frequency on the phone, never hardcoded:
    // see XMRigManager.coresByMaxFreqDesc. Labels name cpu6,7/cpu4,5
    // only as the expected layout for this phone.
    const val THREAD_MODE_MASK4 = 4
    const val THREAD_MODE_MASK8 = 5
    // Pool preset selection (PoolPresets.kt). Default = MoneroOcean APK2 default.
    const val POOL_PRESET_ID = "pool_preset_id"
    // LAN swarm proxy (StratumProxy.kt). Off by default, opt-in only.
    const val SWARM_PROXY_ON = "swarm_proxy_on"
    // APK2 only: GCSPC predictive step-down switch (Settings). Default ON in APK2.
    const val GCSPC_ON = "gcspc_on"

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
