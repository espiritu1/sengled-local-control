# Vinculación y aprovisionamiento (Especificación)

## Propósito

Comportamiento actual del asistente de 4 pasos de Sengled y del alta manual.

## Requisitos

### Requisito: Flujo del asistente de cuatro pasos (pasos de la interfaz)

El asistente DEBERÁ recorrer 4 pasos con puntos: credenciales Wi-Fi → conectar a AP del foco `192.168.8.x` → vinculación en curso → resultado (IP, MAC, nombre predeterminado `Foco {last-5-of-MAC}`, IP estática opcional).

#### Escenario: Vinculación exitosa (navegación del asistente)

- DADO que se han completado los cuatro pasos
- CUANDO el paso 4 guarda
- ENTONCES el asistente finaliza con el comportamiento de guardar y volver especificado

#### Escenario: Falla de escaneo continúa

- DADO que la detección de MAC falló
- CUANDO el asistente avanza
- ENTONCES el paso 4 sigue mostrándose (ingreso manual de IP)

### Requisito: Guardado y regreso del asistente de cuatro pasos

Al guardar, DEBERÁ volver a `MainActivity` con CLEAR_TOP | SINGLE_TOP.

#### Escenario: Vinculación exitosa (guardar y volver)

- DADO que se han completado los cuatro pasos
- CUANDO el paso 4 guarda
- ENTONCES el asistente guarda y finaliza con CLEAR_TOP | SINGLE_TOP

### Requisito: Detección de Wi-Fi en el paso 1

El paso 1 DEBERÁ ofrecer una lista de escaneo Wi-Fi "Detectar" que requiere `ACCESS_FINE_LOCATION` en tiempo de ejecución, esperando 2500 ms fijos después de `startScan()`.

#### Escenario: Se toca Detectar

- DADO que el permiso de ubicación fue otorgado
- CUANDO el usuario toca "Detectar"
- ENTONCES la aplicación espera 2500 ms y muestra las redes escaneadas

### Requisito: Protocolo de vinculación en el paso 3 (secuencia de etapas)

El paso 3 DEBERÁ ejecutarse en un `Thread` en segundo plano contra UDP `192.168.8.1:9080` con la secuencia de etapas: handshake, escaneos Wi-Fi/AP (pausa de 5 s), re-handshake, credenciales RC4 (`SengledCrypto`, clave fija) dirigidas a la IP LAN del teléfono, fin de configuración.

### Requisito: Política de reintentos del handshake del protocolo de vinculación

El handshake DEBERÁ reintentarse 40 × (2 s + 2 s).

### Requisito: Alternativa de MAC en el protocolo de vinculación

Cuando la respuesta de `startConfig` no incluye MAC, el sistema DEBERÁ utilizar el BSSID conectado como MAC provisional. El BSSID del AP del foco NO es necesariamente la MAC LAN del foco: son dos interfaces distintas del mismo dispositivo y pueden diferir en el último byte. Por eso la MAC del handshake es provisional y DEBERÁ reemplazarse por la MAC LAN real (la reportada por `search_devices`) en cuanto el foco se detecte en la red doméstica.

#### Escenario: startConfig omite la MAC

- DADO que la respuesta de `startConfig` no incluye MAC
- CUANDO se ensamblan las credenciales
- ENTONCES se utiliza el BSSID conectado como MAC provisional

#### Escenario: El BSSID difiere de la MAC LAN

- DADO que el handshake respondió sin MAC y se usó el BSSID (p. ej. `...93:74`)
- Y el foco reporta una MAC LAN distinta por `search_devices` (p. ej. `...93:73`)
- CUANDO el foco se detecta en la red doméstica
- ENTONCES la MAC LAN reportada reemplaza al BSSID antes de derivar el id/nombre y persistir

### Requisito: Fijación de red durante la vinculación

El socket de vinculación DEBERÁ enlazarse a la interfaz Wi-Fi del teléfono; el proceso DEBERÁ mantenerse en la red del AP del foco durante el intercambio de credenciales. Ambos se liberarán posteriormente.

#### Escenario: Datos móviles activos

- DADO que los datos móviles están activos
- CUANDO el proceso está fijado a la red del AP
- ENTONCES el tráfico llega al AP del foco, no a los datos móviles

### Requisito: Detección posterior a credenciales y servidor HTTP

Después de enviar las credenciales, el asistente DEBERÁ indicar que vuelva a la Wi-Fi doméstica (`waitOffBulbAp`, 180 s) y luego buscar la IP del foco mediante un escaneo por MAC de `search_devices` (180 s), nunca mediante el seguimiento de `PairingServer`. La coincidencia DEBERÁ hacerse por los primeros 5 bytes de la MAC (OUI + prefijo del dispositivo), no por la MAC completa, porque el BSSID del handshake puede diferir de la MAC LAN en el último byte. El paso 3 DEBERÁ ejecutar `PairingServer` (broker `lanIp:8883`) que atienda ambos endpoints en la nube (`accessCloud.json`, `bimqtt`).

#### Escenario: El foco vuelve a conectarse y coincide el escaneo

- DADO que el foco se conecta a la Wi-Fi doméstica y accede a ambos endpoints
- CUANDO el escaneo coincide dentro de 180 s
- ENTONCES la IP se guarda; el seguimiento de endpoints nunca se consume

#### Escenario: El escaneo coincide por prefijo aunque el último byte difiera

- DADO que la MAC del handshake (BSSID) termina en un byte distinto de la MAC LAN real
- CUANDO el foco responde a `search_devices` con su MAC LAN
- ENTONCES el escaneo coincide por los primeros 5 bytes, devuelve la IP y la MAC LAN real

### Requisito: Rotación y navegación hacia atrás

La rotación DEBERÁ bloquearse ÚNICAMENTE en el paso 3; los demás pasos rotan y restauran su estado desde `savedInstanceState`. La navegación hacia atrás DEBERÁ pasar por `OnBackPressedCallback` → `goBack()`: 1 → confirmar salida, 2 → paso 1, 3 → confirmar cancelación y luego `pairingCancelled`, 4 → `finish()` SIN guardar.

#### Escenario: Atrás en el paso 4

- DADO que se muestra el paso de resultados
- CUANDO el usuario pulsa atrás
- ENTONCES el asistente finaliza sin guardar

#### Escenario: Cancelación durante el paso 3

- DADO que el hilo del protocolo está ejecutándose
- CUANDO el usuario confirma la cancelación
- ENTONCES se establece `pairingCancelled` entre etapas y se vuelve al paso 2

### Requisito: Protección de referencia al broker en el paso 3

El paso 3 DEBERÁ adquirir una referencia transitoria a `MqttBroker`, que se liberará solo si fue adquirida (protección `mqttAcquired`). `onDestroy` DEBERÁ cancelar la vinculación, anular el registro de callbacks y detener `PairingServer`.

#### Escenario: Interrupción antes de la adquisición

- DADO que la vinculación termina antes de adquirir la referencia
- CUANDO se ejecuta el `finally` del hilo
- ENTONCES no se libera ninguna referencia al broker

### Requisito: Alta manual

El alta manual DEBERÁ requerir nombre e IP; la MAC es opcional. El id será los últimos 6 caracteres hexadecimales de la MAC, o bien `"ip"` + la IP sin puntos si no hay MAC. Los campos IP y MAC DEBERÁN usar la máscara de entrada compartida (`InputMasks.attachMask`): punto automático cada tres dígitos en la IP, dos puntos automático cada dos dígitos hex en la MAC, con tope de 12 dígitos/12 hex. El diálogo DEBERÁ ser desplazable para que el teclado en pantalla no oculte los campos.

#### Escenario: Alta sin MAC

- DADO que hay nombre e IP, y la MAC está vacía
- CUANDO el usuario confirma
- ENTONCES el id es `ip` + la IP sin puntos

#### Escenario: Máscara automática al escribir

- DADO que el diálogo de alta manual está abierto
- CUANDO el usuario escribe `192168168106` en IP y `80A036B19373` en MAC
- ENTONCES se muestran `192.168.168.106` y `80:A0:36:B1:93:73`, sin admitir más de 12 dígitos/12 hex

## Anotaciones

- **Cadenas en español codificadas (congeladas):** 16 cadenas del asistente se saltan los recursos; violación de regla, corrección diferida.
- **Código muerto — no especificado:** seguimiento de endpoints de `PairingServer`; `SengledCrypto.decryptPayload`.
- **Desvío respecto al README:** los comentarios sobre reintentos son aproximados; el código es la fuente de verdad.
- **No hay conjunto de pruebas:** `testDebugUnitTest` NO-SOURCE; solo `lint`.
- **Identidad del foco (dos interfaces):** el BSSID del AP del foco y su MAC LAN pueden diferir en el último byte (verificado en dispositivo: BSSID `...93:74`, MAC LAN `...93:73`). La MAC LAN reportada por `search_devices` es la fuente de verdad para id/nombre y para reencontrar el foco.
- **Fuera de alcance:** `SengledTools/` (en .gitignore).
