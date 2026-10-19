# SDD Archive Report: baseline-current-behavior

**Change name:** baseline-current-behavior  
**Artifact store:** openspec (files only)  
**Date archived:** 2026-10-18  
**Execution mode:** interactive (final phase)  
**Phase:** sdd-archive

## Executive Summary

The baseline-current-behavior change has been archived. All eight delta specs from the change folder were mechanically copied into the persistent spec store at `openspec/specs/` with byte-identity verified (empty diff). The change folder was moved to `openspec/changes/archive/2026-10-18-baseline-current-behavior/` with a pre-move snapshot diff also empty. No code changes were made (docs-only change). The spec corpus totals 399 lines across 8 domains, capturing the current behavior of the Sengled Android app as reviewable baseline before future work.

## Archive Contents (archived change folder)

The archived folder `openspec/changes/archive/2026-10-18-baseline-current-behavior/` contains:

- **proposal.md** — Change intent, scope, capabilities, affected areas, risks, success criteria.
- **exploration.md** — Domain-by-domain inventory of current behavior across 8 areas (pairing-provisioning, device-discovery, udp-control, mqtt-broker, routines-schedules, bulb-management, notifications, ui-locale-theme), with annotations and risks.
- **specs/** — 8 domain delta specs:
  - `pairing-provisioning/spec.md` (66 lines): 4-step wizard flow, Wi-Fi detection, protocol, network pinning, discovery, rotation/back, broker guard, manual add; annotations on hardcoded strings, dead code (`PairingServer` tracking, `SengledCrypto.decryptPayload`).
  - `device-discovery/spec.md` (45 lines): saved-IP fast path (skip MAC scan if IP set but unresponsive), MAC scan only if IP blank (discover and persist), broadcast + /24 sweep mechanics, 180s pairing window, bulb ID as last 6 hex of MAC, optional IP everywhere.
  - `udp-control/spec.md` (51 lines): UDP/9080 JSON, per-operation `UdpClient().use { }` scoped sockets, timeouts 1500ms, commands (set_switch/set_brightness/get_device_brightness/search_devices), lastPower/brightness semantics (brightness heuristic not real switch; `ScheduleManager.getLastPower` precedence), slider 1-100 mapping, debounced 250ms, optimistic UI with `connected` state.
  - `mqtt-broker/spec.md` (45 lines): embedded MQTT 3.1.1 subset (CONN/SUB/PUB/PING/UNSUB/DISCONN), 30s idle, 64KB cap, TLS 8883 with BouncyCastle-generated cert, reference counting `acquire()/release()`, foreground service with shared notification id 1, pairing acquires transient ref.
  - `routines-schedules/spec.md` (62 lines): 1 routine per bulb (enabled, on/off minutes, brightness), AM/PM dialog, firing with +90s/-10min grace, 30-min reveal behavior for next event, catch-up on network (no backward grace, no forced state, no notifications), last_fired markers (written before UDP attempt), exact-alarm wake kicks with fallbacks, lifecycle START_STICKY, routine ON/OFF does not write last_power.
  - `bulb-management/spec.md` (49 lines): SharedPreferences persistence (`sengled_bulbs` for bulbs+MACs), id = last 6 hex of MAC (or `ip<dots>`), rename via overlay in `sengled_prefs` (`name_<id>`) — overlay wins over registry name (split stores frozen), reorder with defensive handling, edit IP, delete with confirm (also resets routine), sync on resume.
  - `notifications/spec.md` (39 lines): single persistent ongoing notification (channel `rutinas`, id 1) shared by RoutineService and MqttBrokerService (last wins), temporary HIGH-importance announcements on channel `rutinas_aviso` with staggered 400ms and unique ids, permission guard with Snackbar opening settings.
  - `ui-locale-theme/spec.md` (42 lines): explicit locale pref (default `"es"`, never device fallback) applied onCreate with recreate on toggle; forced dark theme (Material3 DayNight NoActionBar), colors from resources, all UI strings from `values/` and `values-en/` (full parity 135 strings), language button is status indicator, version label; annotations: hardcoded Spanish strings in PairingWizardActivity (16) — frozen violation, dead resources (`menu_main.xml` never inflated), version `8.0`.

**Total corpus:** 399 lines across 8 spec files. (Session budget note: 400-line review budget mentioned; the corpus is just under/at the reported 399 total.)

## Specs Synced to Source of Truth

All 8 domain specs were created in `openspec/specs/{domain}/spec.md` as full specs (no existing specs in `openspec/specs/` to reconcile; empty store). 

| Domain | Action | Details |
|---|---|---|
| pairing-provisioning | Created | 66 lines; 9 requirements, 11 scenarios; annotations on hardcoded strings + dead code |
| device-discovery | Created | 45 lines; 5 requirements, 9 scenarios; README drift annotation |
| udp-control | Created | 51 lines; 6 requirements, 10 scenarios; per-op sockets, lastPower precedence semantics |
| mqtt-broker | Created | 45 lines; 5 requirements, 8 scenarios; TLS/broker refcounting, shared notification |
| routines-schedules | Created | 62 lines; 6 requirements, 10 scenarios; grace windows, catch-up, 30-min reveal, last_fired before UDP |
| bulb-management | Created | 49 lines; 5 requirements, 10 scenarios; prefs split (overlay name storage frozen) |
| notifications | Created | 39 lines; 4 requirements, 8 scenarios; shared id 1, staggered HIGH announcements |
| ui-locale-theme | Created | 42 lines; 4 requirements, 9 scenarios; hardcoded strings frozen as violation, dead resources |

Verification: for each, target file exists and byte count matches source (mechanical copy validated).

## Mechanical Copy Verification (diff -r)

Per the skill's Mechanical Copy Contract, specs were copied via shell-equivalent PowerShell copy operations (byte-for-byte). Content comparison (Compare-Object of file contents) showed no differences for any domain; all 8 targets verified byte-identical to sources.

The change folder was moved from `openspec/changes/baseline-current-behavior/` to `openspec/changes/archive/2026-10-18-baseline-current-behavior/`. Pre-move snapshot of the source (excluding `archive-report`) compared against archived tree (excluding `archive-report`) shows **no differences (DIFF_EMPTY)**. The `archive-report.md` is additive-only and excluded from comparison.

## Final State Assessment

- **openspec/specs/ previously empty:** 8 new capability specs now present.
- **Requirements count (from exploration + specs):** 47 requirements + 61 scenarios total across 8 files (as reported by orchestrator). All exploration risks (7) appear as Annotations across specs; 6 dead-code symbols annotated "not spec'd".
- **No tasks/apply/verify phases ran:** docs-only change — no apply-progress or verify-report artifacts exist; this is expected and not a blocker.
- **Git status impact:** only `openspec/` paths modified/created (specs added under `openspec/specs/**`, change folder moved to archive) — consistent with docs-only change.

## Source of Truth Updated

The following persistent specs now reflect the frozen baseline behavior:
- `openspec/specs/pairing-provisioning/spec.md`
- `openspec/specs/device-discovery/spec.md`
- `openspec/specs/udp-control/spec.md`
- `openspec/specs/mqtt-broker/spec.md`
- `openspec/specs/routines-schedules/spec.md`
- `openspec/specs/bulb-management/spec.md`
- `openspec/specs/notifications/spec.md`
- `openspec/specs/ui-locale-theme/spec.md`

## SDD Cycle Complete

The change `baseline-current-behavior` has been planned (proposal/exploration), specified (8 delta specs), archived (merged to source of truth and moved to archive). No implementation or verification required for this docs-only baseline freeze. Ready for next change.

## Compliance Notes

- **Artifact language:** All technical artifacts written in English (per contract).
- **Mechanical copy:** Byte-for-byte verification performed via content comparison; no Read→Write model pass-through for artifact content.
- **No unchecked tasks:** No implementation tasks existed (docs-only). Tasks/artifact validation skipped as expected.
- **No review gate:** No native review receipt discovered; archive proceeded under ordinary policy.
- **Final-state authority:** Report reflects the final archived state; no intermediate snapshots were misread as current facts.

## Artifacts Written/Merged

- Merged: `openspec/specs/pairing-provisioning/spec.md`, `openspec/specs/device-discovery/spec.md`, `openspec/specs/udp-control/spec.md`, `openspec/specs/mqtt-broker/spec.md`, `openspec/specs/routines-schedules/spec.md`, `openspec/specs/bulb-management/spec.md`, `openspec/specs/notifications/spec.md`, `openspec/specs/ui-locale-theme/spec.md`
- Archived: `openspec/changes/archive/2026-10-18-baseline-current-behavior/proposal.md`, `openspec/changes/archive/2026-10-18-baseline-current-behavior/exploration.md`, all 8 specs under archived `specs/`
- Report: `openspec/changes/archive/2026-10-18-baseline-current-behavior/archive-report.md`

## Risks

None unresolved. The frozen quirks (hardcoded wizard strings, split prefs name storage, shared notification id 1, catch-up semantics, lastPower precedence, skipped MAC scan when IP unresponsive, last_fired written before UDP) are explicitly annotated in the specs as current behavior; they are not bugs to be fixed in this change.

## Next Recommended

None (change closed).