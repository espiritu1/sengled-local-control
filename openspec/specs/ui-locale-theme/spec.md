# Interfaz: idioma y tema (Especificación)

## Propósito

Línea base (comportamiento actual) del manejo de idioma, el tema forzado a oscuro, los recursos de cadenas y los elementos de la pantalla principal en `MainActivity` y los layouts. Solo como referencia para regresiones.

## Requisitos

### Requisito: Idioma explícito con español por defecto

El español DEBERÁ ser el idioma predeterminado de la aplicación. La preferencia `lang` en `sengled_prefs` DEBERÁ ser siempre explícita (`"es"` o `"en"`, nunca un respaldo al idioma del dispositivo). `MainActivity.onCreate` DEBERÁ aplicarla mediante `Locale.setDefault` además del método obsoleto `resources.updateConfiguration`.

#### Escenario: Primer inicio

- DADO que no existe la preferencia `lang`
- CUANDO se inicia `MainActivity`
- ENTONCES la aplicación se ejecuta en español (predeterminado)

#### Escenario: Inglés seleccionado

- DADO que `lang` = `"en"`
- CUANDO se inicia `MainActivity`
- ENTONCES todas las cadenas de la interfaz se resuelven desde `values-en`

### Requisito: Botón de idioma como indicador de estado

El botón de idioma DEBERÁ mostrar el idioma ACTUAL ("ES"/"EN"), no el idioma objetivo; al alternarlo, DEBERÁ guardarse la preferencia y llamarse a `recreate()`.

#### Escenario: Alternar estando en español

- DADO que la aplicación está en español
- CUANDO el usuario toca el botón de idioma
- ENTONCES el botón mostraba "ES", la preferencia pasa a `"en"` y la actividad se recrea en inglés

### Requisito: Tema forzado a oscuro

`Theme.Sengled` DEBERÁ ser `Theme.Material3.DayNight.NoActionBar` con superficies forzadas a oscuro: `background_dark` #121212 (barra de estado y fondo de ventana), tarjetas #1E1E1E, resaltado de arrastre #3A3A3A, primario ámbar #FFC107, estado verde #4CAF50, estado rojo #FF5252. Los layouts NO DEBERÁN tener `android:text` codificado (todo debe ser `@string` o vacío).

#### Escenario: Renderizado de cualquier pantalla

- DADO que la aplicación se inicia en cualquier dispositivo
- CUANDO se infla un layout
- ENTONCES las superficies usan la paleta oscura fija independientemente del modo oscuro del sistema

### Requisito: Paridad de recursos de cadenas

`res/values/strings.xml` (español) y `res/values-en/strings.xml` (inglés) DEBERÁN contener 135 cadenas cada uno, con total paridad entre ellos.

#### Escenario: Auditoría de recursos

- DADO que existen ambos archivos de cadenas
- CUANDO se comparan
- ENTONCES cada clave existe en ambos con el mismo número total de cadenas

### Requisito: Elementos de la pantalla principal

La pantalla principal DEBERÁ proporcionar: un botón de actualizar, un botón para añadir foco (diálogo de selección: vincular mediante asistente o alta manual), un diálogo de información (inflado desde `dialog_info_sections` + `dialog_info` dentro de un ScrollView con autoLink para URLs), el botón de idioma, una etiqueta de versión `v{versionName}` (versionName "8.0") y el Snackbar de notificaciones deshabilitadas.

#### Escenario: Abrir el diálogo de información

- DADO que está en la pantalla principal
- CUANDO el usuario abre la información
- ENTONCES aparece el diálogo de secciones, desplazable y con URLs seleccionables

## Anotaciones

- **Cadenas en español codificadas (congeladas, NO corregidas):** `PairingWizardActivity` contiene 16 cadenas en español mostradas al usuario codificadas directamente, sin usar recursos — 3 errores de campo ("Ingresá el nombre de la red", "Ingresá la contraseña", "Ingresá un nombre para el foco"), 12 líneas de registro de vinculación y el Toast de éxito "¡Foco '…' agregado!". Esto VIOLA la regla del proyecto que exige cadenas en `values/` + `values-en/`; se registra como comportamiento existente según la tabla de riesgos de la propuesta y la corrección se difiere a un cambio posterior. Detalles en `pairing-provisioning`.
- **Recursos/código muerto — no especificado:** `menu_main.xml` nunca se infla (no hay `onCreateOptionsMenu`); `MainActivity.buildRoutineSubtitle()` no tiene llamadas (también anotado en `routines-schedules`). Excluidos de los requisitos.
- **No hay conjunto de pruebas:** `testDebugUnitTest` NO-SOURCE; la línea base se verificó por revisión.
