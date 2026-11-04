# Download full VLC (if needed) and build a minimal libVLC containing only what
# HLS live playback requires. Full VLC dir is ~191MB; output is ~62MB.
# NOTE: ASCII only. PowerShell 5.1 misparses UTF-8-no-BOM files containing CJK comments.

param(
    [string]$WorkDir = 'C:\1\ysp-android\_vlc',
    [string]$VlcVersion = '3.0.21'
)

$ErrorActionPreference = 'Stop'
$archive = Join-Path $WorkDir "vlc-$VlcVersion-win64.7z"
$extract = Join-Path $WorkDir 'vlcx'
$srcDir = Join-Path $extract "vlc-$VlcVersion"
$dst = Join-Path $WorkDir 'minimal'

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

# 1) fetch VLC if not already extracted
if (-not (Test-Path (Join-Path $srcDir 'libvlc.dll'))) {
    if (-not (Test-Path $archive) -or (Get-Item $archive).Length -lt 30MB) {
        Write-Host "downloading VLC $VlcVersion (win64)..."
        $url = "https://download.videolan.org/pub/videolan/vlc/$VlcVersion/win64/vlc-$VlcVersion-win64.7z"
        Remove-Item $archive -Force -ErrorAction SilentlyContinue
        curl.exe -sSL --max-time 900 -o $archive $url
        if ($LASTEXITCODE -ne 0) { throw "download failed" }
    }
    $size = [math]::Round((Get-Item $archive).Length / 1MB, 1)
    Write-Host "archive: $size MB"
    if ($size -lt 30) { throw "archive too small, download likely truncated" }

    $sevenZip = 'C:\Program Files\7-Zip\7z.exe'
    if (-not (Test-Path $sevenZip)) { throw "7-Zip not found at $sevenZip" }
    Write-Host "extracting..."
    Remove-Item $extract -Recurse -Force -ErrorAction SilentlyContinue
    & $sevenZip x $archive "-o$extract" -y | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "extract failed" }
}

if (-not (Test-Path (Join-Path $srcDir 'libvlc.dll'))) {
    throw "libvlc.dll not found under $srcDir"
}

# 2) build minimal tree
Remove-Item $dst -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $dst, "$dst\plugins" | Out-Null

foreach ($f in @('libvlc.dll', 'libvlccore.dll', 'vlc-cache-gen.exe')) {
    if (Test-Path "$srcDir\$f") { Copy-Item "$srcDir\$f" "$dst\$f" -Force }
}

# plugin dirs required for playback
$fullDirs = @('demux', 'access', 'video_output', 'video_chroma', 'audio_output',
              'audio_mixer', 'packetizer', 'stream_filter', 'misc', 'text_renderer')
foreach ($d in $fullDirs) {
    if (Test-Path "$srcDir\plugins\$d") {
        Copy-Item "$srcDir\plugins\$d" "$dst\plugins\$d" -Recurse -Force
    }
}

# codec: keep only what live HLS needs (h264/hevc/aac/mpeg + hardware accel)
New-Item -ItemType Directory -Force -Path "$dst\plugins\codec" | Out-Null
$codecKeep = @(
    'libavcodec_plugin.dll', 'avcodec_plugin.dll', 'd3d11va_plugin.dll',
    'dxva2_plugin.dll', 'libmpeg2_plugin.dll', 'mpeg_audio_plugin.dll',
    'araw_plugin.dll', 'lpcm_plugin.dll', 'g711_plugin.dll', 'aes3_plugin.dll',
    'faad_plugin.dll', 'flac_plugin.dll', 'vorbis_plugin.dll', 'opus_plugin.dll',
    'cvdsub_plugin.dll', 'subsdec_plugin.dll', 'substext_plugin.dll'
)
foreach ($c in $codecKeep) {
    if (Test-Path "$srcDir\plugins\codec\$c") {
        Copy-Item "$srcDir\plugins\codec\$c" "$dst\plugins\codec\$c" -Force
    }
}

$size = (Get-ChildItem $dst -Recurse -File | Measure-Object Length -Sum).Sum
$count = (Get-ChildItem $dst -Recurse -File).Count
Write-Host ""
Write-Host "minimal libVLC: $([math]::Round($size/1MB,1)) MB, $count files"
Write-Host "output: $dst"
