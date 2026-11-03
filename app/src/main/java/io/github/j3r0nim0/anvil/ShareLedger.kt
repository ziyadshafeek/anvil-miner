package io.github.j3r0nim0.anvil

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * VSWL (APK2 only) — Verified Sustained Work Ledger, local part only.
 * From docs/INVENTED-LOW-HASHRATE-POOL-METHOD.md.
 *
 * One hash-chained entry per INCREASE in accepted shares, written to
 * the app-private filesDir (never exported, never sent anywhere).
 * Entry fields: time, accepted, rejected, pool, worker, masked wallet
 * (first 6 + last 4 only), previous hash, entry hash.
 *
 * This is a consistency check between app and pool, NOT proof the pool
 * owes money. Only a MoneroOcean dashboard check and, eventually, an
 * on-chain payout to the user's own wallet prove that. IPSD/BMOS pool
 * changes are NOT implemented here — they need pool adoption.
 * No full wallet address is ever written to this file.
 */
object ShareLedger {
    private const val FILE_NAME = "vswl_ledger.txt"
    private const val MAX_ENTRIES = 1000

    fun recordAccepted(context: Context, accepted: Int, rejected: Int, pool: String, worker: String) {
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            val lines = if (file.exists()) file.readLines() else emptyList()
            if (lines.size >= MAX_ENTRIES) return // ledger full: stop, do not rewrite history
            val prevHash = lines.lastOrNull()?.substringAfterLast("|hash=", "GENESIS") ?: "GENESIS"
            val wallet = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                .getString(Prefs.WALLET, "") ?: ""
            val masked = if (Wallet.isValid(wallet)) Wallet.mask(wallet) else "unset"
            val body = "${System.currentTimeMillis()}|accepted=$accepted|rejected=$rejected|pool=$pool|worker=$worker|wallet=$masked|prev=$prevHash"
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(body.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            file.appendText("$body|hash=$hash\n")
        }
    }

    /** Verify the chain locally. Returns (ok, entries). */
    fun verify(context: Context): Pair<Boolean, Int> {
        return runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return Pair(true, 0)
            val lines = file.readLines().filter { it.isNotBlank() }
            var prev = "GENESIS"
            for (line in lines) {
                val body = line.substringBeforeLast("|hash=")
                val hash = line.substringAfterLast("|hash=", "")
                if (!body.endsWith("prev=$prev")) return Pair(false, lines.size)
                val calc = MessageDigest.getInstance("SHA-256")
                    .digest(body.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                if (calc != hash) return Pair(false, lines.size)
                prev = hash
            }
            Pair(true, lines.size)
        }.getOrDefault(Pair(false, 0))
    }
}
