$env:JAVA_HOME = 'C:\re-toolkit\jdk'
$lib = (Get-ChildItem 'C:\1\ysp-android\_fxtest\lib\*.jar' | ForEach-Object { $_.FullName }) -join ';'
$out = 'C:\1\ysp-android\windows\probe\out'
$java = "$env:JAVA_HOME\bin\java.exe"

$argList = @(
    '-Dfile.encoding=UTF-8',
    '-Dstdout.encoding=UTF-8',
    '-cp', "out;$lib",
    '--module-path', $lib,
    '--add-modules', 'javafx.controls,javafx.media',
    'HlsProbe',
    'http://127.0.0.1:18900/cctv1.m3u8'
)

Set-Location 'C:\1\ysp-android\windows\probe'
& $java @argList 2>&1 | Out-File -FilePath 'C:\1\ysp-android\windows\probe\probe.log' -Encoding utf8
Write-Host "EXIT=$LASTEXITCODE"
Get-Content 'C:\1\ysp-android\windows\probe\probe.log' -Encoding utf8
