# Exploration: baseline-current-behavior

**Change:** `baseline-current-behavior` — faithful inventory of the CURRENT behavior of the Android app (`SengledApp/`, package `com.sengled.control`), to serve as the spec baseline before new work (a Sonoff-brand pairing wizard) is added later.
**Artifact store:** openspec (files only). **Date:** 2026-10-18. **Mode:** documentation of what EXISTS — no changes proposed.
**Scope:** `SengledApp/` only. `SengledTools/` (Python fork) is gitignored and explicitly out of scope. No `.codegraph/` index exists at the repo root, so exploration used direct file reads (Read/Grep/Glob); CodeGraph was not initialized, per instructions.

---

## Current State

The app is a single-module Kotlin Android app (minSdk 24, targetSdk 34, AGP 8.5.2, JDK 17, view binding, Material 3) with **17 Kotlin files** under `SengledApp/app/src/main/java/com/sengled/control/`. Two activities (`MainActivity`, `PairingWizardActivity`), two `specialUse` foreground services (`RoutineService`, `MqttBrokerService`), one broadcast receiver (`ScheduleReceiver`), one `RecyclerView` adapter, and manager `object`s for persistence. There is **no test suite** (config.yaml: `testDebugUnitTest` runs NO-SOURCE) and no third-party libs beyond AppCompat/Material/ConstraintLayout/RecyclerView + `org.bouncycastle:bcpkix-jdk18on:1.78.1`.

### Domain inventory (verified against real code)

#### 1. Pairing / provisioning flow (existing Sengled wizard)

- **Files:** `PairingWizardActivity.kt` (678 lines), `PairingProtocol.kt`, `PairingServer.kt`, `SengledCrypto.kt`, `WifiDetector.kt`; layouts `activity_pairing_wizard.xml`, `step_wifi_credentials.xml`, `step_connect_ap.xml`, `step_pairing_progress.xml`, `step_result.xml`.
- **4-step wizard** with step dots: (1) Wi-Fi SSID + password — with a "Detectar" Wi-Fi scan list (requires runtime `ACCESS_FINE_LOCATION`; fixed 2500 ms wait after `startScan()`); (2) connect to the bulb's AP `192.168.8.x` in system settings; (3) pairing in progress (runs the whole protocol on a background `Thread`); (4) result — shows IP + MAC, name (default `Foco {last-5-of-MAC}`) and optional static IP, then saves and returns to `MainActivity` with `FLAG_ACTIVITY_CLEAR_TOP | SINGLE_TOP`.
- **Step 3 protocol sequence** (`PairingProtocol`, UDP to `192.168.8.1:9080`, W21-N11): handshake → Wi-Fi scan → 5 s sleep → AP list → re-handshake → send credentials (RC4-encrypted via `SengledCrypto`, hardcoded key from the original Sengled app) → end config. Handshake retries **40 attempts × (2 s sleep + 2 s socket timeout)** ≈ up to ~2.7 min; MAC falls back to the connected BSSID when the bulb omits it in `startConfig` response. Credentials payload points the bulb at the phone's LAN IP (`http://{lanIp}:57542/...` for cloud-access and `/jbalancer/new/bimqtt` for the broker address).
- **Network pinning quirks (critical):** the socket is bound to the phone's Wi-Fi interface (`WifiDetector.createWifiSocket`) AND the whole process is pinned to the bulb-AP network (`bindProcessToBulbAp`) because Android otherwise routes through mobile data; both are released after credentials are sent.
- **Post-credentials discovery:** the wizard asks the user to switch back to home Wi-Fi (`waitOffBulbAp`, 180 s timeout), then discovers the bulb's IP by `search_devices` MAC scan (180 s). The `PairingServer` HTTP endpoints (`/life2/device/accessCloud.json`, `/jbalancer/new/bimqtt`) still run and must respond (the `bimqtt` response delivers the broker address), but its `bulbIp`/`hitAccessCloud`/`bothEndpointsHit` tracking is **never consumed** by the wizard — IP discovery is scan-based ("Opción B"). Even on scan failure the wizard always lands on step 4 so the user can type the IP manually.
- **Rotation/back behavior:** rotation is locked ONLY on step 3 (`SCREEN_ORIENTATION_LOCKED`); all other steps rotate freely and restore step + typed inputs from `savedInstanceState`. Back arrow and system back are synced through `OnBackPressedCallback` → `goBack()`: step 1 → confirm-exit dialog; step 2 → step 1; step 3 → confirm-cancel dialog, then flag `pairingCancelled` (a `@Volatile` checked between protocol stages) and return to step 2; step 4 → `finish()` WITHOUT saving. `onDestroy` cancels pairing, unregisters callbacks, stops the HTTP server; the pairing thread's `finally` releases only the MQTT reference it actually acquired (`mqttAcquired` guard).
- **MqttBroker coupling:** step 3 acquires an extra `MqttBroker.acquire()` reference and starts `PairingServer` with `brokerHost = lanIp, brokerPort = 8883`; without a reachable TLS broker the ESP8266 firmware blinks indefinitely.
- **Manual add path (no wizard):** `MainActivity.showAddManualDialog()` — name + IP required, MAC optional; bulb id = last 6 hex of MAC, else `"ip" + ip-without-dots`.

#### 2. Device discovery & static IP handling

- **Files:** `WifiDetector.kt`, `MainActivity.kt` (`refresh()`, `reloadBulbs()`), `BulbRegistry.kt`.
- **Refresh fast path:** each bulb queries its saved IP first (`udp.getState(ip)`). **If a saved IP is set but unresponsive, the MAC scan is SKIPPED** and the bulb is marked offline (keeps refresh fast; commit `c2a66cc`). The MAC scan runs **only when the saved IP is blank** (user skipped IP entry): `WifiDetector.findBulbByMac(mac, 12_000)` → on success the discovered IP is persisted via `BulbRegistry.updateBulbIp`.
- **`findBulbByMac`:** broadcasts `{"func":"search_devices"}` to `255.255.255.255` **plus a per-host sweep of the phone's /24 subnet (hosts 1..254)** on port 9080, listening for a reply whose `result.mac` matches (normalized, case-insensitive). Blank MAC aborts immediately. Blocking I/O — background thread only.
- **Discovery during pairing** uses the same function with a 180 s window.
- **IP identity:** bulb id is the last 6 hex chars of the MAC (lowercase) — shared between wizard and manual add. IP is optional everywhere; a bulb can be re-found by MAC whenever IP is blank.

#### 3. UDP control on port 9080

- **File:** `UdpClient.kt` (80 lines), used by `MainActivity`, `RoutineService`.
- **Protocol:** JSON over UTF-8 UDP, port **9080**, per-request timeout **1500 ms**. Commands: `set_device_switch` (param `switch` 1/0), `set_device_brightness` (clamped 0..100), `get_device_brightness` (reply `{"result":{"ret":0,"brightness":NN}}`), `search_devices` (discovery, in `WifiDetector`).
- **`UdpClient` is NOT thread-safe** — one `DatagramSocket` per instance, so every operation is wrapped in `UdpClient().use { ... }`. A shared socket would let parallel queries receive each other's replies (UDP has no source tag) and mix up per-bulb state. `MainActivity` runs all network work on a `Executors.newFixedThreadPool(2)`; view updates always go through `runOnUiThread`.
- **Why switch state is NOT queried over UDP:** `get_device_brightness` reports only a **latent brightness**, never the real switch — a powered-off bulb keeps reporting its last brightness. `UdpClient.getState` therefore derives a heuristic `on = brightness > 1`, but the UI overrides it with the remembered power state: `ScheduleManager.getLastPower(id)` takes precedence (`rememberedOn ?: finalState.on`), and every user toggle/slider change writes `setLastPower`/`setLastBrightness`. `getLastPower` returns `null` (not `false`) when never set, so first-ever display falls back to the brightness heuristic.
- **Brightness slider semantics:** SeekBar max 99 maps to brightness **1..100** (1 = dimmest, never 0 — only the switch turns a bulb off); changing the slider also implies on (`isOn = value > 0`) and writes `last_power`. Slider input is debounced 250 ms (`BulbAdapter.BRIGHTNESS_DEBOUNCE_MS`). Switch/seekbar are disabled while a card is being dragged and while `connected == false`.
- **Optimistic UI:** switch/slider updates render immediately with `connected = null` ("checking"), then settle to `true`/`false` when the UDP ack arrives.

#### 4. Embedded MQTT broker + foreground service

- **Files:** `MqttBroker.kt` (object, 411 lines), `MqttBrokerService.kt`, `SslCertGenerator.kt`; manifest `foregroundServiceType="specialUse"` (`smart_home_light_mqtt_broker`).
- **Hand-rolled MQTT 3.1.1 subset broker** (no library): CONNECT→CONNACK, SUBSCRIBE→SUBACK (grants QoS 0 for every topic), PUBLISH→PUBACK for QoS 1 (payload logged only), PINGREQ→PINGRESP, UNSUBSCRIBE→UNSUBACK, DISCONNECT→close. 30 s idle socket timeout, 64 KB packet cap, connected clients tracked in a `ConcurrentHashMap` keyed by clientId.
- **TLS on 8883:** `SslCertGenerator` generates a fresh self-signed RSA-2048 cert (CN=`SengledLocalBroker`, 1-year validity, CA+server in one) at every broker start and builds an `SSLContext` ("TLS" → TLS 1.2, which ESP8266 mbedtls requires). Uses **BouncyCastle** (`bcpkix-jdk18on:1.78.1`) for X.509 building but the platform/Conscrypt providers for signing — the file explicitly warns that BouncyCastle's `SHA256withRSA` is unavailable on Android P+ and must not be requested.
- **Reference counting:** `MqttBroker.acquire()`/`release()` — starts on first ref, stops on last. `MqttBrokerService` acquires one ref in `onCreate` (started from `MainActivity.onCreate` AND on boot via `ScheduleReceiver`) and stops itself if acquisition fails; the pairing wizard acquires/releases a second transient ref (`mqttAcquired` guard so an aborted run never releases the service's ref).
- **Single persistent notification shared with `RoutineService`:** same channel id `rutinas` AND same notification id `1` — the two foreground services overwrite each other's notification content, so the phone shows exactly one ongoing bar (deliberate; see `MqttBrokerService` doc comment).
- If the broker is unreachable, paired bulbs blink indefinitely (firmware requirement) — this is why it runs persistently.

#### 5. Routines / schedules

- **Files:** `RoutineService.kt` (359 lines, owns all firing), `ScheduleManager.kt` (persistence + wake kicks), `ScheduleReceiver.kt` (boot/time/timezone/kick → `startService`), `RoutineDialog.kt` (editor UI).
- **Model:** exactly ONE routine per bulb: `enabled`, `onMinutes`/`offMinutes` (minutes-since-midnight), `brightness` (1..100). Defaults: ON **19:30**, OFF **00:00**, brightness **1**. Persisted in `sengled_prefs` (`routine_enabled_/routine_on_/routine_off_/routine_brightness_<id>`). Routine dialog: AM/PM 12-hour time pickers with an always-visible AM/PM toggle (`applyPeriod` preserves hour/minutes across the period flip), brightness SeekBar, enable switch. Saving calls `ScheduleManager.save` → re-`startService`.
- **Firing model:** `RoutineService` is the single owner. A `HandlerThread` runs `tick()`: for each enabled routine, fire due ON and OFF (grace windows: **+90 s forward, −10 min backward**), then sleep until the next event (or `next − 30 min`, see below) and schedule an exact-alarm **wake-up kick** (`setExactAndAllowWhileIdle` → fallback `setExact` → silently none) to `ScheduleReceiver.ACTION_ROUTINE_CHECK`, which merely restarts the service. Idempotency via `last_fired_<bulb>_<on|off> = epoch-minute` markers. ON fires `setBrightness` then `setSwitch(true)` (both must succeed for a notification); OFF fires `setSwitch(false)`. A 10 s partial wake lock guards each fire; notifications are staggered 400 ms per bulb (`NOTIF_STAGGER_MS`).
- **"Reveal 30 min before" behavior** (commit `0f77642`): `tick()` posts the next tick at `next − 30 min` (`NOTIF_AHEAD_MS`), and the **persistent status notification** switches its text to "Próxima a las {time}" only when the next event is within 30 min (`updateStatusNotification`); otherwise it shows the generic text. Temporary per-event announcements go on a separate HIGH-importance channel.
- **Catch-up logic:** `registerNetworkCallback` → on default-network availability, debounce **2 s** → `runCatchUp()` → `recoverTransitions()` per enabled routine: applies any transition whose occurrence has passed and whose `last_fired` marker is unset, using the SAME markers as `tick()` (idempotent). Deliberately does **not** force the "expected" state onto a bulb and posts **no** notification — the code comment documents that strict reconciliation caused a "blinks off then back on" flicker on reconnect (doze exit, Wi-Fi handover, band switch). Note the asymmetry with `tick()`: `recoverTransitions` has **no backward grace window** — a missed transition from earlier the same day is still recovered on reconnect.
- **lastPower integration:** routine ON/OFF does NOT write `last_power` — only user gestures in `MainActivity` do. So after a routine fires, the next refresh still shows the remembered user state as `isOn` (baseline quirk to freeze, not fix here).
- **Lifecycle:** `START_STICKY`; self-stops (`stopRoutine`: cancel kick, clear handler, `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf`) when no enabled routine remains or on `ACTION_STOP`. Started from `MainActivity.onCreate` and on boot.
- **Known quirk:** `fireActionIfDue` writes the `last_fired` marker BEFORE attempting UDP, so a failed command is consumed and never retried (catch-up skips it too).

#### 6. Bulb management (BulbRegistry)

- **Files:** `BulbRegistry.kt`, `Bulb.kt` (`data class Bulb(id, name, ip, brightness=1, isOn=false, connected=null)`), `MainActivity.kt`, `BulbAdapter.kt`.
- **Storage:** bulbs live ONLY in SharedPreferences — prefs file `sengled_bulbs`, JSON array under `bulbs_json` (`id`, `name`, `ip` per entry), MACs as separate keys `mac_<id>` in the same file. No hardcoded defaults; `loadPaired` returns `emptyList()` on any parse error.
- **API:** `getBulbs`, `addBulb` (upsert by id), `updateBulbIp`, `removeBulb`, `reorderBulbs` (caller passes full order; unknown ids dropped, unlisted bulbs kept at the end — defensive), `getMac`.
- **Rename quirk:** renames are NOT written to `BulbRegistry` — they go to `sengled_prefs` as `name_<id>` (overlay). `MainActivity` overlays it on load; `ScheduleManager.getBulbName` prefers the overlay. The registry's `name` stays at its original value forever. Two prefs files, two name stores.
- **UI ops (MainActivity/BulbAdapter):** rename (max 40 chars), edit IP, delete (confirm dialog; also resets the routine via `ScheduleManager.save(..., false, 0, 0, 1)`), drag-and-drop reorder (long-press starts drag; on drop, `reorderBulbs` persists and the in-memory `bulbs` list is re-ordered to match; while dragging the card is highlighted `card_background_active`, elevated, and its slider/switch are disabled), schedule editor. `onResume → reloadBulbs()` synchronizes the adapter with the registry (adds new, removes deleted — covers bulbs added by the wizard).

#### 7. Notifications

- **Files:** `RoutineService.kt` (channels + both notification types), `MqttBrokerService.kt` (shared persistent one), `MainActivity.setupDiagnostics()`.
- **Exactly one persistent/ongoing notification:** channel `rutinas` ("Rutinas", IMPORTANCE_DEFAULT), **id 1**, shared by `RoutineService` and `MqttBrokerService` — whichever service calls `startForeground`/`notify(1, …)` last wins the content. RoutineService's version shows generic text or the next-event time (30-min reveal); MqttBrokerService's says the local broker is active. `setOngoing(true)`, `setOnlyAlertOnce(true)`.
- **Temporary announcements:** channel `rutinas_aviso` ("Avisos de rutina", IMPORTANCE_HIGH), ids from `1000 + counter` (counter persisted in `event_notification_id`, incremented per event so each event is a distinct auto-cancel notification), `DEFAULT_ALL` sound, staggered 400 ms, posted only when the UDP command succeeded.
- **Permission guard:** if notifications are disabled, `MainActivity` shows an indefinite Snackbar with an action opening app notification settings (with a fallback to app-details settings).

#### 8. UI / locale / theme

- **Files:** `res/values/strings.xml` + `res/values-en/strings.xml` (**135 strings each — full parity**), `res/values/colors.xml`, `res/values/themes.xml`, layouts (6 activity/step layouts + 2 dialogs + item + spinner), `menu_main.xml`.
- **Locale:** Spanish is the app default; `lang` pref in `sengled_prefs` is always explicit (`"es"` or `"en"`, never device-locale fallback). `MainActivity.onCreate` applies it via `Locale.setDefault` + deprecated `resources.updateConfiguration`. The language button is a STATUS indicator showing the current language ("ES"/"EN"), not the target; toggling writes the pref and calls `recreate()`.
- **Theme:** `Theme.Sengled` = `Theme.Material3.DayNight.NoActionBar` with forced dark surfaces (`background_dark #121212` for status bar + window background, cards `#1E1E1E`, drag-highlight `#3A3A3A`), amber primary `#FFC107`, status colors green `#4CAF50` / red `#FF5252`. Layouts contain no hardcoded `android:text` (all `@string`/empty).
- **Main screen:** refresh button, add-bulb button (chooser dialog: pair via wizard / manual add), info dialog (inflated from `dialog_info_sections` + `dialog_info` inside a ScrollView; autoLink for URLs), language button, version label (`v{versionName}` from `versionName` = "8.0"), notification-disabled Snackbar.
- **Hardcoded-string violation (freeze as-is):** `PairingWizardActivity` contains 16 hardcoded Spanish user-facing strings that bypass resources (3 field errors — "Ingresá el nombre de la red", "Ingresá la contraseña", "Ingresá un nombre para el foco" — plus 12 pairing-log lines and the success Toast "¡Foco '…' agregado!"). This conflicts with the AGENTS.md/openspec rule requiring `values/` + `values-en/` resources; the baseline must record it as existing behavior, not silently normalize it.
- **Dead resources/code (baseline ambiguity):** `menu_main.xml` is never inflated (no `onCreateOptionsMenu`); `MainActivity.buildRoutineSubtitle()` is never called; `ScheduleManager.hasEnabledRoutine()` has no callers; `getLastBrightness` is written but never read; `PairingServer.bothEndpointsHit`/`bulbIp` are never consumed; `SengledCrypto.decryptPayload` has no callers.

### Cross-cutting constraints worth freezing

- Network I/O NEVER on the main thread: `MainActivity` uses a fixed 2-thread executor; pairing and services use plain `Thread`/`HandlerThread`; view updates via `runOnUiThread`/`Handler(mainLooper)`.
- Every UDP operation uses a scoped `UdpClient().use { }`; sockets are per-operation, never shared across concurrent queries.
- SharedPreferences always behind dedicated `object` managers (`BulbRegistry`, `ScheduleManager`) over two files: `sengled_bulbs` (bulbs + MACs) and `sengled_prefs` (names overlay, lang, routines, last_power/last_brightness, last_fired, notification counter).
- Manifest permissions: INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, ACCESS_FINE_LOCATION (Wi-Fi scan only), WAKE_LOCK, RECEIVE_BOOT_COMPLETED, FOREGROUND_SERVICE(_SPECIAL_USE), SCHEDULE_EXACT_ALARM.
- No tests exist; the only automated checks are `gradlew lint` and `compileDebugKotlin`.

---

## Affected Areas

(For the baseline: the domains covered; for the later Sonoff pairing work these are the touch points.)

- `SengledApp/app/src/main/java/com/sengled/control/PairingWizardActivity.kt` — 4-step wizard UI, rotation/back rules, cancellation
- `SengledApp/app/src/main/java/com/sengled/control/PairingProtocol.kt` — Sengled UDP pairing protocol sequence
- `SengledApp/app/src/main/java/com/sengled/control/PairingServer.kt` — pairing-time HTTP verification server (port 57542)
- `SengledApp/app/src/main/java/com/sengled/control/WifiDetector.kt` — AP detection, process pinning, MAC discovery
- `SengledApp/app/src/main/java/com/sengled/control/UdpClient.kt` — port-9080 control protocol
- `SengledApp/app/src/main/java/com/sengled/control/MainActivity.kt` — refresh/discovery fast path, control callbacks, locale, diagnostics
- `SengledApp/app/src/main/java/com/sengled/control/BulbRegistry.kt` / `Bulb.kt` — persistence model
- `SengledApp/app/src/main/java/com/sengled/control/ScheduleManager.kt` / `RoutineService.kt` / `ScheduleReceiver.kt` / `RoutineDialog.kt` — routines, catch-up, notifications
- `SengledApp/app/src/main/java/com/sengled/control/MqttBroker.kt` / `MqttBrokerService.kt` / `SslCertGenerator.kt` — embedded TLS broker
- `SengledApp/app/src/main/java/com/sengled/control/SengledCrypto.kt` — RC4 credential encryption
- `SengledApp/app/src/main/res/values*/strings.xml`, `colors.xml`, `themes.xml` — locale/theme baseline
- `SengledApp/app/src/main/AndroidManifest.xml` — components, permissions, FGS types

## Approaches

1. **Baseline inventory as spec source (chosen)** — document current behavior domain-by-domain as the frozen reference before any Sonoff work; specs derived from this artifact describe what MUST keep working.
   - Pros: downstream deltas (Sonoff wizard) are diffed against a known truth; non-obvious behaviors (lastPower precedence, skip-MAC-scan, shared notification id) are not accidentally "cleaned up" by later phases.
   - Cons: must explicitly mark dead code/quirks rather than removing them.
   - Effort: Low (done in this phase).
2. **Defer baseline into the proposal/specs directly** — skip a standalone baseline artifact.
   - Pros: less documentation.
   - Cons: specs would mix observed fact with new intent; high risk of misremembered behavior in later phases.
   - Effort: Medium (rework later).

## Recommendation

Freeze THIS artifact as the baseline reference. `sdd-spec` should convert the eight domain sections into `openspec/specs/` requirements (Given/When/Then, RFC 2119) describing existing behavior, and `sdd-propose` should scope the Sonoff pairing wizard strictly as an ADDITION beside domain 1 — the Sonoff flow must not alter domains 2–8 behaviors listed here (per-operation UDP sockets, lastPower precedence, shared notification id, catch-up semantics).

## Risks

- **Dead/ambiguous code:** `buildRoutineSubtitle`, `hasEnabledRoutine`, `getLastBrightness` (write-only), `PairingServer` endpoint tracking, `decryptPayload`, `menu_main.xml` — specs must decide whether each is "current behavior" or out of scope; describing unused APIs as behavior would fossilize noise.
- **Hardcoded Spanish strings in the wizard (16)** violate the project's own string-resource rule — a baseline that freezes them conflicts with `openspec/config.yaml` apply rules; flag when authoring specs.
- **No test suite** — the baseline cannot be regression-checked automatically; verify will rely on `lint`/`compileDebugKotlin` and manual checks only.
- **Two overlapping prefs files** (`sengled_bulbs` vs `sengled_prefs` names overlay) — rename behavior is split; specs must state which store wins (overlay does).
- **README drift:** README claims "Bulb IPs are defined in BulbRegistry.kt" — actually SharedPreferences at runtime; and the wizard's "~2.5 minutes" retry comment vs 40×(2 s+2 s) timing is approximate. Docs are not the source of truth; code is.
- **Shared notification id 1 across two services** is order-dependent (last `notify` wins) — correct as a baseline fact, but fragile if a future phase adds a third foreground service.
- `SengledTools/` deliberately excluded — any spec mentioning "the desktop tool's behaviour" refers to out-of-repo code and cannot be verified here.

## Ready for Proposal

**Yes.** The baseline inventory is complete and grounded in real code. Tell the user: exploration for `baseline-current-behavior` is done; the eight current-behavior domains are documented with quirks frozen; next phase is `sdd-propose` (or directly `sdd-spec` if the orchestrator wants baseline requirements without a proposal, since no code change is planned in this change).
