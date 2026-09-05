# Sengled Control — Local Sengled Bulb Control

**Sengled smart bulbs, under your own roof. No cloud. No accounts. No Internet required.**

<img src="docs/screenshots/captura-pantalla.jpg" alt="Sengled Control — main screen: per-bulb cards with ON/OFF switch and brightness slider" width="540" />

When Sengled discontinued these bulbs, their cloud died — and with it, the official app. This project reverse-engineers the local Sengled UDP protocol and gives you a native Android app that talks directly to each **Sengled W21-N11** white bulb (EMW3091 module, fixed 2700K) over your LAN (UDP port 9080). Discontinued hardware, kept alive — 100% local, 100% yours.

## What's in this repo

| Folder | What it is |
|---|---|
| `SengledApp/` | **Android app** (Kotlin, Material 3) that controls your bulbs from your phone |

> **SengledTools** (Python tooling, web panel, pairing wizard, Home Assistant integration, protocol docs) lives in its **own repository** — it is a fork of the community project [`HamzaETTH/SengledTools`](https://github.com/HamzaETTH/SengledTools). The local clone is kept out of this repo (see `.gitignore`) so each repository keeps its own history and upstream.

## SengledApp — Android app

Native app (minSdk 24, targetSdk 34) that talks directly to each bulb over UDP.

### What you can do

| Action | What it does |
|---|---|
| **Refresh** | Check the live status of every bulb on your LAN: *Prendida* (on) / *Apagada* (off) / *Sin conexión* (offline) |
| **Add / pair a bulb** | Pair a new bulb with the in-app wizard (Wi-Fi credentials + built-in MQTT broker), or add an already-connected bulb by IP/MAC |
| **Turn on / off** | Tap the switch on any bulb card to power the bulb |
| **Adjust brightness** | Drag the slider (1–100%) to set the light intensity |
| **Schedule routines** | Set daily on/off times per bulb, each with its own brightness (e.g. ON at 19:30 at 7%, OFF at 00:00) |
| **Rename** | Give each bulb a friendly, recognizable name |
| **Reorder cards** | Press and hold a card and drag it up or down to change the bulb order |
| **Delete** | Remove a bulb from the app (it stays configured in your router) |

The app also works hands-free in the background: it re-applies missed routine events when the phone reconnects to home Wi-Fi (catch-up), keeps bulbs connected through a persistent local MQTT broker, and surfaces network-change notifications and diagnostics in the toolbar.

<img src="docs/screenshots/captura-opciones.jpg" alt="Sengled Control — bulb options screen" width="540" />

### Build

```powershell
cd SengledApp
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.x"
.\gradlew.bat assembleDebug --no-daemon
```

On Linux/macOS use `./gradlew assembleDebug`. Requires Java 17 (AGP 8.5.2). APKs land in `SengledApp/app/build/outputs/apk/debug/`.

### Network requirements

- Phone must be on the **same LAN** as the bulbs (UDP port 9080).
- **Static IP per bulb** is recommended (reserve them in your router's DHCP) so routines keep working after router restarts.
- Bulb IPs are defined in `SengledApp/app/src/main/java/com/sengled/control/BulbRegistry.kt`.

## SengledTools — Python tooling (separate repo)

The Python tooling lives in the local `SengledTools/` folder, which is a fork of the community project [`HamzaETTH/SengledTools`](https://github.com/HamzaETTH/SengledTools):

- `sengled_tool.py` — CLI to control/diagnose bulbs, Wi-Fi pairing wizard
- `sengled-web.py` — local web panel with one card per bulb
- `sengled/wifi_setup.py` — full provisioning flow (connect to the bulb's AP, scan networks, send encrypted credentials)
- `custom_components/sengled_udp/` — Home Assistant integration over local UDP
- `docs/` — reverse-engineered protocol references: UDP, MQTT, Wi-Fi pairing

The `sengled-pair.ps1` and `start-sengled-web.bat` helper scripts at the root require that local fork (including its `.venv`).

## Security

- **No credentials live in this repository.** Wi-Fi passwords are entered at runtime (pairing) or kept in environment variables; MQTT certificates and private keys are excluded via `.gitignore`.
- Everything runs on your LAN; the bulbs never touch external servers unless you run the cloud-related pairing steps.
