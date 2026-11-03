package io.github.j3r0nim0.anvil

/**
 * Full-7050 pool presets — documentation and verification status only.
 *
 * NOT wired into MiningService, on purpose. MiningService keeps the
 * existing default (gulf.moneroocean.stream:20032, TLS) unless the user
 * explicitly chooses a different pool in a future step, after a test
 * connection. Changing a pool changes who holds pending balances, so
 * it must never be flipped silently by a code change.
 *
 * VERIFIED (from the fork's own source, MoneroOcean/xmrig v6.25.0-mo1):
 * - Algo-perf is real in this fork: WITH_MO_BENCHMARK defaults to ON
 *   (CMakeLists.txt:29), MoBenchmark benchmarks each algo and Network
 *   sends "algo-perf" on login, and the pool then assigns jobs by
 *   price/difficulty. APK2 preserves the config file across restarts
 *   (XMRigManager meta-key logic), so re-starts do not re-benchmark.
 *   GhostRider is also a supported family in CpuConfig::generate.
 *   None of this guarantees the pool will assign anything other than
 *   rx/0 on this phone; on measured phones it assigned rx/0.
 * - Fork default pool in src/config.json: gulf.moneroocean.stream:10128,
 *   tls=false (plaintext). This is the fork's own default, not the
 *   APK's current default (20032/TLS). The two differ, and the APK
 *   default is kept until the user tests 10128 himself.
 *
 * NOT VERIFIED by an official MoneroOcean page in this run, and
 * therefore NOT added as selectable presets:
 * - gulf.moneroocean.stream:10001 — third-party tables describe it as
 *   plaintext, starting difficulty 1000, "mobile". By the 10xxx ->
 *   20xxx pattern in those tables, 20001 would be its TLS twin.
 *   That pattern is consistent but is not an official MoneroOcean
 *   source, and the official pool page was not fetched here, so
 *   10001 is a CANDIDATE TO TEST (connect and watch the XMRig log
 *   for "new job"), not a preset. Do NOT switch tls to false for it
 *   without that test, and do NOT present it as verified.
 *   Note the same caveat applies in reverse to the APK's own
 *   current default 20032: no official MoneroOcean port list was
 *   fetched in this run confirming it either; by the same pattern
 *   it looks like the TLS counterpart of 10032, but that is
 *   inference, not a verified fact. It is kept only because it is
 *   the existing default, and a live check of MoneroOcean's own
 *   Getting Started page is still owed before it is re-affirmed.
 * - HashVault + Tari XTM merge: HashVault's own page
 *   (monero.hashvault.pro) shows pool.hashvault.pro, RandomX,
 *   PPLNS/SOLO, 0.9% fee, pool-config minimum 0.001 XMR but default
 *   wallet minimum 0.11 XMR, hourly payouts — and nothing about
 *   Tari/XTM, no Tari wallet field, no merge config. A third-party
 *   aggregator lists "Tari XTM + XMR merged" with no host, port,
 *   TLS flag, or wallet/coin/pass syntax. NOT added.
 * - DragonX: the coin is real (custom RandomX, DRGX), but its pool
 *   endpoint rests only on single-author social screenshots
 *   (pool.dragonx.is:3433, plaintext, 1% / 3% solo) and its pool
 *   client is DRG-XMRIG, not stock MoneroOcean XMRig. NOT added.
 * - Verus PBaaS: LuckPool's own connect page verifies VerusHash 2.2
 *   + PBaaS at na./eu./ap.luckpool.net, CPU port 3960 plaintext,
 *   SSL 3958 (plus 3957/3956), username = VRSC address + .rig,
 *   password X, merge-mining only for R-addresses — but VerusHash
 *   is mined with ccminer/SRBMiner-class miners, not XMRig, so it
 *   cannot be added as an XMRig-MO pool entry. NOT added.
 *
 * Payout note (third-party, not official): MoneroOcean minimum payout
 * is widely listed as 0.003 XMR. Verify on moneroocean.stream before
 * quoting it to a user; the APK itself does not set payouts.
 */
object PoolPresets {
    data class Preset(val host: String, val port: Int, val tls: Boolean, val note: String)

    /** The only preset the app actually uses today. */
    val currentDefault = Preset(
        host = "gulf.moneroocean.stream",
        port = 20032,
        tls = true,
        note = "APK2 current default, kept unchanged in full-7050.",
    )

    /** The fork's own default, documented for a future manual test. */
    val forkDefault = Preset(
        host = "gulf.moneroocean.stream",
        port = 10128,
        tls = false,
        note = "MoneroOcean/xmrig v6.25.0-mo1 src/config.json default. Plaintext. Not wired in.",
    )
}
