package io.github.j3r0nim0.anvil

import java.util.Calendar

/** Inclusive-start exclusive-end window in minutes from midnight. Handles overnight. */
object QuietHours {
    fun inWindow(startMin: Int, endMin: Int, nowMin: Int = nowMinutes()): Boolean {
        if (startMin == endMin) return false
        return if (startMin < endMin) {
            nowMin in startMin until endMin
        } else {
            nowMin >= startMin || nowMin < endMin
        }
    }

    fun nowMinutes(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
    }

    fun format(minutes: Int): String {
        val h = ((minutes / 60) % 24 + 24) % 24
        val m = ((minutes % 60) + 60) % 60
        return "%02d:%02d".format(h, m)
    }
}
