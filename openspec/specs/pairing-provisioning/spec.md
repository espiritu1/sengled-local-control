# Pairing and Provisioning Specification

## Purpose

Current behavior of the 4-step Sengled wizard and manual add.

## Requirements

### Requirement: Four-Step Wizard Flow (UI Steps)

The wizard SHALL walk 4 dotted steps: Wi-Fi credentials → connect to bulb AP `192.168.8.x` → pairing in progress → result (IP, MAC, default name `Foco {last-5-of-MAC}`, optional static IP).

#### Scenario: Successful pairing (wizard navigation)

- GIVEN all four steps completed
- WHEN step 4 saves
- THEN the wizard completes with the save and return behavior as specified

#### Scenario: Scan failure falls through

- GIVEN MAC discovery failed
- WHEN the wizard advances
- THEN step 4 still shows (manual IP entry)

### Requirement: Four-Step Wizard Save and Return

Saving SHALL return to `MainActivity` with CLEAR_TOP | SINGLE_TOP.

#### Scenario: Successful pairing (save and return)

- GIVEN all four steps completed
- WHEN step 4 saves
- THEN the wizard saves and finishes with CLEAR_TOP | SINGLE_TOP

### Requirement: Wi-Fi Detection on Step 1

Step 1 SHALL offer a "Detectar" Wi-Fi scan list requiring runtime `ACCESS_FINE_LOCATION`, waiting a fixed 2500 ms after `startScan()`.

#### Scenario: Detectar tapped

- GIVEN location permission granted
- WHEN the user taps "Detectar"
- THEN the app waits 2500 ms and lists the scanned networks

### Requirement: Pairing Protocol on Step 3 (Stage Sequence)

Step 3 SHALL run on a background `Thread` against UDP `192.168.8.1:9080` with the protocol stage sequence: handshake, Wi-Fi/AP scans (5 s sleep), re-handshake, RC4 credentials (`SengledCrypto`, hardcoded key) targeting the phone's LAN IP, end config.

### Requirement: Pairing Protocol Handshake Retry Policy

Handshake SHALL retry 40 × (2 s + 2 s).

### Requirement: Pairing Protocol MAC Fallback

When the `startConfig` response has no MAC, the system SHALL fall back to the connected BSSID as the MAC.

#### Scenario: startConfig omits MAC

- GIVEN the `startConfig` response has no MAC
- WHEN credentials are assembled
- THEN the connected BSSID is used as the MAC

### Requirement: Network Pinning During Pairing

The pairing socket SHALL bind to the phone's Wi-Fi interface; the process SHALL pin to the bulb-AP network during credential exchange. Both release afterwards.

#### Scenario: Mobile data enabled

- GIVEN mobile data is active
- WHEN the process is pinned
- THEN traffic reaches the bulb AP, not mobile data

### Requirement: Post-Credentials Discovery and HTTP Server

After credentials the wizard SHALL prompt return to home Wi-Fi (`waitOffBulbAp`, 180 s) then find the bulb IP by `search_devices` MAC scan (180 s), never from `PairingServer` tracking. Step 3 SHALL run `PairingServer` (broker `lanIp:8883`) serving both cloud endpoints (`accessCloud.json`, `bimqtt`).

#### Scenario: Bulb rejoins, scan matches

- GIVEN the bulb joins home Wi-Fi and hits both endpoints
- WHEN the scan matches within 180 s
- THEN the IP persists; endpoint tracking is never consumed

### Requirement: Rotation and Back Navigation

Rotation SHALL be locked ONLY on step 3; other steps rotate and restore state from `savedInstanceState`. Back SHALL route via `OnBackPressedCallback` → `goBack()`: 1 → confirm-exit, 2 → step 1, 3 → confirm-cancel then `pairingCancelled`, 4 → `finish()` WITHOUT saving.

#### Scenario: Back on step 4

- GIVEN the result step shows
- WHEN the user presses back
- THEN the wizard finishes without saving

#### Scenario: Cancel during step 3

- GIVEN the protocol thread runs
- WHEN the user confirms cancel
- THEN `pairingCancelled` is set between stages and step 2 returns

### Requirement: Broker Reference Guard on Step 3

Step 3 SHALL acquire a transient `MqttBroker` reference, released only if acquired (`mqttAcquired` guard). `onDestroy` SHALL cancel pairing, unregister callbacks, and stop `PairingServer`.

#### Scenario: Aborted before acquisition

- GIVEN pairing ends before acquisition
- WHEN the thread's `finally` runs
- THEN no broker reference is released

### Requirement: Manual Add Path

Manual add SHALL require name and IP, MAC optional; id = last 6 hex chars of MAC, else `"ip" + ip-without-dots`.

#### Scenario: Add without MAC

- GIVEN name and IP, MAC blank
- WHEN the user confirms
- THEN the id is `ip` + the IP with dots stripped

## Annotations

- **Hardcoded Spanish strings (frozen):** 16 wizard strings bypass resources; rule violation, fix deferred.
- **Dead code — not spec'd:** `PairingServer` endpoint tracking; `SengledCrypto.decryptPayload`.
- **README drift:** retry comments approximate; code is truth.
- **No test suite:** `testDebugUnitTest` NO-SOURCE; `lint` only.
- **Out of scope:** `SengledTools/` (gitignored).
