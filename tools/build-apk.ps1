<#
.SYNOPSIS
    Pure command-line Android APK builder (aapt2 + javac + d8 + zipalign + apksigner).
    No Gradle, no Android Studio required.

.DESCRIPTION
    Builds a plain-Java Android project directory into a signed debug APK.

    Expected project layout (all relative to -ProjectDir):
        AndroidManifest.xml          (required)
        res/                         (optional; aapt2 --dir res)
        src/  or  java/              (required; Java sources, recursive)
        assets/                      (optional, copied into APK root as assets/)

    Output:
        <OutDir>\<ProjectName>-debug.apk    signed, zipaligned APK
        <OutDir>\unsigned.apk               intermediate
        <OutDir>\base.apk                   aapt2 link output

.PARAMETER ProjectDir
    Project root containing AndroidManifest.xml.

.PARAMETER VersionCode
    android:versionCode / aapt2 --version-code.

.PARAMETER VersionName
    android:versionName / aapt2 --version-name.

.PARAMETER MinSdk
    aapt2 --min-sdk-version and d8 --min-api. Default 21.

.PARAMETER TargetSdk
    aapt2 --target-sdk-version. Default 34.

.PARAMETER OutDir
    Build output directory. Default <ProjectDir>\build.

.PARAMETER SdkRoot
    Android SDK root. Default C:\AndroidSdkCli.

.PARAMETER JavaHome
    JDK root. Default C:\re-toolkit\jdk.

.PARAMETER Keystore
    Debug keystore path. Created if missing.
    Default C:\1\ysp-android\tools\debug.keystore

.PARAMETER ExtraAapt2LinkArgs
    Extra arguments appended to the aapt2 link command line.

.PARAMETER ExtraJavacArgs
    Extra arguments appended to the javac command line.

.PARAMETER ExtraD8Args
    Extra arguments appended to the d8 command line.

.PARAMETER SkipSign
    Produce an unsigned (but zipaligned) APK. Not for installation.

.PARAMETER LogLevel
    quiet | normal | verbose. Default normal.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File C:\1\ysp-android\tools\build-apk.ps1 C:\1\ysp-android\_hello

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File C:\1\ysp-android\tools\build-apk.ps1 `
        -ProjectDir C:\myapp -VersionCode 7 -VersionName "1.6.0" -MinSdk 24

.NOTES
    Exit codes: 0 = success, 1 = build/tool failure, 2 = bad arguments/environment.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$ProjectDir,

    [int]$VersionCode = 1,
    [string]$VersionName = '1.0',

    [int]$MinSdk = 21,
    [int]$TargetSdk = 34,

    [string]$OutDir,
    [string]$SdkRoot = 'C:\AndroidSdkCli',
    [string]$JavaHome = 'C:\re-toolkit\jdk',
    [string]$Keystore = 'C:\1\ysp-android\tools\debug.keystore',
    [string]$BuildToolsVersion = '34.0.0',
    [string]$Platform = 'android-34',

    [string[]]$ExtraAapt2LinkArgs = @(),
    [string[]]$ExtraJavacArgs = @(),
    [string[]]$ExtraD8Args = @(),

    # JDK 9+ forbids -bootclasspath with -source/-target >= 9. Default: auto
    # (use -bootclasspath for target <= 8, -classpath otherwise).
    [ValidateSet('auto', 'bootclasspath', 'classpath')]
    [string]$AndroidClasspathMode = 'auto',

    [string]$JavaSource = '17',
    [string]$JavaTarget = '17',

    [switch]$SkipSign,
    [ValidateSet('quiet', 'normal', 'verbose')]
    [string]$LogLevel = 'normal',

    # Anything else is forwarded to aapt2 link.
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingArgs
)

$ErrorActionPreference = 'Continue'
Set-StrictMode -Version 2.0

# ---------------------------------------------------------------- helpers ----

$script:StepNo = 0

function Write-Step([string]$msg) {
    if ($LogLevel -eq 'quiet') { return }
    $script:StepNo++
    Write-Host ''
    Write-Host ("[{0}] {1}" -f $script:StepNo, $msg) -ForegroundColor Cyan
}

function Write-Detail([string]$msg) {
    if ($LogLevel -eq 'quiet') { return }
    Write-Host "    $msg" -ForegroundColor DarkGray
}

function Write-Warn2([string]$msg) {
    Write-Host "WARN: $msg" -ForegroundColor Yellow
}

function Fail([string]$msg, [int]$code = 1) {
    Write-Host ''
    Write-Host "BUILD FAILED: $msg" -ForegroundColor Red
    exit $code
}

<#
    Run a native executable, capture merged stdout+stderr and the exit code.
    Never throws; caller decides what a non-zero exit means.
    Output lines are returned as plain strings (ErrorRecord noise stripped).
#>
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$Exe,
        [string[]]$Arguments = @(),
        [string]$WorkingDirectory = $null,
        [switch]$EchoOutput
    )

    if (-not (Test-Path -LiteralPath $Exe)) {
        return [pscustomobject]@{
            ExitCode = 127
            Output   = "executable not found: $Exe"
            Command  = $Exe
        }
    }

    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $pushd = $false
    try {
        if ($WorkingDirectory) {
            Push-Location -LiteralPath $WorkingDirectory
            $pushd = $true
        }

        $lines = @()
        $lines = & $Exe @Arguments 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE

        if ($EchoOutput -and $lines) {
            $lines | ForEach-Object {
                if ($LogLevel -eq 'verbose') { Write-Host "      | $_" -ForegroundColor DarkGray }
            }
        }

        $cmdText = "$Exe " + (($Arguments | ForEach-Object { if ($_ -match '\s') { "'$_'" } else { $_ } }) -join ' ')
        return [pscustomobject]@{
            ExitCode = $code
            Output   = ($lines -join [Environment]::NewLine)
            Command  = $cmdText
        }
    } finally {
        if ($pushd) { Pop-Location }
        $ErrorActionPreference = $prev
    }
}

# ------------------------------------------------------- environment check ---

$scriptPath = $PSCommandPath
if (-not $scriptPath) { $scriptPath = $MyInvocation.MyCommand.Definition }

Write-Host '=== Android CLI APK builder ===' -ForegroundColor Green

# Resolve project dir
if (-not (Test-Path -LiteralPath $ProjectDir)) {
    Fail "-ProjectDir does not exist: $ProjectDir" 2
}
$ProjectDir = (Resolve-Path -LiteralPath $ProjectDir).ProviderPath
if (-not $OutDir) { $OutDir = Join-Path $ProjectDir 'build' }

$manifest = Join-Path $ProjectDir 'AndroidManifest.xml'
if (-not (Test-Path -LiteralPath $manifest)) {
    Fail "AndroidManifest.xml not found in $ProjectDir" 2
}

# Resolve source root: src/ then java/ then sources/
$srcRoot = $null
foreach ($cand in @('src', 'java', 'sources')) {
    $p = Join-Path $ProjectDir $cand
    if (Test-Path -LiteralPath $p) { $srcRoot = $p; break }
}
if (-not $srcRoot) {
    Fail "no Java source root found (expected one of: src/, java/, sources/) in $ProjectDir" 2
}

$resDir = Join-Path $ProjectDir 'res'
$hasRes = Test-Path -LiteralPath $resDir

# Toolchain paths
$androidJar = Join-Path $SdkRoot "platforms\$Platform\android.jar"
$buildTools = Join-Path $SdkRoot "build-tools\$BuildToolsVersion"
$aapt2      = Join-Path $buildTools 'aapt2.exe'
$zipalign   = Join-Path $buildTools 'zipalign.exe'
$apksigner  = Join-Path $buildTools 'apksigner.bat'
$d8bat      = Join-Path $buildTools 'd8.bat'

$javac   = Join-Path $JavaHome 'bin\javac.exe'
$jarExe  = Join-Path $JavaHome 'bin\jar.exe'
$keytool = Join-Path $JavaHome 'bin\keytool.exe'

$missing = @()
foreach ($p in @($androidJar, $aapt2, $zipalign, $javac, $jarExe, $keytool)) {
    if (-not (Test-Path -LiteralPath $p)) { $missing += $p }
}
if (-not $SkipSign) {
    foreach ($p in @($apksigner, $d8bat)) {
        if (-not (Test-Path -LiteralPath $p)) { $missing += $p }
    }
}
if ($missing.Count -gt 0) {
    Write-Host 'Missing toolchain components:' -ForegroundColor Red
    $missing | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Fail "run sdkmanager to install platforms;$Platform and build-tools;$BuildToolsVersion" 2
}

# The SDK's .bat wrappers require JAVA_HOME pointing at a REAL JDK.
# The ambient JAVA_HOME on this machine is broken (nonexistent JDK 17 path),
# so always override it for the child processes.
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    Fail "invalid -JavaHome (no bin\java.exe): $JavaHome" 2
}
$env:JAVA_HOME = $JavaHome
$javaExe = Join-Path $JavaHome 'bin\java.exe'

$projectName = Split-Path -Leaf $ProjectDir
Write-Detail "project   : $ProjectDir"
Write-Detail "sources   : $srcRoot"
Write-Detail "resources : $(if ($hasRes) { $resDir } else { '(none)' })"
Write-Detail "sdk       : $SdkRoot"
Write-Detail "jdk       : $JavaHome"
Write-Detail "output    : $OutDir"

# ------------------------------------------------------------- workspace ----

if (Test-Path -LiteralPath $OutDir) {
    Remove-Item -LiteralPath $OutDir -Recurse -Force -ErrorAction SilentlyContinue
    if (Test-Path -LiteralPath $OutDir) { Fail "cannot clear output dir: $OutDir" 1 }
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$genDir     = Join-Path $OutDir 'gen'
$classesDir = Join-Path $OutDir 'classes'
$dexDir     = Join-Path $OutDir 'dex'
$libsDir    = Join-Path $OutDir 'libs'
foreach ($d in @($genDir, $classesDir, $dexDir, $libsDir)) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

$compiledRes = Join-Path $OutDir 'compiled.zip'
$baseApk     = Join-Path $OutDir 'base.apk'
$unsignedApk = Join-Path $OutDir 'unsigned.apk'
$alignedApk  = Join-Path $OutDir 'aligned.apk'
$finalApk    = Join-Path $OutDir "$projectName-debug.apk"

# ------------------------------------------------------------ 1. keystore ---

if (-not $SkipSign) {
    Write-Step 'debug keystore'
    if (Test-Path -LiteralPath $Keystore) {
        Write-Detail "reusing $Keystore"
    } else {
        $ksDir = Split-Path -Parent $Keystore
        if ($ksDir -and -not (Test-Path -LiteralPath $ksDir)) {
            New-Item -ItemType Directory -Force -Path $ksDir | Out-Null
        }
        $r = Invoke-Native -Exe $keytool -Arguments @(
            '-genkeypair', '-v',
            '-keystore', $Keystore,
            '-storetype', 'PKCS12',
            '-storepass', 'android',
            '-keypass', 'android',
            '-alias', 'androiddebugkey',
            '-keyalg', 'RSA',
            '-keysize', '2048',
            '-validity', '10000',
            '-dname', 'CN=Android Debug,O=Android,C=US'
        )
        if ($r.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $Keystore)) {
            Write-Host $r.Output -ForegroundColor Red
            Fail "keytool could not create debug keystore ($Keystore)" 1
        }
        Write-Detail "created $Keystore (alias androiddebugkey, pass android)"
    }
}

# --------------------------------------------------------- 2. aapt2 compile --

Write-Step 'aapt2 compile'
if ($hasRes) {
    $r = Invoke-Native -Exe $aapt2 -Arguments @('compile', '--dir', $resDir, '-o', $compiledRes)
    if ($r.ExitCode -ne 0) {
        Write-Host $r.Output -ForegroundColor Red
        Fail "aapt2 compile failed (exit $($r.ExitCode))" 1
    }
    Write-Detail "compiled resources -> $compiledRes"
} else {
    Write-Detail 'no res/ directory, skipping'
}

# ------------------------------------------------------------ 3. aapt2 link --

Write-Step 'aapt2 link'
$linkArgs = @(
    'link',
    '-I', $androidJar,
    '--manifest', $manifest,
    '--java', $genDir,
    '-o', $baseApk,
    '--min-sdk-version', "$MinSdk",
    '--target-sdk-version', "$TargetSdk",
    '--version-code', "$VersionCode",
    '--version-name', "$VersionName"
)
if ($hasRes) { $linkArgs += $compiledRes }
if ($ExtraAapt2LinkArgs -and $ExtraAapt2LinkArgs.Count -gt 0) { $linkArgs += $ExtraAapt2LinkArgs }
if ($RemainingArgs -and $RemainingArgs.Count -gt 0) { $linkArgs += $RemainingArgs }

$r = Invoke-Native -Exe $aapt2 -Arguments $linkArgs
if ($r.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $baseApk)) {
    Write-Host $r.Output -ForegroundColor Red
    Fail "aapt2 link failed (exit $($r.ExitCode))" 1
}
Write-Detail "base apk -> $baseApk"

# ---------------------------------------------------------------- 4. javac --

Write-Step 'javac'
$javaFiles = @(Get-ChildItem -LiteralPath $srcRoot -Recurse -Filter *.java -File -ErrorAction SilentlyContinue |
               ForEach-Object { $_.FullName })
$genFiles = @(Get-ChildItem -LiteralPath $genDir -Recurse -Filter *.java -File -ErrorAction SilentlyContinue |
              ForEach-Object { $_.FullName })
if ($javaFiles.Count -eq 0) {
    Fail "no .java files under $srcRoot" 1
}
Write-Detail "$($javaFiles.Count) source file(s), $($genFiles.Count) generated (R.java)"

# JDK 9+ rejects -bootclasspath when -target >= 9 ("option --boot-class-path not
# allowed with target 17"). Only target <= 8 still honours it.
$cpMode = $AndroidClasspathMode
if ($cpMode -eq 'auto') {
    $tgtNum = 0
    if ([int]::TryParse($JavaTarget, [ref]$tgtNum) -and $tgtNum -ge 9) { $cpMode = 'classpath' }
    else { $cpMode = 'bootclasspath' }
}
if ($cpMode -eq 'bootclasspath') {
    $cpFlag = '-bootclasspath'
    Write-Detail "android.jar as -bootclasspath (target $JavaTarget)"
} else {
    $cpFlag = '-classpath'
    if ($AndroidClasspathMode -eq 'auto') {
        Write-Detail "android.jar as -classpath (JDK 9+ forbids -bootclasspath with target >= 9)"
    } else {
        Write-Detail "android.jar as -classpath"
    }
}

$javacArgs = @(
    '-source', $JavaSource,
    '-target', $JavaTarget,
    '-encoding', 'UTF-8',
    '-Xlint:-options',
    # Force English diagnostics so build logs are greppable on this zh-CN host.
    '-J-Duser.language=en', '-J-Duser.country=US',
    $cpFlag, $androidJar,
    '-d', $classesDir
)
if ($ExtraJavacArgs -and $ExtraJavacArgs.Count -gt 0) { $javacArgs += $ExtraJavacArgs }
$javacArgs += $javaFiles
$javacArgs += $genFiles

$r = Invoke-Native -Exe $javac -Arguments $javacArgs
if ($r.ExitCode -ne 0) {
    Write-Host $r.Output -ForegroundColor Red
    Fail "javac failed (exit $($r.ExitCode))" 1
}
$classCount = @(Get-ChildItem -LiteralPath $classesDir -Recurse -Filter *.class -File).Count
if ($classCount -eq 0) { Fail "javac produced no .class files" 1 }
Write-Detail "$classCount class file(s)"

# ------------------------------------------------------------------ 5. d8 ---

Write-Step 'd8'
# d8 refuses a bare directory of .class files ("Unsupported source file type");
# it needs a .jar / .zip / .class file. Pack classes into a jar first.
$classesJar = Join-Path $libsDir 'classes.jar'
$r = Invoke-Native -Exe $jarExe -Arguments @('cf', $classesJar, '-C', $classesDir, '.')
if ($r.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $classesJar)) {
    Write-Host $r.Output -ForegroundColor Red
    Fail "jar could not package classes.jar (exit $($r.ExitCode))" 1
}

# d8 --output directory must already exist.
$d8Args = @(
    '--lib', $androidJar,
    '--min-api', "$MinSdk",
    '--output', $dexDir
)
if ($ExtraD8Args -and $ExtraD8Args.Count -gt 0) { $d8Args += $ExtraD8Args }
$d8Args += $classesJar

$r = Invoke-Native -Exe $d8bat -Arguments $d8Args
$dexFile = Join-Path $dexDir 'classes.dex'
if ($r.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $dexFile)) {
    Write-Host $r.Output -ForegroundColor Red
    Fail "d8 failed (exit $($r.ExitCode))" 1
}
Write-Detail "classes.dex ($((Get-Item -LiteralPath $dexFile).Length) bytes)"

# ------------------------------------------------- 6. put dex into the APK ---

Write-Step 'package APK'
Copy-Item -LiteralPath $baseApk -Destination $unsignedApk -Force

# jar uf appends classes.dex at the archive root without disturbing the
# compression method of existing entries (resources.arsc stays STORED, which
# Android 11+ requires for targetSdk >= 30).
$stagingDir = Join-Path $OutDir 'staging'
New-Item -ItemType Directory -Force -Path $stagingDir | Out-Null
Copy-Item -LiteralPath $dexFile -Destination (Join-Path $stagingDir 'classes.dex') -Force

$r = Invoke-Native -Exe $jarExe -Arguments @('uf', $unsignedApk, '-C', $stagingDir, 'classes.dex')
if ($r.ExitCode -ne 0) {
    Write-Host $r.Output -ForegroundColor Red
    Fail "jar could not add classes.dex to the APK (exit $($r.ExitCode))" 1
}

# Copy optional assets/ in as well.
$assetsDir = Join-Path $ProjectDir 'assets'
if (Test-Path -LiteralPath $assetsDir) {
    $r = Invoke-Native -Exe $jarExe -Arguments @('uf', $unsignedApk, '-C', $ProjectDir, 'assets')
    if ($r.ExitCode -ne 0) { Write-Warn2 "could not add assets/ ($($r.Output))" }
    else { Write-Detail 'assets/ added' }
}

Write-Detail "unsigned apk ($((Get-Item -LiteralPath $unsignedApk).Length) bytes)"

# ------------------------------------------------------------ 7. zipalign ---

Write-Step 'zipalign'
$zArgs = @('-f', '-p', '4')
if ($SkipSign) {
    # Nothing will re-write the zip afterwards, so align the final name directly.
    $zArgs += @($unsignedApk, $finalApk)
} else {
    $zArgs += @($unsignedApk, $alignedApk)
}
$r = Invoke-Native -Exe $zipalign -Arguments $zArgs
if ($r.ExitCode -ne 0) {
    Write-Host $r.Output -ForegroundColor Red
    Fail "zipalign failed (exit $($r.ExitCode))" 1
}
Write-Detail 'aligned (4-byte, -p page-aligns .so files)'

# ----------------------------------------------------------- 8. apksigner ---

if ($SkipSign) {
    Write-Step 'signing skipped (-SkipSign)'
    Remove-Item -LiteralPath $unsignedApk -Force -ErrorAction SilentlyContinue
} else {
    Write-Step 'apksigner'
    $signArgs = @(
        'sign',
        '--ks', $Keystore,
        '--ks-pass', 'pass:android',
        '--key-pass', 'pass:android',
        '--ks-key-alias', 'androiddebugkey',
        '--min-sdk-version', "$MinSdk",
        # v4 produces a .idsig sidecar we do not need for sideloading.
        '--v4-signing-enabled', 'false',
        '--out', $finalApk,
        $alignedApk
    )
    $r = Invoke-Native -Exe $apksigner -Arguments $signArgs
    if ($r.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $finalApk)) {
        Write-Host $r.Output -ForegroundColor Red
        Fail "apksigner sign failed (exit $($r.ExitCode))" 1
    }

    # ------------------------------------------------------- 9. verify ------
    Write-Step 'verify'
    $r = Invoke-Native -Exe $apksigner -Arguments @('verify', '--verbose', '--min-sdk-version', "$MinSdk", $finalApk)
    if ($r.ExitCode -ne 0) {
        Write-Host $r.Output -ForegroundColor Red
        Fail "apksigner verify FAILED (exit $($r.ExitCode))" 1
    }
    $schemes = ($r.Output -split "`r?`n" | Where-Object { $_ -match 'Verified using v\d' -and $_ -match 'true' }) -join '; '
    Write-Detail "signature OK: $schemes"

    Remove-Item -LiteralPath $alignedApk -Force -ErrorAction SilentlyContinue
}

Remove-Item -LiteralPath $unsignedApk -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $baseApk -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $stagingDir -Recurse -Force -ErrorAction SilentlyContinue

# --------------------------------------------------------------- summary ---

$finalItem = Get-Item -LiteralPath $finalApk
$sizeBytes = $finalItem.Length
$sizeKb = [math]::Round($sizeBytes / 1024.0, 1)

# package name from the built APK (authoritative)
$pkg = ''
$r = Invoke-Native -Exe $aapt2 -Arguments @('dump', 'badging', $finalApk)
if ($r.ExitCode -eq 0) {
    foreach ($line in ($r.Output -split "`r?`n")) {
        if ($line -match "^package:\s+name='([^']+)'") { $pkg = $Matches[1]; break }
    }
}

Write-Host ''
Write-Host '=== BUILD OK ===' -ForegroundColor Green
Write-Host "APK      : $finalApk"
Write-Host "Size     : $sizeBytes bytes ($sizeKb KiB)"
if ($pkg) { Write-Host "Package  : $pkg" }
Write-Host "Version  : $VersionName ($VersionCode)"
Write-Host "SDK      : min $MinSdk / target $TargetSdk"

exit 0
