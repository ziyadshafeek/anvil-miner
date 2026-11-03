# Frontier Workstream F — New low-level PoW algorithms and coins vs Monero rx/0

Scope: mining only, low-level focus. Baseline phone: 500 H/s RandomX sustained
(400–600 H/s range, Dimensity 7050, unmeasured on this phone — all $/month below
are estimates at 500 H/s, 24/7, before battery wear, before India tax, pool fee
noted where known).

## Baseline — Monero XMR, RandomX rx/0 — BENCHMARK

- Price ~$598.96–$599.03, 30 Oct 2026 (Kryptex / CoinWarz); network ~6.38 GH/s
  (Kryptex, 30 Oct 2026); tail emission 0.6 XMR / ~120 s = ~432 XMR/day
  (Kryptex daily emission figure 461.94 XMR includes fees, giving $0.61–$0.65/mo).
- Math: 500 / 6,380,000,000 × 432 × $599 = $0.0203/day = **$0.61/month gross**.
- Pool: MoneroOcean, 0% direct, minimum 0.003 XMR → ~87–100 days to first payout
  at 500 H/s. No pool proof problem: largest, most liquid RandomX coin.
- Verdict: BENCHMARK. Nothing below beats it on verified evidence.

## Ranked results

### 1. Tari XTM, RandomX — TIE (closest, not a beat)
- Kryptex, updated 29 Oct 2026: price $0.001050, network 47.01 MH/s,
  block 10,024.26 XTM, ~480 s block, daily emission 1,804,367 XTM.
- Math: 500 / 47,010,000 × 1,804,367 × $0.00105 = $0.0202/day = **$0.60/month**.
- Kryptex desktop cross-check: at 9,000 H/s, XTM revenue $135.34/yr vs XMR
  $133.78/yr — a +1.2% edge that flips by CPU/date (other Kryptex pages put XMR
  first). That is noise, not a frontier.
- Pool/minimum for a phone: Kryptex pool exists, phone-specific minimum and
  phone payout proof NOT verified in this hunt. Tari SHA3x is ASIC-dominated
  (580 TH/s, 30 Oct) and irrelevant to a phone; only Tari RandomX counts.
- Verdict: TIE. Same work, same money, weaker payout proof than XMR. Do not
  switch for +1% that disappears in fees/spread.

### 2. Salvium SAL, RandomX — LOSES
- Kryptex desktop: at 9,000 H/s, SAL revenue $73.06/yr vs XMR $133.78/yr.
- Phone scale: $73.06 / 12 / 18 = **~$0.34/month** at 500 H/s.
- Verdict: LOSES, ~55% of XMR.

### 3. Zephyr ZEPH, RandomX — LOSES
- AsicProfit: price $0.2052, network 28.5 MH/s, block 121 s, reward 3.8889 ZEPH.
- Direct math: ~2,777 ZEPH/day × (500 / 28,500,000) = 0.049 ZEPH/day
  × $0.2052 = $0.010/day = **~$0.30/month**.
- Kryptex cross-check: $60.03/yr at 9,000 H/s → $0.28/month at 500 H/s.
- Verdict: LOSES, ~45–50% of XMR. ASICs (Antminer X9/X5) already list ZEPH.

### 4. Xelis XEL, RandomX-family — LOSES
- Kryptex: at a 9,000 H/s RandomX CPU, Xelis effective ~6,300 H/s, revenue
  $65.10/yr → phone scale **~$0.30/month** or less.
- Verdict: LOSES, ~50% of XMR, different hashrate scale makes direct comparison
  messy — another reason not to chase it.

### 5. ArQmA rx/arq, Scala panthera, TurtleCoin cn-pico — LOSES as standalone
- VoltMiner, 22 June 2026, MoneroOcean XMRig v6.25.0-mo1, phone calibration:
  rx/arq 3,031 H/s (9× rx/0), cn-pico 1,802 H/s (5×), panthera 689 H/s (2×),
  rx/0 339 H/s baseline — yet MoneroOcean assigned rx/0 on BOTH phones and
  held it, because it weights price ÷ difficulty, not raw H/s.
- Wownero WOW price is itself contradictory across sources on 29–30 Oct 2026
  ($0.0067–$0.037), volume ~$0 on one listing — no reliable price/difficulty
  to beat XMR with, and no phone payout proof.
- Verdict: LOSES. Raw H/s is the trap this workstream was told to ignore.
  Keep MoneroOcean switching ON as a free hedge for a spike; do not force
  a light algo.

### 6. Raptoreum RTM, GhostRider — LOSES badly
- WhatToMine, Oct 2026: 530 H/s GhostRider = $0.01/day; 4,900 H/s = $0.12/day;
  network ~5–6 MH/s, block 3,427.5 RTM / ~121 s. Pools: zpool 1%, Mining-Dutch 2%.
- Phone GhostRider hashrate is NOT RandomX hashrate: desktop 5900X does
  3,400 H/s GhostRider vs ~16,000 H/s RandomX (~21%). A phone at ~100 H/s
  GhostRider scales to roughly $0.002/day = **~$0.06/month**, before pool fee.
  No proven Android GhostRider miner with phone payout proof was found.
- Verdict: LOSES, by an order of magnitude at phone scale.

### 7. RandomX v2 — WATCH, NOT a beat, NOT yet mineable as Monero
- XMRig 6.26.0 (Apr 2026) supports RandomX v2, but as of 27 Apr 2026 and again
  in Oct 2026 sources, Monero mainnet has NO confirmed activation date.
  FCMP++/Carrot stressnet v3 forked 5 Oct 2026 at block 3,102,800 — testnet only.
- Low-level change, real: program 256 → 384 instructions, total ops per hash
  4,456,448 → 6,815,744 (+52.9%, SChernykh gist, desktop), AES register mixing
  replaces simple XOR mixing. Secondary sources claim +5–8% CPU performance /
  +10–15% efficiency — those are NOT phone measurements and conflict with the
  heavier-hash arithmetic: H/s will fall, $/day depends on whether v2 keeps
  ASICs out and thus difficulty down, which is unknowable until activation.
- Verdict: WATCH. When/if Monero activates v2, rx/0 becomes rx/2 — it is the
  successor, not a competitor to beat rx/0 with. Do not build a phone miner
  for v2 Monero today; it would mine nothing on mainnet. Borrow only its
  mechanisms (deeper prefetch, AES mixing to fill memory waits) if they test
  faster on v1.

### 8. Zecnero ZMR, rx/zecnero — WATCH, $0 today
- Sep 2026 Bitcointalk/dev.to: Zcash-derived, shielded-only, RandomX with only
  the Argon2 salt changed, 17 test vectors across 4 implementations, testnet
  24 Sep 2026, XMRig fork with donate 0, desktop reference ~8,200 H/s on
  i7-13700KF (16 threads, huge pages).
- Mainnet date NOT announced, price $0, pool pool.zecnero.org is testnet.
  Same hardware efficiency as RandomX by design — it cannot beat XMR on
  efficiency, only on early low difficulty, which is speculation, not proof.
- Verdict: WATCH, LOSES today ($0). Re-check only after a mainnet date,
  a live price, and a phone payout.

### 9. Nerva XNV, CryptoNight-Adaptive v8 — LOSES on practicality, UNKNOWN on raw math
- Nerva, 2026: HF14 at block 4,480,000 (~10 Nov 2026), CNA v8 restores 1 MB core,
  drops HF13's 8 MB scratchpad/VM, ties hash to full chain history, CPU-only,
  POOL-RESISTANT by design (no pools), solo only. Network after HF13:
  25–40 kH/s, difficulty ~2M, block 60 s, reward 0.3 XNV, tail emission.
  Price contradictory: $0.128 (CoinGecko) vs $0.103 (CryptoTracker);
  24h volume only $289–$588.
- Phone CNA hashrate was NOT verified, and solo variance on a phone is fatal:
  no pool means no steady accrual, and $500/day total market volume means a
  phone's coins may not convert at the listed price at all.
- Verdict: LOSES for this goal. Interesting low-level design (1 MB, chain-tied
  hash, no pool), unusable as phone income proof.

### 10. Dilithion DIL, RandomX + post-quantum — UNKNOWN, no beat evidence
- Whitepaper v2, Mar 2026: mainnet Jan 2026, RandomX PoW, Dilithium signatures.
  No price, network hashrate, pool, or phone payout found in this hunt.
- Verdict: UNKNOWN. Same algorithm as XMR, so no low-level edge is claimed;
  do not count it until a pool + price + payout exist.

### 11. Bitflash PoW v2 — UNKNOWN, tiny chain
- Bitflash docs: RandomX with different input (83 bytes, nonce moved) and key,
  mainnet switch 21 Sep 2026. No price, pool, or phone miner verified.
- Verdict: UNKNOWN. A different RandomX keying is not a faster RandomX.

### 12. HashCore — RESEARCH ONLY, no coin, no miner, no $/month
- Paper/site: design a PoW modelled on SPEC CPU 2017 workloads so a general
  CPU is by definition the best hardware — an inverted ASIC argument.
  Formal collision-resistance claim, no implementation, no testnet, no pool,
  no price, no benchmark on ARM.
- Verdict: RESEARCH ONLY. The idea is the frontier in principle (stop fighting
  ASICs, co-opt the CPU), but it pays $0 today and cannot be mined on a phone
  today. Do not present it as a beat.

### 13. qRandomX — LOSES
- QTC whitepaper: Kyber1024 + RandomX + Cuckoo Cycle + SHA3/BLAKE3 hybrid.
  Its own table: qRandomX 8 threads ~15 H/s, RandomX only ~25 H/s (desktop
  figures, implementation partial, needs completion).
- Verdict: LOSES. Slower than RandomX by its own numbers, no live coin/pool.

### 14. PoWR (Jung et al., Sustainability 2024) — RESEARCH ONLY
- Peer-reviewed 2024: random selection reduces PoW work, CPU participation,
  energy saving vs ASICs. No live coin, pool, or price found.
- Verdict: RESEARCH ONLY, not a mineable beat.

### 15. Frontier RISK, not a beat — RandomX ASICs
- 2026 guides list Antminer X5 (212 kH/s), X9 (1 MH/s), Pinecone R1X (1.2 MH/s)
  for RandomX. If ASIC share grows, phone share of XMR — and of every RandomX
  coin above — falls. This hurts the baseline, it does not create a new beat.
  RandomX v2 is Monero's answer to this risk, which is why it is watch-only.

## Bottom line for the parent

- BEATS Monero rx/0 on verified Oct 2026 evidence: NONE.
- TIES: Tari RandomX only (~$0.60/mo vs $0.61/mo, within noise/fees).
- LOSES: Salvium, Zephyr, Xelis, ArQmA/Scala/TurtleCoin standalone,
  GhostRider/Raptoreum, Nerva on practicality, qRandomX.
- WATCH: RandomX v2 (Monero successor, not a competitor), Zecnero (testnet),
  HashCore / PoWR (papers, no coin).
- UNKNOWN, do not count: Dilithion, Bitflash PoW v2, Nerva raw phone hashrate.
- The frontier, honestly, is NOT a new coin today. It is: (a) RandomX v2
  activation timing, (b) whether ASICs force a difficulty regime change,
  (c) HashCore-style GPP-native PoW if anyone ships it, and (d) the
  low-level phone work itself (affinity, JIT, cache) — which is the other
  factory workstreams' job, not a coin switch.
- No seed/private key was requested, used, or entered. No wallet address
  was invented. No live-browser action was taken; all above is web-search
  text, with prices/dates as found on 30 Oct 2026 sources.
