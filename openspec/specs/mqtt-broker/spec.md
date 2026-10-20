# Broker MQTT (Especificación)

## Propósito

Línea base (comportamiento actual) del broker embebido implementado manualmente (`MqttBroker`, `MqttBrokerService`, `SslCertGenerator`) y su ciclo de vida como servicio en primer plano. Solo como referencia para regresiones.

## Requisitos

### Requisito: Broker con subconjunto MQTT 3.1.1

El broker DEBERÁ implementar, sin ninguna librería: CONNECT→CONNACK, SUBSCRIBE→SUBACK (concesión de QoS 0 para todos los temas), PUBLISH→PUBACK para QoS 1 (solo se registra el payload), PINGREQ→PINGRESP, UNSUBSCRIBE→UNSUBACK y DISCONNECT→close. DEBERÁ aplicar un tiempo de espera de 30 s para sockets inactivos y un límite de 64 KB por paquete, y DEBERÁ seguir el rastro de clientes conectados en un `ConcurrentHashMap` indexado por clientId.

#### Escenario: Suscripción con solicitud QoS 1

- DADO que un cliente está conectado
- CUANDO se suscribe a un tema
- ENTONCES SUBACK concede QoS 0 independientemente del QoS solicitado

#### Escenario: Conexión inactiva

- DADO que un cliente no tiene tráfico durante 30 s
- CUANDO expira el tiempo de espera
- ENTONCES el socket se cierra

### Requisito: Escucha TLS en el puerto 8883

En cada arranque del broker, el sistema DEBERÁ generar un certificado autofirmado RSA-2048 nuevo (CN=`SengledLocalBroker`, validez de 1 año, CA y servidor combinados) y construir un `SSLContext` negociado como "TLS" → TLS 1.2 (requisito de mbedtls en ESP8266). BouncyCastle DEBERÁ construir las estructuras X.509 mientras que los proveedores de plataforma/Conscrypt DEBERÁN firmar (BouncyCastle `SHA256withRSA` no está disponible en Android P+ y NO DEBERÁ solicitarse).

#### Escenario: Reinicio del broker

- DADO que el broker se reinicia
- CUANDO se configura TLS
- ENTONCES se genera un nuevo certificado y el escuchador acepta TLS 1.2 en el puerto 8883

### Requisito: Inicio y detención con conteo de referencias

El broker DEBERÁ iniciarse en la primera llamada a `MqttBroker.acquire()` y detenerse en la última llamada a `release()`. `MqttBrokerService` DEBERÁ adquirir una referencia en `onCreate` y DEBERÁ detenerse si la adquisición falla. La segunda referencia transitoria del asistente de vinculación DEBERÁ liberarse solo si fue realmente adquirida (protección `mqttAcquired`).

#### Escenario: Servicio y asistente mantienen referencias

- DADO que el servicio mantiene una referencia
- CUANDO el paso 3 adquiere y posteriormente libera su referencia transitoria
- ENTONCES el broker sigue ejecutándose porque la referencia del servicio permanece

#### Escenario: Fallo en la adquisición

- DADO que `acquire()` devuelve fallo en `onCreate`
- CUANDO el servicio lo gestiona
- ENTONCES el servicio se detiene a sí mismo

### Requisito: Ciclo de vida del servicio en primer plano

`MqttBrokerService` DEBERÁ ser un servicio en primer plano de tipo `specialUse` (`smart_home_light_mqtt_broker`), iniciado desde `MainActivity.onCreate` Y en el arranque del dispositivo mediante `ScheduleReceiver`.

#### Escenario: Reinicio del dispositivo

- DADO que el dispositivo se reinicia
- CUANDO se ejecuta `ScheduleReceiver`
- ENTONCES el servicio del broker se inicia de nuevo

### Requisito: Disponibilidad persistente (los focos parpadean si no está disponible)

El broker DEBERÁ mantenerse en ejecución de forma persistente: los focos ESP8266 vinculados parpadean indefinidamente cuando los endpoints HTTP de verificación en el puerto 57542 (atendidos por `PairingServer`) no son accesibles para el foco (el firmware usa estos endpoints para verificar/obtener la dirección del broker), y específicamente cuando el broker configurado en `lanIp:8883` no es accesible (requisito del firmware).

#### Escenario: Broker inaccesible

- DADO que el broker se detiene mientras hay focos vinculados
- CUANDO los focos pierden la conexión con el broker
- ENTONCES parpadean indefinidamente — este es un comportamiento del firmware, no de la aplicación

## Anotaciones

- **Id de notificación compartido:** la notificación persistente del servicio (id 1, canal `rutinas`) se comparte con `RoutineService` — la última llamada a `notify` prevalece; se especifica completamente en el dominio `notifications`.
- **Nota de fragilidad:** el id 1 compartido depende del orden; si se añadiera un tercer servicio en primer plano en un cambio futuro, heredaría este comportamiento (riesgo del diseño, no corregido aquí).
