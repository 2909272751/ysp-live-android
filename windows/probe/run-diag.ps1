$env:JAVA_HOME = 'C:\re-toolkit\jdk'
$java = "$env:JAVA_HOME\bin\java.exe"
$coreCp = 'C:\1\ysp-android\core\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$probeDir = 'C:\1\ysp-android\windows\probe'
$port = 18907
$htmlPort = 18908

$gwArgs = @('-Dfile.encoding=UTF-8', '-cp', $coreCp, 'com.gary.ysplive.core.engine.DesktopHarness', '--port', "$port", '--channel', 'cctv6')
$gw = Start-Process -FilePath $java -ArgumentList $gwArgs -RedirectStandardOutput "$probeDir\gw6.log" -RedirectStandardError "$probeDir\gw6.err" -WindowStyle Hidden -PassThru
$ok = $false
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Seconds 1
    if ("$(curl.exe -sS -o NUL -w '%{http_code}' --max-time 3 "http://127.0.0.1:$port/health")".Trim() -eq '200') { $ok = $true; break }
}
if (-not $ok) { Write-Host "gw fail"; Stop-Process -Id $gw.Id -Force -EA SilentlyContinue; exit 1 }
for ($i = 0; $i -lt 30; $i++) {
    if ("$(curl.exe -sS -o NUL -w '%{http_code}' --max-time 25 "http://127.0.0.1:$port/cctv6.m3u8")".Trim() -eq '200') { break }
    Start-Sleep -Seconds 2
}
Write-Host "gateway ready"

$py = (Get-Command python).Source
$htmlSrv = Start-Process -FilePath $py -ArgumentList @('-m','http.server',"$htmlPort",'--bind','127.0.0.1') -WorkingDirectory $probeDir -WindowStyle Hidden -PassThru
Start-Sleep -Seconds 2

$edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
$shot = "$probeDir\diag-shot.png"
Remove-Item $shot -Force -EA SilentlyContinue
$pageUrl = "http://127.0.0.1:$htmlPort/diag.html?src=" + [uri]::EscapeDataString("http://127.0.0.1:$port/cctv6.m3u8")
Write-Host "page: $pageUrl"

& $edge '--headless=new' '--disable-gpu' '--autoplay-policy=no-user-gesture-required' '--window-size=1100,900' '--virtual-time-budget=30000' "--screenshot=$shot" "--user-data-dir=$probeDir\edge-diag" $pageUrl 2>&1 | Select-String 'written' | Select-Object -First 2
Write-Host "settled"

Start-Sleep -Seconds 1
if (Test-Path $shot) { Write-Host "SHOT size=$((Get-Item $shot).Length)" }

Stop-Process -Id $htmlSrv.Id -Force -EA SilentlyContinue
Stop-Process -Id $gw.Id -Force -EA SilentlyContinue
