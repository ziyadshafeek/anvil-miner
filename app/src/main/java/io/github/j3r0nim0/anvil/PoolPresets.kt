package io.github.j3r0nim0.anvil

/**
 * Pool presets for pool-presets-7050 — selectable in Settings, NOT a forced default.
 *
 * How selection works (see MiningService / MainActivity):
 * - The saved preset id lives in Prefs.POOL_PRESET_ID. Default is
 *   "moneroocean-default" (gulf.moneroocean.stream:20032, TLS), unchanged
 *   from APK2. Nothing in this file changes the default.
 * - A preset is only used when the user explicitly picks it in Settings
 *   AND enters his own public wallet address for that coin. No wallet
 *   address is hard-coded anywhere in this file, and no preset is wired
 *   to mining at app start without that explicit choice.
 * - Payout, fee, bonus and network-hashrate notes are UI TEXT ONLY,
 *   copied from the user's preset list. They are NOT verified claims
 *   unless the matching `verified` flag is true, and that flag is only
 *   set when the pool's own page/source was actually checked. See
 *   docs/POOL-PRESETS-7050-STATUS.md for the per-preset verdict.
 * - The user creates and holds every wallet himself. This app never
 *   asks for, stores, or transmits a seed phrase, private key, or
 *   password. For Monero presets the existing Cryptonote checksum
 *   validation applies; for other coins the app cannot checksum-
 *   verify the address, so the UI warns the user to check the first
 *   and last characters himself and to never paste seed words.
 */
object PoolPresets {
    data class Preset(
        val id: String,
        val name: String,
        val host: String,
        val port: Int,
        val tls: Boolean,
        val tlsPort: Int? = null,
        val algo: String = "rx/0",
        /** Null = pool auto / vardiff. Non-null = XMRig sends wallet+N. */
        val startDifficulty: Int? = null,
        /** Prepended to the stable worker name, e.g. "RZP3-". Empty = none. */
        val workerPrefix: String = "",
        /** True only if the wallet format is a Monero Cryptonote address. */
        val requiresMoneroAddress: Boolean = false,
        /** True only if this preset's host/port/TLS was checked against the pool's own source. */
        val verified: Boolean = false,
        val note: String,
    )

    val moneroOceanDefault = Preset(
        id = "moneroocean-default",
        name = "MoneroOcean (APK2 default)",
        host = "gulf.moneroocean.stream",
        port = 20032,
        tls = true,
        requiresMoneroAddress = true,
        verified = false,
        note = "APK2 current default, kept unchanged. Note: no official " +
            "MoneroOcean port list was fetched in this run confirming 20032, " +
            "so this is kept as the existing default, not a re-verified fact.",
    )

    val presets: List<Preset> = listOf(
        moneroOceanDefault,
        Preset(
            id = "feelcoin-official",
            name = "Feelcoin Official Pool",
            host = "pool.feelcoin.org",
            port = 4242,
            tls = false,
            tlsPort = 4244,
            startDifficulty = 100,
            note = "User-named: rx/0, start diff 100, 0.33 FEEL auto-payout, " +
                "0.5% fee, TLS option port 4244. UNVERIFIED in this run — " +
                "payout/fee figures are UI text only, not checked claims.",
        ),
        Preset(
            id = "veinpool-feelcoin-pplns",
            name = "VeinPool Feelcoin PPLNS",
            host = "veinpool.fr",
            port = 3290,
            tls = false,
            note = "User-named: rx/0, VeinPool Feelcoin PPLNS. UNVERIFIED in this run.",
        ),
        Preset(
            id = "veinpool-feelcoin-squad",
            name = "VeinPool Feelcoin Open Squad",
            host = "veinpool.fr",
            port = 3293,
            tls = false,
            workerPrefix = "RZP3-",
            note = "User-named: rx/0, Open Squad, prepend RZP3- to worker name, " +
                "+5% small-miner bonus claimed by the user. The bonus is " +
                "UNVERIFIED in this run — UI text only.",
        ),
        Preset(
            id = "veinpool-feelcoin-solo",
            name = "VeinPool Feelcoin SOLO",
            host = "veinpool.fr",
            port = 3292,
            tls = false,
            note = "User-named: rx/0, VeinPool Feelcoin SOLO. UNVERIFIED in this run.",
        ),
        Preset(
            id = "veinpool-lajkto-solo",
            name = "VeinPool Lajkto Coin SOLO (0% fee)",
            host = "veinpool.fr",
            port = 3702,
            tls = false,
            note = "User-named: rx/0, 0%-fee direct SOLO, wallet from " +
                "lajkto.eu/lkct. The 0% fee is UNVERIFIED in this run — UI text only.",
        ),
        Preset(
            id = "veinpool-wam",
            name = "VeinPool WAM Coin",
            host = "veinpool.fr",
            port = 3310,
            tls = false,
            note = "User-named: rx/0, 185 kH/s network claimed by the user. " +
                "That network figure is UNVERIFIED in this run — UI text only.",
        ),
        Preset(
            id = "moneroocean-tari-merge",
            name = "MoneroOcean + Tari XTM Merge-Mining (UNVERIFIED)",
            host = "gulf.moneroocean.stream",
            port = 10001,
            tls = false,
            requiresMoneroAddress = true,
            verified = false,
            note = "UNVERIFIED — DO NOT present as verified. Port 10001 is " +
                "only a third-party table candidate (plaintext, start diff " +
                "1000, 'mobile' in those tables), not an official MoneroOcean " +
                "source, and no verified HashVault/MoneroOcean Tari merge " +
                "preset was found in earlier work. Selecting this preset " +
                "does not guarantee any Tari XTM payout. Test by connecting " +
                "and watching the XMRig log for 'new job' before trusting it.",
        ),
    )

    fun byId(id: String): Preset = presets.firstOrNull { it.id == id } ?: moneroOceanDefault

    /** Fork's own default, documented only, not selectable as a mining preset here. */
    val forkDefault = Preset(
        id = "moneroocean-fork-default",
        name = "MoneroOcean fork default (not wired in)",
        host = "gulf.moneroocean.stream",
        port = 10128,
        tls = false,
        note = "MoneroOcean/xmrig v6.25.0-mo1 src/config.json default. Plaintext. Not wired in.",
    )
}
