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
    // APK2 only: GCSPC predictive step-down switch (Settings). Default ON in APK2.
    const val GCSPC_ON = "gcspc_on"

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
