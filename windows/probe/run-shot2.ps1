$env:JAVA_HOME = 'C:\re-toolkit\jdk'
$java = "$env:JAVA_HOME\bin\java.exe"
$coreCp = 'C:\1\ysp-android\core\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$probeDir = 'C:\1\ysp-android\windows\probe'
$port = 18905
$htmlPort = 18906

$gwArgs = @('-Dfile.encoding=UTF-8', '-cp', $coreCp, 'com.gary.ysplive.core.engine.DesktopHarness', '--port', "$port", '--channel', 'cctv1')
$gw = Start-Process -FilePath $java -ArgumentList $gwArgs -RedirectStandardOutput "$probeDir\gw5.log" -RedirectStandardError "$probeDir\gw5.err" -WindowStyle Hidden -PassThru
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
Write-Host "gateway ready on $port"

$py = (Get-Command python).Source
$htmlSrv = Start-Process -FilePath $py -ArgumentList @('-m','http.server',"$htmlPort",'--bind','127.0.0.1') -WorkingDirectory $probeDir -WindowStyle Hidden -PassThru
Start-Sleep -Seconds 2

$edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
$shot = "$probeDir\play-shot.png"
Remove-Item $shot -Force -EA SilentlyContinue

$playerUrl = "http://127.0.0.1:$port/cctv1.m3u8"
$pageUrl = "http://127.0.0.1:$htmlPort/player.html?src=" + [uri]::EscapeDataString($playerUrl)
Write-Host "page: $pageUrl"

# direct invocation, NOT Start-Process (arg quoting gets mangled there)
& $edge '--headless=new' '--disable-gpu' '--autoplay-policy=no-user-gesture-required' '--window-size=1280,720' '--virtual-time-budget=25000' "--screenshot=$shot" "--user-data-dir=$probeDir\edge-shot2" $pageUrl 2>&1 | Select-String 'written|ERROR' | Select-Object -First 3
Write-Host "edge done"

Start-Sleep -Seconds 1
if (Test-Path $shot) { Write-Host "SHOT OK size=$((Get-Item $shot).Length)" } else { Write-Host "SHOT MISSING" }

Stop-Process -Id $htmlSrv.Id -Force -EA SilentlyContinue
Stop-Process -Id $gw.Id -Force -EA SilentlyContinue
