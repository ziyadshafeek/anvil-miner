# How much Monero can an Android phone actually mine?

Short answer: **about a penny a day**. On MoneroOcean, the most profitable algorithm for the two phones we tested was plain **Monero (RandomX)** itself.

Numbers from two real ARM64 devices, MoneroOcean XMRig fork `v6.25.0-mo1`, no huge pages (Android does not grant them). Single-run, light thermal throttling — ballpark, not lab-grade. Measured 2026-06-22.

## Phones

| | Redmi Pad | LG V60 ThinQ |
|---|---|---|
| SoC | MediaTek **Helio G99** | Qualcomm **Snapdragon 865** |
| Cores | 2× Cortex-A76 + 6× Cortex-A55 | 4× Cortex-A77 + 4× Cortex-A55 |
| Class | budget (2022) | flagship (2020) |
| RAM | 6 GB | 8 GB |

## 1. Raw RandomX (`rx/0`)

| Phone | RandomX hashrate |
|---|---|
| Redmi Pad (Helio G99) | **~420 H/s** |
| LG V60 (Snapdragon 865) | **~750 H/s** |

Order of magnitude: **cents per day**, not dollars. ~420 H/s was roughly **0.00006 XMR/day** at the time of measurement.

## 2. More threads is not more hashrate

RandomX is memory-bandwidth-bound. On big.LITTLE, waking the little cores can *hurt*.

**Redmi Pad — Helio G99 (2 big + 6 little)**

| Threads | H/s | % of max |
|---|---|---|
| 2 (big cores only) | 328 | 77% |
| 4 | 385 | 91% |
| 8 (all) | 424 | 100% |

**LG V60 — Snapdragon 865 (4 big + 4 little)**

| Threads | H/s | |
|---|---|---|
| 2 | 466 | |
| **4 (all big cores)** | **748** | **← peak** |
| 8 (all) | 684 | **−9%** |

On the 865, 8 threads is slower than 4. Sweet spot is roughly the number of **big** cores.

## 3. MoneroOcean assigned Monero

Raw H/s of the light algos crushes RandomX (`rx/arq` ~9×, `cn-pico` ~5× on the G99). After the pool's profit calibration, **both phones got `rx/0` (Monero)** and held it.

A RandomX-only pool therefore loses ~nothing vs the profit-switcher on this hardware, at these prices.

## Reproduce

```bash
adb push xmrig /data/local/tmp/xmrig && adb shell chmod 755 /data/local/tmp/xmrig
adb shell 'cd /data/local/tmp && ./xmrig --bench=1M'
adb shell 'cd /data/local/tmp && ./xmrig --bench=1M --threads=4'
```

Live pool assignment (Anvil's bundled binary is TLS; a typical Termux/prebuilt phone binary often is not — use port `10032` / `tls: false` in that case):

```
gulf.moneroocean.stream:20032   # TLS
```

Watch for `new job ... algo XYZ`.

Open a GitHub issue with chip, core layout, RandomX H/s at 2/4/8 threads, and the algo MoneroOcean assigned. This table will grow.

Mine on a charger, with a thermal limit. Phones are not desktop CPUs.
