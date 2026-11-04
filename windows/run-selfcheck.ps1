$env:JAVA_HOME = 'C:\re-toolkit\jdk'
$jar = 'C:\1\ysp-android\windows\build\libs\YspLive-Desktop.jar'
$probeDir = 'C:\1\ysp-android\windows'
$out = "$probeDir\selfcheck.txt"

Remove-Item $out -Force -EA SilentlyContinue

$args2 = @('-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8', '-Dprism.order=sw', '-cp', $jar, 'com.gary.ysplive.desktop.SelfCheck', $out)
& "$env:JAVA_HOME\bin\java.exe" @args2 2>&1 | Select-Object -Last 15

Write-Host "=== SELFCHECK RESULT ==="
if (Test-Path $out) { Get-Content $out -Encoding utf8 } else { Write-Host "NO OUTPUT FILE" }
