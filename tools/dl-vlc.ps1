# Download full VLC with resume loop until complete.
# The mirror keeps dropping the connection; curl -C - resumes from where it stopped.
$url = 'https://download.videolan.org/pub/videolan/vlc/3.0.21/win64/vlc-3.0.21-win64.7z'
$out = 'C:\1\ysp-android\_vlc\vlc.7z'
$target = 38000000   # ~37MB
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null

for ($i = 1; $i -le 25; $i++) {
    $size = 0
    if (Test-Path $out) { $size = (Get-Item $out).Length }
    if ($size -ge $target) { break }
    Write-Host ("attempt {0}: have {1} MB" -f $i, [math]::Round($size/1MB,1))
    # resume download
    & curl.exe -sSL -C - --max-time 300 -o $out $url 2>$null
    Start-Sleep -Seconds 2
}

$final = 0
if (Test-Path $out) { $final = (Get-Item $out).Length }
Write-Host ("final size = {0} MB" -f [math]::Round($final/1MB,1))
if ($final -ge $target) {
    Write-Host "DOWNLOAD COMPLETE"
    exit 0
} else {
    Write-Host "DOWNLOAD INCOMPLETE"
    exit 1
}
