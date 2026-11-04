$env:JAVA_HOME = 'C:\re-toolkit\jdk'
$vlcRoot = 'C:\1\ysp-android\_vlc'
$vlcDir = "$vlcRoot\minimal"
$probeDir = 'C:\1\ysp-android\windows\probe'
$java = "$env:JAVA_HOME\bin\java.exe"
$javac = "$env:JAVA_HOME\bin\javac.exe"

$jars = @(
    "$vlcRoot\vlcj.jar",
    "$vlcRoot\vlcj-natives.jar",
    "$vlcRoot\jna.jar",
    "$vlcRoot\jna-platform.jar"
) -join ';'

Set-Location $probeDir

& $javac -encoding UTF-8 -cp $jars -d vlc-out VlcProbe.java 2>&1 | Out-File vlc-compile.log -Encoding utf8
if ($LASTEXITCODE -ne 0) { Write-Host "COMPILE FAILED"; Get-Content vlc-compile.log; exit 1 }
Write-Host "compile ok"

$coreCp = 'C:\1\ysp-android\core\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$port = 18912
$gwArgs = @('-Dfile.encoding=UTF-8', '-cp', $coreCp, 'com.gary.ysplive.core.engine.DesktopHarness', '--port', "$port", '--channel', 'cctv1')
$gw = Start-Process -FilePath $java -ArgumentList $gwArgs -RedirectStandardOutput "$probeDir\gwv.log" -RedirectStandardError "$probeDir\gwv.err" -WindowStyle Hidden -PassThru
$ok = $false
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Seconds 1
    if ("$(curl.exe -sS -o NUL -w '%{http_code}' --max-time 3 "http://127.0.0.1:$port/health")".Trim() -eq '200') { $ok = $true; break }
}
if (-not $ok) { Write-Host "gateway failed"; Stop-Process -Id $gw.Id -Force -EA SilentlyContinue; exit 1 }
for ($i = 0; $i -lt 30; $i++) {
    if ("$(curl.exe -sS -o NUL -w '%{http_code}' --max-time 25 "http://127.0.0.1:$port/cctv1.m3u8")".Trim() -eq '200') { break }
    Start-Sleep -Seconds 2
}
Write-Host "gateway ready"

$env:JAVA_TOOL_OPTIONS = "-Djna.library.path=$vlcDir -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8"
$cp = "vlc-out;$jars"
& $java -cp $cp VlcProbe $vlcDir "http://127.0.0.1:$port/cctv1.m3u8" 2>&1 | Out-File vlc-probe.log -Encoding utf8
Write-Host "EXIT=$LASTEXITCODE"
Get-Content vlc-probe.log -Encoding utf8 | Select-Object -Last 40
$env:JAVA_TOOL_OPTIONS = $null

Stop-Process -Id $gw.Id -Force -EA SilentlyContinue
