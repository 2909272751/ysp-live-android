$dist = 'C:\1\ysp-android\windows\dist'
$outDir = 'C:\1\ysp-android\windows\release'
Remove-Item $outDir -Recurse -Force -EA SilentlyContinue
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$appImg = "$dist\YspLive"
if (-not (Test-Path "$appImg\YspLive.exe")) { Write-Host "NO EXE"; exit 1 }
if (-not (Test-Path "$appImg\vlc\libvlc.dll")) { Write-Host "NO libVLC"; exit 1 }

Copy-Item 'C:\1\ysp-android\windows\README.md' "$appImg\README.md" -Force

Write-Host "=== zipping (this takes a while, 226MB) ==="
$zipExe = "$outDir\YspLive-Windows-v9.5.3-selfcontained.zip"
Compress-Archive -Path $appImg -DestinationPath $zipExe -CompressionLevel Optimal -Force
Write-Host "zip: $([math]::Round((Get-Item $zipExe).Length/1MB,1)) MB"

Write-Host "=== copy single jar too ==="
Copy-Item 'C:\1\ysp-android\windows\build\libs\YspLive-Desktop.jar' $outDir -Force
Copy-Item 'C:\1\ysp-android\windows\README.md' "$outDir\README.md" -Force

Write-Host "=== release ==="
Get-ChildItem $outDir -File | ForEach-Object {
    "$($_.Name)|$([math]::Round($_.Length/1MB,2))MB|$((Get-FileHash $_.FullName -Algorithm SHA256).Hash)"
}
