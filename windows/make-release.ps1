$dist = 'C:\1\ysp-android\windows\dist'
$outDir = 'C:\1\ysp-android\windows\release'
Remove-Item $outDir -Recurse -Force -EA SilentlyContinue
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# 1) portable exe dir -> zip
$appImg = "$dist\YspLive"
if (-not (Test-Path "$appImg\YspLive.exe")) { Write-Host "NO EXE"; exit 1 }

Copy-Item 'C:\1\ysp-android\windows\README.md' "$appImg\README.md" -Force
$zipExe = "$outDir\YspLive-Windows-v9.5.3-portable.zip"
Compress-Archive -Path $appImg -DestinationPath $zipExe -CompressionLevel Optimal -Force
Write-Host "portable zip: $([math]::Round((Get-Item $zipExe).Length/1MB,1)) MB"

# 2) single jar + README
$jarSrc = 'C:\1\ysp-android\windows\build\libs\YspLive-Desktop.jar'
Copy-Item $jarSrc $outDir -Force
Copy-Item 'C:\1\ysp-android\windows\README.md' "$outDir\README.md" -Force

Write-Host "=== release dir ==="
Get-ChildItem $outDir | Select-Object Name,@{n='MB';e={[math]::Round($_.Length/1MB,1)}}
Write-Host "=== hashes ==="
Get-ChildItem $outDir -Filter '*.zip','*.jar' -EA SilentlyContinue | ForEach-Object { "$($_.Name)  $((Get-FileHash $_.FullName -Algorithm SHA256).Hash)" }
