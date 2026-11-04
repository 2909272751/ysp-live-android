$env:JAVA_HOME = 'C:\re-toolkit\jdk'
$jar = 'C:\1\ysp-android\windows\build\libs\YspLive-Desktop.jar'
$dataDir = 'C:\1\vsdata-exit'
Remove-Item $dataDir -Recurse -Force -ErrorAction SilentlyContinue
$outFile = "$dataDir.out"
$errFile = "$dataDir.err"
Remove-Item $outFile, $errFile -Force -ErrorAction SilentlyContinue

$jargs = @(
    '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
    "-Dysp.datadir=$dataDir",
    '-cp', $jar, 'com.gary.ysplive.desktop.ExitCheck'
)

Write-Host "=== launching child JVM, measuring time to exit ==="
$sw = [Diagnostics.Stopwatch]::StartNew()
$p = Start-Process -FilePath "$env:JAVA_HOME\bin\java.exe" -ArgumentList $jargs `
    -RedirectStandardOutput $outFile -RedirectStandardError $errFile -PassThru -WindowStyle Hidden

$exited = $p.WaitForExit(40000)
$sw.Stop()

if (-not $exited) {
    Write-Host "RESULT: FAIL - process still alive after 40s (cannot exit bug remains)"
    Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    Get-Content $outFile -Encoding utf8 -ErrorAction SilentlyContinue | Select-Object -Last 5
    exit 1
}

$sec = [math]::Round($sw.Elapsed.TotalSeconds, 1)
Write-Host "process exited in $sec s, exitCode=$($p.ExitCode)"
$out = Get-Content $outFile -Encoding utf8 -ErrorAction SilentlyContinue
$out | Select-Object -Last 6

if ($out -match 'RESULT: FAIL') {
    Write-Host "RESULT: FAIL - halt did not take effect"
    exit 1
}
if ($sec -gt 32) {
    Write-Host "RESULT: FAIL - exit took too long"
    exit 1
}
Write-Host "RESULT: PASS - shutdown() terminates the process"
exit 0
