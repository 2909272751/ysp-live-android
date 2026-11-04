# Full lifecycle test: install -> run -> verify -> uninstall -> verify cleanliness.
# ASCII only.

$setup = 'C:\1\ysp-android\windows\release\YspLive-Setup-9.5.3.exe'
$installDir = "$env:LOCALAPPDATA\Programs\YspLive"
$userData = "$env:APPDATA\YspLive"
$uninsKey = 'HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall\{8F3A9C21-5D74-4B8E-A1C6-2E9F0B7D4A53}_is1'

function Assert($label, $cond) {
    if ($cond) { Write-Host "  PASS  $label" } else { Write-Host "  FAIL  $label"; $script:failed = $true }
}

$failed = $false

Write-Host "########## 0. CLEAN SLATE ##########"
Get-Process YspLive -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Remove-Item $installDir -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item $userData -Recurse -Force -ErrorAction SilentlyContinue
Write-Host "cleaned"

Write-Host ""
Write-Host "########## 1. INSTALL (silent, per-user) ##########"
$sw = [Diagnostics.Stopwatch]::StartNew()
$p = Start-Process -FilePath $setup -ArgumentList @(
    '/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/CURRENTUSER',"/DIR=$installDir"
) -PassThru -Wait
$sw.Stop()
Write-Host "installer exit=$($p.ExitCode) in $([math]::Round($sw.Elapsed.TotalSeconds,1))s"
Assert "exit code 0" ($p.ExitCode -eq 0)
Assert "exe installed" (Test-Path "$installDir\YspLive.exe")
Assert "runtime installed" (Test-Path "$installDir\runtime\release")
Assert "jar installed" (Test-Path "$installDir\app\YspLive-Desktop.jar")
Assert "libVLC installed" (Test-Path "$installDir\vlc\libvlc.dll")
Assert "README installed" (Test-Path "$installDir\README.md")
Assert "uninstaller present" (Test-Path "$installDir\unins000.exe")
if (Test-Path $installDir) {
    $sz = [math]::Round((Get-ChildItem $installDir -Recurse -File | Measure-Object Length -Sum).Sum/1MB,1)
    Write-Host "  installed size = $sz MB"
}

Write-Host ""
Write-Host "########## 2. RUN ##########"
$app = Start-Process -FilePath "$installDir\YspLive.exe" -PassThru
Start-Sleep -Seconds 24
Assert "process alive" (-not $app.HasExited)
if (-not $app.HasExited) {
    $code = "$(curl.exe -sS -o NUL -w '%{http_code}' --max-time 8 'http://127.0.0.1:8767/health')".Trim()
    Assert "health 200" ($code -eq '200')
    $m3u = curl.exe -sS --max-time 15 "http://127.0.0.1:8767/all.m3u" 2>&1
    Write-Host "  all.m3u lines = $($m3u.Count)"
    Assert "all.m3u has channels" ($m3u.Count -gt 120)
    Assert "userdata created" (Test-Path $userData)
    Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

Write-Host ""
Write-Host "########## 3. UNINSTALL (silent, keep data) ##########"
if (Test-Path $userData) { 'marker' | Out-File "$userData\__marker.txt" -Encoding ascii }
$sw2 = [Diagnostics.Stopwatch]::StartNew()
$u = Start-Process -FilePath "$installDir\unins000.exe" -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART') -PassThru
$exited = $u.WaitForExit(90000)
$sw2.Stop()
Write-Host "uninstaller exited=$exited in $([math]::Round($sw2.Elapsed.TotalSeconds,1))s"
Assert "uninstaller did not hang" $exited
if (-not $exited) { $u.Kill() }
Start-Sleep -Seconds 3

Assert "install dir removed" (-not (Test-Path $installDir))
Assert "user data kept" (Test-Path $userData)
Assert "marker survived" (Test-Path "$userData\__marker.txt")
Assert "registry entry removed" (-not (Test-Path $uninsKey))
$smDir = "$env:APPDATA\Microsoft\Windows\Start Menu\Programs\" + [char]0x592E + [char]0x89C6 + [char]0x9891 + [char]0x76F4 + [char]0x64AD
Assert "start menu removed" (-not (Test-Path $smDir))

Write-Host ""
Write-Host "########## 4. REINSTALL + UNINSTALL WITH /REMOVEDATA ##########"
$p3 = Start-Process -FilePath $setup -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/CURRENTUSER',"/DIR=$installDir") -PassThru -Wait
Assert "reinstall ok" ($p3.ExitCode -eq 0)
Start-Sleep -Seconds 2
Assert "user data still there from before" (Test-Path $userData)

$u2 = Start-Process -FilePath "$installDir\unins000.exe" -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/REMOVEDATA') -PassThru
$exited2 = $u2.WaitForExit(90000)
Write-Host "uninstaller(/REMOVEDATA) exited=$exited2"
Assert "did not hang" $exited2
if (-not $exited2) { $u2.Kill() }
Start-Sleep -Seconds 3

Assert "install dir removed (2)" (-not (Test-Path $installDir))
Assert "user data removed via /REMOVEDATA" (-not (Test-Path $userData))

Write-Host ""
if ($failed) { Write-Host "########## RESULT: SOME CHECKS FAILED ##########" }
else { Write-Host "########## RESULT: ALL CHECKS PASSED ##########" }
