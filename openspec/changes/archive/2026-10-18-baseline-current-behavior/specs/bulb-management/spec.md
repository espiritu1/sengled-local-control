# Bulb Management Specification

## Purpose

Baseline (current behavior) of `BulbRegistry`, the `Bulb` model, and the list operations in `MainActivity`/`BulbAdapter`. Regression reference only.

## Requirements

### Requirement: SharedPreferences-Only Storage

Bulbs SHALL live only in SharedPreferences file `sengled_bulbs`: a JSON array under `bulbs_json` (`id`, `name`, `ip` per entry) plus MACs as separate `mac_<id>` keys in the same file. There SHALL be no hardcoded defaults; `loadPaired` SHALL return `emptyList()` on any parse error.

#### Scenario: Corrupt JSON

- GIVEN `bulbs_json` fails to parse
- WHEN `loadPaired` runs
- THEN an empty list is returned and the app starts with no bulbs

#### Scenario: MAC round-trip

- GIVEN a bulb was stored with a MAC
- WHEN `getMac(id)` is called
- THEN the stored MAC is returned

### Requirement: Registry API Semantics

`BulbRegistry` SHALL expose `getBulbs`, `addBulb` (upsert by id), `updateBulbIp`, `removeBulb`, `reorderBulbs` (caller passes the full order; unknown ids are dropped and unlisted bulbs are kept at the end — defensive), and `getMac`.

#### Scenario: Reorder with unknown id

- GIVEN the requested order contains an id not in storage
- WHEN `reorderBulbs` runs
- THEN the unknown id is dropped and existing unlisted bulbs are appended at the end

### Requirement: Rename Overlay Wins Over Registry

Renames SHALL NOT be written to `BulbRegistry`; they SHALL go to `sengled_prefs` as `name_<id>` (overlay). `MainActivity` SHALL overlay it on load, and `ScheduleManager.getBulbName` SHALL prefer the overlay. The registry's `name` SHALL stay at its original value forever.

#### Scenario: Rename a paired bulb

- GIVEN a bulb named "Foco 1a2b3c" in the registry
- WHEN the user renames it to "Escritorio"
- THEN display uses "Escritorio" from `sengled_prefs` while `bulbs_json` still says "Foco 1a2b3c"

#### Scenario: Overlay vs registry precedence

- GIVEN both stores hold a name for the same id
- WHEN the name is displayed anywhere
- THEN the `sengled_prefs` overlay wins

### Requirement: List Operations

The list SHALL support rename (max 40 chars), edit IP, delete (confirm dialog; also resets the routine via `ScheduleManager.save(..., false, 0, 0, 1)`), drag-and-drop reorder (long-press starts drag; on drop `reorderBulbs` persists and the in-memory `bulbs` list is reordered to match; while dragging the card is highlighted `card_background_active`, elevated, and its slider/switch are disabled), and the schedule editor.

#### Scenario: Delete a bulb with a routine

- GIVEN a bulb with an enabled routine
- WHEN the user confirms deletion
- THEN the bulb is removed and its routine is reset to disabled defaults

#### Scenario: Drag in progress

- GIVEN a card is being dragged
- WHEN the user holds it mid-drag
- THEN that card's slider and switch are disabled and the card is highlighted

### Requirement: Resume Synchronization

`onResume` SHALL run `reloadBulbs()` to synchronize the adapter with the registry (add newly added bulbs, remove deleted ones), covering bulbs added by the wizard.

#### Scenario: Bulb added by wizard

- GIVEN a bulb was saved by the pairing wizard while `MainActivity` was paused
- WHEN `MainActivity` resumes
- THEN the new bulb appears in the list without a manual refresh

## Annotations

- **Duplicate prefs (frozen):** two stores for names — `sengled_bulbs` (registry, stale after rename) vs `sengled_prefs` `name_<id>` (overlay, authoritative). Spec states overlay wins; unification is a deferred fix candidate (proposal mitigation).
- **README drift:** README claims IPs are "defined in BulbRegistry.kt"; runtime truth is SharedPreferences. Code is truth.
