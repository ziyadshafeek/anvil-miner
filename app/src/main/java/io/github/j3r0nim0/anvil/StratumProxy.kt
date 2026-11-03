package io.github.j3r0nim0.anvil

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Optional, off-by-default LAN Stratum proxy (pool-presets-7050).
 *
 * Listens on 0.0.0.0:3333 only when the user explicitly turns it on in
 * Settings. It never starts automatically when mining starts, and it
 * stops when the service is destroyed or the user turns it off.
 *
 * What it actually does (per LAN client, one upstream connection each):
 * - Accepts a TCP connection, rejects it unless the remote address is
 *   loopback or a private LAN address (10/8, 172.16/12, 192.168/16,
 *   169.254 link-local). A public-IP connection is closed, not proxied.
 * - Opens one connection to the selected upstream pool (TLS via
 *   SSLSocketFactory when the preset says TLS, otherwise plaintext).
 * - Intercepts the client's first login/authorize message and replaces
 *   its credentials with the phone's selected preset wallet + worker
 *   (prefix + stable worker name). The client's own wallet address is
 *   NEVER forwarded upstream and NEVER logged in full; only a masked
 *   connection event is logged. No seed, key, or password is ever
 *   read, stored, or logged here — upstream pass is the constant "x".
 * - Forwards upstream job notifications, difficulty messages, and
 *   submit responses verbatim to that client, and forwards that
 *   client's share submissions verbatim upstream, on that client's
 *   own upstream connection. Request ids are per-connection, so no
 *   global id mapping is needed.
 *
 * What it does NOT do (honest limit, not a placeholder claim):
 * - It does NOT multiplex many LAN clients onto a single upstream
 *   TCP connection with shared job ids. Each LAN client gets its own
 *   upstream connection, under the same worker name. A pool will see
 *   the same worker name on several connections; whether it aggregates
 *   their hashrate into one worker total is the pool's behaviour, not
 *   something this proxy can guarantee.
 * - It is NOT tested in this run against a real laptop XMRig or a
 *   real pool (no adb device, no on-device run). The on-device
 *   checklist in docs/POOL-PRESETS-7050-STATUS.md requires a real
 *   laptop XMRig connection to <PHONE_WIFI_IP>:3333 and a check that
 *   upstream accepted shares increase, before anyone calls this
 *   "merging hashes".
 * - It does not parse full Stratum JSON beyond method/id extraction
 *   for the login intercept; all other lines are forwarded verbatim.
 *   If a miner uses a non-line-delimited or non-JSON variant, that
 *   client will fail visibly (connection closed on error) rather
 *   than silently dropping shares.
 */
class StratumProxy(
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val upstreamTls: Boolean,
    private val upstreamUser: String,
    private val workerName: String,
) {
    companion object {
        const val PORT = 3333
        private const val TAG = "StratumProxy"
    }

    @Volatile var isRunning = false
        private set
    @Volatile var upstreamDescription = ""
        private set

    private var server: ServerSocket? = null
    private val clientSockets = mutableListOf<Socket>()
    private val lock = Any()

    fun start(): Boolean {
        if (isRunning) return true
        return try {
            val srv = ServerSocket(PORT, 50, InetAddress.getByName("0.0.0.0"))
            server = srv
            isRunning = true
            upstreamDescription = "$upstreamHost:$upstreamPort tls=$upstreamTls worker=$workerName"
            Log.i(TAG, "LAN proxy listening on 0.0.0.0:$PORT -> $upstreamDescription (opt-in only, LAN only)")
            thread(name = "stratum-proxy-accept", isDaemon = true) {
                while (isRunning) {
                    try {
                        val client = srv.accept()
                        handleClient(client)
                    } catch (_: Exception) {
                        if (isRunning) Log.w(TAG, "Proxy accept ended")
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Proxy start failed: ${e.message}")
            isRunning = false
            false
        }
    }

    fun stop() {
        isRunning = false
        runCatching { server?.close() }
        server = null
        synchronized(lock) {
            clientSockets.toList().forEach { runCatching { it.close() } }
            clientSockets.clear()
        }
        Log.i(TAG, "LAN proxy stopped")
    }

    private fun isLanAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress) return true
        val b = addr.address
        if (b.size != 4) return false
        val a = b[0].toInt() and 0xFF
        val c = b[1].toInt() and 0xFF
        return (a == 10) ||
            (a == 172 && c in 16..31) ||
            (a == 192 && c == 168) ||
            (a == 169 && c == 254)
    }

    private fun handleClient(client: Socket) {
        val remote = client.inetAddress?.hostAddress ?: "unknown"
        if (client.inetAddress == null || !isLanAddress(client.inetAddress)) {
            Log.w(TAG, "Proxy rejected non-LAN client $remote")
            runCatching { client.close() }
            return
        }
        thread(name = "stratum-proxy-client", isDaemon = true) {
            var upstream: Socket? = null
            try {
                upstream = if (upstreamTls) {
                    SSLSocketFactory.getDefault().createSocket(upstreamHost, upstreamPort)
                } else {
                    Socket(upstreamHost, upstreamPort)
                }
                synchronized(lock) { clientSockets.add(client); clientSockets.add(upstream) }
                Log.i(TAG, "Proxy client $remote connected, upstream open")
                val clientIn = BufferedReader(InputStreamReader(client.getInputStream()))
                val clientOut = BufferedWriter(OutputStreamWriter(client.getOutputStream()))
                val upIn = BufferedReader(InputStreamReader(upstream.getInputStream()))
                val upOut = BufferedWriter(OutputStreamWriter(upstream.getOutputStream()))
                // Upstream -> client, verbatim.
                thread(name = "stratum-proxy-upstream", isDaemon = true) {
                    try {
                        var line: String?
                        while (upIn.readLine().also { line = it } != null) {
                            synchronized(clientOut) {
                                clientOut.write(line); clientOut.newLine(); clientOut.flush()
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { client.close() }; runCatching { upstream.close() }
                    }
                }
                // Client -> upstream, intercepting only the first login/authorize.
                var loginDone = false
                var line: String?
                while (clientIn.readLine().also { line = it } != null) {
                    val raw = line ?: break
                    val outLine = if (!loginDone && isLoginLine(raw)) {
                        loginDone = true
                        upstreamLoginLine(raw)
                    } else {
                        raw
                    }
                    synchronized(upOut) {
                        upOut.write(outLine); upOut.newLine(); upOut.flush()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Proxy client $remote ended: ${e.message}")
            } finally {
                runCatching { client.close() }; runCatching { upstream?.close() }
                synchronized(lock) {
                    clientSockets.remove(client)
                    if (upstream != null) clientSockets.remove(upstream)
                }
            }
        }
    }

    private fun isLoginLine(line: String): Boolean {
        return line.contains("\"login\"") || line.contains("mining.authorize")
    }

    private fun upstreamLoginLine(clientLine: String): String {
        val idMatch = Regex("\"id\"\\s*:\\s*(\\d+)").find(clientLine)
        val id = idMatch?.groupValues?.get(1) ?: "1"
        val userJson = "\"" + upstreamUser.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return if (clientLine.contains("mining.authorize")) {
            "{\"id\":$id,\"method\":\"mining.authorize\",\"params\":[$userJson,\"x\"]}"
        } else {
            "{\"id\":$id,\"jsonrpc\":\"2.0\",\"method\":\"login\",\"params\":{\"login\":$userJson,\"pass\":\"x\",\"agent\":\"anvil-swarm-proxy\"}}"
        }
    }
}
