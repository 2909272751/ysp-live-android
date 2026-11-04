# Build the Inno Setup installer (and keep the portable zip as an alternative).
# ASCII only (PS 5.1 mangles CJK in UTF-8-no-BOM files).

param([switch]$SkipPortable)

$env:JAVA_HOME = 'C:\re-toolkit\jdk'
$root = 'C:\1\ysp-android'
$win = "$root\windows"
$release = "$win\release"
$iscc = "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe"

New-Item -ItemType Directory -Force -Path $release | Out-Null

if (-not (Test-Path $iscc)) {
    Write-Host "Inno Setup not found at $iscc"
    Write-Host "install with: winget install JRSoftware.InnoSetup"
    exit 1
}
if (-not (Test-Path "$win\dist\YspLive\YspLive.exe")) {
    Write-Host "app image missing; run pack2.ps1 first"
    exit 1
}

# 1) compile installer
Write-Host "=== compiling installer ==="
Push-Location "$win\installer"
& $iscc 'installer.iss' 2>&1 | Select-String 'Compressing|Successful|Error|Warning:' | Select-Object -Last 6
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { Write-Host "ISCC FAILED"; exit 1 }

# 2) optional portable zip
if (-not $SkipPortable) {
    Write-Host "=== building portable zip ==="
    $zip = "$release\YspLive-Windows-v9.5.3-selfcontained.zip"
    Remove-Item $zip -Force -ErrorAction SilentlyContinue
    Compress-Archive -Path "$win\dist\YspLive" -DestinationPath $zip -CompressionLevel Optimal -Force
}

# 3) jar
Copy-Item "$win\build\libs\YspLive-Desktop.jar" $release -Force -ErrorAction SilentlyContinue
Copy-Item "$win\README.md" $release -Force

Write-Host ""
Write-Host "=== release artifacts ==="
Get-ChildItem $release -File | ForEach-Object {
    $mb = [math]::Round($_.Length/1MB, 1)
    $hash = (Get-FileHash $_.FullName -Algorithm SHA256).Hash
    Write-Host ("{0,-48} {1,7} MB  {2}" -f $_.Name, $mb, $hash)
}
