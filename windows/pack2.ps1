$env:JAVA_HOME = 'C:\re-toolkit\jdk'
$dist = 'C:\1\ysp-android\windows\dist'
$inputDir = 'C:\1\ysp-android\windows\jpackage-input'
$vlcSrc = 'C:\1\ysp-android\_vlc\minimal'
$jar = 'C:\1\ysp-android\windows\build\libs\YspLive-Desktop.jar'

Remove-Item $dist -Recurse -Force -EA SilentlyContinue
Remove-Item $inputDir -Recurse -Force -EA SilentlyContinue
New-Item -ItemType Directory -Force -Path $dist, $inputDir | Out-Null

Copy-Item $jar "$inputDir\YspLive-Desktop.jar"

# jpackage app-image: use the built jar + bundled libVLC as an extra resource dir
$jpArgs = @(
    '--type', 'app-image',
    '--name', 'YspLive',
    '--app-version', '9.5.7',
    '--input', $inputDir,
    '--main-jar', 'YspLive-Desktop.jar',
    '--main-class', 'com.gary.ysplive.desktop.Launcher',
    '--dest', $dist,
    '--java-options', '-Dfile.encoding=UTF-8',
    '--java-options', '-Dstdout.encoding=UTF-8',
    '--java-options', '-Dstderr.encoding=UTF-8',
    # Try D3D hardware first, fall back to software automatically.
    # Forcing 'sw' (as earlier builds did) disables GPU acceleration on every
    # machine and is the main cause of the reported UI lag / unresponsive hover.
    '--java-options', '-Dprism.order=d3d,sw',
    # Keep the UI thread responsive: modern GC + modest fixed heap so we do not
    # spend time growing/collecting during scrolling.
    '--java-options', '-XX:+UseG1GC',
    '--java-options', '-XX:MaxGCPauseMillis=20',
    '--java-options', '-Xmx512m'
    # NOTE 1: do NOT pass --win-console (would create PE subsystem=3 CONSOLE
    #         and a black console window that stays up).
)
& "$env:JAVA_HOME\bin\jpackage.exe" @jpArgs 2>&1 | Select-Object -Last 10
if ($LASTEXITCODE -ne 0) { Write-Host "JPACKAGE FAILED"; exit 1 }

# copy libVLC next to the exe
$vlcDst = "$dist\YspLive\vlc"
if (-not (Test-Path $vlcSrc)) { Write-Host "NO libVLC at $vlcSrc"; exit 1 }
Copy-Item $vlcSrc $vlcDst -Recurse -Force
Write-Host "libVLC copied to $vlcDst"

# README
Copy-Item 'C:\1\ysp-android\windows\README.md' "$dist\YspLive\README.md" -Force

$total = (Get-ChildItem "$dist\YspLive" -Recurse -File | Measure-Object Length -Sum).Sum
$vlcSize = (Get-ChildItem $vlcDst -Recurse -File | Measure-Object Length -Sum).Sum
Write-Host "runtime=$([math]::Round(((Get-ChildItem "$dist\YspLive\runtime" -Recurse -File | Measure-Object Length -Sum).Sum)/1MB,1)) MB"
Write-Host "vlc    =$([math]::Round($vlcSize/1MB,1)) MB"
Write-Host "total  =$([math]::Round($total/1MB,1)) MB"
if (Test-Path "$dist\YspLive\YspLive.exe") { Write-Host "EXE OK $((Get-Item "$dist\YspLive\YspLive.exe").Length) bytes" }
