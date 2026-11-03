<#
.SYNOPSIS
    One-command Gradle wrapper for the pure-CLI Android toolchain on this machine.

.DESCRIPTION
    Sets up the environment (JAVA_HOME, ANDROID_SDK_ROOT/ANDROID_HOME,
    GRADLE_USER_HOME), then forwards every remaining argument to the
    locally installed Gradle 8.9 launcher.

    This exists because the ambient environment on this box is broken:
    JAVA_HOME and ANDROID_SDK_ROOT point into a non-existent
    "C:\实用软件开发\.android_build_tools\..." tree.

.EXAMPLE
    # from a project directory containing settings.gradle
    & C:\1\ysp-android\tools\gradlew.ps1 assembleDebug -x lint

.EXAMPLE
    # target another project explicitly
    & C:\1\ysp-android\tools\gradlew.ps1 -ProjectDir C:\1\ysp-android\app assembleDebug

.EXAMPLE
    # release build, clean first
    & C:\1\ysp-android\tools\gradlew.ps1 clean assembleRelease

.NOTES
    Exit code is Gradle's exit code.
    Builds use --no-daemon by default so output can be captured/redirected
    safely; pass -WithDaemon for interactive builds.
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    # Project (module root) directory. Defaults to the current working directory.
    [string]$ProjectDir = (Get-Location).ProviderPath,

    # Opt back in to the Gradle daemon. Off by default because a live daemon
    # inherits stdout/stderr and makes redirected/captured calls hang.
    [switch]$WithDaemon,

    # Print the resolved environment and command, then run.
    [switch]$ShowEnv,

    # Everything else goes to Gradle verbatim.
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArgs
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------- config ----
$GradleHome   = 'C:\1\ysp-android\tools\gradle-8.9'
$GradleBat    = Join-Path $GradleHome 'bin\gradle.bat'
$JavaHome     = 'C:\re-toolkit\jdk'
$AndroidSdk   = 'C:\AndroidSdkCli'

# Keep the Gradle cache on a non-user path so every agent/process shares it.
$GradleUserHome = 'C:\1\ysp-android\tools\gradle-home'

# ----------------------------------------------------------------- checks ---
if (-not (Test-Path -LiteralPath $GradleBat)) {
    Write-Host "ERROR: Gradle not found at $GradleBat" -ForegroundColor Red
    Write-Host "       Expected an extracted gradle-8.9 distribution (bin\gradle.bat)." -ForegroundColor Red
    exit 2
}
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    Write-Host "ERROR: JDK not found at $JavaHome" -ForegroundColor Red
    exit 2
}
if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk 'platforms\android-34\android.jar'))) {
    Write-Host "ERROR: Android SDK not found at $AndroidSdk (missing platforms\android-34)" -ForegroundColor Red
    exit 2
}
if (-not (Test-Path -LiteralPath $ProjectDir)) {
    Write-Host "ERROR: -ProjectDir does not exist: $ProjectDir" -ForegroundColor Red
    exit 2
}

$ProjectDir = (Resolve-Path -LiteralPath $ProjectDir).ProviderPath
if (-not (Test-Path -LiteralPath (Join-Path $ProjectDir 'settings.gradle')) -and
    -not (Test-Path -LiteralPath (Join-Path $ProjectDir 'settings.gradle.kts'))) {
    Write-Host "WARNING: no settings.gradle in $ProjectDir - Gradle will search parent dirs." -ForegroundColor Yellow
}

# ------------------------------------------------------------ environment ---
# Always override the broken ambient values.
$env:JAVA_HOME        = $JavaHome
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:ANDROID_HOME     = $AndroidSdk
$env:GRADLE_USER_HOME = $GradleUserHome

if (-not (Test-Path -LiteralPath $GradleUserHome)) {
    New-Item -ItemType Directory -Force -Path $GradleUserHome | Out-Null
}

# Give the Gradle JVM a sane heap and English diagnostics by default.
if (-not $env:GRADLE_OPTS) {
    $env:GRADLE_OPTS = '-Dfile.encoding=UTF-8 -Duser.language=en -Duser.country=US'
}

# Write local.properties if the project has none, so AGP can find the SDK.
$localProps = Join-Path $ProjectDir 'local.properties'
if (-not (Test-Path -LiteralPath $localProps)) {
    if (Test-Path -LiteralPath (Join-Path $ProjectDir 'settings.gradle')) {
        $sdkEscaped = $AndroidSdk -replace '\\', '\\'
        Set-Content -LiteralPath $localProps -Encoding ASCII -Value "sdk.dir=$($sdkEscaped)"
        Write-Host "wrote $localProps (sdk.dir=$AndroidSdk)" -ForegroundColor DarkGray
    }
}

# ---------------------------------------------------------------- execute ---
# PowerShell mangles an unquoted -Dorg.gradle.jvmargs=... . Depending on how it
# is parsed the argv arrives as ".gradle.jvmargs=-Xmx2g" (leading "-Dorg" eaten)
# or "org.gradle.jvmargs=-Xmx2g", and Gradle then reports
# `Task '.gradle.jvmargs=-Xmx2g' not found`. Rebuild the canonical form here so
# callers can pass it either way.
$normalized = @()
foreach ($a in @($GradleArgs)) {
    if (-not $a) { continue }
    if ($a -match '^\.?org\.gradle\.([\w.]+=[^=].*)$') {
        $normalized += "-Dorg.gradle.$($Matches[1])"
    } elseif ($a -match '^\.gradle\.([\w.]+=[^=].*)$') {
        # "-Dorg" was eaten, leaving the leading dot.
        $normalized += "-Dorg.gradle.$($Matches[1])"
    } elseif ($a -match '^org\.gradle\.([\w.]+=[^=].*)$') {
        $normalized += "-Dorg.gradle.$($Matches[1])"
    } elseif ($a -eq '-D') {
        continue
    } else {
        $normalized += $a
    }
}

$gradleArgList = @('--console=plain')

# Daemon handling.
# A live Gradle daemon inherits this process's stdout/stderr handles. When the
# caller captures or redirects output (`*> log`, `| Out-String`, a CI harness),
# the daemon keeps those handles open after Gradle exits, so the redirect never
# sees EOF and the parent call hangs indefinitely. Observed repeatedly here.
# Default to --no-daemon; opt back in with -WithDaemon for interactive use.
$overridesJvmArgs = @($normalized | Where-Object { $_ -match '^-Dorg\.gradle\.jvmargs=' }).Count -gt 0
if ($overridesJvmArgs) {
    Write-Host 'note: org.gradle.jvmargs override detected -> using --no-daemon' -ForegroundColor DarkGray
}
if ($WithDaemon -and -not $overridesJvmArgs) {
    Write-Host 'note: -WithDaemon requested; output capture may hang if redirected' -ForegroundColor Yellow
} else {
    $gradleArgList += '--no-daemon'
}

if ($normalized.Count -gt 0) { $gradleArgList += $normalized }

if ($ShowEnv) {
    Write-Host '--- gradlew.ps1 environment ---' -ForegroundColor Cyan
    Write-Host "  JAVA_HOME        = $env:JAVA_HOME"
    Write-Host "  ANDROID_SDK_ROOT = $env:ANDROID_SDK_ROOT"
    Write-Host "  GRADLE_USER_HOME = $env:GRADLE_USER_HOME"
    Write-Host "  GRADLE_OPTS      = $env:GRADLE_OPTS"
    Write-Host "  ProjectDir       = $ProjectDir"
    Write-Host "  Command          = $GradleBat $($gradleArgList -join ' ')"
    Write-Host '-------------------------------' -ForegroundColor Cyan
}

Push-Location -LiteralPath $ProjectDir
try {
    & $GradleBat @gradleArgList
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

if ($code -ne 0) {
    Write-Host ''
    Write-Host "GRADLE FAILED (exit $code)" -ForegroundColor Red
}
exit $code
