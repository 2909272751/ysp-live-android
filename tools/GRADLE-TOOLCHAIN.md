# Gradle + AGP toolchain (primary build path)

No Android Studio, no IDE. Gradle 8.9 + AGP 8.5.2 + androidx.media3 1.4.1,
building on JDK 21 against a command-line-installed SDK.

> The raw `aapt2`/`javac`/`d8` pipeline in `build-apk.ps1` still works and is
> documented in `ANDROID-CLI-TOOLCHAIN.md`, but **Gradle is the primary path**
> for the real app (it handles AAR dependencies and resources).

## Installed components

| Component | Version | Path |
|---|---|---|
| Gradle | 8.9 | `C:\1\ysp-android\tools\gradle-8.9\` |
| Gradle launcher | — | `C:\1\ysp-android\tools\gradle-8.9\bin\gradle.bat` |
| Gradle user home | — | `C:\1\ysp-android\tools\gradle-home\` |
| JDK | 21.0.12.1 | `C:\re-toolkit\jdk` |
| Android SDK | — | `C:\AndroidSdkCli` |
| AGP | 8.5.2 | resolved from `google()` |
| media3 (ExoPlayer) | 1.4.1 | resolved from `google()` |

SDK packages (installed with `--sdk_root=C:\AndroidSdkCli`): `platform-tools`
37.0.1, `platforms;android-34`, `build-tools;34.0.0`.

## One-command build: gradlew.ps1

`C:\1\ysp-android\tools\gradlew.ps1` sets every env var and forwards args to
the local Gradle launcher.

```powershell
# from the project dir (contains settings.gradle)
& C:\1\ysp-android\tools\gradlew.ps1 assembleDebug -x lint

# target a project explicitly
& C:\1\ysp-android\tools\gradlew.ps1 -ProjectDir C:\1\ysp-android\app assembleDebug

# inspect the resolved environment + exact command
& C:\1\ysp-android\tools\gradlew.ps1 -ShowEnv assembleDebug

# release
& C:\1\ysp-android\tools\gradlew.ps1 -ProjectDir C:\1\ysp-android\app clean assembleRelease
```

It exports:

```
JAVA_HOME        = C:\re-toolkit\jdk
ANDROID_SDK_ROOT = C:\AndroidSdkCli
ANDROID_HOME     = C:\AndroidSdkCli
GRADLE_USER_HOME = C:\1\ysp-android\tools\gradle-home
GRADLE_OPTS      = -Dfile.encoding=UTF-8 -Duser.language=en -Duser.country=US
```

and writes `local.properties` (`sdk.dir=C\:\\AndroidSdkCli`) if the project has
none. Exit code is Gradle's exit code.

## Raw Gradle invocation (no helper)

```powershell
$env:JAVA_HOME        = 'C:\re-toolkit\jdk'
$env:ANDROID_SDK_ROOT = 'C:\AndroidSdkCli'
$env:ANDROID_HOME     = 'C:\AndroidSdkCli'
$env:GRADLE_USER_HOME = 'C:\1\ysp-android\tools\gradle-home'

Set-Location C:\1\ysp-android\_probe
& C:\1\ysp-android\tools\gradle-8.9\bin\gradle.bat assembleDebug -x lint --console=plain --no-daemon
```

`local.properties` must exist in the project root:

```properties
## Backslashes escaped; colons only need escaping in some editors, but be safe.
sdk.dir=C\:\\AndroidSdkCli
```

## Does AGP 8.5.2 work with JDK 21?

**Yes — no separate JDK needed.** AGP 8.5.x supports running on JDK 17–21;
Gradle 8.9 fully supports JDK 21. Confirmed here:

```
Launcher JVM:  21.0.12.1 (Eclipse Adoptium 21.0.12.1+1-LTS)
Daemon JVM:    C:\re-toolkit\jdk
```

Source/target compatibility for the app itself remains Java 17
(`compileOptions { sourceCompatibility/targetCompatibility = VERSION_17 }`).

## Probe scaffold

`C:\1\ysp-android\_probe\` — proves AGP + media3 resolve and compile.

```
_probe/
  settings.gradle          google() + mavenCentral(), pluginManagement, PREFER_SETTINGS
  build.gradle             com.android.application 8.5.2 (apply false)
  gradle.properties        jvmargs, useAndroidX, nonTransitiveRClass
  local.properties         sdk.dir
  app/build.gradle         compileSdk 34, minSdk 21, targetSdk 34, Java 17, media3 1.4.1
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/gary/ysp/probe/MainActivity.java
  app/src/main/java/com/gary/ysp/probe/PlayerProbeActivity.java
  app/src/main/res/values/{strings,themes}.xml
```

`PlayerProbeActivity` constructs the real API surface:

```java
DefaultHttpDataSource.Factory http =
    new DefaultHttpDataSource.Factory().setUserAgent("ysp-probe/1.0")...;
MediaSource src = new HlsMediaSource.Factory(http)
    .setAllowChunklessPreparation(true)
    .createMediaSource(MediaItem.fromUri("https://example.com/stream.m3u8"));
ExoPlayer player = new ExoPlayer.Builder(this).build();
player.setMediaSource(src); player.prepare();
```

Build result: **BUILD SUCCESSFUL**, 34 tasks, ~10 s cold / ~2 s warm.

APK: `C:\1\ysp-android\_probe\app\build\outputs\apk\debug\app-debug.apk`
— 3,819,267 bytes, package `com.gary.ysp.probe`, launchable `MainActivity`,
`application-debuggable`, `sdkVersion 21` / `targetSdkVersion 34`.

Dex contents verified: 6,331 class defs; media3 present —
`androidx/media3/exoplayer/hls/HlsMediaSource`, `.../ExoPlayerImpl`,
`androidx/media3/datasource/DefaultHttpDataSource`, 2,281 `androidx.media3.*`
classes in `classes.dex`.

Resolved graph (`:app:dependencies --configuration debugRuntimeClasspath`):

```
+--- androidx.media3:media3-exoplayer:1.4.1
|    +--- androidx.media3:media3-common:1.4.1
|    +--- androidx.media3:media3-datasource:1.4.1
|    +--- androidx.media3:media3-decoder:1.4.1
|    +--- androidx.media3:media3-container:1.4.1
|    \--- androidx.media3:media3-extractor:1.4.1
\--- androidx.media3:media3-exoplayer-hls:1.4.1
```

## Pitfalls found on this machine

1. **`--offline=false` is not a valid Gradle flag.**
   `Command-line option '--offline' does not take an argument.`
   `--offline` is a boolean switch; just omit it (online is the default).

2. **Unquoted `-Dorg.gradle.jvmargs=-Xmx2g` breaks under PowerShell.**
   The argv arrives as `.gradle.jvmargs=-Xmx2g` (leading `-Dorg` eaten) and
   Gradle fails with
   `Task '.gradle.jvmargs=-Xmx2g' not found in root project`.
   Fix: put it in `gradle.properties` (`org.gradle.jvmargs=-Xmx2g ...`), or
   single-quote it (`'-Dorg.gradle.jvmargs=-Xmx2g'`). `gradlew.ps1` normalizes
   the mangled form automatically.

3. **A live Gradle daemon makes redirected output hang forever.**
   The daemon inherits the parent's stdout/stderr handles; after Gradle exits
   the redirect never sees EOF, so `*> log` / `| Out-String` blocks
   indefinitely (observed 10+ minute hangs). Use `--no-daemon` whenever you
   capture output. `gradlew.ps1` defaults to `--no-daemon` and offers
   `-WithDaemon` for interactive use.

4. **`Select-Object -First N` on a Gradle pipeline kills it early** and leaves
   orphaned `java.exe` daemons behind. Redirect to a file, then filter the file.
   Clean up with `gradle.bat --stop` and/or `Stop-Process -Name java`.

5. **UTF-8 BOM in a `.java` file breaks javac**: `error: illegal character: '\ufeff'`
   followed by a cascade of "class, interface, enum or record expected".
   PowerShell 5.1's `Set-Content -Encoding UTF8` **emits a BOM**. Write sources
   with `[System.IO.File]::WriteAllText($p, $s, (New-Object System.Text.UTF8Encoding($false)))`
   or use the harness `write` tool. `gradlew.ps1` cannot fix this — check with:
   `[System.IO.File]::ReadAllBytes($f)[0] -eq 0xEF`.

6. **`androidx.annotation` is not on the compile classpath** unless declared.
   AGP pulls `androidx.annotation:annotation` transitively, but importing
   `androidx.annotation.Nullable` failed (`package androidx.annotation does not exist`)
   because media3 brings it only as a runtime/compile-transitive of other modules.
   Declare `implementation 'androidx.annotation:annotation:1.7.1'` explicitly if
   you want annotations in source.

7. **`dependencies` tasks need the module path**: `gradle dependencies` on the
   root project fails with `configuration 'debugRuntimeClasspath' not found`;
   use `gradle :app:dependencies`.

8. **Broken ambient env** — `JAVA_HOME` and `ANDROID_SDK_ROOT` point into
   `C:\实用软件开发\.android_build_tools\...`, which does not exist. Always set
   them explicitly (the helper does).

9. **Keep build paths ASCII.** PowerShell 5.1 mangles CJK characters in native
   argv (see `ANDROID-CLI-TOOLCHAIN.md`).

## Cleanup

```powershell
& C:\1\ysp-android\tools\gradle-8.9\bin\gradle.bat --stop    # stop daemons
Remove-Item C:\1\ysp-android\tools\gradle-dist.zip           # 136 MB, no longer needed
```
