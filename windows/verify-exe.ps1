$exe = 'C:\1\ysp-android\windows\dist\YspLive\YspLive.exe'
if (-not (Test-Path $exe)) { Write-Host "EXE MISSING"; exit 1 }

Write-Host "exe size=$((Get-Item $exe).Length)"
Write-Host "app image size=$([math]::Round((Get-ChildItem 'C:\1\ysp-android\windows\dist\YspLive' -Recurse -File | Measure-Object Length -Sum).Sum / 1MB, 1)) MB"

$p = Start-Process -FilePath $exe -PassThru
Write-Host "started pid=$($p.Id)"
Start-Sleep -Seconds 25

if ($p.HasExited) {
    Write-Host "EXITED code=$($p.ExitCode)"
    exit 1
}
Write-Host "process alive = OK"

Write-Host "=== gateway reachable? ==="
curl.exe -sS -o NUL -w "gateway /health = %{http_code}`n" --max-time 8 "http://127.0.0.1:8767/health"
curl.exe -sS --max-time 10 "http://127.0.0.1:8767/health" 2>&1 | Select-String 'version|channels|ready'

Write-Host "=== all.m3u line count ==="
$m3u = curl.exe -sS --max-time 15 "http://127.0.0.1:8767/all.m3u" 2>&1
Write-Host "lines=$($m3u.Count)"

Stop-Process -Id $p.Id -Force -EA SilentlyContinue
Write-Host "stopped"
