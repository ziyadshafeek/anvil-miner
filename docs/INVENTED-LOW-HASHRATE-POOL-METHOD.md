# Invented Method: Low-Hashrate Phone Mining Settlement (LHPMS)

## A phone-only pool, payout and verification method for a ~500 H/s miner

**Scope, stated first.** Mining only. Phone only. No laptop node, no
P2Pool laptop node, no custodian token, no DePIN balance, no new coin,
no change to Monero consensus, and no handling of a seed phrase,
private key or wallet password at any point. Only a public Monero
receiving address is ever sent to a pool. No earnings figure is
pre-claimed in this paper. Every number that would become an earnings
figure is left as a variable to be measured in the experiment in
§7, because a ~500 H/s phone has not yet been measured under this
method.

**Status of this document.** This is an invention and a proposal, not
a description of an existing pool. §8 separates, in a table, what
already works today with no pool change, what is a proposal that a
pool would have to implement, and what is not claimed at all. Reading
only the method section without §8 would misrepresent the invention.

---

## Abstract

A phone mining at ~500 H/s faces two distinct problems, which are
often conflated. The first is a *rate* problem: 500 H/s is a small
share of a multi-GH/s network, so expected earnings per unit time are
small. No pool method can fix that without changing the phone's
hashrate or the network. The second is a *variance and verifiability*
problem: under Pay-Per-Last-N-Shares (PPLNS), a low-hashrate miner's
accrual is lumpy, its time to a payout threshold is highly variable,
and the only proof of work done before payout is a number shown in
an app or on a pool web page, which the user cannot independently
re-derive.

This paper invents Low-Hashrate Phone Mining Settlement (LHPMS),
three linked components:

1. **VSWL — Verified Sustained Work Ledger (works today, no pool
   change).** A tamper-evident, hash-chained local ledger of accepted
   stratum shares, bound to pool dashboard snapshots, that lets a
   user or a third party check that app-reported work matches
   pool-reported work, without trusting the app alone.
2. **IPSD-PPLNS — Integral-Preserving Slow-Decay PPLNS (proposal,
   pool must implement).** A replacement for the rectangular PPLNS
   window, in which a low-hashrate miner's share weight decays
   slowly instead of expiring abruptly, scaled so that total
   expected payout per unit of work is unchanged. Accrual becomes
   smoother and time-to-threshold becomes more predictable, at the
   cost of smaller payouts per individual block.
3. **BMOS — Batched Multi-Output Settlement (proposal, pool must
   implement).** A pool, on a fixed schedule, pays many low-balance
   miners in a single standard Monero transaction with many outputs,
   sharing the fixed transaction overhead. This can lower the
   economic payout threshold without changing consensus, because
   the fee per miner falls. It does not eliminate the pool holding
   a balance until settlement; it shortens that holding time.

Together, LHPMS does not increase expected earnings. Its claim is
narrower and falsifiable: it reduces accrual variance and
verification opacity for a low-hashrate phone miner, while
preserving expected value, Monero consensus, and a direct-to-wallet
payout.

---

## 1. Problem

### 1.1 Definitions

- *Miner hashrate* $h$, here ~500 H/s as a working assumption, not a
  measured result for any particular phone.
- *Network hashrate* $H$, *block reward* $R$ (Monero tail emission
  is 0.6 XMR per 2-minute block, i.e. 432 XMR/day, as a protocol
  fact, not a price claim).
- *Stratum share:* a miner submission meeting the pool's share
  difficulty $d_s$ (for example 30,000 in the Anvil fork). Stratum
  shares prove work to the pool; they are not, by themselves,
  payout shares.
- *PPLNS window:* the last $N$ shares, or the last $W$ time, that
  count when a block is found. In Monero P2Pool the window is
  2,160 shares. In a central pool the window is a pool policy, not
  a consensus rule.
- *Payout threshold* $T$: the minimum balance at which a central
  pool sends an on-chain payment. For MoneroOcean, published
  material lists 0.003 XMR. That figure must be re-checked live
  before any experiment, because pools can change policy.

### 1.2 The three sub-problems

**P1 — Rectangular-window variance.** Under rectangular PPLNS, a
share contributes weight 1 if a block is found while it is inside
the window, and weight 0 otherwise. For a low-hashrate miner, the
time between its own shares is long, and the chance that *no* block
is found while a given share is in the window — or, in P2Pool, that
a sidechain share is not found in time at all — is material. The
result is not a lower expected value in the long run, but a high
variance in *when* value accrues and *when* a threshold is reached.
A user who sees "pending: 0" for weeks cannot distinguish "pool is
slow" from "my miner is broken" from "variance".

**P2 — Threshold economics.** A central pool will not send a
separate on-chain transaction for a dust balance, because the
transaction fee and the output cost would consume a large fraction
of it. The threshold is therefore an economic, not a consensus,
choice. For a phone, a threshold designed for desktop miners
translates into a wait of many weeks to months. During that wait
the balance is custodial in the limited sense that the pool holds
the accounting until it pays; there is no on-chain proof of it.

**P3 — Verification opacity.** Before payout, the only evidence is
(i) the miner app's own counters, which are written by the same
software whose correctness is in question, and (ii) the pool's
dashboard, which is a web page controlled by the pool. Neither,
alone, is an independent proof. A user cannot re-derive "my
accepted shares should be X" from a durable, checkable record.

These are the problems LHPMS addresses. It does not address the
rate problem: $h/H$ is what it is.

---

## 2. Hypothesis

**H1 (variance smoothing).** Replacing a rectangular PPLNS window
with an integral-preserving slow-decay weight for low-hashrate
miners reduces the variance of cumulative accrual over a fixed
horizon, while preserving expected accrual per unit of share
difficulty, under a Poisson block-arrival model.

**H2 (batch economics).** Batching $N$ low-balance payouts into one
standard multi-output Monero transaction reduces the fee per miner
below the fee of $N$ separate transactions, so a pool can lower its
payout threshold for batched miners without the fee share rising
above a stated cap, provided $N$ is large enough and the batch
schedule is fixed.

**H3 (verifiability without a node).** A phone-only miner can
maintain a hash-chained ledger of its own accepted stratum shares
and of pool dashboard snapshots, such that any discrepancy between
app counters, ledger, and dashboard is detectable, without running
a Monero node, without a laptop, and without trusting the app
alone.

Each hypothesis has a falsification criterion in §7.4. If any
fails, that component is dropped; the others do not depend on it
for their own claims, except that BMOS without IPSD still works,
and VSWL works without either.

---

## 3. Related work

This section is deliberately short: it names only work that bears
directly on the mechanism, and marks what each does *not* do for a
phone.

- **PPLNS, PPS, FPPS.** Standard pool reward schemes. PPS/FPPS move
  variance to the pool (the pool bears it, and charges for it);
  PPLNS leaves variance with the miner and is withholding-resistant
  in the APoW classification (PPLNS: variance bearer is the miner,
  withholding not profitable). LHPMS keeps PPLNS's withholding
  property; it changes the *shape* of the window, not the bearer.
- **P2Pool (SChernykh, Monero).** Decentralised PPLNS with a
  sharechain: miners are paid directly in the coinbase transaction,
  proportional to share difficulty in the window, with no operator
  wallet. This solves custody, but worsens P1 for a phone: a phone
  must first find a *sidechain* share, which at ~500 H/s takes on
  the order of days, and that share must still be in the window
  when a Monero block is found. P2Pool is therefore the
  decentralisation reference, not the low-hashrate solution, and
  it requires a node, which this paper excludes by scope.
- **MoneroOcean profit-switching.** A central pool that benchmarks
  algorithms, assigns the most profitable RandomX-family job by
  price/difficulty, and pays in XMR. On measured phone calibrations
  it assigned plain `rx/0` (Monero) and held it. LHPMS is compatible
  with profit-switching: IPSD weights share difficulty regardless
  of which algorithm produced the share, and BMOS pays in XMR.
  Profit-switching does not solve P1–P3.
- **FIBERPOOL (Sakurai, arXiv:2501.15459, preprint).**
  Decentralised pooled mining via a main chain, a storage chain
  for share verification, and a child chain for cheap withdrawal,
  designed to fix P2Pool's early-stage sharechain capacity and
  security problems and SmartPool's budget-balance and fee
  problems. It is a pool-design reference for share verification,
  but it is not implemented for Monero, not available to a phone
  miner today, and it does not solve a phone's share-window
  problem by itself.
- **Smart contract pools (Papathanasiou et al., IEEE Blockchain
  2024).** Notes that P2Pool, while pioneering, suffered high
  performance problems under low share difficulty. Monero has no
  general smart-contract layer, so a smart-contract enforcement
  mechanism cannot be imported. This is why VSWL receipts in this
  paper are *reputational and checkable*, not *enforceable
  on-chain* — see §6.3.
- **Stratum v1 / v2.** Monero/XMRig mining uses Stratum v1
  (JSON-RPC, here over TLS). Stratum v2 is a Bitcoin protocol
  (job declaration, template distribution). It is cited only to
  avoid a category error: LHPMS does not claim Stratum v2 for
  Monero, and does not require it. VSWL uses existing Stratum v1
  share responses, which already exist.
- **Heterogeneous scheduling and thermal work (HARP, FBHS, Phoenix,
  and related 2025–2026 work).** Relevant to *how fast* a phone can
  sustain hashrate, not to *how it is paid*. LHPMS assumes a
  sustained hashrate is already being measured by the test
  protocol in the factory workstreams; it does not re-derive it.

**Gap.** No work found combines (a) a variance-smoothing window
that preserves expected value, (b) a batched settlement that
lowers the threshold, and (c) a phone-only verification ledger,
for a sub-kH/s miner, without requiring a node, a token, or a
consensus change. That combination is the invention.

---

## 4. Method

### 4.1 Component 1 — VSWL: Verified Sustained Work Ledger (works today)

**Idea.** The miner already receives, for every accepted share, a
Stratum response that the pool has validated. VSWL turns those
responses, plus periodic pool dashboard snapshots, into a
hash-chained, exportable record that the user can check against
both the app and the pool, without trusting either alone.

**Record format (one entry per accepted share, plus one per snapshot).**

Share entry:

```text
{
  "type": "share",
  "worker": "<worker id, e.g. AX-… — actual value only, never invented>",
  "wallet_trunc": "<first 6 + last 4 of public address only, in the ledger UI>",
  "job_id": "<pool job id from Stratum>",
  "share_difficulty": <pool share difficulty>,
  "timestamp_unix": <phone clock, plus pool time if the pool returns it>,
  "prev_hash": "<hash of previous ledger entry>",
  "entry_hash": "<hash of this entry, excluding entry_hash itself>"
}
```

No nonce, no private data, and no full wallet address need appear
in a *shared* ledger export; the full address is already known to
the pool and appears in the pool dashboard URL, but a ledger
exported for a third party to check should carry only the
truncated form plus the worker id, to avoid gratuitous exposure.
The on-phone ledger may store the full address in the same
private storage already used for the wallet, and nowhere else.

Snapshot entry, once per day (or once per 6 hours for a test):

```text
{
  "type": "dashboard_snapshot",
  "timestamp_unix": <…>,
  "pool_reported_hashrate": <as shown>,
  "pool_reported_accepted": <as shown>,
  "pool_reported_pending": "<as shown, in XMR, with pool and date>",
  "prev_hash": "<…>",
  "entry_hash": "<…>"
}
```

**Checks VSWL enables, with no pool change:**

1. *Monotonicity.* Ledger accepted-share count must be
   non-decreasing, and must not jump by more than the shares
   actually received in the interval.
2. *App-vs-ledger.* App counters must equal ledger totals for the
   same interval. A mismatch is a bug in the app, detectable
   without the pool.
3. *Ledger-vs-dashboard.* Pool-reported accepted shares must be
   greater than or equal to ledger accepted shares minus a stated
   tolerance for shares still in flight at snapshot time, and
   must move in the same direction over days. A dashboard that
   stays at zero while the ledger grows is a fault signal (wrong
   wallet, wrong pool, dead worker), not a variance mystery.
4. *Chain integrity.* Recomputing `entry_hash` over the chain must
   reproduce the head hash. Any edit to a past entry breaks the
   chain. This does not prove the pool accepted the shares — only
   the pool can do that — but it proves the *record* has not been
   silently rewritten after the fact.

**What VSWL is not.** It is not a proof that the pool owes money.
It is a proof that a record is internally consistent and that app
and pool agree or disagree, in a checkable way. A pool that
refuses to show a dashboard, or that shows a different worker,
fails check 3, and that failure is itself the finding.

**Cost.** One hash per share, plus one snapshot per day, stored
locally. At phone share rates this is negligible compared with
mining itself. Export is a plain text file the user can keep;
it contains no seed, no key, no password.

### 4.2 Component 2 — IPSD-PPLNS: Integral-Preserving Slow-Decay PPLNS (proposal)

**Idea.** Keep PPLNS, change the window shape for miners below a
pool-stated low-hashrate threshold $h_{low}$ (for example, a pool
might set $h_{low}$ = 2,000 H/s; the exact value is a pool policy
choice, not a claim in this paper).

Standard rectangular window, for a share of difficulty $d$
submitted at time $t_0$, weight at block time $t \ge t_0$:

```text
w_rect(t) = d   if t - t_0 < W
            0   otherwise
```

where $W$ is the window length. Total weight-time integral is
$d \cdot W$.

Slow-decay window, same share:

```text
w_decay(t) = d * a * exp(-lambda * (t - t_0))   for t >= t_0
```

Choose $a$ and $\lambda$ so that the integral is preserved:

```text
integral_0^inf w_decay(t) dt = d * a / lambda = d * W
  =>  a = W * lambda
```

So, for example, if $\lambda = 1 / (3W)$, then $a = 1/3$: the share
starts at one-third of rectangular weight, decays slowly, and is
still (weakly) eligible long after $W$, instead of dropping to
zero at $W$. The pool must apply the *same* rule to every miner
in the low-hashrate class, and must normalise per block by total
weight in that block, exactly as PPLNS normalises by total share
difficulty in the window. That normalisation is what preserves
expected value in §5.

**Opt-in and fairness guardrails (part of the proposal, not
optional extras):**

- Opt-in per worker, not per pool. A desktop miner is unaffected.
- The low-hashrate class is defined by *measured* sustained
  hashrate over a rolling window (for example 7 days), not by
  self-declaration, to prevent a large miner splitting into many
  low workers to game the decay. Splitting does not help under
  IPSD in any case: total weight-time is preserved per unit of
  difficulty, so $N$ workers of $h/N$ each have the same total
  integral as one worker of $h$, up to normalisation — but the
  guardrail is still required, because a split worker could
  otherwise exploit a per-worker minimum or rounding rule in a
  specific pool implementation.
- No minimum payout per block is promised. A decay-weighted share
  may earn a very small amount from a given block. That is the
  point: many small accruals instead of few large-or-zero ones.
- IPSD does not change block rewards, coinbase construction, or
  consensus. It is a pool accounting rule, like PPLNS itself.

**Why this is not "just a longer window".** A longer rectangular
window (larger $W$) also reduces expiry risk, but it increases
the weight-time integral, and therefore — unless the pool dilutes
everyone else — increases the low miner's expected share beyond
its hash proportion, which is unfair to other miners. IPSD is
designed to avoid that: longer eligibility is *paid for* by lower
weight per block, with the integral held constant. That trade is
the invention's core.

### 4.3 Component 3 — BMOS: Batched Multi-Output Settlement (proposal)

**Idea.** On a fixed schedule (for example weekly, pool policy),
the pool constructs *one* standard Monero transaction with many
outputs, one per batched miner whose IPSD (or ordinary PPLNS)
balance has reached a lower batched threshold $T_{batch} < T$,
and sends it. Each miner's output is a normal Monero output to
that miner's public address. No token, no smart contract, no
consensus change, no sidechain.

**Fee logic, in general form (no fee number is claimed, because
Monero fees vary with transaction size and network conditions).**

Let a single payout transaction cost $F_{base} + f \cdot o$,
where $F_{base}$ is the fixed overhead (inputs, extra data,
signatures) and $f \cdot o$ is the per-output cost for $o$
outputs. For $N$ separate transactions, total fee is
$N \cdot (F_{base} + f)$. For one batched transaction with $N$
outputs, total fee is $F_{base} + N \cdot f$. Saving is
$(N-1) \cdot F_{base}$, and fee per miner falls from
$F_{base} + f$ to $F_{base}/N + f$.

A pool can therefore set $T_{batch}$ such that the batched fee
per miner is below a stated cap (for example, below 5% of
$T_{batch}$ — the cap itself is a pool policy choice, not a
claim here), whereas the same balance paid singly would exceed
that cap. The batch schedule, the cap, and $T_{batch}$ must all be
published by the pool; a batch that is never scheduled is not a
lower threshold, it is a delayed threshold.

**Custody note, honestly stated.** BMOS shortens, but does not
eliminate, the period during which a balance is pool accounting
rather than on-chain money. Until the batch transaction is
confirmed, the miner's claim is against the pool's accounting.
BMOS must not be described as "non-custodial" in the P2Pool
sense. Its advantage over a single high threshold is *shorter*
custody and *earlier* on-chain proof (a transaction id, amount
and date in the user's own wallet), not zero custody.

**Privacy note.** A batched transaction links $N$ miners' outputs
in one transaction, visible on-chain as outputs to $N$ addresses
(though Monero's ring signatures, stealth addresses and
confidential amounts hide amounts and linkage beyond the
transaction structure itself). A miner who does not want to be
in a batch must be able to opt out and wait for the ordinary
threshold. Opt-out is part of the proposal.

### 4.4 How the three components fit together

```text
Phone miner (Anvil fork, charging-only, thermal pauses on)
  -> Stratum v1 over TLS to pool, wallet = public address
  -> Pool validates shares (existing)
  -> VSWL (phone): hash-chained ledger + daily dashboard snapshot
  -> Pool accounting (proposal): IPSD weight instead of rectangular window
  -> Pool settlement (proposal): BMOS batch on fixed schedule
  -> On-chain payment to user's wallet
  -> Verification: wallet receipt (txid, amount, date)
                   + VSWL ledger + dashboard snapshot
                   All three must agree; app balance alone is never proof.
```

If a pool implements only VSWL's *pool side* (a published,
signed epoch receipt — see §6.2), that alone improves P3 without
IPSD or BMOS. If a pool implements only BMOS, that alone improves
P2 without IPSD. The components are separable, and §7 tests them
separately.

---

## 5. Theoretical justification

### 5.1 Expected-value preservation for IPSD

Assume blocks arrive as a Poisson process with rate $\mu$
blocks per unit time, independent of miner shares, and that total
normalised weight per block is computed correctly by the pool.
For a share of difficulty $d$, expected payout over an infinite
horizon is proportional to its weight-time integral divided by
the expected total weight-time of all shares, times total block
rewards over that horizon.

Rectangular: integral $= d \cdot W$.
Decay: integral $= d \cdot a / \lambda = d \cdot W$ by
construction ($a = W \lambda$).

Therefore, *holding total weight of other miners and block rate
constant*, expected payout per unit of difficulty is unchanged.
This is a statement about expectation, not about any single
month: a miner can still earn zero in a given month under IPSD,
just with a different distribution.

*Assumptions that must hold for the proof to apply in practice,
and that the experiment must check:* block arrivals are
approximately Poisson over the horizon (true for Monero at
network scale, not guaranteed for a small pool with lumpy luck);
the pool normalises correctly per block; no other miner's weight
rule changes mid-horizon without disclosure; and the low-hashrate
class definition does not change mid-horizon. If any fails, the
preservation claim fails for that horizon, and the pool must say
so.

### 5.2 Variance reduction, sketch

Under rectangular PPLNS, a share's contribution to a given block
is a Bernoulli-like indicator: 1 (scaled) if a block falls inside
its window, 0 otherwise, with high variance per share. Under
IPSD, the same share contributes a small weight to *many* blocks
over a long tail. Summing many small, weakly correlated
contributions has lower variance than summing few large,
all-or-nothing contributions, by the standard variance-of-a-sum
argument, provided block rewards and total weight do not
themselves spike in a way that re-concentrates the sum.

Formally, for cumulative accrual $S_H$ over horizon $H$, with
rectangular weights $X_i \in \{0, 1\}$ and decay weights
$Y_i \in [0, a]$ with the same mean integral, $\mathrm{Var}(S_H)$
under decay is lower when the number of blocks contributing
non-trivially is larger and individual contributions are smaller,
which is exactly what a long tail with $a < 1$ achieves. The
experiment in §7 quantifies this by Monte Carlo simulation
before any live pool change, because the analytic bound depends
on the pool's actual block rate and total weight, which are not
assumed here.

*What is not claimed:* that variance goes to zero, that
time-to-threshold becomes deterministic, or that a phone will
reach any threshold in any stated number of days. IPSD smooths;
it does not guarantee.

### 5.3 Batch fee saving, sketch

From §4.3, fee per miner in a batch of size $N$ is
$F_{base}/N + f$, strictly decreasing in $N$, approaching $f$
as $N$ grows. Therefore there exists an $N$ above which the fee
share for any given $T_{batch}$ falls below any stated cap,
provided $f$ itself is below that cap. If $f$ alone exceeds the
cap, no batch size helps, and $T_{batch}$ cannot be lowered on
fee grounds alone — that is a falsification condition for H2
for that fee regime (see §7.4).

### 5.4 Why VSWL does not need a node

VSWL's checks are all *consistency* checks between records the
phone already receives (Stratum responses) and records the pool
already publishes (dashboard). No block header, no transaction
verification, and no node sync are required to detect a mismatch
between app and pool. Full on-chain verification — that a payout
transaction exists and is confirmed — is done in the user's own
wallet, which already does that work. VSWL fills the gap *before*
payout; it does not replace wallet verification *after* payout.

---

## 6. Design details, risks and honest limits

### 6.1 Pool-side changes required (IPSD + BMOS)

- A per-worker hashrate measurement over a rolling window, to
  assign the low-hashrate class.
- A weight function replacing the rectangular window, with
  $W$, $\lambda$ and $a$ published, and per-block normalisation
  auditable from pool logs (at least to the pool operator; a
  fully public audit log is desirable but not assumed).
- A batch scheduler, a published $T_{batch}$, a published fee
  cap, and an opt-out.
- No consensus change, no new transaction type, no new
  cryptographic primitive. A pool that claims IPSD/BMOS requires
  a Monero hard fork is misunderstanding the proposal.

### 6.2 Optional pool-signed epoch receipt (strengthens VSWL, still not on-chain enforcement)

A pool *may* additionally publish, per worker per epoch (for
example daily), a signed receipt:

```text
{
  "pool": "<pool domain>",
  "worker": "<worker id>",
  "wallet_trunc": "<first 6 + last 4>",
  "epoch_start": "<…>", "epoch_end": "<…>",
  "total_share_difficulty": <…>,
  "weighted_difficulty_ipsc": "<if IPSD, else null>",
  "pool_pubkey_id": "<key identifier published by pool>",
  "signature": "<pool signature over the above fields>"
}
```

A third party with the pool's published public key can verify
the signature, without trusting the miner's app. This receipt
does *not* force the pool to pay: Monero has no smart contract
to enforce it. It creates reputational accountability — a pool
that signs receipts and then does not pay can be shown to have
done so — and it creates a checkable record that survives the
miner app being uninstalled. Key distribution is the weak point:
if the pool rotates keys without publishing old keys, old
receipts become unverifiable. The pool must publish a key history
for receipts to remain useful. This is a limitation, not a
footnote: without key history, receipts degrade to dashboard
screenshots with extra steps.

### 6.3 Limitations, stated plainly

1. **No rate gain.** IPSD + BMOS do not change $h/H$. If a phone's
   expected earnings are small, smoothed small earnings are still
   small.
2. **Pool adoption is the bottleneck.** VSWL works today; IPSD
   and BMOS work only if a pool implements them. A proposal that
   no pool adopts changes nothing for a real miner, however
   elegant its maths.
3. **Custody remains until settlement.** BMOS shortens it; only
   P2Pool-style coinbase payment removes it, and P2Pool requires
   a node and worsens the share-window problem for a phone.
4. **Privacy trade-off in batches.** Batched outputs are in one
   transaction. Opt-out must exist.
5. **Gaming and rounding.** Any per-worker minimum, rounding rule,
   or class threshold can be gamed if not designed carefully.
   The guardrails in §4.2 are necessary, not decorative, and a
   pool implementation must be audited for split-worker and
   rounding exploits before launch.
6. **Phone constraints unchanged.** Thermal pauses, charging-only
   operation, battery wear, and Android killing a service still
   apply. LHPMS is a settlement method, not a hashrate method.
   It must not be used to justify disabling thermal or charging
   pauses.
7. **Tax and legal.** Nothing in this paper is tax advice. In
   India, conversion of crypto to rupees has tax and reporting
   consequences that are outside this paper's scope and must be
   checked separately. The paper's "direct to wallet" means
   direct to the user's own wallet, not direct to rupees.
8. **No token.** LHPMS deliberately introduces no token, no
   points that trade, and no "receipt token" that could be
   mistaken for a payout. A receipt is a signed statement, not
   an asset.

---

## 7. Experiment design

### 7.1 Phase 0 — Simulation (no phone, no pool change, must come first)

Monte Carlo simulation, with code and seed published, of:

- Block arrivals: Poisson, rate from a stated network/pool block
  rate (to be filled from live pool data at experiment time, not
  assumed here).
- Miners: one low miner at $h$ = 500 H/s (and sensitivity runs
  at 300, 750 H/s), plus a background of other miners at a total
  weight that reproduces a stated pool share.
- Schemes: rectangular PPLNS with window $W$, IPSD with
  $\lambda = 1/(3W)$, $1/W$, $1/(6W)$ (sensitivity), and ordinary
  PPLNS with a longer rectangular window as a control (to show
  the fairness problem IPSD is designed to avoid).
- Trials: at least 10,000 simulated horizons per scheme, horizon
  at least 90 days of simulated blocks, to measure distribution
  of cumulative accrual, not just its mean.
- Metrics: mean cumulative accrual (must match across schemes
  within simulation error, or H1 fails), standard deviation and
  interquartile range of cumulative accrual, distribution of
  time to reach $T$ and $T_{batch}$, and share of trials reaching
  threshold by day 30 / 60 / 90.

*Pass condition for Phase 0:* mean matches within simulation
error, and variance / IQR is lower for IPSD than rectangular at
the same mean, for at least two $\lambda$ values. If not, IPSD
is dropped before any pool work.

### 7.2 Phase 1 — Phone-only live baseline (VSWL validation, no pool change)

- Phone: NARZO 70 Pro 5G, Dimensity 7050, Anvil fork, charging-only
  ON, CPU pause 65 °C, battery pause 40 °C, pool MoneroOcean
  direct, wallet = user's own public address only.
- Duration: at least 14 days of sustained mining, ideally 30,
  with daily VSWL snapshots. A 30-minute rung test does not
  validate a payout method; it validates hashrate only.
- Log daily: ledger accepted shares, app accepted shares,
  dashboard accepted shares, dashboard pending XMR, CPU/battery
  temperature maxima, pause count, battery % change while
  charging, pool algo in log.
- *Pass condition for Phase 1:* ledger = app within tolerance
  (exact, except shares in flight at snapshot time), and
  dashboard moves in the same direction as ledger over days.
  A persistent dashboard-at-zero while ledger grows is a fault
  finding, not a variance finding, and must be investigated
  (wrong wallet, wrong worker, pool issue) before Phase 2.

Phase 1 does not test IPSD or BMOS, because no pool implements
them yet. It tests the baseline model that Phase 0 simulated,
and it tests VSWL. Claiming Phase 1 tests IPSD would be false.

### 7.3 Phase 2 — Pool prototype (testnet / private test, not mainnet)

- Implement IPSD + BMOS in a fork of an existing open-source pool
  implementation, run on a private test network or testnet with
  simulated blocks, *not* on mainnet with real user funds.
- Verify: per-block normalisation, expected-value preservation
  against Phase 0 predictions, batch transaction construction
  (standard multi-output transaction, fee per miner measured,
  opt-out honoured), key history for epoch receipts, and
  split-worker / rounding audit.
- Only if Phase 2 passes, and only with a pool operator willing
  to publish $W$, $\lambda$, $T_{batch}$, fee cap and schedule,
  should a mainnet pilot with a small, opt-in cohort be considered.
  That pilot is outside this paper.

### 7.4 Falsification criteria

- **H1 falsified if:** Phase 0 shows IPSD mean differs from
  rectangular mean beyond simulation error, *or* IPSD variance is
  not lower than rectangular variance at the same mean, for all
  tested $\lambda$, *or* Phase 2 shows normalisation cannot be
  implemented without breaking existing PPLNS accounting.
- **H2 falsified if:** measured batch fee per miner does not fall
  below the single-transaction fee per miner for realistic $N$,
  *or* per-output fee $f$ alone exceeds the stated fee cap, so no
  $N$ helps, *or* batch scheduling cannot be fixed without
  delaying payouts beyond the ordinary threshold wait.
- **H3 falsified if:** Phase 1 shows ledger-vs-dashboard
  discrepancies that cannot be explained by in-flight shares,
  *or* the ledger chain can be rewritten without detection in a
  test, *or* snapshot collection itself destabilises mining
  (for example, by waking the phone or draining battery beyond a
  stated tolerance).
- **Overall invention falsified as a practical method if:** no
  pool implements IPSD/BMOS after a good-faith approach with
  Phase 0–2 evidence, *and* VSWL alone does not change user
  verifiability in Phase 1. In that case the honest conclusion
  is: VSWL is a useful local check, IPSD/BMOS remain unadopted
  proposals, and the phone's practical route remains ordinary
  PPLNS direct to wallet.

---

## 8. What works today vs what is a proposal — the split

| Component | Status | Requires | What it changes |
|---|---|---|---|
| VSWL ledger + dashboard snapshots | **Works today, no pool change** | Phone miner logging Stratum responses; dashboard already exists | Verifiability before payout; detects app/pool mismatch |
| Ordinary PPLNS direct to wallet (MoneroOcean, 0% direct, TLS) | **Works today** | Existing pool | Baseline; lumpy accrual, threshold wait |
| IPSD-PPLNS slow-decay window | **Proposal, pool must implement** | Pool accounting change, published $W$, $\lambda$, class rule, normalisation audit | Smoother accrual, same expected value (to be proven in Phase 0) |
| BMOS batched multi-output settlement | **Proposal, pool must implement** | Pool batch scheduler, published $T_{batch}$, fee cap, opt-out | Lower economic threshold, shorter custody, earlier on-chain proof |
| Pool-signed epoch receipt | **Proposal, pool must implement** | Pool key publication + key history | Checkable receipt, reputational accountability only, not on-chain enforcement |
| Any claim that LHPMS increases hashrate, increases expected $/month, changes Monero consensus, removes custody entirely, or works without a pool | **Not claimed** | — | — |

---

## 9. Conclusion

LHPMS is invented to solve the part of phone mining that is
actually a design problem — variance and opacity — without
pretending to solve the part that is physics: a 500 H/s phone is
a small share of a large network. VSWL can be built today and
tested on a phone in weeks. IPSD and BMOS are honest proposals
with a clear adoption bottleneck, a clear expected-value
argument, and a clear experiment that can kill them before any
real funds depend on them. If the experiment kills them, the
paper's conclusion changes, and that is the point of writing it
with falsification criteria instead of a marketing claim.

**Next step, in order:** Phase 0 simulation, then Phase 1
phone-only VSWL validation, then — only if both pass — a
private-test pool prototype. No mainnet pilot, no threshold
promise, and no earnings figure should be quoted until those
phases are measured.

---

## Appendix A — Notation

$h$ miner hashrate; $H$ network hashrate; $R$ block reward;
$d$ share difficulty; $d_s$ pool share difficulty; $W$ PPLNS
window length; $\lambda$ decay rate; $a = W\lambda$ decay
amplitude; $w(t)$ share weight at time $t$; $T$ ordinary payout
threshold; $T_{batch}$ batched payout threshold; $F_{base}$ fixed
transaction overhead; $f$ per-output fee; $N$ batch size.

## Appendix B — What was not invented, and why

- No new consensus rule, because a phone miner cannot impose one
  on Monero, and a method that requires a hard fork is not a
  phone method.
- No token or points system, because a token reintroduces the
  custodian, price and conversion problems this paper is trying
  to avoid.
- No "proof of phone" or device attestation, because device
  attestation on Android is spoofable by a rooted device and
  would exclude legitimate phones, while adding no payout value.
- No change to RandomX itself, because changing the hash function
  mines a different coin, not Monero.
- No P2Pool replacement, because P2Pool already solves custody
  correctly for miners that can run a node; this paper solves a
  different problem for miners that cannot.

## Appendix C — Safety and handling rules for any implementation

- Never request, accept, store, log or transmit a seed phrase,
  mnemonic, private key or wallet password. Only a public
  receiving address.
- Keep charging-only ON, CPU pause 65 °C, battery pause 40 °C
  (Anvil defaults) during any live test. A settlement method
  must never be used as a reason to disable safety pauses.
- Mining on a primary phone carries battery-wear cost that is
  outside the settlement maths in this paper and must be weighed
  separately before any long live test.
- All live tests must verify payout in the user's own wallet
  (transaction id, amount, date), not in an app balance alone.
