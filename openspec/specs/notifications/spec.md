# Notifications Specification

## Purpose

Baseline (current behavior) of all notifications: the shared persistent one (`MqttBrokerService`, `RoutineService`) and routine event announcements (`RoutineService` channels, `MainActivity` permission guard). Regression reference only.

## Requirements

### Requirement: Single Shared Persistent Notification With Id 1

The system SHALL show exactly one ongoing notification on channel `rutinas` ("Rutinas", IMPORTANCE_DEFAULT) with notification id 1, shared by `RoutineService` and `MqttBrokerService`; whichever service calls `startForeground`/`notify(1, …)` last SHALL win the content. The notification SHALL be `setOngoing(true)` and `setOnlyAlertOnce(true)`.

#### Scenario: Broker starts after routines

- GIVEN `RoutineService` posted id 1
- WHEN `MqttBrokerService` posts id 1
- THEN the bar shows the broker text and only one ongoing notification exists

#### Scenario: Routine status refresh

- GIVEN the broker posted id 1
- WHEN `RoutineService` refreshes its status notification
- THEN the bar content switches back to the routine text

### Requirement: Persistent Notification Content Per Owner

`RoutineService`'s persistent content SHALL be generic text or the next-event time (30-minute reveal). `MqttBrokerService`'s content SHALL state that the local broker is active.

#### Scenario: Next event within 30 minutes

- GIVEN a routine event is 10 minutes away
- WHEN the status notification updates
- THEN it shows "Próxima a las {time}"

### Requirement: Temporary Event Announcements on a Separate Channel

Routine events SHALL post auto-cancel announcements on channel `rutinas_aviso` ("Avisos de rutina", IMPORTANCE_HIGH) with ids from `1000 + counter`, the counter persisted in `event_notification_id` and incremented per event (each event gets a distinct id), `DEFAULT_ALL` sound, staggered 400 ms per bulb, and SHALL be posted only when the UDP command succeeded.

#### Scenario: Two bulbs fire ON

- GIVEN two routines fire in the same tick
- WHEN both UDP commands succeed
- THEN two distinct notifications post (distinct incremented ids) 400 ms apart

#### Scenario: UDP failure suppresses the announcement

- GIVEN the ON command failed
- WHEN firing completes
- THEN no event announcement is posted

### Requirement: Notification Permission Guard

When app notifications are disabled, `MainActivity` SHALL show an indefinite Snackbar whose action opens app notification settings, with a fallback to app-details settings.

#### Scenario: Notifications disabled

- GIVEN the app's notifications are blocked
- WHEN `MainActivity` runs diagnostics
- THEN an indefinite Snackbar appears with a settings action

## Annotations

- **Shared id 1 (frozen, fragile):** two foreground services deliberately share channel `rutinas` and id 1, so content is last-writer-wins and the phone shows a single bar (see `MqttBrokerService` doc comment). A future third foreground service would inherit this order-dependent behavior (exploration risk, not fixed here).
- **No test suite:** `testDebugUnitTest` runs NO-SOURCE; scenarios are review-verified only.
