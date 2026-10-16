# GeckoLoader and GeckoMods

**GeckoLoader** is the runtime module loader built into this fork of Iceraven. A **GeckoMod** is an archive (`.apk` or
`.jar`) that customises the **browser itself**, not the pages it shows: it runs in the browser process, adds entries to the
browser menu and the toolbar, ships its own settings rows, and can override the theme. Modules are installed and removed
at runtime, no rebuild of the browser is involved.

There is no userscript involved: a module is native code with access to the same UI surfaces the browser uses.

## How it works

1. `FenixApplication.onCreate` calls `ModuleManager.ensureStarted(this)`. That only remembers the application context,
   because Fenix kills the app for main thread disk reads.
2. The manager scans `noBackupFilesDir/geckomod` for archives, checks their signature, loads the entry point through a
   `DexClassLoader` whose parent is the browser class loader, and calls `onEnable` on every enabled module.
3. Entries registered through `ModuleHost.addMenuItem` are merged into the browser menu by `WebExtensionsMenuBinding`,
   the same code path WebExtension menu items use, so modules and add-ons render identically. Toolbar buttons are
   injected the same way in `BrowserToolbarMiddleware`.
4. Settings → GeckoLoader lists everything on disk: install, enable, disable, inspect, uninstall.

Because the plugin class loader inherits from the browser class loader, the `BrowserModule` and `ModuleHost` classes a
module is compiled against resolve to the browser's own classes at runtime. No stub jar or shared library plumbing.

### Why archives are stored read-only

ART refuses to map a dex file the process is allowed to write to (`Writable dex file ... is not allowed`), so GeckoLoader
makes every archive read-only before handing it to `DexClassLoader`. The directory stays writable, so an update can
still replace a file; the loader simply flips the write bit back before doing so.

Archives live in `noBackupFilesDir/geckomod/<installId>.apk`, where `installId` is the first 16 hex characters of the
archive's SHA-256. That keeps install ids stable across reinstalls without a separate index to keep in sync. Archives
written by older builds, which stored them in `filesDir/modules`, are moved automatically on the next start.

## Manifest metadata

Everything a module shows in the manager is declared in its own `AndroidManifest.xml`:

```xml
<application android:label="Example GeckoMod">
    <meta-data android:name="org.mozilla.fenix.modules.entrypoint" android:value="com.example.geckomod.ExampleModule" />
    <meta-data android:name="org.mozilla.fenix.modules.name" android:value="Example GeckoMod" />
    <meta-data android:name="org.mozilla.fenix.modules.description" android:value="Adds a menu entry and a toolbar button." />
    <meta-data android:name="org.mozilla.fenix.modules.version" android:value="1" />
    <meta-data android:name="org.mozilla.fenix.modules.author" android:value="You" />
    <meta-data android:name="org.mozilla.fenix.modules.update_url" android:value="https://example.com/releases" />
    <meta-data
        android:name="org.mozilla.fenix.modules.permissions"
        android:value="MENU_ITEMS,TOOLBAR,SETTINGS,THEME,PREFERENCES_READ" />
</application>
```

| Key | Required | Fallback |
|---|---|---|
| `...entrypoint` | yes, a public class implementing `BrowserModule` | none |
| `...name` | no | the archive label |
| `...description` | no | `BrowserModule.description` |
| `...version` | no | the archive version code |
| `...author` | no | hidden |
| `...update_url` | no | no update entry; only `http`/`https` is ever opened |
| `...permissions` | no | `BrowserModule.permissions` |

**The manifest is the authority on permissions.** A module whose code asks for a capability its manifest does not
declare is refused, and every `ModuleHost` call is checked against the declared set, so a module cannot do more than what
the user saw before installing.

## API

`org.mozilla.fenix.modules.api` is the whole surface:

| Type | Purpose |
|---|---|
| `BrowserModule` | Entry point: `id`, `name`, `version`, `description`, `permissions`, `onEnable`, `onDisable` |
| `ModuleHost` | `context`, `resources`, menu, toolbar, settings, preferences, theme, `log` |
| `ModuleMenuItem` | Browser menu entry: `id`, `label`, `icon`, `enabled`, `badgeText`, `onClick` |
| `ModuleToolbarAction` | Toolbar button: `id`, `label`, `icon`, `visible`, `enabled`, `onClick` |
| `ModuleSetting` | Settings row: `Switch`, `Text` or `Action` |
| `ModuleColorSlot`, `ModuleThemeScope` | Theme overrides |
| `ModulePermission` | `PREFERENCES_READ`, `PREFERENCES_WRITE`, `MENU_ITEMS`, `TOOLBAR`, `SETTINGS`, `THEME` |

### Rules

- `id` must match `[A-Za-z0-9_.-]{1,64}` and stay stable across updates, user state is keyed by it.
- Never touch the module's own `R` class: host and module resource ids can collide. Resolve ids by name through
  `host.resources.getIdentifier("label", "string", "com.example.my_module")`.
- `onEnable` runs on a loader thread. Keep it fast, do not block, and never throw: exceptions are caught and shown in the
  module manager.
- `onClick` runs on the main thread, and main thread disk reads are fatal under StrictMode. Do not do IO there.
- `host.context` is the browser's **application context**: activities, dialogs, services, files, network, the whole
  platform. Never cache an Activity from it, read it fresh whenever you need one.
- Permissions are **consent, not a sandbox**. They are what the user is told before installing, and GeckoLoader refuses
  a module that asks for more than its manifest declared. Inside those boundaries the module has the same power as the
  browser itself, so treat anything you write accordingly.

## Xposed hooks

A GeckoMod can also hook the browser through the Xposed protocol. Declare it in the manifest:

```xml
<meta-data android:name="xposedmodule" android:value="true" />
<meta-data android:name="xposeddescription" android:value="What your module does" />
```

and implement `de.robv.android.xposed.IXposedHookLoadPackage` (optionally `IXposedHookZygoteInit`) on the entry point
class, alongside `BrowserModule`. GeckoLoader then:

1. checks whether `de.robv.android.xposed.XposedBridge` exists, which it only does when the browser itself runs under
   LSPosed/Zygisk;
2. calls `initZygote` and `handleLoadPackage` **reflectively**, filling `packageName`, `processName`, `classLoader`,
   `appInfo`, `firstApplication` and `isSystemServer` for you.

Both the official `de.robv.android.xposed:api` jar and hand-written stubs work, because the call sites are resolved by
name. Without LSPosed the hooks stay inactive and the module manager says so; nothing crashes and nothing is silently
ignored. `geckomod-example` ships the stub interfaces in
`app/src/main/java/de/robv/android/xposed/XposedApi.kt` as a working example.

## Safe mode

A module that crashes the browser three times in a row turns safe mode on by itself: no module is loaded any more, and
the manager lists the archives without instantiating them. The switch under Settings → GeckoLoader → Advanced forces
safe mode on or off manually and clears the failure counters. Loading a module successfully clears its counter.

## Building a module

`geckomod-example` is a standalone Gradle project that builds a ready to install archive:

```bash
cd geckomod-example

# Either sign with the same key as the browser build...
export MODULE_KEYSTORE=/path/to/module.jks
export MODULE_KEYSTORE_PASSWORD=...
export MODULE_KEY_ALIAS=...
export MODULE_KEY_PASSWORD=...

./gradlew :app:assembleRelease
# -> app/build/outputs/apk/release/app-release.apk

# ...or build an unsigned debug archive for testing with developer mode on.
./gradlew :app:assembleDebug
```

Then in the browser: **Settings → GeckoLoader → Install module from file**.

The example compiles the SPI sources directly into the archive (see `sourceSets` in `app/build.gradle.kts`):

```kotlin
kotlin.srcDir("../../app/src/main/java/org/mozilla/fenix/modules/api")
```

Any project layout works as long as the manifest declares the entry point.

## Signing

A module runs with the full privileges of the browser, so archives are signature checked by default:

- allowed: archives signed with a certificate whose SHA-256 fingerprint is in the trusted set, which is seeded with the
  browser's own signing certificates;
- **Settings → GeckoLoader → Advanced → Developer mode** lifts that check for locally built modules.

Fingerprints can be managed through `ModulePreferences.trustedSigners`. Install failures due to an untrusted signature
are reported on the module row instead of failing silently.

## Files

| File | Role |
|---|---|
| `app/src/main/java/org/mozilla/fenix/modules/api/BrowserModule.kt` | Public module API |
| `app/src/main/java/org/mozilla/fenix/modules/ModuleManager.kt` | Discovery, loading, state, safe mode |
| `app/src/main/java/org/mozilla/fenix/modules/ModulePluginLoader.kt` | Signature check, `DexClassLoader`, metadata |
| `app/src/main/java/org/mozilla/fenix/modules/ModuleInfo.kt` | Metadata read from the manifest |
| `app/src/main/java/org/mozilla/fenix/modules/ModuleHostImpl.kt` | Browser side of the API, permission enforcement |
| `app/src/main/java/org/mozilla/fenix/modules/ModuleToolbarBridge.kt` | Toolbar actions and their middleware |
| `app/src/main/java/org/mozilla/fenix/modules/ModuleThemeOverrides.kt` | Colour overrides applied in `FirefoxTheme` |
| `app/src/main/java/org/mozilla/fenix/modules/ModuleXposedBridge.kt` | Optional Xposed hooks, called reflectively |
| `app/src/main/java/org/mozilla/fenix/modules/ModulePreferences.kt` | Module manager preferences |
| `app/src/main/java/org/mozilla/fenix/modules/ui/ModulesFragment.kt` | Settings screen |
| `app/src/main/res/xml/modules_preferences.xml` | Screen layout |
| `app/src/main/res/values/modules_strings.xml` | Screen strings, kept out of `strings.xml` to keep merges clean |

## Upstream merges

Seven upstream files are touched, each in one place only:

| File | Change |
|---|---|
| `FenixApplication.kt` | one `ModuleManager.ensureStarted(this)` call |
| `components/menu/WebExtensionsMenuBinding.kt` | appends module items to the menu dispatch |
| `browser/BrowserToolbarStoreBuilder.kt` | attaches the module toolbar middleware and its store |
| `settings/SettingsFragment.kt` | one `when` branch for the new preference key |
| `theme/FirefoxTheme.kt` | two palette arguments wrapped in `ModuleThemeOverrides.applyTo` |
| `res/xml/preferences.xml` | one preference entry |
| `res/navigation/nav_graph.xml` | one destination and one action |

Everything else lives under `org.mozilla.fenix.modules/`, so an upstream merge only has to be taught these lines once.