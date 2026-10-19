# Device Discovery Specification

## Purpose

Baseline (current behavior) of refresh/discovery in `MainActivity.refresh()`/`reloadBulbs()`, `WifiDetector`, and `BulbRegistry` IP handling. Regression reference only.

## Requirements

### Requirement: Saved-IP Refresh Fast Path

Each refresh SHALL query every bulb's saved IP first via `udp.getState(ip)`. If a saved IP is set but unresponsive, the system SHALL SKIP the MAC scan and mark the bulb offline (keeps refresh fast, commit `c2a66cc`).

#### Scenario: Saved IP unresponsive

- GIVEN a bulb has a saved IP that does not answer
- WHEN refresh runs
- THEN no MAC scan is attempted for that bulb and it is shown offline

#### Scenario: Saved IP responsive

- GIVEN a bulb has a saved IP that answers
- WHEN refresh runs
- THEN the bulb state is updated from the direct query

### Requirement: MAC Scan Only When IP Is Blank

The MAC scan SHALL run only when the bulb's saved IP is blank (user skipped IP entry). On success the discovered IP SHALL be persisted via `BulbRegistry.updateBulbIp`.

#### Scenario: Bulb added without IP

- GIVEN a bulb whose saved IP is blank
- WHEN refresh runs
- THEN `WifiDetector.findBulbByMac(mac, 12_000)` runs and a successful match is saved as the bulb's IP

### Requirement: MAC Scan Mechanics

`findBulbByMac` SHALL broadcast `{"func":"search_devices"}` to `255.255.255.255` AND sweep the phone's /24 subnet (hosts 1..254) on port 9080, listening for a reply whose `result.mac` matches after normalization (case-insensitive). A blank MAC SHALL abort immediately. The call SHALL use blocking I/O on a background thread only.

#### Scenario: Matching reply

- GIVEN a non-blank MAC
- WHEN any probed host replies with that normalized MAC
- THEN the reply's IP is returned

#### Scenario: Blank MAC

- GIVEN an empty MAC string
- WHEN `findBulbByMac` is called
- THEN it aborts without sending probes

### Requirement: Pairing-Time Discovery Window

Discovery during pairing SHALL use the same `findBulbByMac` function with a 180 s window.

#### Scenario: Discovery from the wizard

- GIVEN the wizard finished sending credentials
- WHEN MAC discovery runs
- THEN the same probe logic runs with a 180 s timeout instead of 12 s

### Requirement: Bulb IP Identity

The bulb id SHALL be the last 6 lowercase hex chars of the MAC, shared between the wizard and manual add. IP SHALL be optional everywhere; a bulb can be re-found by MAC whenever its IP is blank.

#### Scenario: IP later cleared

- GIVEN a bulb whose IP was cleared to blank
- WHEN refresh runs
- THEN discovery falls back to the MAC scan for that bulb

## Annotations

- **README drift:** the README claim that "Bulb IPs are defined in BulbRegistry.kt" is wrong at runtime — IPs live in SharedPreferences (`sengled_bulbs`); code is truth.
- **Dead code — not spec'd:** none in this domain; related unused symbols are annotated in their owning specs.
