# Design: Add IP + MAC Editing with Input Masks

## Technical Approach

Replace `showEditIpDialog` with a two-field (IP + MAC) dialog per the `RoutineDialog` precedent (object + XML layout + binding). New Android-free `InputMasks` (pure mask/validation) is shared by dialog and `BulbRegistry.updateBulbMac`; `Listener.onEditIp` → `onEditConnection(bulb, newIp, newMac)` → `MainActivity` (both writes, card refresh). Covers all 10 delta-spec scenarios.

## Architecture Decisions

### Decision 1: Dialog surface

**Choice**: `dialog_edit_connection.xml` (2 × `TextInputLayout`, OutlinedBox) inflated by new `object EditConnectionDialog` with `show(context, bulb, onSaved)`.
**Alternatives rejected**: programmatic in adapter; LinearLayout of TILs.
**Rationale**: mirrors `RoutineDialog.kt:16-23`; TIL gives labels/errors free; AGENTS.md mandates layouts + binding + Material. Inline stays only for trivial single fields.

### Decision 2: Mask — rebuild-from-raw, re-entry guarded

Pure functions in new `InputMasks.kt`, flat package (no `util/`; `ScheduleManager`/`BulbRegistry` precedent). Watchers stay in `EditConnectionDialog` (keeps `InputMasks` Android-free).

| Concern | Mechanism |
|---|---|
| Guard | `guard` flag; programmatic `setText`/`setSelection` only while true (like `updating`, `BulbAdapter.kt:37,80`) |
| Filter | `ipRaw`: digits, cap 12; `macRaw`: strip `:`/`-`/`.`/space, hex only, cap 12, uppercase — raw caps absorb paste/prefix/oversize |
| Rebuild | `formatIp`: 1–3-digit octets joined by `.`; `formatMac`: hex pairs joined by `:`; masked max 15/17 from raw caps |
| Cursor | old cursor → raw index (non-sep chars before) → display index (+ preceding seps, clamped) |
| Backspace | `onTextChanged(s, start, before=1, count=0)` deleting a separator also drops the raw digit before it — digit+separator go together; cursor never strands after a re-inserted `.` |

Input types: IP `TYPE_CLASS_PHONE`, MAC `TYPE_CLASS_TEXT` (manual-add precedent, `MainActivity.kt:251-258`).

### Decision 3: Validation — pure contracts, save-time only

- `isValidIpv4(s)`: trim; split `.`; 4 parts; each 1–3 digits; `toIntOrNull()` in 0..255.
- `normalizeMacToCanonical(s)`: strip separators/whitespace; exactly 12 hex chars → `XX:XX:XX:XX:XX:XX` uppercase; anything else (including blank/short input) → `null`.

Save-time only (spec). Override the positive button after `show()` (per `showAddManualDialog`, `MainActivity.kt:277-302`): failure → `tilIp.error`/`tilMac.error`, dialog stays open, zero writes; empty MAC skips the check (clear path in `updateBulbMac`, below); errors clear on edit.

### Decision 4: `updateBulbMac` — empty clears first, mac key only

```kotlin
fun updateBulbMac(context: Context, bulbId: String, newMac: String) {
    if (loadPaired(context).none { it.id == bulbId }) return   // unknown id: no-op (mirrors updateBulbIp)
    val editor = prefs(context).edit()
    if (newMac.isBlank()) {                                    // spec: empty MAC clears mac_<id>
        editor.remove("mac_$bulbId").apply()
        return
    }
    val canonical = InputMasks.normalizeMacToCanonical(newMac) ?: return  // defensive: non-canonicalizable input is a no-op
    editor.putString("mac_$bulbId", canonical).apply()
}
```

- **Branch order is load-bearing**: `isBlank()` before canonicalization because `normalizeMacToCanonical("")` returns `null` (0 hex chars, not 12) — canonicalizing first would swallow the spec-required empty-clear (`MAC vacía limpia mac_<id>`, spec `:77-81`).
- Writes only `mac_<id>` — never `bulbs_json`/`bulb.id` (id derives once at pairing, `PairingWizardActivity.kt:645`; `name_<id>`, `routine_*_<id>`, `last_*_<id>` stay stable). Canonicalization choke point.
- Empty → `editor.remove("mac_$bulbId")`, not `putString("")`: `getMac` defaults missing → `""` (`BulbRegistry.kt:71-74`); remove() leaves no dead keys. Dialog passes trimmed field text, so a cleared MAC arrives as `""`.

### Decision 5: Callback — single `onEditConnection`

Rename `onEditIp` → `onEditConnection(bulb, newIp, newMac)`; one gesture, one callback — two would allow partial saves.

```kotlin
override fun onEditConnection(bulb: Bulb, newIp: String, newMac: String) {
    BulbRegistry.updateBulbIp(this, bulb.id, newIp)
    BulbRegistry.updateBulbMac(this, bulb.id, newMac)
    val latest = bulbs.firstOrNull { it.id == bulb.id } ?: bulb
    updateBulb(latest.copy(ip = newIp))
}
```

`apply()` never throws — no error path. `updateBulb(latest.copy(ip = newIp))` refreshes the card so connectivity uses the new IP (spec). MAC is not part of `Bulb`; card display unchanged (non-goal).

### Decision 6: Strings (both locales)

| Key | `values/` (ES) | `values-en/` (EN) |
|---|---|---|
| `edit_connection_title` | Editar conexión del foco | Edit bulb connection |
| `edit_connection_label_ip` | Dirección IP | IP address |
| `edit_connection_label_mac` | Dirección MAC | MAC address |
| `edit_connection_error_ip` | Ingresá una IP válida (4 octetos entre 0 y 255) | Enter a valid IP address (4 octets, 0–255) |
| `edit_connection_error_mac` | Ingresá 6 pares hexadecimales o dejá vacío | Enter 6 hex byte pairs, or leave empty |
| `content_desc_edit_connection` | Editar IP y MAC del foco | Edit bulb IP and MAC |

Removed: `edit_ip_title`, `edit_ip_save`, `edit_ip_hint`, `content_desc_edit_ip`. The old key's only remaining reference (`btnEditIp` contentDescription, `item_bulb.xml:84`) is repointed to `content_desc_edit_connection` in the same change, so no dangling resource reference remains. Save reuses existing `rename_save` ("Guardar"/"Save").

### Decision 7: Prefill & flow

Prefill `bulb.ip` and `formatMac(macRaw(BulbRegistry.getMac(context, bulb.id)))` — canonical display even for legacy values (normalization by construction; stored data untouched). Cancel: negative button, nothing written. Save: both pass → dismiss + callback.

## Data Flow

    btnEditIp → EditConnectionDialog { IP+MAC fields, live masks }
        Save ─ fail → til*.error, zero writes
              └ pass → MainActivity: updateBulbIp + updateBulbMac
                        → adapter.updateBulb(copy(ip))

## File Changes

All paths under `SengledApp/app/src/main/`.

| File | Action | Description |
|---|---|---|
| `java/.../control/InputMasks.kt` | Create | Pure mask + validation functions |
| `java/.../control/EditConnectionDialog.kt` | Create | Two-field dialog object |
| `res/layout/dialog_edit_connection.xml` | Create | 2 × TextInputLayout + TextInputEditText |
| `java/.../control/BulbAdapter.kt` | Modify | Listener rename; delete `showEditIpDialog`; wire button |
| `java/.../control/BulbRegistry.kt` | Modify | Add `updateBulbMac` |
| `java/.../control/MainActivity.kt` | Modify | `onEditConnection` (both writes) |
| `res/layout/item_bulb.xml` | Modify | `btnEditIp` contentDescription → `content_desc_edit_connection` (`:84`) |
| `res/values{,-en}/strings.xml` | Modify | Table above |

## Interfaces / Contracts

```kotlin
object InputMasks {
    fun ipRaw(text: String): String;   fun formatIp(raw: String): String
    fun macRaw(text: String): String;  fun formatMac(raw: String): String
    fun displayCursor(rawIndex: Int, masked: String, separator: Char): Int
    fun isValidIpv4(s: String): Boolean;   fun normalizeMacToCanonical(s: String): String?
}
```

## Testing Strategy

No unit framework (NO-SOURCE); gates `assembleDebug` + `lint`. Manual: auto `.`/`:`; coordinated backspace; paste `192.168.001.1`/`aabb.ccdd.eeff`; invalid input blocks save (visible error, zero writes); empty MAC removes `mac_<id>`; mixed-case stored uppercase; legacy lowercase still matches (`WifiDetector.kt:191,218,258`); `*_<id>` keys survive; card uses new IP; card edit-button content description resolves in both locales (lint flags any dangling `content_desc_*` reference).

## Threat Matrix

N/A — no routing, shell, subprocess, VCS/PR automation, executable-file classification, or process integration.

## Migration / Rollout

No migration. Additive persistence only; rollback: revert feature commits.

## Open Questions

None.
