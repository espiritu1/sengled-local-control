# Notificaciones (Especificación)

## Propósito

Línea base (comportamiento actual) de todas las notificaciones: la notificación persistente compartida (`MqttBrokerService`, `RoutineService`) y los avisos de eventos de rutina (canales de `RoutineService`, comprobación de permiso en `MainActivity`). Solo como referencia para regresiones.

## Requisitos

### Requisito: Una única notificación persistente compartida con id 1

El sistema DEBERÁ mostrar exactamente una notificación en curso en el canal `rutinas` ("Rutinas", IMPORTANCE_DEFAULT) con id de notificación 1, compartida por `RoutineService` y `MqttBrokerService`; el servicio que llame a `startForeground`/`notify(1, …)` en último lugar DEBERÁ establecer el contenido. La notificación DEBERÁ tener `setOngoing(true)` y `setOnlyAlertOnce(true)`.

#### Escenario: El broker se inicia después de las rutinas

- DADO que `RoutineService` publicó la notificación con id 1
- CUANDO `MqttBrokerService` publica la notificación con id 1
- ENTONCES la barra muestra el texto del broker y solo existe una notificación en curso

#### Escenario: Actualización del estado de las rutinas

- DADO que el broker publicó la notificación con id 1
- CUANDO `RoutineService` actualiza su notificación de estado
- ENTONCES el contenido de la barra vuelve al texto de las rutinas

### Requisito: Contenido de la notificación persistente por propietario

El contenido persistente de `RoutineService` DEBERÁ ser un texto genérico o la hora del siguiente evento (revelación de 30 minutos). El contenido de `MqttBrokerService` DEBERÁ indicar que el broker local está activo.

#### Escenario: Siguiente evento dentro de 30 minutos

- DADO que un evento de rutina está a 10 minutos
- CUANDO se actualiza la notificación de estado
- ENTONCES muestra "Próxima a las {time}"

### Requisito: Avisos temporales de eventos en un canal separado

Los eventos de rutina DEBERÁN publicar avisos de cancelación automática en el canal `rutinas_aviso` ("Avisos de rutina", IMPORTANCE_HIGH) con ids a partir de `1000 + contador`, siendo el contador persistente en `event_notification_id` e incrementado por evento (cada evento recibe un id distinto), con sonido `DEFAULT_ALL`, espaciados 400 ms por foco, y DEBERÁN publicarse únicamente cuando el comando UDP haya tenido éxito.

#### Escenario: Dos focos ejecutan ON

- DADO que dos rutinas se ejecutan en el mismo tick
- CUANDO ambos comandos UDP tienen éxito
- ENTONCES se publican dos notificaciones distintas (con ids incrementados distintos) separadas por 400 ms

#### Escenario: Fallo de UDP suprime el aviso

- DADO que el comando ON falló
- CUANDO finaliza la ejecución
- ENTONCES no se publica ningún aviso de evento

### Requisito: Comprobación del permiso de notificaciones

Cuando las notificaciones de la aplicación están deshabilitadas, `MainActivity` DEBERÁ mostrar un Snackbar indefinido cuya acción abra los ajustes de notificaciones de la aplicación, con alternativa a los ajustes de la aplicación.

#### Escenario: Notificaciones deshabilitadas

- DADO que las notificaciones de la aplicación están bloqueadas
- CUANDO `MainActivity` ejecuta diagnósticos
- ENTONCES aparece un Snackbar indefinido con una acción para abrir ajustes

## Anotaciones

- **Id 1 compartido (congelado, frágil):** dos servicios en primer plano comparten deliberadamente el canal `rutinas` y el id 1, por lo que el contenido es el último en escribirlo y el teléfono muestra una única barra (véase comentario en `MqttBrokerService`). Un tercer servicio en primer plano futuro heredaría este comportamiento dependiente del orden (riesgo de exploración, no corregido aquí).
- **No hay conjunto de pruebas:** `testDebugUnitTest` NO-SOURCE; los escenarios se verificaron por revisión.
