# POOL-PRESETS-7050 — Photo APK upgrade (pool presets, L3 masks, LAN proxy, in-app guide)

Branch: `pool-presets-7050`, based on `full-7050` at `4d7a093`.
Status: code complete on this branch, committed locally, pushed, CI tag created.
**No on-device benchmark exists yet. No H/s, temperature, or AnonHugePages figure
for this branch may be quoted until the user installs a signed CI APK and runs
the checklist at the bottom of this file.**

## Safety rules kept (untouched)

- Safety pauses unchanged: CPU pause 65 °C / resume 55 °C, battery pause 40 °C /
  resume 37 °C, charging-only default ON.
- No keystore, password, seed/private-key code touched. No wallet address is
  hard-coded anywhere. The app never asks for, stores, displays, or logs a seed
  phrase, private key, or password. The only wallet data is the user's own
  public Receive address, saved on the phone and sent to the selected pool.
- MoneroOcean default `gulf.moneroocean.stream:20032` TLS is unchanged unless
  the user explicitly picks another preset in Settings.

## Per-preset verification status

"Verified" below means verified against the pool's own page/source in this run.
Everything else is UI text copied from the user's photo list, NOT a checked claim.
All presets are selectable only; none is wired to mining until the user picks it
AND enters his own public wallet address for that coin.

| Preset | Endpoint (as coded) | Status |
|---|---|---|
| MoneroOcean default | `gulf.moneroocean.stream:20032`, TLS | UNVERIFIED against an official MoneroOcean port list in this run. Kept as the APK2 default, unchanged. The fork's own default `gulf.moneroocean.stream:10128` plaintext is documented only, not selectable. |
| Feelcoin Official Pool | `pool.feelcoin.org:4242`, TLS option `:4244`, start diff 100, pass `x` | PARTIALLY VERIFIED: feelcoin.org's own whitepaper (v1.2) and faucet page confirm `pool.feelcoin.org:4242` standard, `:4244` TLS, RandomX, PPLNS, 0.5% fee. NOT verified here: start diff 100, 0.33 FEEL auto-payout, and `wallet.feelcoin.org` as the wallet URL — those remain user-named UI text. |
| VeinPool Feelcoin PPLNS | `veinpool.fr:3290` | UNVERIFIED — no VeinPool source confirming this port/coin in this run. |
| VeinPool Feelcoin Open Squad | `veinpool.fr:3293`, worker prefix `RZP3-` | UNVERIFIED — port, squad mode, and the 5% small-miner bonus named by the user are not verified. |
| VeinPool Feelcoin SOLO | `veinpool.fr:3292` | UNVERIFIED — no VeinPool source confirming this port in this run. |
| VeinPool Lajkto Coin 0%-Fee Direct SOLO | `veinpool.fr:3702`, wallet from `https://lajkto.eu/lkct/` | UNVERIFIED — port, 0% fee, and wallet URL not verified against a VeinPool/Lajkto source in this run. |
| VeinPool WAM Coin | `veinpool.fr:3310`, 185 kH/s network as named by the user | UNVERIFIED — port and network hashrate not verified. |
| MoneroOcean + Tari XTM Merge-Mining | `gulf.moneroocean.stream:10001`, plaintext | UNVERIFIED — explicitly. Earlier work found no verified HashVault/MoneroOcean Tari merge preset, and 10001 was only a third-party table candidate. The in-app chooser shows an extra "Unverified preset" warning for this entry and tells the user to watch the XMRig log for "new job" before trusting it. Do NOT present this as verified. |

## Per-item status

### 1. Pool presets in the APK UI — IMPLEMENTED (UI + wiring, untested on device)
- `PoolPresets.kt`: selectable model (`id`, `name`, `host`, `port`, `tls`,
  `tlsPort`, `algo` default `rx/0`, `startDifficulty`, `workerPrefix`,
  `requiresMoneroAddress`, `verified`, `note`). No hard-coded wallet.
- `MainActivity.kt`: Settings "Choose pool preset" button + dialog showing name,
  host, port, TLS/plaintext; unverified non-default presets get a second warning
  dialog. Selection saves `POOL_PRESET_ID` and reconfigures mining if running.
- `MiningService.kt`: `startMining` reads the selected preset, sets pool, TLS,
  difficulty (preset value, else APK2 MoneroOcean start difficulty 30000 for the
  default, else 0 = pool auto/vardiff), and worker (preset prefix + stable worker
  name). `reconfigure` restarts XMRig if pool/TLS/difficulty/worker changed.
- Wallet validation per preset: Monero presets use the existing Cryptonote
  checksum validation; other presets accept a single non-empty token (no spaces,
  not a seed phrase — multi-word input is rejected) and the guide tells the user
  to check first/last characters himself, because the app cannot checksum-verify
  other coins. Wallet display shows only first 6 + last 4 characters.

### 2. Dimensity 7050 L3 isolation + MADV_HUGEPAGE + thread masks — IMPLEMENTED (code, untested on device)
- Reuses the existing native allocator (2 MB `posix_memalign` + `madvise`
  `MADV_HUGEPAGE | MADV_WILLNEED` for allocations >= 256 KB; return reported,
  never claimed as granted) and the existing smaps_rollup readouts.
- NEW: Mine tab `thpDisplay` TextView, updated in `render`, showing app vs
  XMRig-child AnonHugePages, labelled "process totals, NOT dataset-only proof".
- NEW thread masks in `Prefs.kt` (`THREAD_MODE_MASK4 = 4`, `THREAD_MODE_MASK8 = 5`)
  and `XMRigManager.kt`: mask 4 = big cores + last 2 little cores by sysfs order
  (`cpu4, cpu5` on the expected Dimensity 7050 layout, leaving the first little
  cores for Android); mask 8 = all ordered cores. Both use the corrected XMRig
  `[1, cpu]` config format, NOT the old `affine_to_cpu` object format that
  XMRig 6.25.0-mo1 does not parse. Cores are discovered by max frequency on the
  phone and fall back safely; UI labels name `cpu6,7` only as the expected layout.
- Mask switches are mutually exclusive with big-only, asymmetric, and
  L3-isolated, following the existing listener pattern.

### 3. Compiler/JIT — MATCHED, patch kept, no new claims
- Wrapper native module `CMakeLists.txt` already has:
  `-O3 -march=armv8.2-a+crypto+aes+sha2+dotprod+fp16 -mtune=cortex-a78 -flto
  -funroll-loops -fomit-frame-pointer`.
- XMRig CI build flags in `.github/workflows/release.yml` match
  (`-O3 -march=armv8.2-a+crypto+aes+sha2+dotprod+fp16 -mcpu=cortex-a78
  -mtune=cortex-a78 -flto -funroll-loops -fomit-frame-pointer`); `-mcpu` is
  equivalent/additional, not a divergence.
- Existing CFROUND patch kept exactly: it NOPs a previous CFROUND emission when
  the next CFROUND arrives with no FP use in between. It does NOT implement
  `msr fpcr` skipping and does NOT implement `__builtin_prefetch` gains, and no
  such claim is made anywhere in this branch.

### 4. LAN swarm proxy — IMPLEMENTED (code, NOT tested against a real laptop XMRig or pool)
- `StratumProxy.kt`: optional, off-by-default listener on `0.0.0.0:3333` inside
  the foreground service. Never auto-started by mining; started only by the
  Settings toggle (`ACTION_SWARM_START` / `ACTION_SWARM_STOP`).
- Accepts a TCP connection only if the remote address is loopback or a private
  LAN address (10/8, 172.16/12, 192.168/16, 169.254 link-local); a public-IP
  connection is closed, not proxied.
- One upstream connection per LAN client, TLS via `SSLSocketFactory` when the
  preset says TLS, otherwise plaintext. The client's first login/authorize is
  intercepted and its credentials replaced with the phone's selected preset
  wallet + worker (prefix + stable worker name); the client's own wallet address
  is never forwarded upstream and never logged in full (masked connection event
  only). Upstream pass is the constant `x`. No seed, key, or password is read,
  stored, or logged.
- Upstream job notifications, difficulty messages, and submit responses are
  forwarded verbatim to that client; that client's share submissions are
  forwarded verbatim upstream, on that client's own upstream connection.
  Request ids are per-connection, so no global id mapping is needed.
- HONEST LIMIT: it does NOT multiplex many LAN clients onto a single upstream
  TCP connection with shared job ids. Each LAN client gets its own upstream
  connection under the same worker name; whether a pool aggregates their
  hashrate into one worker total is the pool's behaviour, not something the
  proxy can guarantee. It does not parse full Stratum JSON beyond method/id
  extraction for the login intercept; all other lines are forwarded verbatim,
  and a miner using a non-line-delimited or non-JSON variant will fail visibly
  (connection closed on error) rather than silently dropping shares.
- UI: Settings swarm toggle + `swarmLabel` showing on/off state and upstream
  pool; `MiningState.swarmOn` / `swarmUpstream`, cleared on reset/teardown.

### 5. In-app step-by-step guide — IMPLEMENTED
- The existing 6-step Monero wallet flow in `MainActivity.kt` is kept in full
  and renumbered to Steps 1–6 of 12; Steps 7–12 are added in the same plain style:
  (7) create a Feelcoin wallet yourself at `wallet.feelcoin.org`, Create New
  Wallet with a name and password, write the recovery seed on paper offline,
  never photograph it, never share it, never paste it into the miner or to any
  agent; (8) copy ONLY the public Receive address; (9) in the APK pick the
  preset, paste only that public address, with the warning that for non-Monero
  presets the app cannot checksum-verify the address; (10) start mining, check
  accepted shares, temperatures, and the SUSTAINED60 log, then check the pool's
  own page yourself; (11) thread-mask choice, judged on 60-second speed over
  30 minutes, not a burst, plus the THP process-total caveat; (12) LAN proxy is
  off unless the user turns it on, local-network only, never logs a seed/key/password.
- No seed, key, or password field, storage, display, or log line was added
  anywhere in the guide implementation.

## Verification actually run on this VM (host x86_64, no emulator, no adb device)

- `git diff --check` — exit 0.
- Brace/paren balance balanced for `MainActivity.kt`, `MiningService.kt`,
  `StratumProxy.kt`, `PoolPresets.kt`.
- Template JSON parses: `app/src/main/assets/xmrig_config_template.json` — OK.
- NDK ARM64 cross-compile — exit 0:
  `aarch64-linux-android34-clang++ -std=c++17 -O3
  -march=armv8.2-a+crypto+aes+sha2+dotprod+fp16 -mtune=cortex-a78 -flto
  -funroll-loops -fomit-frame-pointer -c app/src/main/cpp/anvil_native.cpp`
  and same compiler for `app/src/main/cpp/vulkan_miner.cpp`.
- Local Gradle — FAILED as expected on this VM, not a Kotlin result:
  `JAVA_HOME=~/workspace/toolchains/jdk17 gradle-8.7/bin/gradle compileDebugKotlin
  --no-daemon` → "Could not receive a message from the daemon." (known VM
  sandbox IPC limitation). Kotlin correctness is therefore NOT proven locally;
  the CI build is the proof step.
- `adb devices` — "List of devices attached" with no devices; the paired phone
  is not an adb device, so no install and no on-device benchmark was possible
  from here.
- Web checks (search only, not a live browser visit): feelcoin.org whitepaper
  v1.2 + faucet page confirm pool endpoints 4242/4244 and 0.5% fee (see preset
  table). No VeinPool source confirming ports 3290/3292/3293/3702/3310 was found
  in this run; no MoneroOcean official port list confirming 10001 or 20032 was
  found in this run. A live-browser check of MoneroOcean's own Getting Started
  page is still owed, by an eligible agent.

## On-device 60-second benchmark checklist (user must run after installing a signed CI APK)

Phone: charging, case off, safety pauses ON (CPU 65/55 °C, battery 40/37 °C).
For EACH rung, run at least 60 seconds (30 minutes per rung for a sustained
verdict), and record from the app log: SUSTAINED60 60-second H/s (never a
10-second burst), accepted/rejected shares, CPU + battery temperatures, app
THP readout and XMRig-child THP readout, and the XMRig startup thread profile
(it must show the intended pinned affinities; if it shows unpinned threads,
the affinity fix has failed and no L3/mask conclusion may be drawn).

1. Big-only (existing switch).
2. L3-isolated (existing switch, per-algo arrays).
3. Asymmetric (existing switch).
4. 4-Thread mask (new switch).
5. 8-Thread mask (new switch).
6. Selected pool preset: pick one preset in Settings, paste ONLY your own public
   Receive address for that coin, start, confirm accepted shares rise, then
   check that pool's own page yourself for the worker. For the Tari preset,
   watch the XMRig log for "new job" before trusting it — it is unverified.
7. Native checks: mining-start log must show `aes_ok=1` and `sha_portable_ok=1`;
   Settings "Run native GPU bench (once, SHA-256 test)" — `GPU SHA256 TEST FAIL`
   means the kernel is not trusted, full stop; `GPU SHA256 OK` means only that
   the SHA-256 kernel hashes correctly, not GPU mining income.
8. LAN proxy (only if you want it): turn the toggle ON in Settings, connect a
   laptop XMRig on the same Wi-Fi to `<PHONE_WIFI_IP>:3333`, confirm the laptop's
   shares are accepted upstream on the pool page, then turn the toggle OFF.

Only after those real log lines exist may anyone report a before-and-after
H/s, temperature, or AnonHugePages comparison for this branch.
