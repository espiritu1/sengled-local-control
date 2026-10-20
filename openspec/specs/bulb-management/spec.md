# Gestión de focos (Especificación)

## Propósito

Línea base (comportamiento actual) de `BulbRegistry`, el modelo `Bulb` y las operaciones de lista en `MainActivity`/`BulbAdapter`. Solo como referencia para regresiones.

## Requisitos

### Requisito: Modelo Bulb (observado)

La data class `Bulb` DEBERÁ observarse tal como se define: `data class Bulb(id, name, ip, brightness=1, isOn=false, connected=null)`, con los campos y valores predeterminados especificados.

### Requisito: Almacenamiento exclusivamente en SharedPreferences

Los focos DEBERÁN almacenarse únicamente en el archivo SharedPreferences `sengled_bulbs`: un array JSON bajo `bulbs_json` (`id`, `name`, `ip` por entrada) junto con las MAC como claves separadas `mac_<id>` en el mismo archivo. NO DEBERÁ haber valores predeterminados codificados; `loadPaired` DEBERÁ devolver `emptyList()` ante cualquier error de análisis. Toda MAC nueva en `mac_<id>` DEBERÁ persistirse canónicamente como `XX:XX:XX:XX:XX:XX` (mayúsculas, dos puntos), cualquiera sea la notación. Los valores heredados (sin separadores, minúsculas) siguen valiendo porque `WifiDetector` normaliza ambos lados.

#### Escenario: JSON corrupto

- DADO que `bulbs_json` no se puede analizar
- CUANDO se ejecuta `loadPaired`
- ENTONCES se devuelve una lista vacía y la aplicación se inicia sin focos

#### Escenario: Retorno de MAC

- DADO que un foco se guardó con una MAC
- CUANDO se llama a `getMac(id)`
- ENTONCES se devuelve la MAC guardada

#### Escenario: Escritura canónica de MAC

- DADO que se ingresa una MAC con separadores, sin ellos o con mayúsculas y minúsculas mixtas
- CUANDO se persiste mediante `updateBulbMac`
- ENTONCES se guarda una sola vez como `mac_<id>` en forma canónica `XX:XX:XX:XX:XX:XX`

### Requisito: Semántica de la API de BulbRegistry

`BulbRegistry` DEBERÁ exponer `getBulbs`, `addBulb` (upsert por id), `updateBulbIp`, `updateBulbMac`, `removeBulb`, `reorderBulbs` (quien llama pasa el orden completo; los id desconocidos se descartan y los focos no listados se mantienen al final — defensivo) y `getMac`. `updateBulbMac(context, bulbId, newMac)` DEBERÁ escribir únicamente `mac_<id>`, canonizarlo a mayúsculas `XX:XX:XX:XX:XX:XX` y NO modificar `bulb.id` ni `bulbs_json` (el id deriva de la MAC una sola vez al vincular y es clave de `name_<id>`, `routine_*_<id>`, `last_power_<id>`, `last_brightness_<id>`, `last_fired_<id>_*`).

#### Escenario: Reordenar con id desconocido

- DADO que el orden solicitado contiene un id que no existe en el almacenamiento
- CUANDO se ejecuta `reorderBulbs`
- ENTONCES el id desconocido se descarta y los focos existentes no listados se añaden al final

#### Escenario: updateBulbMac escribe y canoniza

- DADO que un foco tiene una MAC guardada
- CUANDO se llama a `updateBulbMac` con una MAC en minúsculas o sin separadores
- ENTONCES solo cambia `mac_<id>`, almacenada en mayúsculas `XX:XX:XX:XX:XX:XX`, y `bulbs_json` queda intacto

#### Escenario: updateBulbMac preserva el id

- DADO que un foco tiene id y claves `name_<id>` u otras `*_<id>`
- CUANDO se cambia su MAC
- ENTONCES `bulb.id` y todas las claves `*_<id>` permanecen sin cambios

### Requisito: La superposición de renombrado tiene prioridad sobre el registro

Los renombrados NO DEBERÁN escribirse en `BulbRegistry`; DEBERÁN guardarse en `sengled_prefs` como `name_<id>` (superposición). `MainActivity` DEBERÁ aplicar esta superposición al cargar, y `ScheduleManager.getBulbName` DEBERÁ preferir la superposición. El `name` del registro DEBERÁ permanecer con su valor original para siempre.

#### Escenario: Renombrar un foco vinculado

- DADO que un foco se llama "Foco 1a2b3c" en el registro
- CUANDO el usuario lo renombra a "Escritorio"
- ENTONCES la visualización usa "Escritorio" de `sengled_prefs` mientras que `bulbs_json` sigue diciendo "Foco 1a2b3c"

#### Escenario: Prioridad entre superposición y registro

- DADO que ambos almacenes contienen un nombre para el mismo id
- CUANDO se muestra el nombre en cualquier lugar
- ENTONCES la superposición de `sengled_prefs` tiene prioridad

### Requisito: Operaciones de lista (renombrar)

La lista DEBERÁ admitir renombrar con un máximo de 40 caracteres.

#### Escenario: Renombrar con límite de 40 caracteres

- DADO que un foco vinculado se muestra en la lista
- CUANDO el usuario guarda un nuevo nombre en el diálogo de renombrado
- ENTONCES la entrada se limita a 40 caracteres, un nombre recortado no vacío se escribe como `name_<id>` en `sengled_prefs`, y `sengled_bulbs` conserva el nombre original

### Requisito: Operaciones de lista (editar conexión)

La lista DEBERÁ admitir editar la conexión del foco (IP y MAC) en cualquier momento tras la vinculación, con un diálogo de dos campos precargados y editables. Al escribir, la IP DEBERÁ insertar un `.` tras cada octeto de 1 a 3 dígitos (máximo 4) y la MAC un `:` cada 2 dígitos hexadecimales. Al guardar DEBERÁ validarse: IP, 4 octetos entre 0 y 255; MAC, si no está vacía, 6 pares hexadecimales. Una entrada inválida DEBERÁ bloquear el guardado con error visible y sin persistir. Una MAC vacía DEBERÁ limpiar `mac_<id>`. La IP DEBERÁ persistirse con `updateBulbIp` y la MAC con `updateBulbMac`; la tarjeta DEBERÁ usar la nueva IP para conectividad. El diálogo DEBERÁ ser desplazable para que el teclado en pantalla no oculte los campos.

#### Escenario: La edición se guarda en el registro

- DADO que un foco tiene IP y MAC guardadas
- CUANDO el usuario guarda una IP no vacía y una MAC válida
- ENTONCES `updateBulbIp` reescribe la `ip` del foco en `bulbs_json` (`sengled_bulbs`), `updateBulbMac` reescribe la MAC en `mac_<id>`, y la tarjeta usa la nueva IP

#### Escenario: Máscaras automáticas al escribir

- DADO que los campos de IP y MAC están vacíos
- CUANDO el usuario escribe octetos de 1 a 3 dígitos (máximo 4) o dígitos hexadecimales
- ENTONCES la IP recibe un `.` tras cada octeto y la MAC un `:` cada 2 dígitos hexadecimales

#### Escenario: Entrada inválida bloquea el guardado

- DADO que se ingresó una IP que no son 4 octetos 0–255, o una MAC no vacía que no son 6 pares hexadecimales
- CUANDO el usuario intenta guardar
- ENTONCES se muestra un error visible y no se persiste ningún cambio

#### Escenario: MAC vacía limpia mac_<id>

- DADO que un foco vinculado tiene una MAC guardada
- CUANDO el usuario borra el campo de MAC y guarda
- ENTONCES se limpia `mac_<id>` y el resto del foco no cambia

### Requisito: Operaciones de lista (eliminar)

La lista DEBERÁ admitir eliminar con un diálogo de confirmación; al eliminar un foco, TAMBIÉN DEBERÁ restablecerse su rutina mediante `ScheduleManager.save(..., false, 0, 0, 1)`.

#### Escenario: Eliminar un foco con una rutina

- DADO que un foco tiene una rutina habilitada
- CUANDO el usuario confirma la eliminación
- ENTONCES el foco se elimina y su rutina se restablece a los valores predeterminados deshabilitados

### Requisito: Operaciones de lista (reordenar por arrastre)

La lista DEBERÁ admitir reordenar por arrastre (mantener pulsado inicia el arrastre; al soltar, `reorderBulbs` guarda y la lista `bulbs` en memoria se reordena para coincidir; mientras se arrastra, la tarjeta se destaca con `card_background_active`, se eleva y su deslizador/interruptor se desactivan).

#### Escenario: Arrastre en curso

- DADO que una tarjeta se está arrastrando
- CUANDO el usuario la mantiene en arrastre
- ENTONCES el deslizador y el interruptor de esa tarjeta se desactivan y la tarjeta se destaca

### Requisito: Operaciones de lista (editor de horarios)

La lista DEBERÁ admitir abrir el editor de horarios.

#### Escenario: Abrir el diálogo de rutina

- DADO que una tarjeta de foco está en la lista
- CUANDO el usuario toca el botón de horario
- ENTONCES `RoutineDialog` se abre con la rutina actual del foco precargada desde `ScheduleManager.getRoutine`, y al guardar se llama a `ScheduleManager.save`

### Requisito: Sincronización al reanudar

`onResume` DEBERÁ ejecutar `reloadBulbs()` para sincronizar el adaptador con el registro (añadir focos nuevos, eliminar focos borrados), cubriendo los focos añadidos por el asistente.

#### Escenario: Foco añadido por el asistente

- DADO que un foco fue guardado por el asistente de vinculación mientras `MainActivity` estaba en pausa
- CUANDO `MainActivity` se reanuda
- ENTONCES el nuevo foco aparece en la lista sin necesidad de actualizar manualmente

## Anotaciones

- **Preferencias duplicadas (congeladas):** dos almacenes para nombres — `sengled_bulbs` (registro, obsoleto tras renombrar) vs `sengled_prefs` `name_<id>` (superposición, autoritativo). La especificación establece que la superposición tiene prioridad; la unificación es una corrección diferida (mitigación propuesta).
- **Desvío respecto al README:** el README afirma que las IP están "definidas en BulbRegistry.kt"; en tiempo de ejecución las IP están en SharedPreferences. El código es la fuente de verdad.
