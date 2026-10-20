# Rutinas y horarios (Especificación)

## Propósito

Línea base (comportamiento actual) de la persistencia, ejecución y diálogo de rutinas (`RoutineDialog`).

## Requisitos

### Requisito: Una rutina por foco con valores predeterminados fijos

El sistema DEBERÁ admitir exactamente UNA rutina por foco: `enabled`, `onMinutes`/`offMinutes` (minutos desde medianoche), `brightness` (1..100). Los valores predeterminados son ENCENDIDO a 19:30, APAGADO a 00:00, brillo 1; se guardan en `sengled_prefs` como `routine_{enabled,on,off,brightness}_<id>`.

#### Escenario: Foco nuevo

- DADO que no hay una rutina guardada
- CUANDO se abre su diálogo
- ENTONCES los valores predeterminados son ENCENDIDO 19:30, APAGADO 00:00, brillo 1

### Requisito: Interfaz de edición de rutinas

El diálogo DEBERÁ usar selectores AM/PM con un conmutador AM/PM siempre visible (`applyPeriod` conserva hora/minutos), un SeekBar de brillo y un interruptor de habilitación. Al guardar, DEBERÁ llamarse a `ScheduleManager.save` y reiniciarse el servicio con `startService`.

#### Escenario: Cambiar AM a PM

- DADO que está establecido 19:30
- CUANDO se cambia el periodo
- ENTONCES la hora/minutos se mantienen en 7:30 y solo cambia el periodo

### Requisito: Ejecución unificada con ventanas de gracia

`RoutineService` DEBERÁ ser el único responsable de la ejecución: un `HandlerThread` ejecuta `tick()`, que ejecuta los eventos ON/OFF programados con una ventana de gracia de +90 s hacia adelante y −10 min hacia atrás, siendo idempotente mediante marcadores `last_fired_<bulb>_<on|off>` por minuto de época. Para ON, DEBERÁ ejecutarse `setBrightness` y luego `setSwitch(true)` (ambos deben tener éxito para notificar); para OFF, DEBERÁ ejecutarse `setSwitch(false)`.

#### Escenario: Dentro de la ventana de gracia

- DADO que ON está programado a las 12:00, el tick ocurre a las 12:00:30 y el marcador no está establecido
- CUANDO `tick()` lo evalúa
- ENTONCES se ejecuta ON

#### Escenario: Fuera de la ventana de gracia hacia atrás

- DADO que ON está programado a las 12:00, el tick ocurre a las 11:49:30 (fuera de los −10 min de gracia hacia atrás) y el marcador no está establecido
- CUANDO `tick()` lo evalúa
- ENTONCES se omite

### Requisito: Marcador de fallo escrito antes de UDP

`fireActionIfDue` DEBERÁ escribir el marcador `last_fired` ANTES de intentar el comando UDP, de modo que un comando fallido se consuma y nunca se reintente mediante `tick()` o catch-up.

#### Escenario: Comando UDP falla

- DADO que se intentó ejecutar ON pero el foco no respondió
- CUANDO el marcador ya se ha escrito
- ENTONCES ni `tick()` ni catch-up lo reintentan

### Requisito: Despertador exacto

`tick()` DEBERÁ dormir hasta el siguiente evento (o `next − 30 min`) y programar una activación mediante alarma exacta (`setExactAndAllowWhileIdle` → `setExact` → silenciosamente ninguna) a `ACTION_ROUTINE_CHECK`, que simplemente reinicia el servicio.

#### Escenario: Alarma dispara

- DADO que se ha programado una activación
- CUANDO la alarma dispara
- ENTONCES el servicio se reinicia; no se envía ningún comando

### Requisito: Revelación de 30 minutos para el siguiente evento

`tick()` DEBERÁ programar el siguiente tick a `next − 30 min` (`NOTIF_AHEAD_MS`). El estado persistente DEBERÁ mostrar "Próxima a las {time}" únicamente dentro de los últimos 30 min antes del siguiente evento; en caso contrario, mostrará un texto genérico.

#### Escenario: Evento dentro de 30 minutos

- DADO que el siguiente evento está a 25 minutos
- CUANDO se ejecuta `updateStatusNotification`
- ENTONCES se muestra la hora; a 45 minutos se muestra el texto genérico

### Requisito: Recuperación silenciosa sin ventana de gracia hacia atrás

Cuando la red predeterminada vuelve a estar disponible, el sistema DEBERÁ esperar 2 s, luego ejecutar `runCatchUp()` → `recoverTransitions()`: aplicar cualquier transición pasada cuyo marcador `last_fired` no esté establecido, utilizando los marcadores de `tick()`. La recuperación NO DEBERÁ forzar un estado esperado, NO DEBERÁ mostrar ninguna notificación y NO DEBERÁ tener ventana de gracia hacia atrás — una ejecución perdida de ese mismo día aún se recupera.

#### Escenario: Reconexión tras un ON perdido

- DADO que la red vuelve a las 20:00, ON estaba programado a 19:30 y su marcador no está establecido
- CUANDO catch-up se ejecuta 2 s después de la disponibilidad
- ENTONCES ON se ejecuta una sola vez de forma silenciosa; si el marcador ya está establecido, no se envía nada

### Requisito: Las rutinas no escriben last_power

Las ejecuciones de rutina ON/OFF NO DEBERÁN escribir `last_power`; solo lo hacen los gestos del usuario en `MainActivity`; la siguiente actualización seguirá mostrando el estado recordado por el usuario.

#### Escenario: Una rutina enciende el foco

- DADO que el estado recordado es apagado
- CUANDO la rutina ejecuta ON
- ENTONCES la siguiente actualización sigue mostrando apagado (el estado recordado tiene prioridad)

### Requisito: Ciclo de vida del servicio

`RoutineService` DEBERÁ ser `START_STICKY` y DEBERÁ detenerse por sí mismo (cancelar activación, limpiar handler, `stopForeground(STOP_FOREGROUND_REMOVE)`, `stopSelf`) cuando no queden rutinas habilitadas o cuando reciba `ACTION_STOP`. DEBERÁ iniciarse desde `MainActivity.onCreate` y en el arranque del dispositivo.

#### Escenario: Última rutina deshabilitada

- DADO que la única rutina está deshabilitada
- CUANDO `ScheduleManager.save` reinicia el servicio
- ENTONCES detecta que no hay rutinas habilitadas y se detiene

## Anotaciones

- **Código muerto — no especificado:** `hasEnabledRoutine` sin llamadas, `getLastBrightness` solo escritura, `buildRoutineSubtitle` sin llamadas.
- **Particularidades congeladas:** escribir el marcador antes de UDP y la recuperación sin ventana de gracia hacia atrás son intencionales (se eliminó la reconciliación para evitar parpadeo al reconectar).
- **División de preferencias:** rutinas/`last_power`/`last_fired` en `sengled_prefs`; focos en `sengled_bulbs`.
