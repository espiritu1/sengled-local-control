# Control UDP (Especificación)

## Propósito

Línea base (comportamiento actual) del control JSON por puerto 9080 en `UdpClient.kt`, utilizado por `MainActivity` y `RoutineService`. Solo como referencia para regresiones.

## Requisitos

### Requisito: Protocolo de control en el puerto 9080

El sistema DEBERÁ emitir comandos JSON por UDP UTF-8 en el puerto 9080 con un tiempo de espera de 1500 ms por solicitud: `set_device_switch` (parámetro `switch` 1/0), `set_device_brightness` (limitado a 0..100), `get_device_brightness` (respuesta `{"result":{"ret":0,"brightness":NN}}`) y `search_devices` (detección, a través de `WifiDetector`).

#### Escenario: Establecer brillo

- DADO que un foco está accesible
- CUANDO la aplicación envía `set_device_brightness` con 42
- ENTONCES el comando se limita a 0..100 y se recibe confirmación dentro de 1500 ms

#### Escenario: Tiempo de espera agotado

- DADO que un foco no responde
- CUANDO se envía un comando
- ENTONCES la operación termina tras 1500 ms sin respuesta

### Requisito: Sockets con ámbito por operación

Los comandos de control emitidos por `MainActivity` y `RoutineService` DEBERÁN usar un socket con ámbito `UdpClient().use { ... }`. `UdpClient` NO es thread-safe (un `DatagramSocket` por instancia) y los sockets NUNCA DEBERÁN compartirse entre consultas concurrentes, ya que las respuestas UDP no llevan etiqueta de origen y un socket compartido mezclaría el estado por foco.

#### Escenario: Consultas paralelas a dos focos

- DADO que dos consultas a focos se ejecutan en paralelo
- CUANDO cada una envuelve su trabajo en `UdpClient().use { ... }`
- ENTONCES cada operación lee únicamente su propia respuesta

### Requisito: E/S de red fuera del hilo principal

`MainActivity` DEBERÁ ejecutar todo el trabajo de red en un `Executors.newFixedThreadPool(2)`; las actualizaciones de la interfaz DEBERÁN hacerse mediante `runOnUiThread`.

#### Escenario: Llegada de respuesta UDP

- DADO que un comando de control finalizó en el executor
- CUANDO el resultado actualiza la interfaz
- ENTONCES la actualización se ejecuta mediante `runOnUiThread`, nunca en el hilo de red

### Requisito: Estado de encendido mostrado desde memoria, no desde UDP

El sistema NO DEBERÁ consultar el estado del interruptor por UDP (solo se informa el brillo latente: un foco apagado sigue informando su último brillo). `UdpClient.getState` DEBERÁ derivar la heurística `on = brightness > 1`; el estado mostrado DEBERÁ preferir `ScheduleManager.getLastPower(id)` (`rememberedOn ?: finalState.on`), que devuelve `null` — no `false` — cuando nunca se ha establecido. Cada cambio de interruptor o deslizador por parte del usuario DEBERÁ escribir `setLastPower`/`setLastBrightness`.

#### Escenario: Primera visualización

- DADO que nunca se ha guardado `last_power` para el foco
- CUANDO finaliza la actualización
- ENTONCES la visualización recurre a la heurística del brillo (`brightness > 1`)

#### Escenario: Estado apagado recordado prevalece sobre el brillo

- DADO que el usuario apagó el foco por última vez, pero el brillo es 80
- CUANDO finaliza la actualización
- ENTONCES la interfaz muestra apagado porque `getLastPower` tiene prioridad

### Requisito: Semántica del deslizador de brillo

El SeekBar con máximo 99 DEBERÁ mapearse a brillo 1..100 (1 = mínimo brillo, nunca 0 — solo el interruptor apaga el foco). Un cambio en el deslizador DEBERÁ implicar encendido (`isOn = value > 0`) y escribir `last_power`. La entrada del deslizador DEBERÁ tener un retardo de rebote de 250 ms (`BulbAdapter.BRIGHTNESS_DEBOUNCE_MS`); el interruptor y el SeekBar DEBERÁN desactivarse mientras una tarjeta se arrastra y mientras `connected == false`.

#### Escenario: Deslizador en posición mínima

- DADO que el deslizador se mueve a su posición más baja
- CUANDO se aplica el valor
- ENTONCES el brillo pasa a 1 (no 0) y se escribe `last_power` como encendido

### Requisito: Interfaz optimista con confirmación diferida

Las actualizaciones del interruptor y del deslizador DEBERÁN mostrarse inmediatamente con `connected = null` ("checking") y ajustarse a `true`/`false` cuando llegue la confirmación UDP.

#### Escenario: Alternar un foco

- DADO que se alterna el interruptor en pantalla
- CUANDO el comando se pone en cola
- ENTONCES la interfaz muestra el nuevo estado con "checking" hasta que la confirmación lo resuelva

## Anotaciones

- **Particularidad congelada:** por diseño, el estado del interruptor nunca se consulta por UDP; la heurística de "estado recordado tiene prioridad sobre el brillo" descrita arriba es la regla obligatoria para la visualización — no se debe "limpiar".
- **No hay conjunto de pruebas:** `testDebugUnitTest` NO-SOURCE; los escenarios aquí se verificaron por revisión.
