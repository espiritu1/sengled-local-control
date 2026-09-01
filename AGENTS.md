# Code Review Rules

## Kotlin / Android

- Use Kotlin idioms: immutable `val` by default, `data class` for value holders, nullable types over sentinel values.
- Use `UdpClient().use { ... }` scoped sockets for every UDP operation. Never share a single `DatagramSocket` across concurrent operations — UDP responses carry no source tag, so a shared socket lets parallel queries receive each other's replies and mixes up per-bulb state.
- Do not query the on/off switch state over UDP: these bulbs only report a latent brightness, never the real switch. Remember the last power state the user set (`ScheduleManager.getLastPower`/`setLastPower`) and use that as the displayed `isOn`.
- Keep network work off the main thread: use the executor / coroutines; never block the UI thread.
- Run Android operations on the UI thread via `runOnUiThread` when updating views.
- Prefer `SharedPreferences` backed by a dedicated `object` manager (`ScheduleManager`, `BulbRegistry`) over ad-hoc prefs access scattered in activities.
- Use string resources (`@string/...`) for all user-facing text, in both `values/` (Spanish) and `values-en/` (English). Never hardcode UI strings.
- Keep layouts in `res/layout` with view binding; use Material components; respect the dark theme colors from `res/values/colors.xml`.
- Catch and log network exceptions with `Log.d`, never crash on device I/O failures.
- Conventional commits only: `feat:`, `fix:`, `refactor:`, `docs:`, `build:`, `chore:`. Avoid "Co-Authored-By" and AI attribution.
