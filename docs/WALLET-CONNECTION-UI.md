# Wallet-connection UI flow — Anvil Monero miner (plain language, India)

Audience: a non-technical user in India. Every step below lives **in the app**,
in a 6-step wizard that runs before the Mine tab can start. It is not a
README-only flow. Short sentences only. No technical jargon without a
one-line explanation.

> Design note, read first: the current code already has the right building
> blocks, and two gaps to fix.
> - Already present: `Wallet.parse()` + `Wallet.isValid()` in
>   `app/src/main/java/io/github/j3r0nim0/anvil/Wallet.kt`;
>   `MainActivity.commitWallet()` / `openMoneroOcean()` in `MainActivity.kt`;
>   pool `gulf.moneroocean.stream:20032` with TLS in `MiningService.kt`;
>   battery pause at 40°C / resume at 37°C and CPU thermal pause at 65°C
>   (default) in `MiningService.kt` + `ThermalMonitor.kt`.
> - Gap 1: `tab_wallet.xml` / `MainActivity.showWalletUi()` currently show
>   and copy the **full** address. This flow requires masked display only.
> - Gap 2: there is currently no in-app step-by-step wallet creation guide,
>   no payout/tax explainer, and no plain-language error screen. Add them;
>   do not add a seed/password field to fill Gap 2.

## 0. The unmissable warning — shown on EVERY wallet screen, top of screen

Use a solid red banner, white bold text, at least 18sp. It must not be
dismissible, and it must not be hidden behind a "Learn more" link.

> STOP. NEVER type your seed phrase here.
>
> This app will NEVER ask for your seed phrase.
> This app will NEVER ask for your private key.
> This app will NEVER ask for your wallet password.
>
> Your seed phrase is 25 secret words. Anyone who has them can take your money.
> Keep those words on paper, offline. Never paste them into any app, website, or chat.
>
> This app needs only ONE thing: your public receiving address.
> That address is safe to share. It starts with 4. It cannot spend your money.

Code rule: there is no seed, private-key, or password field anywhere in
this flow. No such value is stored, logged, or sent. Grep the code for
`seed`, `private key`, `mnemonic`, `password` in wallet code — the result
must be empty. Only `Prefs.WALLET` (SharedPreferences `"settings"`,
`MODE_PRIVATE`) stores the public address, and only that address is sent
to the pool (see Step 4).

## Screen 1 — Step 1: Make a SEPARATE mining wallet

Title: `Step 1 of 6 — Make a separate mining wallet`

> Do this in a trusted Monero wallet app, not in this miner.
> This miner does not make a wallet for you.
>
> 1. Open your trusted Monero wallet app.
> 2. Make a NEW wallet. Use it only for mining.
> 3. Do not use your main savings wallet for mining.
> 4. Write your 25 seed words on paper. Keep the paper safe and offline.
> 5. Never take a photo of those words. Never send them to anyone.
>
> Why a separate wallet?
> If something goes wrong with mining, your main money is not in that wallet.
>
> [ I have made a separate wallet ]  →  goes to Screen 2
> [ I already have a mining wallet ]  →  goes to Screen 2

No wallet-app name is hard-coded here: the user picks their own trusted
app. Do not link to a download inside this screen.

## Screen 2 — Step 2: Copy ONLY your public receiving address

Title: `Step 2 of 6 — Copy only the public address`

> In your wallet app, tap Receive.
> Copy the long address shown there.
>
> It starts with the number 4.
> It is about 95 letters and numbers long.
> This is the ONLY thing you will paste into this miner.
>
> Do NOT copy your seed words.
> Do NOT copy your private key.
> Do NOT copy your password.
>
> If your wallet app shows a second address that starts with 8, that is
> a subaddress. It also works. For this simple setup, use the address
> that starts with 4.
>
> [ I have copied my public address ]  →  goes to Screen 3

## Screen 3 — Step 3: Paste it in the miner

Title: `Step 3 of 6 — Paste your address here`

Field label: `Your public Monero address`
Field hint: `Paste here. It starts with 4.`
Buttons: `[ Paste ]`  `[ Scan QR code ]`  `[ Continue ]`

> Paste the address you copied in Step 2.
> Check: it starts with 4. It has no spaces at the start or end.
>
> This app will check the address for you in the next step.
> It will not send money yet. It will not start mining yet.

Code: `MainActivity.pasteWallet()` → `Wallet.parse()` strips a
`monero:` prefix, any `?...` part, spaces and invisible characters, then
puts the cleaned text in the field. QR scan goes through the same
`Wallet.parse()` path (`MainActivity` scan result handler). The field
must have autofill off and no suggestion bar, as today
(`importantForAutofill="no"`, `textNoSuggestions`).

## Screen 4 — Step 4: Check — this is what the miner will use

Title: `Step 4 of 6 — Check this address`

This screen appears only after validation passes. The app must NEVER
show the full address on this screen, on the Mine tab, or in a toast.

> ✓ Address looks correct.
>
> This miner will use:
> `4•••••• … ••••`  (first 6 and last 4 characters only, filled in by the app)
>
> Is this the start and end of the address you copied?
> If yes, tap Continue. If no, tap Change address.
>
> Your full address is saved on this phone only.
> It is sent to the mining pool so the pool knows where to pay you.
> It is not your seed phrase. It cannot spend your money.
>
> [ Continue ]   [ Change address ]

Code — validation, all in one place, do not duplicate it in the UI:
`Wallet.isValid()` in `Wallet.kt`:
1. Shape: 95 characters starting with `4` or `8`, or 106 characters
   starting with `4` (standard / subaddress / integrated).
2. Block Base58 decode (Monero's block format, not plain Base58).
3. Checksum: first 4 bytes of Keccak-256 of the rest must match the
   last 4 bytes.
4. Network tag: mainnet tag 18, integrated 19, or subaddress 42 only.
   Testnet / stagenet addresses are rejected.

Call sites — every one of these must call `Wallet.isValid()`, never a
shorter check:
- `MainActivity.commitWallet()` — blocks Save/Continue on failure.
- `MainActivity.pasteWallet()` / scan result — cleans, then validates
  before saving.
- `MainActivity.openMoneroOcean()` — refuses to open the dashboard
  unless the saved address is valid.
- Mine-start guard in `MainActivity` (existing `noWalletHint` path) —
  mining must not start with an invalid or empty address.

Masking — new, required: add `Wallet.mask(address)` returning
`first 6 + " … " + last 4` for a valid address, and `"Not set"` otherwise.
In `MainActivity.showWalletUi()`, replace `walletDisplay.text = addr`
with `walletDisplay.text = Wallet.mask(addr)`, and remove the
`copyWallet` button that copies the full address from
`tab_wallet.xml`. The dashboard button (Step 6) still works because it
builds its link from the saved address internally; the user never needs
to copy the full address out of this app.

## Screen 5 — Step 5: Start a test

Title: `Step 5 of 6 — Start a short test`

> Tap Start mining.
> Keep your phone plugged in while you test.
> Mining makes your phone warm. That is normal, up to a limit.
>
> In the first 10 minutes, look for these 3 things:
> 1. Speed (hashrate) is more than 0.
> 2. Accepted shares go up, even slowly.
> 3. Temperature stays in the safe range shown below.
>
> If all 3 happen, your wallet connection works.
> If accepted shares stay at 0 after 30 minutes, see Step 6 and the
> error list below. Do not change your address yet.
>
> [ Start mining ]
>
> Safe test settings used here:
> Charging only: ON. Mining pauses if you unplug the phone.
> Heat limit: mining pauses if the battery gets too hot, and starts
> again when it cools down.

Code: `MiningService` with `EXTRA_WALLET` = saved address,
`EXTRA_CHARGING_ONLY = true` by default, pool
`gulf.moneroocean.stream:20032`, TLS on, 0% developer donation in the
configured pool mode (see `strings.xml` footer). Accepted/rejected
counts come from `XMRigManager` parsing XMRig output into
`MiningState.accepted` / `MiningState.rejected`, shown in `MainActivity`.

## Screen 6 — Step 6: Check on MoneroOcean yourself

Title: `Step 6 of 6 — Check on the pool's own website`

> Do not trust only this app's numbers.
> Check them on MoneroOcean's own website.
>
> 1. Tap the button below. It opens MoneroOcean in your browser.
> 2. On that page, check: your worker is there, speed is more than 0,
>    and accepted shares are going up.
> 3. If your worker is there, your wallet connection works.
>
> [ Open MoneroOcean dashboard ]
>
> What if you see nothing? Wait 10 minutes and refresh the page.
> The pool page can be slow to show a new worker.

Code: `MainActivity.openMoneroOcean()` builds, internally only:
`https://moneroocean.stream/#/wallet/<saved-full-address>/overview`
and opens it with `Intent.ACTION_VIEW`. Do not print that full URL as
text in the app. The full address appears in the browser's own address
bar — that is expected, because a receiving address is public, not
secret. It is still not a seed phrase.

## Always-visible explainer — "About your money" (link from every wallet screen)

Title: `About your money — read this before you expect payment`

> Who pays you?
> MoneroOcean is the mining pool. It is a company that runs the pool.
> It is NOT fully no-custody. The pool counts your work and sends the
> payment to your wallet. You do not need a MoneroOcean account, but
> the pool is still in the middle. That is different from mining with
> no pool at all.
>
> How much, and when?
> The pool pays only when your balance reaches about 0.003 XMR.
> At 400 to 600 hashes per second, that takes about 73 to 104 days.
> That is an estimate, not a promise. Speed, price, and pool rules can change.
>
> Your earnings will go up and down. They are not fixed pay.
> A phone earns roughly $0.50 to $1 a month in the estimates used here,
> before battery wear. It will not reliably earn $10 a month.
>
> Tax in India:
> When you convert crypto to rupees, India charges 30% tax on crypto
> gains, plus 1% TDS. Keep a record of what you receive and when.
> This app does not file tax for you. Ask a tax adviser if you are unsure.
>
> Your money is not held by this miner app.
> This app never holds your XMR. Payment goes from the pool to the
> wallet address you pasted.

## Error states — exact copy, what the app does, where it is checked

Keep the saved address on every error below. Never clear it because of
a network or heat error. Only an invalid address blocks saving.

### 1. Invalid address
Trigger: `Wallet.isValid()` returns false in `commitWallet()`.
Show in the field error (`walletError` in `tab_wallet.xml`):
> This address does not look right.
> A Monero address starts with 4, and is 95 or 106 characters long.
> Please copy it again from your wallet app. Do not type it by hand.
> Do not paste seed words here.
Extra detail, only if length is known (existing behaviour, keep it):
> You pasted [N] characters. A correct address has 95 or 106.
Common causes to list under the error:
> It may have a missing letter, an extra space, or it may be a
> Bitcoin or other-coin address. Monero addresses do not use the
> letters 0, O, I, or l.
Action: stay on Screen 3. Do not start mining.

### 2. Nothing pasted / empty clipboard
Trigger: `pasteWallet()` finds blank clipboard text.
> Nothing was copied. Go back to your wallet app, tap Receive, then
> Copy, then come back and tap Paste here.

### 3. No network
Trigger: `MiningService.hasInternet()` is false, or
`NetworkCallback.onLost()` fires in `MiningService.kt`.
Banner on Mine tab, mining paused, address kept:
> No internet connection. Mining is paused.
> Check Wi-Fi or mobile data, then mining will try again.
> Your address is still saved. You do not need to paste it again.

### 4. Pool unreachable
Trigger: XMRig cannot connect to `gulf.moneroocean.stream:20032`
(TLS), or mining runs with speed above 0 but the pool never answers.
Banner:
> Cannot reach the mining pool. Mining is paused.
> Check your internet, wait 10 minutes, and try again.
> If it still fails, check MoneroOcean's website in your browser.
> Your address is still saved.

### 5. 0 accepted shares
Trigger: mining has run for 30 minutes, speed is above 0, but
`MiningState.accepted == 0`.
> Mining is running, but no work has been accepted yet.
> This can happen when your speed is low or the pool is busy.
> Wait 30 more minutes. Then check Step 6 on MoneroOcean.
> If your worker is there, wait. If your worker is not there, stop
> mining, check your address in Step 4, and start again.
> Do not change to a new wallet unless Step 4 shows the wrong address.
Note for the implementer: do not show this as a fatal error at 1 or
2 minutes. Shares at phone speed are slow by nature.

### 6. Temperature too high
Triggers: `ThermalMonitor` CPU threshold (default 65°C, resume 10°C
lower) or `MiningService` battery pause at 40°C / resume at 37°C.
Banner, mining auto-paused:
> Phone too hot. Mining is paused to protect your phone.
> Let your phone cool down. Mining will start again on its own
> when it is cool enough.
> If this happens often, use fewer threads, or mine only while
> charging in a cool place. Do not cover the phone while mining.

### 7. Battery not charging (charging-only mode on)
Trigger: `MiningService.chargingReceiver` / `applyChargingNow()`:
`EXTRA_PLUGGED == 0` while `CHARGING_ONLY` is true.
Banner, mining auto-paused:
> Phone is not charging. Mining is paused.
> Plug your phone in to start mining again.
> This setting protects your battery. You can turn charging-only
> off in Settings, but mining on battery will drain it fast.
If charging-only is off, do not show this error; show battery level
instead and warn once per session that mining drains battery.

## What must NOT be in this flow

- No field labelled seed, recovery words, private key, or password.
- No QR scan that accepts anything except a `monero:` address that
  passes `Wallet.isValid()`. A scanned seed phrase must be rejected
  with the invalid-address message, never saved.
- No full-address display, copy, screenshot hint, or share button in
  the app UI. The only full-address use is internal: pool config and
  the dashboard link.
- No promise of $10 a month, no fixed daily earning, no countdown to
  payout that pretends the 73–104 day estimate is guaranteed.
- No tax advice beyond the India 30% + 1% TDS note above.
