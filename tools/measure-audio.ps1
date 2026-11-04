# 测量系统是否真的在输出声音（WASAPI 峰值）
# ASCII only - PS 5.1 mangles CJK in UTF-8-no-BOM files.
Write-Host "=== add type ==="
Add-Type -Path 'C:\1\ysp-android\tools\AudioPeak.cs'
if (-not ('AudioPeak' -as [type])) { Write-Host "TYPE LOAD FAILED"; exit 1 }

Write-Host "=== baseline (idle) ==="
$base = @()
for ($i = 0; $i -lt 6; $i++) { $base += [AudioPeak]::ReadPeak(); Start-Sleep -Milliseconds 200 }
$baseMax = ($base | Measure-Object -Maximum).Maximum
Write-Host ("baseline peak max = {0}" -f $baseMax)

Write-Host "=== launch app ==="
Get-Process YspLive,javaw,java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2
Start-Process -FilePath 'C:\1\ysp-android\windows\dist\YspLive\YspLive.exe'
Start-Sleep -Seconds 20
Write-Host "app launched"

Write-Host "=== now click a channel via UI automation ==="
# 用 UIA 找到 CCTV-1 卡片并点击（通过 Invoke 或坐标）
$exe = Get-Process YspLive -ErrorAction SilentlyContinue | Select-Object -First 1
Write-Host ("app pid = {0}" -f $exe.Id)

Write-Host "=== sample peak for 25s (waiting for user/automation to start playback) ==="
$peaks = @()
for ($i = 0; $i -lt 50; $i++) {
    $p = [AudioPeak]::ReadPeak()
    $peaks += $p
    if ($i % 5 -eq 0) { Write-Host ("  t={0,2}s peak={1}" -f ($i/2), $p) }
    Start-Sleep -Milliseconds 500
}
$max = ($peaks | Measure-Object -Maximum).Maximum
$avg = ($peaks | Measure-Object -Average).Average
Write-Host ""
Write-Host ("RESULT: baselineMax={0}  playbackMax={1}  avg={2}" -f $baseMax, $max, $avg)
if ($max -gt 0.001 -and $max -gt ($baseMax * 3)) {
    Write-Host "AUDIO: PRODUCING SOUND (peak clearly above baseline)"
} else {
    Write-Host "AUDIO: NO SOUND DETECTED"
}
