$env:JAVA_HOME = 'C:\re-toolkit\jdk'
$libDir = 'C:\1\ysp-android\_fxtest\lib'
$lib = (Get-ChildItem "$libDir\*.jar" | ForEach-Object { $_.FullName }) -join ';'
$java = "$env:JAVA_HOME\bin\java.exe"
$javac = "$env:JAVA_HOME\bin\javac.exe"

Set-Location 'C:\1\ysp-android\windows\probe'

& $javac -encoding UTF-8 -cp $lib -d out WebViewProbe.java 2>&1 | Out-File compile-wv.log -Encoding utf8
if ($LASTEXITCODE -ne 0) {
    Write-Host "COMPILE FAILED"
    Get-Content compile-wv.log
    exit 1
}
Write-Host "compile ok"

$argList = @(
    '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-cp', "out;$lib",
    '--module-path', $lib,
    '--add-modules', 'javafx.controls,javafx.web',
    'WebViewProbe',
    "$libDir\hls.min.js",
    'http://127.0.0.1:18900/cctv1.m3u8'
)
& $java @argList 2>&1 | Out-File probe-wv.log -Encoding utf8
Write-Host "EXIT=$LASTEXITCODE"
Get-Content probe-wv.log -Encoding utf8
