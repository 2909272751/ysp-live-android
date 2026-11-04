$env:JAVA_HOME = 'C:\re-toolkit\jdk'
$jar = 'C:\1\ysp-android\windows\build\libs\YspLive-Desktop.jar'
$distDir = 'C:\1\ysp-android\windows\dist'
$inputDir = 'C:\1\ysp-android\windows\jpackage-input'

Remove-Item $distDir -Recurse -Force -EA SilentlyContinue
Remove-Item $inputDir -Recurse -Force -EA SilentlyContinue
New-Item -ItemType Directory -Force -Path $distDir, $inputDir | Out-Null

Copy-Item $jar "$inputDir\YspLive-Desktop.jar"

# ASCII-only args: description/vendor with CJK get mangled by PS 5.1 file decoding.
$jpArgs = @(
    '--type', 'app-image',
    '--name', 'YspLive',
    '--app-version', '9.5.3',
    '--input', $inputDir,
    '--main-jar', 'YspLive-Desktop.jar',
    '--main-class', 'com.gary.ysplive.desktop.Launcher',
    '--dest', $distDir,
    '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', '-Dstdout.encoding=UTF-8',
    '--java-options', '-Dprism.order=sw',
    '--win-console'
)

& "$env:JAVA_HOME\bin\jpackage.exe" @jpArgs 2>&1 | Select-Object -Last 25
Write-Host "JPACKAGE EXIT=$LASTEXITCODE"

if (Test-Path "$distDir\YspLive") {
    Write-Host "=== app image built ==="
    Get-ChildItem "$distDir\YspLive" | Select-Object Name,Mode
    if (Test-Path "$distDir\YspLive\YspLive.exe") {
        Write-Host "EXE OK: $((Get-Item "$distDir\YspLive\YspLive.exe").Length) bytes"
    }
}
