# Pure CLI Android build toolchain

No Android Studio, no Gradle. Plain Java → signed, installable APK using
`aapt2` + `javac` + `d8` + `zipalign` + `apksigner`.

## Installed toolchain

| Component | Absolute path |
|---|---|
| SDK root | `C:\AndroidSdkCli` |
| android.jar | `C:\AndroidSdkCli\platforms\android-34\android.jar` |
| aapt2 | `C:\AndroidSdkCli\build-tools\34.0.0\aapt2.exe` |
| d8 | `C:\AndroidSdkCli\build-tools\34.0.0\d8.bat` |
| apksigner | `C:\AndroidSdkCli\build-tools\34.0.0\apksigner.bat` |
| zipalign | `C:\AndroidSdkCli\build-tools\34.0.0\zipalign.exe` |
| aapt (v1) | `C:\AndroidSdkCli\build-tools\34.0.0\aapt.exe` |
| adb | `C:\AndroidSdkCli\platform-tools\adb.exe` |
| sdkmanager | `C:\AndroidSdkCli\cmdline-tools\latest\bin\sdkmanager.bat` |
| JDK 21 | `C:\re-toolkit\jdk` |
| debug keystore | `C:\1\ysp-android\tools\debug.keystore` |

Installed SDK packages: `platform-tools` 37.0.1, `platforms;android-34`,
`build-tools;34.0.0`.

## Build script

`C:\1\ysp-android\tools\build-apk.ps1`

```powershell
# minimal
powershell -NoProfile -ExecutionPolicy Bypass -File C:\1\ysp-android\tools\build-apk.ps1 C:\1\ysp-android\_hello

# with version overrides
powershell -NoProfile -ExecutionPolicy Bypass -File C:\1\ysp-android\tools\build-apk.ps1 `
    -ProjectDir C:\myapp -VersionCode 7 -VersionName "1.6.0" -MinSdk 24

# extra aapt2 link args are forwarded verbatim
powershell -NoProfile -ExecutionPolicy Bypass -File C:\1\ysp-android\tools\build-apk.ps1 `
    C:\myapp -ExtraAapt2LinkArgs @('--no-version-vectors')
```

Exit codes: `0` success, `1` build/tool failure, `2` bad args or environment.

### Project layout it expects

```
<ProjectDir>/
    AndroidManifest.xml      required
    res/                     optional (aapt2 compile --dir res)
    src/ | java/ | sources/  required, .java scanned recursively
    assets/                  optional, copied into APK root
```

### Output

```
<ProjectDir>/build/<ProjectName>-debug.apk
```

## Manual pipeline (what the script does)

```powershell
$BT = 'C:\AndroidSdkCli\build-tools\34.0.0'
$AJ = 'C:\AndroidSdkCli\platforms\android-34\android.jar'
$JDK = 'C:\re-toolkit\jdk'
$env:JAVA_HOME = $JDK           # REQUIRED for d8.bat / apksigner.bat

# 1. resources
& $BT\aapt2.exe compile --dir res -o build\compiled.zip

# 2. link -> base.apk + generated R.java
& $BT\aapt2.exe link -I $AJ --manifest AndroidManifest.xml --java build\gen `
    -o build\base.apk --min-sdk-version 21 --target-sdk-version 34 `
    --version-code 1 --version-name 1.0 build\compiled.zip

# 3. compile (android.jar via -classpath, NOT -bootclasspath -- see pitfalls)
& $JDK\bin\javac.exe -source 17 -target 17 -encoding UTF-8 `
    -classpath $AJ -d build\classes (src\**\*.java) (build\gen\**\*.java)

# 4. dex -- d8 needs a JAR, not a directory
& $JDK\bin\jar.exe cf build\libs\classes.jar -C build\classes .
& $BT\d8.bat --lib $AJ --min-api 21 --output build\dex build\libs\classes.jar

# 5. fold classes.dex into the APK
copy build\base.apk build\unsigned.apk
& $JDK\bin\jar.exe uf build\unsigned.apk -C build\staging classes.dex

# 6. align then sign (order matters)
& $BT\zipalign.exe -f -p 4 build\unsigned.apk build\aligned.apk
& $BT\apksigner.bat sign --ks tools\debug.keystore --ks-pass pass:android `
    --key-pass pass:android --ks-key-alias androiddebugkey `
    --min-sdk-version 21 --v4-signing-enabled false `
    --out build\app-debug.apk build\aligned.apk

# 7. verify
& $BT\apksigner.bat verify --verbose build\app-debug.apk
& $BT\aapt2.exe dump badging build\app-debug.apk
```

Debug keystore (created automatically by the script if absent):

```powershell
& $JDK\bin\keytool.exe -genkeypair -v -keystore tools\debug.keystore `
    -storetype PKCS12 -storepass android -keypass android `
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
    -dname "CN=Android Debug,O=Android,C=US"
```

## Pitfalls found on this machine

1. **Broken ambient `JAVA_HOME`.** It points at
   `C:\实用软件开发\.android_build_tools\jdk17\jdk-17.0.19+10`, which does not exist.
   `d8.bat` / `apksigner.bat` then fail with *"JAVA_HOME is set to an invalid
   directory"*. The script always overrides `JAVA_HOME` with `-JavaHome`
   (default `C:\re-toolkit\jdk`). `ANDROID_SDK_ROOT` / `ANDROID_HOME` are equally
   stale — pass `--sdk_root` to sdkmanager and `-SdkRoot` to the script.

2. **JDK 21 rejects `-bootclasspath` for target ≥ 9.**
   `javac -source 17 -target 17 -bootclasspath android.jar` →
   `error: option --boot-class-path not allowed with target 17`.
   Use `-classpath android.jar` for Java 8+ targets. Only `-target 8` still
   accepts `-bootclasspath`. The script auto-selects via `-AndroidClasspathMode auto`.
   (`android-34/android.jar` contains the full `java.*` API, so `-classpath`
   works fine and d8 resolves the platform from `--lib`.)

3. **`d8` refuses a directory of `.class` files** —
   `Unsupported source file type ... Compilation failed`. It needs a `.jar`,
   `.zip`, or `.class` file. Always `jar cf classes.jar -C classes .` first.
   Also: the `--output` **directory must already exist**, else
   `Error: Invalid output`.

4. **`jar uf` is the safe way to inject `classes.dex`.** It appends at the
   archive root and leaves existing entries' compression untouched, so
   `resources.arsc` stays `STORED` (Android 11+ / targetSdk ≥ 30 requires that).
   Re-zipping with `Compress-Archive` would recompress everything and break it —
   do not use it for APKs.

5. **Sign after zipalign, never before.** `apksigner` rewrites the APK; signing
   first and aligning after invalidates the signature.

6. **PowerShell 5.1 mangles non-ASCII (CJK) characters in native command
   arguments**, e.g. `aapt2 --dir "C:\x\中文\res"` arrives as mojibake and the
   directory "does not exist". `Start-Process -ArgumentList` mangles them too.
   Keep build paths ASCII-only. (ASCII paths with spaces are fine — tested.)

7. **`&`-invoked native tools write to the `$ErrorActionPreference`-driven error
   stream** and PowerShell wraps each stderr line in a red `NativeCommandError`
   record. The script captures `2>&1 | ForEach-Object { "$_" }` and reads
   `$LASTEXITCODE` explicitly so real exit codes are preserved.

8. **Localized (zh-CN) javac diagnostics** are unreadable mojibake in the
   console. The script passes `-J-Duser.language=en -J-Duser.country=US` for
   English errors. Note: `-Duser.language=en` without the `-J` prefix is
   rejected by javac (`invalid flag`).

9. **`-v4-signing-enabled false`** avoids the `.idsig` sidecar file, which is
   only needed for `adb install --incremental`.

## Hello-world reference build

Project: `C:\1\ysp-android\_hello` (manifest `com.example.hello`, one `MainActivity`
with a green-background `TextView`, no dependencies).

APK: `C:\1\ysp-android\_hello\build\_hello-debug.apk`, 12,698 bytes.

`aapt2 dump badging`:

```
package: name='com.example.hello' versionCode='1' versionName='1.0' platformBuildVersionName='14' platformBuildVersionCode='34' compileSdkVersion='34' compileSdkVersionCodename='14'
sdkVersion:'21'
targetSdkVersion:'34'
application-label:'HelloCli'
application: label='HelloCli' icon=''
launchable-activity: name='com.example.hello.MainActivity'  label='' icon=''
feature-group: label=''
  uses-feature: name='android.hardware.faketouch'
  uses-implied-feature: name='android.hardware.faketouch' reason='default feature for all apps'
main
supports-screens: 'small' 'normal' 'large' 'xlarge'
supports-any-density: 'true'
locales: '--_--'
densities: '160'
```

`apksigner verify --verbose`: v1, v2, v3 all `true` (v3.1/v4 false, expected).

Install with:

```powershell
C:\AndroidSdkCli\platform-tools\adb.exe install -r C:\1\ysp-android\_hello\build\_hello-debug.apk
```
