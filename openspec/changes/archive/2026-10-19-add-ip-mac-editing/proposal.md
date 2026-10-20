# Proposal: Add IP + MAC Editing with Input Masks

## Intent

The config dialog (`showEditIpDialog`, `BulbAdapter.kt:143-162`) edits only the IP, no validation, no separators. A wrong stored MAC is uncorrectable and deadlocks rediscovery — verified: `mac_b19374=80:A0:36:B1:93:74` (AP/BSSID) vs hardware `...:73`; `findBulbByMac` never matches. Both fields must be correctable any time after pairing.

## Scope

### In Scope

- Two-field dialog (IP + MAC), prefilled, editable any time after pairing.
- Masks: auto `.` after each 1–3-digit octet; auto `:` every 2 hex digits.
- Validation: IPv4 = 4 octets 0–255; MAC (if non-empty) = 6 hex byte-pairs; invalid input blocks save with visible error.
- Empty MAC allowed (matches manual-add): clears `mac_<id>` — a wrong MAC false-matches other devices.
- New `BulbRegistry.updateBulbMac(context, bulbId, newMac)` writes `mac_<id>` only; **`bulb.id` must NOT change** — id derives from MAC once at pairing (`PairingWizardActivity.kt:645`) and keys all `*_ <id>` state (`name_`, `routine_*`, `last_power_`, …).
- Canonical `XX:XX:XX:XX:XX:XX` uppercase: pairing-wizard format, matches router/DHCP notation. No migration — `WifiDetector` normalizes both sides (`:191,218,258`); new writes only.
- Callback: `BulbAdapter.Listener.onEditIp` → `onEditConnection(bulb, newIp, newMac)` → `MainActivity` → both registry calls.
- Strings in `res/values/strings.xml` + `res/values-en/strings.xml` (new labels/errors; reword `edit_ip_*`).

### Out of Scope

- Pairing-time BSSID-vs-LAN-MAC fix (follow-up change); router IP reservation.
- Re-deriving `bulb.id` (forbidden — orphans `*_ <id>` keys); orphan `mac_<id>` cleanup (pre-existing, deferred).
- Card display of IP/MAC; routine/schedule logic; `SengledTools/`.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `bulb-management`: MODIFY `Requisito: Operaciones de lista (editar IP)` (spec.md:65) — IP+MAC with mask/validation scenarios; MODIFY `Requisito: Semántica de la API de BulbRegistry` (spec.md:29) — add `updateBulbMac`; ADD canonical-write scenario under `Requisito: Almacenamiento exclusivamente en SharedPreferences` (spec.md:13). Deltas in the spec phase.

## Approach

One dialog, two `EditText`s; each gets a re-entry-guarded `TextWatcher` rebuilding masked text from a raw model, restoring the cursor. Registry gains one function mirroring `updateBulbIp`, canonicalizing at the single write choke point. Validators as pure functions (unit-testable once JUnit lands — absent per `openspec/config.yaml`; gates today: `assembleDebug` + Lint). Diff: one dialog, one registry function, two string files, one spec delta.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `BulbAdapter.kt` | Modified | Dialog, masks, validation, Listener |
| `BulbRegistry.kt` | Modified | `updateBulbMac` |
| `MainActivity.kt` | Modified | `onEditConnection` |
| `res/layout/` edit dialog | Added | Two-field layout |
| `res/values{,-en}/strings.xml` | Modified | Strings |
| `specs/bulb-management/spec.md` (change) | Added | Delta spec |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Mask watcher breaks on backspace/paste/cursor | Med | Re-entry flag; rebuild-from-raw; manual checks (no suite) |
| MAC edit changes `bulb.id` | Low | Writes only `mac_<id>`; id never recomputed |
| Legacy lowercase MACs stop matching | Low | Matching already normalizes both sides |

## Rollback Plan

Revert feature commit(s): dialog/Listener IP-only again; delete `updateBulbMac`. Additive-only persistence; no migration to undo.

## Dependencies

None external. Unit tests gated on adding `testImplementation junit` (optional).

## Success Criteria

- [ ] IP/MAC editable on any paired bulb; separators auto-insert.
- [ ] Invalid input cannot be saved; valid input persists via both registry calls.
- [ ] Corrected MAC restores rediscovery; `bulb.id` and `*_ <id>` keys unchanged.
- [ ] Legacy lowercase MACs still match; `assembleDebug` + Lint green; strings in both locales.

## Proposal question round (assumptions for user review)

1. Canonical `XX:XX:XX:XX:XX:XX` uppercase? — assumed yes.
2. Empty MAC allowed (unknown, no false matches)? — assumed yes; alt: require valid.
3. Card display unchanged? — assumed yes.
