# Detección de dispositivos (Especificación)

## Propósito

Línea base (comportamiento actual) de la actualización/detección en `MainActivity.refresh()`/`reloadBulbs()`, `WifiDetector` y el manejo de IP en `BulbRegistry`. Solo como referencia para regresiones.

## Requisitos

### Requisito: Ruta rápida de actualización con IP guardada

Cada actualización DEBERÁ consultar primero la IP guardada de cada foco mediante `udp.getState(ip)`. Si hay una IP guardada pero no responde, el sistema DEBERÁ OMITIR el escaneo por MAC y marcar el foco como sin conexión (para mantener la actualización rápida, commit `c2a66cc`).

#### Escenario: IP guardada sin respuesta

- DADO que un foco tiene una IP guardada que no responde
- CUANDO se ejecuta la actualización
- ENTONCES no se intenta escaneo por MAC para ese foco y se muestra como sin conexión

#### Escenario: IP guardada responde

- DADO que un foco tiene una IP guardada que responde
- CUANDO se ejecuta la actualización
- ENTONCES el estado del foco se actualiza desde la consulta directa

### Requisito: Escaneo por MAC solo cuando la IP está vacía

El escaneo por MAC DEBERÁ ejecutarse únicamente cuando la IP guardada del foco está vacía (el usuario omitió el ingreso de IP). En caso de éxito, la IP encontrada DEBERÁ guardarse mediante `BulbRegistry.updateBulbIp`.

#### Escenario: Foco añadido sin IP

- DADO que un foco tiene la IP guardada vacía
- CUANDO se ejecuta la actualización
- ENTONCES se ejecuta `WifiDetector.findBulbByMac(mac, 12_000)` y, si hay coincidencia, la IP encontrada se guarda como IP del foco

### Requisito: Mecánica del escaneo por MAC

`findBulbByMac` DEBERÁ enviar por broadcast `{"func":"search_devices"}` a `255.255.255.255` Y barrer la subred /24 del teléfono (hosts 1..254) en el puerto 9080, escuchando una respuesta cuyo `result.mac` coincida tras la normalización (sin distinción entre mayúsculas/minúsculas). Una MAC vacía DEBERÁ abortar inmediatamente. La llamada DEBERÁ usar E/S bloqueante únicamente en un hilo en segundo plano.

#### Escenario: Respuesta coincidente

- DADO que la MAC no está vacía
- CUANDO cualquier host probado responde con esa MAC normalizada
- ENTONCES se devuelve la IP de la respuesta

#### Escenario: MAC vacía

- DADO que la cadena MAC está vacía
- CUANDO se llama a `findBulbByMac`
- ENTONCES se aborta sin enviar sondas

### Requisito: Ventana de detección durante la vinculación

La detección durante la vinculación DEBERÁ usar la misma función `findBulbByMac` con una ventana de 180 s.

#### Escenario: Detección desde el asistente

- DADO que el asistente terminó de enviar las credenciales
- CUANDO se ejecuta la detección por MAC
- ENTONCES se aplica la misma lógica de sondeo con un tiempo de espera de 180 s en lugar de 12 s

### Requisito: Identidad del foco e IP

El id del foco DEBERÁ ser los últimos 6 caracteres hexadecimales en minúsculas de la MAC, compartido entre el asistente y el alta manual. La IP DEBERÁ ser opcional en todo momento; un foco puede volverse a encontrar por MAC cuando su IP esté vacía.

#### Escenario: IP borrada posteriormente

- DADO que la IP de un foco se borró (quedó vacía)
- CUANDO se ejecuta la actualización
- ENTONCES la detección recurre al escaneo por MAC para ese foco

## Anotaciones

- **Desvío respecto al README:** la afirmación del README de que "las IP de los focos están definidas en BulbRegistry.kt" es incorrecta en tiempo de ejecución — las IP se almacenan en SharedPreferences (`sengled_bulbs`); el código es la fuente de verdad.
- **Código muerto — no especificado:** ninguno en este dominio; los símbolos no usados relacionados se anotan en sus especificaciones correspondientes.
