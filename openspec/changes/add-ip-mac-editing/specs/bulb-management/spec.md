# Delta para Gestión de focos

## MODIFIED Requirements

### Requisito: Almacenamiento exclusivamente en SharedPreferences

Los focos DEBERÁN almacenarse únicamente en `sengled_bulbs`: un array JSON bajo `bulbs_json` (`id`, `name`, `ip`) junto con las MAC como claves `mac_<id>`. NO DEBERÁ haber valores predeterminados; `loadPaired` DEBERÁ devolver `emptyList()` ante cualquier error de análisis. Toda MAC nueva en `mac_<id>` DEBERÁ persistirse canónicamente como `XX:XX:XX:XX:XX:XX` (mayúsculas, dos puntos), cualquiera sea la notación. Los valores heredados (sin separadores, minúsculas) siguen valiendo porque `WifiDetector` normaliza ambos lados (`WifiDetector.kt:191,218,258`).

(Previously: sin forma canónica.)

#### Escenario: JSON corrupto

- DADO que `bulbs_json` no se puede analizar
- CUANDO se ejecuta `loadPaired`
- ENTONCES se devuelve una lista vacía y la app inicia sin focos

#### Escenario: Retorno de MAC

- DADO que un foco se guardó con una MAC
- CUANDO se llama a `getMac(id)`
- ENTONCES se devuelve la MAC guardada

#### Escenario: Escritura canónica de MAC

- DADO que se ingresa una MAC con separadores, sin ellos o con mayúsculas y minúsculas mixtas
- CUANDO se persiste mediante `updateBulbMac`
- ENTONCES se guarda una sola vez como `mac_<id>` en forma canónica `XX:XX:XX:XX:XX:XX`

### Requisito: Semántica de la API de BulbRegistry

`BulbRegistry` DEBERÁ exponer `getBulbs`, `addBulb` (upsert por id), `updateBulbIp`, `updateBulbMac`, `removeBulb`, `reorderBulbs` (orden completo del llamador; ids desconocidos se descartan, no listados al final) y `getMac`. `updateBulbMac(context, bulbId, newMac)` DEBERÁ escribir únicamente `mac_<id>`, canonizarlo a mayúsculas `XX:XX:XX:XX:XX:XX` y NO modificar `bulb.id` ni `bulbs_json` (el id deriva de la MAC una sola vez al vincular (`PairingWizardActivity.kt:645`) y es clave de `name_<id>`, `routine_*_<id>`, `last_power_<id>`, `last_brightness_<id>`, `last_fired_<id>_*`).

(Previously: sin `updateBulbMac` ni estabilidad del id.)

#### Escenario: Reordenar con id desconocido

- DADO que el orden solicitado contiene un id inexistente
- CUANDO se ejecuta `reorderBulbs`
- ENTONCES el id desconocido se descarta y los focos no listados se añaden al final

#### Escenario: updateBulbMac escribe y canoniza

- DADO que un foco tiene una MAC guardada
- CUANDO se llama a `updateBulbMac` con una MAC en minúsculas o sin separadores
- ENTONCES solo cambia `mac_<id>`, almacenada en mayúsculas `XX:XX:XX:XX:XX:XX`, y `bulbs_json` queda intacto

#### Escenario: updateBulbMac preserva el id

- DADO que un foco tiene id y claves `name_<id>` u otras `*_<id>`
- CUANDO se cambia su MAC
- ENTONCES `bulb.id` y todas las claves `*_<id>` permanecen sin cambios

### Requisito: Operaciones de lista (editar IP)

La lista DEBERÁ admitir editar la conexión del foco (IP y MAC) en cualquier momento tras la vinculación, con un diálogo de dos campos precargados y editables. Al escribir, la IP DEBERÁ insertar un `.` tras cada octeto de 1 a 3 dígitos (máximo 4) y la MAC un `:` cada 2 dígitos hexadecimales. Al guardar DEBERÁ validarse: IP, 4 octetos entre 0 y 255; MAC, si no está vacía, 6 pares hexadecimales. Una entrada inválida DEBERÁ bloquear el guardado con error visible y sin persistir. Una MAC vacía DEBERÁ limpiar `mac_<id>`. La IP DEBERÁ persistirse con `updateBulbIp` y la MAC con `updateBulbMac`; la tarjeta DEBERÁ usar la nueva IP para conectividad.

(Previously: solo IP, sin validación.)

#### Escenario: La edición se guarda en el registro

- DADO que un foco tiene IP y MAC guardadas
- CUANDO el usuario guarda una IP no vacía y una MAC válida
- ENTONCES `updateBulbIp` reescribe la IP en `bulbs_json` y `updateBulbMac` la MAC en `mac_<id>`; la verificación de conectividad usa la nueva IP

#### Escenario: Máscaras automáticas al escribir

- DADO que los campos de IP y MAC están vacíos
- CUANDO el usuario escribe octetos de 1 a 3 dígitos (máximo 4) o dígitos hexadecimales
- ENTONCES la IP recibe un `.` tras cada octeto y la MAC un `:` cada 2 dígitos hexadecimales

#### Escenario: Entrada inválida bloquea el guardado

- DADO que se ingresó una IP que no son 4 octetos 0–255, o una MAC no vacía que no son 6 pares hexadecimales
- CUANDO intenta guardar
- ENTONCES se muestra un error visible y no se persiste ningún cambio

#### Escenario: MAC vacía limpia mac_<id>

- DADO que un foco vinculado tiene una MAC guardada
- CUANDO el usuario borra el campo de MAC y guarda
- ENTONCES se limpia `mac_<id>` y el resto del foco no cambia
