package io.github.j3r0nim0.anvil

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tag: String,
    val versionName: String,
    val htmlUrl: String,
)

object UpdateChecker {
    // Fork repo only. Must not phone home to the upstream Anvil repo.
    private const val API =
        "https://api.github.com/repos/ziyadshafeek/anvil-miner/releases/latest"

    fun fetch(onResult: (UpdateInfo?) -> Unit) {
        Thread {
            val info = runCatching { fetchBlocking() }.getOrNull()
            Handler(Looper.getMainLooper()).post { onResult(info) }
        }.apply { isDaemon = true; name = "anvil-update"; start() }
    }

    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parse(v: String): List<Int> =
        v.trim().removePrefix("v").split('.', '-', '_')
            .mapNotNull { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() }

    private fun fetchBlocking(): UpdateInfo? {
        val conn = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Anvil-Android")
        }
        return try {
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            val tag = json.optString("tag_name")
            if (tag.isBlank()) return null
            UpdateInfo(
                tag = tag,
                versionName = tag.removePrefix("v"),
                htmlUrl = json.optString("html_url").ifBlank {
                    "https://github.com/ziyadshafeek/anvil-miner/releases"
                },
            )
        } finally {
            conn.disconnect()
        }
    }
}
