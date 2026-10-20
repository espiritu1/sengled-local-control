# SDD Archive Report: add-ip-mac-editing

**Change name:** add-ip-mac-editing
**Artifact store:** openspec (files only)
**Date archived:** 2026-10-19
**Execution mode:** interactive (final phase)
**Phase:** sdd-archive

## Executive Summary

The `add-ip-mac-editing` change has been archived. The delta spec was merged into the
persistent spec store at `openspec/specs/bulb-management/spec.md`, and the
`pairing-provisioning` spec was updated directly with the verified pairing-identity
behavior discovered during this change. The change folder was moved to
`openspec/changes/archive/2026-10-19-add-ip-mac-editing/`.

Two scope expansions happened during apply and are recorded in `tasks.md` as Phase 7 and
Phase 8; both are delivered and verified on device. The change is now closed.

## Delivered Scope

### Core feature (planned)

- Two-field (IP + MAC) connection editor for any paired bulb, opened from the bulb card.
- Input masks: auto `.` after each 1–3-digit IP octet, auto `:` every 2 hex digits.
- Validation on save: IPv4 = 4 octets 0–255; MAC (if non-empty) = 6 hex byte-pairs.
  Invalid input blocks save with a visible error and persists nothing.
- Empty MAC clears `mac_<id>` (prevents a wrong MAC from false-matching another device).
- Canonical MAC writes: `XX:XX:XX:XX:XX:XX` uppercase. `bulb.id` never changes.
- `BulbRegistry.updateBulbMac(context, bulbId, newMac)` writes only `mac_<id>`.

### Phase 7 — Add-manual dialog parity (discovered during apply)

- `MainActivity.showAddManualDialog()` rebuilt as `ScrollView > LinearLayout >`
  (help `TextView` + name/IP/MAC fields) so the soft keyboard cannot hide the fields.
- The IP and MAC fields use the shared `InputMasks.attachMask`; the MAC field's
  `InputFilter.LengthFilter(17)` was removed because the mask's `take(12)` cap is the
  real limit and the filter interfered with deletion.
- `attachMask` was hoisted into `InputMasks` as the single implementation;
  `EditConnectionDialog` delegates to it.

### Phase 8 — Pairing MAC identity fix (promoted from Out of Scope)

Root cause of a live defect: the pairing handshake response carries no `mac` field, so
the code fell back to the connected AP BSSID. That BSSID is a different interface than
the bulb's LAN MAC — on real hardware `80:a0:36:b1:93:74` (BSSID) vs
`80:a0:36:b1:93:73` (LAN MAC reported by `search_devices`), differing only in the last
byte. Result: the stored entry had a wrong MAC (and therefore a wrong id) and the bulb
IP was never found.

- `WifiDetector.findBulbByMac` matches on the first 5 bytes (OUI + device prefix) and
  returns `DiscoveryResult(ip, mac)` with the bulb's real reported MAC. Added
  `normalizeMac`; `extractMac` reuses it and caps at 12 chars.
- `PairingWizardActivity` adopts the discovered real MAC before deriving id/name and
  persisting.
- `MainActivity` adapted to the `DiscoveryResult` return type.
- `PairingProtocol.handshake` documents that the AP BSSID fallback is not necessarily
  the bulb's LAN MAC.

## Specs Synced to Source of Truth

| Domain | Action | Details |
|---|---|---|
| `bulb-management` | Merged (delta applied) | `Almacenamiento`: canonical MAC write scenario added. `Semántica de la API`: `updateBulbMac` + id-stability scenarios added. `Operaciones de lista`: "editar IP" replaced by "editar conexión" (IP+MAC) with mask/validation/empty-MAC scenarios. |
| `pairing-provisioning` | Updated directly | `Alternativa de MAC`: BSSID is provisional, not the LAN MAC; new "BSSID differs from LAN MAC" scenario. `Detección posterior a credenciales`: match by first 5 bytes; new prefix-match scenario. `Alta manual`: mask + scroll requirement with mask scenario. Annotations record the two-interface identity finding. |

## Final State Assessment

- **All planned tasks complete** (Phase 6 verified; Phases 7 and 8 delivered).
- **Device verification:** pairing stored `id=b19373`, `mac=80:A0:36:B1:93:73`,
  `ip=192.168.168.121` for one bulb and `id=b15cf0`, `mac=80:A0:36:B1:5C:F0`,
  `ip=192.168.168.106` for the other — both matching the router ARP entries exactly.
- **Build:** `assembleDebug` green after every change; Lint gate per config.
- **Git status impact:** delivered as five conventional commits (spec translation,
  feature, pairing MAC fix, keyboard/scroll fix, SDD docs).

## Compliance Notes

- **Artifact language:** All technical artifacts in English per contract (spec bodies in
  the project's established Spanish).
- **No review gate:** No native review receipt discovered; archive proceeded under
  ordinary repository policy.
- **No unchecked tasks:** `tasks.md` records every phase checked off.
- **Final-state authority:** This report reflects the final state at close; it also
  records the two post-verification scope expansions explicitly rather than leaving
  stale snapshot claims.

## Artifacts Written/Merged

- Merged: `openspec/specs/bulb-management/spec.md`
- Updated: `openspec/specs/pairing-provisioning/spec.md`
- Archived: `openspec/changes/archive/2026-10-19-add-ip-mac-editing/proposal.md`,
  `.../design.md`, `.../tasks.md`, `.../specs/bulb-management/spec.md`
- Report: `openspec/changes/archive/2026-10-19-add-ip-mac-editing/archive-report.md`

## Risks

- **Prefix matching residual risk (accepted):** matching on the first 5 bytes can
  confuse two sibling bulbs that share the same 5-byte prefix and differ only in the
  last byte. The two bulbs in use differ in the 5th byte (`93` vs `5C`), so this is not
  hit in practice. Optional hardening (first 5 bytes + last byte within ±1 of the
  handshake BSSID) is documented but not applied.

## Next Recommended

None (change closed).
