# Routines and Schedules Specification

## Purpose

Baseline (current behavior) of routine persistence, firing, and `RoutineDialog`.

## Requirements

### Requirement: One Routine Per Bulb With Fixed Defaults

The system SHALL support exactly ONE routine per bulb: `enabled`, `onMinutes`/`offMinutes` (minutes-since-midnight), `brightness` (1..100). Defaults ON 19:30, OFF 00:00, brightness 1; persisted in `sengled_prefs` as `routine_{enabled,on,off,brightness}_<id>`.

#### Scenario: New bulb

- GIVEN no routine stored
- WHEN its dialog opens
- THEN defaults are ON 19:30, OFF 00:00, brightness 1

### Requirement: Routine Editor UI

The dialog SHALL use AM/PM pickers with an always-visible AM/PM toggle (`applyPeriod` preserves hour/minutes), a brightness SeekBar, and an enable switch. Saving SHALL call `ScheduleManager.save` and re-`startService`.

#### Scenario: Flip AM to PM

- GIVEN 19:30 set
- WHEN the period toggles
- THEN hour/minutes stay 7:30 and only the period changes

### Requirement: Single-Owner Firing With Grace Windows

`RoutineService` SHALL own all firing: a `HandlerThread` runs `tick()`, firing due ON/OFF with grace +90 s forward, −10 min backward, idempotent via epoch-minute `last_fired_<bulb>_<on|off>` markers. ON SHALL fire `setBrightness` then `setSwitch(true)` (both must succeed to notify); OFF SHALL fire `setSwitch(false)`.

#### Scenario: Due within grace

- GIVEN ON time 12:00, tick at 12:00:30, marker unset
- WHEN `tick()` evaluates it
- THEN ON fires; past −10 min grace it is skipped

### Requirement: Failure Marker Written Before UDP

`fireActionIfDue` SHALL write the `last_fired` marker BEFORE attempting UDP, so a failed command is consumed and never retried by `tick()` or catch-up.

#### Scenario: UDP command fails

- GIVEN ON fired but the bulb did not respond
- WHEN the marker was already written
- THEN neither `tick()` nor catch-up retries it

### Requirement: Exact-Alarm Wake Kick

`tick()` SHALL sleep until the next event (or `next − 30 min`) and schedule an exact-alarm kick (`setExactAndAllowWhileIdle` → `setExact` → silently none) to `ACTION_ROUTINE_CHECK`, which merely restarts the service.

#### Scenario: Alarm fires

- GIVEN a kick is scheduled
- WHEN the alarm fires
- THEN the service restarts; no command is sent

### Requirement: 30-Minute Reveal for Next Event

`tick()` SHALL post the next tick at `next − 30 min` (`NOTIF_AHEAD_MS`). The persistent status SHALL show "Próxima a las {time}" only within the last 30 min before the next event, else generic text.

#### Scenario: Event within 30 minutes

- GIVEN the next event is 25 minutes away
- WHEN `updateStatusNotification` runs
- THEN the time shows; at 45 minutes the generic text shows

### Requirement: Silent Catch-Up Without Backward Grace

On default-network availability the system SHALL debounce 2 s, then `runCatchUp()` → `recoverTransitions()`: apply any passed transition with an unset `last_fired` marker, using `tick()` markers. Catch-up SHALL NOT force an expected state, SHALL post NO notification, and SHALL have NO backward grace — an earlier-same-day miss still recovers.

#### Scenario: Reconnect after a missed ON

- GIVEN network returns at 20:00, ON 19:30 unmarked
- WHEN catch-up runs 2 s after availability
- THEN ON fires once silently; a set marker sends nothing

### Requirement: Routines Do Not Write Last Power

Routine ON/OFF SHALL NOT write `last_power`; only user gestures in `MainActivity` do; the next refresh still shows the remembered user state.

#### Scenario: Routine turns bulb on

- GIVEN remembered state is off
- WHEN the routine fires ON
- THEN the next refresh still displays off (remembered state wins)

### Requirement: Service Lifecycle

`RoutineService` SHALL be `START_STICKY` and SHALL self-stop (cancel kick, clear handler, `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf`) when no enabled routine remains or on `ACTION_STOP`. It SHALL start from `MainActivity.onCreate` and on boot.

#### Scenario: Last routine disabled

- GIVEN the only routine is disabled
- WHEN `ScheduleManager.save` restarts the service
- THEN it finds none enabled and stops itself

## Annotations

- **Dead code — not spec'd:** `hasEnabledRoutine` uncalled, `getLastBrightness` write-only, `buildRoutineSubtitle` uncalled.
- **Frozen quirks:** marker-before-UDP and no-backward-grace catch-up are intentional (reconciliation removed to avoid reconnect flicker).
- **Prefs split:** routines/`last_power`/`last_fired` in `sengled_prefs`; bulbs in `sengled_bulbs`.
