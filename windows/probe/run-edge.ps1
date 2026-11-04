$env:JAVA_HOME = 'C:\re-toolkit\jdk'
$java = "$env:JAVA_HOME\bin\java.exe"
$coreCp = 'C:\1\ysp-android\core\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$probeDir = 'C:\1\ysp-android\windows\probe'
$libDir = 'C:\1\ysp-android\_fxtest\lib'
$port = 18902

# 1) start gateway
$gwArgs = @('-Dfile.encoding=UTF-8', '-cp', $coreCp, 'com.gary.ysplive.core.engine.DesktopHarness', '--port', "$port", '--channel', 'cctv1')
$gw = Start-Process -FilePath $java -ArgumentList $gwArgs -RedirectStandardOutput "$probeDir\gw3.log" -RedirectStandardError "$probeDir\gw3.err" -WindowStyle Hidden -PassThru
Write-Host "gateway pid=$($gw.Id)"

$ok = $false
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Seconds 1
    $code = "$(curl.exe -sS -o NUL -w '%{http_code}' --max-time 3 "http://127.0.0.1:$port/health")".Trim()
    if ($code -eq '200') { $ok = $true; break }
}
Write-Host "gateway ready=$ok"
if (-not $ok) { Stop-Process -Id $gw.Id -Force -ErrorAction SilentlyContinue; exit 1 }

for ($i = 0; $i -lt 30; $i++) {
    $code = "$(curl.exe -sS -o NUL -w '%{http_code}' --max-time 25 "http://127.0.0.1:$port/cctv1.m3u8")".Trim()
    if ($code -eq '200') { break }
    Start-Sleep -Seconds 2
}
Write-Host "gateway serving"

# 2) launch Edge in app mode pointing at local player page
$edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
$playerUrl = "http://127.0.0.1:$port/cctv1.m3u8"
$htmlUrl = (New-Object System.Uri ("file:///" + ($probeDir -replace '\\','/') + "/player.html?src=" + [uri]::EscapeDataString($playerUrl))).AbsoluteUri
Write-Host "open: $htmlUrl"

$edgeArgs = @(
    '--app=' + $htmlUrl,
    '--window-size=1280,720',
    '--autoplay-policy=no-user-gesture-required',
    '--disable-features=CalculateNativeWinOcclusion',
    '--user-data-dir=' + "$probeDir\edge-profile"
)
$edgeProc = Start-Process -FilePath $edge -ArgumentList $edgeArgs -PassThru
Write-Host "edge pid=$($edgeProc.Id), waiting 25s..."
Start-Sleep -Seconds 25
Stop-Process -Id $edgeProc.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $gw.Id -Force -ErrorAction SilentlyContinue
Write-Host "done"
