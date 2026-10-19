# UI Locale and Theme Specification

## Purpose

Baseline (current behavior) of locale handling, the forced-dark theme, string resources, and the main-screen elements in `MainActivity` and layouts. Regression reference only.

## Requirements

### Requirement: Explicit Locale With Spanish Default

Spanish SHALL be the app default. The `lang` pref in `sengled_prefs` SHALL always be explicit (`"es"` or `"en"`, never device-locale fallback). `MainActivity.onCreate` SHALL apply it via `Locale.setDefault` plus the deprecated `resources.updateConfiguration`.

#### Scenario: First launch

- GIVEN no `lang` pref exists
- WHEN `MainActivity` starts
- THEN the app runs in Spanish (default)

#### Scenario: English selected

- GIVEN `lang` = `"en"`
- WHEN `MainActivity` starts
- THEN all UI strings resolve from `values-en`

### Requirement: Language Button as Status Indicator

The language button SHALL show the CURRENT language ("ES"/"EN"), not the target language; toggling SHALL write the pref and call `recreate()`.

#### Scenario: Toggle while in Spanish

- GIVEN the app is in Spanish
- WHEN the user taps the language button
- THEN the button showed "ES", the pref becomes `"en"`, and the activity recreates in English

### Requirement: Forced-Dark Theme

`Theme.Sengled` SHALL be `Theme.Material3.DayNight.NoActionBar` with forced dark surfaces: `background_dark` #121212 (status bar and window background), cards #1E1E1E, drag-highlight #3A3A3A, amber primary #FFC107, status green #4CAF50, status red #FF5252. Layouts SHALL contain no hardcoded `android:text` (all `@string` or empty).

#### Scenario: Any screen renders

- GIVEN the app launches on any device
- WHEN a layout inflates
- THEN surfaces use the fixed dark palette regardless of system dark mode

### Requirement: String Resource Parity

`res/values/strings.xml` (Spanish) and `res/values-en/strings.xml` (English) SHALL each contain 135 strings in full parity.

#### Scenario: Resource audit

- GIVEN both string files
- WHEN they are compared
- THEN every key exists in both with the same count

### Requirement: Main Screen Elements

The main screen SHALL provide: a refresh button, an add-bulb button (chooser dialog: pair via wizard or manual add), an info dialog (inflated from `dialog_info_sections` + `dialog_info` inside a ScrollView with autoLink for URLs), the language button, a version label `v{versionName}` (versionName "8.0"), and the notification-disabled Snackbar.

#### Scenario: Open info dialog

- GIVEN the main screen
- WHEN the user opens info
- THEN the sections dialog appears scrollable with URLs tappable

## Annotations

- **Hardcoded Spanish strings (frozen, NOT fixed):** `PairingWizardActivity` contains 16 user-facing hardcoded Spanish strings bypassing resources — 3 field errors ("Ingresá el nombre de la red", "Ingresá la contraseña", "Ingresá un nombre para el foco"), 12 pairing-log lines, and the success Toast "¡Foco '…' agregado!". This VIOLATES the project rule requiring `values/` + `values-en/` strings; recorded as existing behavior per the proposal's risk table, fix deferred to a later change. Details in `pairing-provisioning`.
- **Dead resources/code — not spec'd:** `menu_main.xml` is never inflated (no `onCreateOptionsMenu`); `MainActivity.buildRoutineSubtitle()` has no callers (also noted in `routines-schedules`). Excluded from requirements.
- **No test suite:** `testDebugUnitTest` runs NO-SOURCE; baseline is review-verified only.
