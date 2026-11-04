$env:JAVA_HOME = 'C:\re-toolkit\jdk'
$libDir = 'C:\1\ysp-android\_fxtest\lib'
$java = "$env:JAVA_HOME\bin\java.exe"
$javac = "$env:JAVA_HOME\bin\javac.exe"
$probeDir = 'C:\1\ysp-android\windows\probe'
$lib = (Get-ChildItem "$libDir\*.jar" | ForEach-Object { $_.FullName }) -join ';'

Set-Location $probeDir
& $javac -encoding UTF-8 -cp $lib -d out CapProbe.java 2>&1 | Out-File cap-compile.log -Encoding utf8
if ($LASTEXITCODE -ne 0) { Get-Content cap-compile.log; exit 1 }

$args2 = @('-Dfile.encoding=UTF-8', '-cp', "out;$lib", '--module-path', $lib, '--add-modules', 'javafx.controls,javafx.web', 'CapProbe')
& $java @args2 2>&1 | Out-File cap.log -Encoding utf8
Get-Content cap.log -Encoding utf8
