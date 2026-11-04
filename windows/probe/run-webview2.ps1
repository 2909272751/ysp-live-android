# Start gateway -> run WebView HLS probe -> stop gateway
# NOTE: ASCII only. PowerShell 5.1 misparses UTF-8-no-BOM files with CJK comments.
$env:JAVA_HOME = 'C:\re-toolkit\jdk'
$libDir = 'C:\1\ysp-android\_fxtest\lib'
$java = "$env:JAVA_HOME\bin\java.exe"
$coreCp = 'C:\1\ysp-android\core\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes'
$port = 18901
$probeDir = 'C:\1\ysp-android\windows\probe'

$gwArgs = @('-Dfile.encoding=UTF-8', '-cp', $coreCp, 'com.gary.ysplive.core.engine.DesktopHarness', '--port', "$port", '--channel', 'cctv1')
$gw = Start-Process -FilePath $java -ArgumentList $gwArgs -RedirectStandardOutput "$probeDir\gw2.log" -RedirectStandardError "$probeDir\gw2.err" -WindowStyle Hidden -PassThru
Write-Host "gateway pid=$($gw.Id) waiting..."

$ok = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 1
    $code = curl.exe -sS -o NUL -w "%{http_code}" --max-time 3 "http://127.0.0.1:$port/health"
    $code = "$code".Trim()
    if ($code -eq '200') { $ok = $true; break }
}
Write-Host "gateway ready=$ok"
if (-not $ok) { Stop-Process -Id $gw.Id -Force -ErrorAction SilentlyContinue; exit 1 }

for ($i = 0; $i -lt 30; $i++) {
    $code = curl.exe -sS -o NUL -w "%{http_code}" --max-time 25 "http://127.0.0.1:$port/cctv1.m3u8"
    $code = "$code".Trim()
    if ($code -eq '200') { Write-Host "m3u8 ready"; break }
    Start-Sleep -Seconds 2
}

Write-Host "=== CORS on m3u8 ==="
curl.exe -sS -D - -o NUL --max-time 20 "http://127.0.0.1:$port/cctv1.m3u8" | Select-String 'Access-Control|HTTP/'
Write-Host "=== OPTIONS preflight ==="
curl.exe -sS -X OPTIONS -D - -o NUL --max-time 10 "http://127.0.0.1:$port/cctv1.m3u8" | Select-String 'Access-Control|HTTP/'

Set-Location $probeDir
$lib = (Get-ChildItem "$libDir\*.jar" | ForEach-Object { $_.FullName }) -join ';'
$wxArgs = @('-Dfile.encoding=UTF-8', '-cp', "out;$lib", '--module-path', $lib, '--add-modules', 'javafx.controls,javafx.web', 'WebViewProbe', "$libDir\hls.min.js", "http://127.0.0.1:$port/cctv2.m3u8")
& $java @wxArgs 2>&1 | Out-File "$probeDir\probe-wv2.log" -Encoding utf8
Write-Host "PROBE EXIT=$LASTEXITCODE"
Get-Content "$probeDir\probe-wv2.log" -Encoding utf8

Stop-Process -Id $gw.Id -Force -ErrorAction SilentlyContinue
