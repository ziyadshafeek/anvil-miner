# Anvil Privacy Policy

*Last updated: 2026-09-17*

Anvil does not have accounts, ads, analytics, or a backend of its own.

## The short version

Your Monero wallet address stays on this device and is sent only to the mining pool you connect to (default: MoneroOcean) over TLS. Anvil never sees your private keys or seed.

## What stays on this device

- Wallet address, thread %, CPU pause temperature, charging-only, Wi-Fi-only, quiet hours
- A random worker name (`AX-` plus 8 hex characters), generated on first launch so the pool can tell this phone from another on the same wallet
- XMRig’s local config, algorithm benchmark, and rolling log (capped at ~400 KB)

Uninstalling the app deletes this data.

## What leaves this device

- Wallet address, worker name, and hashes — to `gulf.moneroocean.stream` over TLS
- On launch, Anvil asks GitHub if a newer release exists (`api.github.com`, no account, no advertising id). There is no switch to turn this off. Mining still works if the check fails.

## Camera

Used only when you tap Scan, to read a Monero wallet QR (`monero:` URI or the address). Frames are not stored or uploaded.

## What we do not collect

Name, email, phone number, contacts, location, hardware serials, or private keys.

XMRig is bundled unmodified (GPL-3). Pool payouts are between you and MoneroOcean.
