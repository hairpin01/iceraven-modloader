# Iceraven runtime modules

A module is a small archive (`.apk` or `.jar`) that customises the **browser itself**, not the pages it shows. It runs in
the browser process, can add entries to the browser menu and read or write browser preferences, and it is installed and
removed at runtime without rebuilding the app.

There is no userscript involved: a module is native code with access to the same UI surfaces the browser uses.

## How it works

1. `FenixApplication.onCreate` calls `ModuleManager.ensureStarted(this)`.
2. The manager scans `filesDir/modules` for archives, checks their signature, loads the entry point through a
   `DexClassLoader` whose parent is the browser class loader, and calls `onEnable` on every enabled module.
3. Entries registered through `ModuleHost.addMenuItem` are merged into the browser menu by `WebExtensionsMenuBinding`,
   which is the same code path WebExtension menu items use, so modules and add-ons render identically.
4. Settings → Modules lists everything on disk: enable, disable, inspect, uninstall, install from a file.

Because the plugin class loader inherits from the browser class loader, the `BrowserModule` and `ModuleHost` classes a
module is compiled against resolve to the browser's own classes at runtime. No stub jar or shared library plumbing.

## API

`org.mozilla.fenix.modules.api` is the whole surface:

| Type | Purpose |
|---|---|
| `BrowserModule` | Entry point: `id`, `name`, `version`, `description`, `permissions`, `onEnable`, `onDisable` |
| `ModuleHost` | `resources`, `addMenuItem`, `removeMenuItems`, `addToolbarAction`, `removeToolbarActions`, `addSetting`, `removeSettings`, `getBooleanPref`, `getStringPref`, `setPref`, `setThemeColor`, `clearThemeColors`, `log` |
| `ModuleMenuItem` | Browser menu entry: `id`, `label`, `icon`, `enabled`, `badgeText`, `onClick` |
| `ModuleToolbarAction` | Toolbar button: `id`, `label`, `icon`, `visible`, `enabled`, `onClick` |
| `ModuleSetting` | Row rendered in the module manager: `Switch`, `Text` or `Action` |
| `ModulePermission` | `PREFERENCES_READ`, `PREFERENCES_WRITE`, `MENU_ITEMS`, `TOOLBAR`, `SETTINGS`, `THEME` |
| `ModuleColorSlot` | Colour slots: the Acorn palette plus the Material 3 scheme |
| `ModuleThemeScope` | `ALL`, `LIGHT`, `DARK`, `OLED`, `PRIVATE` |

Every host method checks the permissions the module declared, so a module can never do more than it asked for.

## Themes

A module with the `THEME` permission recolours the browser by naming slots, never by shipping a full palette:

```kotlin
host.setThemeColor(ModuleThemeScope.ALL, ModuleColorSlot.PRIMARY, 0xFF00AA00.toInt())
host.setThemeColor(ModuleThemeScope.PRIVATE, ModuleColorSlot.PRIMARY, 0xFFFF5555.toInt())
```

How it works:

- `theme/FirefoxTheme.kt` is the only place in the browser where the two palettes are picked, and it now hands them to
  `ModuleThemeOverrides.applyTo(...)`. That is the entire upstream change, two lines.
- `ModuleThemeOverrides` merges the overrides of every enabled module and copies the shipped palettes with named
  arguments, so slots nobody touched keep the browser's own values. Cost when no theme module is installed is one empty
  map lookup per theme.
- Modules are merged in load order, so the last installed module wins for a conflicting slot, and that order is stable
  across runs.
- Colours are plain ARGB integers, which keeps Compose out of the module API: a theme module compiles against nothing but
  the Android SDK.
- The merged map is Compose state, so switching a theme module on re-themes the running browser instead of waiting for a
  restart.

Not covered yet: gradients, typography, shapes and the XML theme used by the few non-Compose preference screens. Those
would need either more Acorn copies or an overlay, so they are deliberately out of the first cut.

### Rules

- `id` must match `[A-Za-z0-9_.-]{1,64}` and stay stable across updates, user state is keyed by it.
- Never touch the module's own `R` class: host and module resource ids can collide. Resolve ids by name through
  `host.resources.getIdentifier("label", "string", "com.example.my_module")`.
- `onEnable` runs on a loader thread. Keep it fast, do not block, and never throw: exceptions are caught and shown in the
  the module manager.
- `onClick` runs on the main thread. Do not do IO there.

## Building a module

`modules-example` is a standalone Gradle project that builds a ready to install archive:

```bash
cd modules-example

# A debug archive, no key needed. Enable developer mode in the browser to install it.
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Or a release archive signed with the same key as the browser build, which installs without developer mode.
export MODULE_KEYSTORE=/path/to/module.jks
export MODULE_KEYSTORE_PASSWORD=...
export MODULE_KEY_ALIAS=...
export MODULE_KEY_PASSWORD=...

./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

The signing config only exists when `MODULE_KEYSTORE` is set, so debug builds never need a key.

Then in the browser: **Settings → Modules → Install module from file**.

The example compiles the SPI sources directly into the archive (see `sourceSets` in `app/build.gradle.kts`):

```kotlin
kotlin.srcDir("../../app/src/main/java/org/mozilla/fenix/modules/api")
```

Any project layout works as long as the manifest declares the entry point:

```xml
<meta-data
    android:name="org.mozilla.fenix.modules.entrypoint"
    android:value="com.example.my_module.MyModule" />
```

## Signing

A module runs with the full privileges of the browser, so archives are signature checked by default:

- allowed: archives signed with a certificate whose SHA-256 fingerprint is in the trusted set, which is seeded with the
  browser's own signing certificates;
- **Settings → Modules → Advanced → Developer mode** lifts that check for locally built modules.

Fingerprints can be managed through `ModulePreferences.trustedSigners`. Install failures due to an untrusted signature
are reported on the module row instead of failing silently.

## Files

| File | Role |
|---|---|
| `app/src/main/java/org/mozilla/fenix/modules/api/BrowserModule.kt` | Public module API |
| `app/src/main/java/org/mozilla/fenix/modules/ModuleManager.kt` | Discovery, loading, state |
| `app/src/main/java/org/mozilla/fenix/modules/ModulePluginLoader.kt` | Signature check, `DexClassLoader`, resources |
| `app/src/main/java/org/mozilla/fenix/modules/ModuleHostImpl.kt` | Browser side of the API, permission enforcement |
| `app/src/main/java/org/mozilla/fenix/modules/ModuleThemeOverrides.kt` | Merges module colours into the Acorn and Material palettes |
| `app/src/main/java/org/mozilla/fenix/modules/ModuleToolbarBridge.kt` | Turns module toolbar actions into `ActionButton`s and routes taps back |
| `app/src/main/java/org/mozilla/fenix/modules/ModulePreferences.kt` | Module manager preferences |
| `app/src/main/java/org/mozilla/fenix/modules/ui/ModulesFragment.kt` | Settings screen |
| `app/src/main/res/xml/modules_preferences.xml` | Screen layout |
| `app/src/main/res/values/modules_strings.xml` | Screen strings, kept out of `strings.xml` to keep merges clean |

## Upstream merges

Eight upstream files are touched, each in one place only:

| File | Change |
|---|---|
| `FenixApplication.kt` | one `ModuleManager.ensureStarted(this)` call |
| `components/menu/WebExtensionsMenuBinding.kt` | appends module items to the menu dispatch |
| `settings/SettingsFragment.kt` | one `when` branch for the new preference key |
| `res/xml/preferences.xml` | one preference entry |
| `res/navigation/nav_graph.xml` | one destination and one action |
| `theme/FirefoxTheme.kt` | two palette arguments wrapped in `ModuleThemeOverrides.applyTo` |
| `components/toolbar/BrowserToolbarMiddleware.kt` | appends module actions to the start and end browser action lists |
| `browser/BrowserToolbarStoreBuilder.kt` | one more toolbar middleware |

Everything else lives under `modules/`, so an upstream merge only has to be taught these five lines once.