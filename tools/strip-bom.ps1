# Strip UTF-8 BOM from files given as arguments.
# PowerShell 5.1's "Set-Content -Encoding utf8" always writes a BOM,
# which makes javac fail with "illegal character: '\ufeff'".
# ASCII only.
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Files)

if (-not $Files -or $Files.Count -eq 0) {
    Write-Host "usage: strip-bom.ps1 <file> [file...]"
    exit 1
}

$fixed = 0
foreach ($f in $Files) {
    if (-not (Test-Path $f)) { Write-Host "missing: $f"; continue }
    $bytes = [System.IO.File]::ReadAllBytes($f)
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        $len = $bytes.Length - 3
        $out = New-Object byte[] $len
        [Array]::Copy($bytes, 3, $out, 0, $len)
        [System.IO.File]::WriteAllBytes($f, $out)
        Write-Host "BOM removed: $f ($($bytes.Length) -> $len)"
        $fixed++
    } else {
        Write-Host "ok (no BOM): $f"
    }
}
Write-Host "fixed=$fixed"
exit 0
