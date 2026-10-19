# MQTT Broker Specification

## Purpose

Baseline (current behavior) of the hand-rolled embedded broker (`MqttBroker`, `MqttBrokerService`, `SslCertGenerator`) and its foreground-service lifecycle. Regression reference only.

## Requirements

### Requirement: MQTT 3.1.1 Subset Broker

The broker SHALL implement, without any library: CONNECT→CONNACK, SUBSCRIBE→SUBACK (granting QoS 0 for every topic), PUBLISH→PUBACK for QoS 1 (payload logged only), PINGREQ→PINGRESP, UNSUBSCRIBE→UNSUBACK, and DISCONNECT→close. It SHALL enforce a 30 s idle socket timeout and a 64 KB packet cap, and SHALL track connected clients in a `ConcurrentHashMap` keyed by clientId.

#### Scenario: Subscribe with QoS 1 request

- GIVEN a connected client
- WHEN it SUBSCRIBEs to a topic
- THEN SUBACK grants QoS 0 regardless of the requested QoS

#### Scenario: Idle connection

- GIVEN a client with no traffic for 30 s
- WHEN the timeout elapses
- THEN the socket is closed

### Requirement: TLS Listener on Port 8883

At every broker start the system SHALL generate a fresh self-signed RSA-2048 certificate (CN=`SengledLocalBroker`, 1-year validity, CA and server combined) and build an `SSLContext` negotiated as "TLS" → TLS 1.2 (ESP8266 mbedtls requirement). BouncyCastle SHALL build the X.509 structures while platform/Conscrypt providers SHALL sign (BouncyCastle `SHA256withRSA` is unavailable on Android P+ and MUST NOT be requested).

#### Scenario: Broker restart

- GIVEN the broker restarts
- WHEN TLS setup runs
- THEN a new certificate is generated and the listener accepts TLS 1.2 on 8883

### Requirement: Reference-Counted Start and Stop

The broker SHALL start on the first `MqttBroker.acquire()` and stop on the last `release()`. `MqttBrokerService` SHALL acquire one reference in `onCreate` and SHALL stop itself if acquisition fails. The pairing wizard's transient second reference SHALL be released only when actually acquired (`mqttAcquired` guard).

#### Scenario: Service and wizard both hold a reference

- GIVEN the service holds one reference
- WHEN step 3 acquires and later releases its transient reference
- THEN the broker keeps running because the service reference remains

#### Scenario: Acquisition fails

- GIVEN `acquire()` returns failure in `onCreate`
- WHEN the service handles it
- THEN the service stops itself

### Requirement: Foreground Service Lifecycle

`MqttBrokerService` SHALL be a `specialUse` foreground service (`smart_home_light_mqtt_broker`) started from `MainActivity.onCreate` AND on boot via `ScheduleReceiver`.

#### Scenario: Device reboot

- GIVEN the device boots
- WHEN `ScheduleReceiver` runs
- THEN the broker service is started again

### Requirement: Persistent Availability Because Bulbs Blink Without It

The broker SHALL remain running persistently: paired ESP8266 bulbs blink indefinitely when the HTTP verification endpoints on port 57542 (served by `PairingServer`) are unreachable for the bulb (the firmware uses these endpoints to verify/obtain the broker address), and specifically when the configured broker at `lanIp:8883` is unreachable (firmware requirement).

#### Scenario: Broker unreachable

- GIVEN the broker is stopped while bulbs are paired
- WHEN bulbs lose the broker
- THEN they blink indefinitely — this is firmware behavior, not app behavior

## Annotations

- **Shared notification id:** the service's persistent notification (id 1, channel `rutinas`) is shared with `RoutineService` — last `notify` wins; specified fully in the `notifications` domain.
- **Fragility note:** the shared id 1 is order-dependent; adding a third foreground service in a future change would inherit this behavior (proposal risk, not fixed here).
