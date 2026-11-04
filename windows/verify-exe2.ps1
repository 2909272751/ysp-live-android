$exe = 'C:\1\ysp-android\windows\dist\YspLive\YspLive.exe'
$log = 'C:\1\ysp-android\windows\exe-run.log'

Remove-Item $log -Force -EA SilentlyContinue

Write-Host "=== files present ==="
Write-Host "exe : $(Test-Path $exe)"
Write-Host "vlc : $(Test-Path 'C:\1\ysp-android\windows\dist\YspLive\vlc\libvlc.dll')"

$p = Start-Process -FilePath $exe -RedirectStandardOutput $log -RedirectStandardError "$log.err" -PassThru
Write-Host "pid=$($p.Id)"
Start-Sleep -Seconds 25

if ($p.HasExited) {
    Write-Host "EXITED code=$($p.ExitCode)"
    Get-Content "$log.err" -Encoding utf8 -EA SilentlyContinue | Select-Object -Last 20
    exit 1
}
Write-Host "alive = OK"

Write-Host "=== engine ==="
curl.exe -sS -o NUL -w "  /health      = %{http_code}`n" --max-time 8 "http://127.0.0.1:8767/health"
$m3u = curl.exe -sS --max-time 15 "http://127.0.0.1:8767/all.m3u" 2>&1
Write-Host "  /all.m3u lines = $($m3u.Count)"

Write-Host "=== channel playlist ==="
curl.exe -sS -o NUL -w "  /cctv1.m3u8  = %{http_code}`n" --max-time 25 "http://127.0.0.1:8767/cctv1.m3u8"

Write-Host "=== app log (vlc related) ==="
Get-Content $log -Encoding utf8 -EA SilentlyContinue | Select-String 'vlc|VLC|播放|libVLC' | Select-Object -Last 10

Write-Host "=== app log tail ==="
Get-Content $log -Encoding utf8 -EA SilentlyContinue | Select-Object -Last 15

Stop-Process -Id $p.Id -Force -EA SilentlyContinue
