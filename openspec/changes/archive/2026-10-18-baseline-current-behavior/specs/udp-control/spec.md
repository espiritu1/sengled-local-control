# UDP Control Specification

## Purpose

Baseline (current behavior) of port-9080 JSON control in `UdpClient.kt`, consumed by `MainActivity` and `RoutineService`. Regression reference only.

## Requirements

### Requirement: Control Protocol on Port 9080

The system SHALL issue JSON commands over UTF-8 UDP on port 9080 with a 1500 ms per-request timeout: `set_device_switch` (param `switch` 1/0), `set_device_brightness` (clamped 0..100), `get_device_brightness` (reply `{"result":{"ret":0,"brightness":NN}}`), and `search_devices` (discovery, via `WifiDetector`).

#### Scenario: Set brightness

- GIVEN a reachable bulb
- WHEN the app sends `set_device_brightness` with 42
- THEN the command is clamped to 0..100 and acknowledged within 1500 ms

#### Scenario: Command timeout

- GIVEN a bulb that does not reply
- WHEN a command is sent
- THEN the operation ends after 1500 ms without a reply

### Requirement: Per-Operation Scoped Sockets

Every UDP operation SHALL use a scoped `UdpClient().use { ... }` socket. `UdpClient` is NOT thread-safe (one `DatagramSocket` per instance) and sockets SHALL never be shared across concurrent queries, because UDP replies carry no source tag and a shared socket would mix up per-bulb state.

#### Scenario: Parallel queries to two bulbs

- GIVEN two bulb queries running concurrently
- WHEN each wraps its work in `UdpClient().use { ... }`
- THEN each operation reads only its own reply

### Requirement: Network I/O Off the Main Thread

`MainActivity` SHALL run all network work on an `Executors.newFixedThreadPool(2)`; view updates SHALL go through `runOnUiThread`.

#### Scenario: UDP reply arrives

- GIVEN a control command completed on the executor
- WHEN the result updates the UI
- THEN the update runs via `runOnUiThread`, never on the network thread

### Requirement: Power State Displayed From Memory, Not UDP

The system SHALL NOT query the switch state over UDP (only latent brightness is reported — a powered-off bulb keeps reporting its last brightness). `UdpClient.getState` SHALL derive heuristic `on = brightness > 1`; the displayed state SHALL prefer `ScheduleManager.getLastPower(id)` (`rememberedOn ?: finalState.on`), which returns `null` — not `false` — when never set. Every user toggle and slider change SHALL write `setLastPower`/`setLastBrightness`.

#### Scenario: First-ever display

- GIVEN no `last_power` was ever stored for the bulb
- WHEN refresh completes
- THEN the display falls back to the brightness heuristic (`brightness > 1`)

#### Scenario: Remembered off beats brightness

- GIVEN the user last set the bulb off but brightness is 80
- WHEN refresh completes
- THEN the UI shows off because `getLastPower` takes precedence

### Requirement: Brightness Slider Semantics

The SeekBar max of 99 SHALL map to brightness 1..100 (1 = dimmest, never 0 — only the switch turns a bulb off). A slider change SHALL imply on (`isOn = value > 0`) and write `last_power`. Slider input SHALL be debounced 250 ms (`BulbAdapter.BRIGHTNESS_DEBOUNCE_MS`); switch and SeekBar SHALL be disabled while a card is dragged and while `connected == false`.

#### Scenario: Slider at minimum

- GIVEN the slider is moved to its lowest position
- WHEN the value is applied
- THEN brightness becomes 1 (not 0) and `last_power` is written as on

### Requirement: Optimistic UI With Deferred Confirmation

Switch and slider updates SHALL render immediately with `connected = null` ("checking") and settle to `true`/`false` when the UDP acknowledgement arrives.

#### Scenario: Toggle a bulb

- GIVEN an on-screen switch is toggled
- WHEN the command is queued
- THEN the UI shows the new state with "checking" until the ack settles it

## Annotations

- **Frozen quirk:** the switch state is never queried over UDP by design; the remembered-power-over-brightness heuristic above is the required display rule — do not "clean it up".
- **No test suite:** `testDebugUnitTest` runs NO-SOURCE; scenarios here are review-verified only.
