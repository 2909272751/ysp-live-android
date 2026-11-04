# Full verification of the performance/interaction build.
# ASCII only.

# 自动取 release 目录里最新的安装包，避免版本号变更后测试失效
$setup = Get-ChildItem 'C:\1\ysp-android\windows\release\YspLive-Setup-*.exe' |
         Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
if (-not $setup) { Write-Host "NO SETUP FOUND"; exit 1 }
Write-Host "setup = $setup"
$installDir = "$env:LOCALAPPDATA\Programs\YspLive"
$env:JAVA_HOME = 'C:\re-toolkit\jdk'
$failed = $false

function Assert($label, $cond) {
    if ($cond) { Write-Host "  PASS  $label" } else { Write-Host "  FAIL  $label"; $script:failed = $true }
}

Write-Host "########## 1. PACKAGING CONFIG ##########"
$cfg = Get-Content 'C:\1\ysp-android\windows\dist\YspLive\app\YspLive.cfg' -Encoding utf8 -Raw
Assert "prism uses d3d,sw (hardware first)" ($cfg -match 'prism\.order=d3d,sw')
Assert "no forced software rendering" (-not ($cfg -match 'prism\.order=sw\s'))
Assert "G1GC enabled" ($cfg -match 'UseG1GC')
Assert "GC pause target set" ($cfg -match 'MaxGCPauseMillis')

$bytes = [System.IO.File]::ReadAllBytes('C:\1\ysp-android\windows\dist\YspLive\YspLive.exe')
$peOff = [BitConverter]::ToInt32($bytes, 0x3C)
$subsystem = [BitConverter]::ToUInt16($bytes, $peOff + 0x5C)
Assert "GUI subsystem (no console window)" ($subsystem -eq 2)

Write-Host ""
Write-Host "########## 2. INSTALL ##########"
Get-Process YspLive -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Remove-Item $installDir -Recurse -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1
$p = Start-Process -FilePath $setup -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/CURRENTUSER',"/DIR=$installDir") -PassThru -Wait
Assert "installer exit 0" ($p.ExitCode -eq 0)
Assert "exe present" (Test-Path "$installDir\YspLive.exe")
Assert "libVLC present" (Test-Path "$installDir\vlc\libvlc.dll")

Write-Host ""
Write-Host "########## 3. RUN + ENGINE ##########"
$app = Start-Process -FilePath "$installDir\YspLive.exe" -PassThru
Start-Sleep -Seconds 24
Assert "process alive" (-not $app.HasExited)
if (-not $app.HasExited) {
    $code = "$(curl.exe -sS -o NUL -w '%{http_code}' --max-time 8 'http://127.0.0.1:8767/health')".Trim()
    Assert "engine health 200" ($code -eq '200')
    $m3u = curl.exe -sS --max-time 15 "http://127.0.0.1:8767/all.m3u" 2>&1
    Assert "channel list > 120 lines" ($m3u.Count -gt 120)
    Assert "log file written" (Test-Path "$env:APPDATA\YspLive\logs\ysp-live.log")
    Stop-Process -Id $app.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
}

Write-Host ""
Write-Host "########## 4. PLAYER E2E (bundled libVLC) ##########"
$e2e = 'C:\1\ysp-android\windows\verify-e2e.txt'
Remove-Item $e2e -Force -ErrorAction SilentlyContinue
$a2 = @('-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dysp.datadir=C:\1\ysp-android\_verifydata',
        '-cp', "$installDir\app\YspLive-Desktop.jar",
        'com.gary.ysplive.desktop.E2EPlayCheck','cctv6',$e2e)
$null = & "$env:JAVA_HOME\bin\java.exe" @a2 2>&1
if (Test-Path $e2e) {
    $txt = Get-Content $e2e -Raw -Encoding utf8
    Assert "e2e RESULT: PASS" ($txt -match 'RESULT: PASS')
    Assert "player reached PLAYING" ($txt -match 'state=PLAYING')
    if ($txt -match 'videoTracks=(\d+)') { Write-Host "  videoTracks=$($Matches[1])" }
}

Write-Host ""
Write-Host "########## 5. PLAYER UI STRUCTURE ##########"
$uidump = 'C:\1\ysp-android\windows\verify-ui.txt'
$uipng = 'C:\1\ysp-android\windows\verify-player-ui.png'
Remove-Item $uidump,$uipng -Force -ErrorAction SilentlyContinue
$a3 = @('-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dysp.datadir=C:\1\ysp-android\_verifydata',
        '-cp', "$installDir\app\YspLive-Desktop.jar",
        'com.gary.ysplive.desktop.PlayerShowcase','cctv6','12',$uipng,$uidump)
$null = & "$env:JAVA_HOME\bin\java.exe" @a3 2>&1
if (Test-Path $uidump) {
    $u = Get-Content $uidump -Raw -Encoding utf8
    Assert "side channel panel exists" ($u -match 'hasSidePanel=true')
    Assert "bottom control bar exists" ($u -match 'hasControlBar=true')
    Assert "OSD overlay exists" ($u -match 'hasOsd=true')
}

Write-Host ""
Write-Host "########## 6. UI SNAPSHOT ##########"
$snap = 'C:\1\ysp-android\docs\ui-windows.png'
Remove-Item $snap -Force -ErrorAction SilentlyContinue
$a4 = @('-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dysp.datadir=C:\1\ysp-android\_verifydata',
        '-cp', "$installDir\app\YspLive-Desktop.jar",
        'com.gary.ysplive.desktop.SnapshotCheck',$snap)
$null = & "$env:JAVA_HOME\bin\java.exe" @a4 2>&1
Assert "snapshot produced" (Test-Path $snap)
if (Test-Path $snap) { Write-Host "  snapshot=$((Get-Item $snap).Length) bytes" }

Write-Host ""
Write-Host "########## 7. UNINSTALL ##########"
$u2 = Start-Process -FilePath "$installDir\unins000.exe" -ArgumentList @('/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART') -PassThru
$exited = $u2.WaitForExit(90000)
Assert "uninstaller exited (no hang)" $exited
if (-not $exited) { $u2.Kill() }
Start-Sleep -Seconds 3
Assert "install dir removed" (-not (Test-Path $installDir))

Write-Host ""
if ($failed) { Write-Host "########## RESULT: SOME CHECKS FAILED ##########" }
else { Write-Host "########## RESULT: ALL CHECKS PASSED ##########" }
