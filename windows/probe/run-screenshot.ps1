$env:JAVA_HOME = 'C:\re-toolkit\jdk'
$java = "$env:JAVA_HOME\bin\java.exe"
$coreCp = 'C:\1\ysp-android\core\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$probeDir = 'C:\1\ysp-android\windows\probe'
$port = 18903

$gwArgs = @('-Dfile.encoding=UTF-8', '-cp', $coreCp, 'com.gary.ysplive.core.engine.DesktopHarness', '--port', "$port", '--channel', 'cctv1')
$gw = Start-Process -FilePath $java -ArgumentList $gwArgs -RedirectStandardOutput "$probeDir\gw4.log" -RedirectStandardError "$probeDir\gw4.err" -WindowStyle Hidden -PassThru
Write-Host "gateway pid=$($gw.Id)"

$ok = $false
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Seconds 1
    $code = "$(curl.exe -sS -o NUL -w '%{http_code}' --max-time 3 "http://127.0.0.1:$port/health")".Trim()
    if ($code -eq '200') { $ok = $true; break }
}
Write-Host "ready=$ok"
if (-not $ok) { Stop-Process -Id $gw.Id -Force -ErrorAction SilentlyContinue; exit 1 }

for ($i = 0; $i -lt 30; $i++) {
    $code = "$(curl.exe -sS -o NUL -w '%{http_code}' --max-time 25 "http://127.0.0.1:$port/cctv1.m3u8")".Trim()
    if ($code -eq '200') { break }
    Start-Sleep -Seconds 2
}
Write-Host "m3u8 serving"

# serve player.html over HTTP too (file:// may block; gateway cannot serve it yet, use simple python server)
$py = (Get-Command python).Source
$htmlSrv = Start-Process -FilePath $py -ArgumentList @('-m','http.server','18904','--bind','127.0.0.1') -WorkingDirectory $probeDir -WindowStyle Hidden -PassThru
Start-Sleep -Seconds 2
Write-Host "html server pid=$($htmlSrv.Id)"

$edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
$shot = "$probeDir\shot.png"
Remove-Item $shot -Force -ErrorAction SilentlyContinue
$playerUrl = "http://127.0.0.1:$port/cctv1.m3u8"
$pageUrl = "http://127.0.0.1:18904/player.html?src=" + [uri]::EscapeDataString($playerUrl)
Write-Host "page: $pageUrl"

$edgeArgs = @(
    '--headless=new',
    '--disable-gpu',
    '--autoplay-policy=no-user-gesture-required',
    '--window-size=1280,720',
    '--virtual-time-budget=20000',
    "--screenshot=$shot",
    '--user-data-dir=' + "$probeDir\edge-shot-profile",
    $pageUrl
)
$p = Start-Process -FilePath $edge -ArgumentList $edgeArgs -PassThru -WindowStyle Hidden
$p.WaitForExit(60000) | Out-Null
Write-Host "edge exit"

Start-Sleep -Seconds 1
if (Test-Path $shot) {
    $sz = (Get-Item $shot).Length
    Write-Host "SCREENSHOT OK size=$sz bytes"
} else {
    Write-Host "SCREENSHOT MISSING"
}

Stop-Process -Id $htmlSrv.Id -Force -ErrorAction SilentlyContinue
Stop-Process -Id $gw.Id -Force -ErrorAction SilentlyContinue
