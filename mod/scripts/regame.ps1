# Closes a held game, relaunches the client gametest with a long hold so bridge clients can keep using the world,
# and prints "gametest passed" once the gametest's bridge client disconnects, or the failure
$root = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
# a held gametest ends once this file exists
New-Item -ItemType File -Force "$root\mod\build\run\clientGameTest\e2e-done" | Out-Null
$deadline = (Get-Date).AddSeconds(120)
while ((Get-Date) -lt $deadline -and (Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'KnotClient|fabric.dli' })) { Start-Sleep 3 }
Start-Sleep 3
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\jdk-25.0.4.1+1"
$started = Get-Date
Start-Process -FilePath "$root\mod\gradlew.bat" -ArgumentList "-p", "$root\mod", "runClientGameTest", "-Pe2eHold=86400", "--console=plain" -RedirectStandardOutput "$env:TEMP\mcd-gametest.log" -RedirectStandardError "$env:TEMP\mcd-gametest.err" -WindowStyle Hidden
$result = "timed out"
$deadline = (Get-Date).AddMinutes(12)
while ((Get-Date) -lt $deadline) {
  Start-Sleep 5
  $log = Get-Content "$env:TEMP\mcd-gametest.log" -Raw -ErrorAction SilentlyContinue
  if ($log -match "AssertionError|BUILD FAILED|gametests failed") { $result = "FAILED"; break }
  $mod = Get-ChildItem "$root\mod\run\logs\mod-*.jsonl" | Sort-Object LastWriteTime | Select-Object -Last 1
  if ($mod.CreationTime -gt $started -and (Select-String -Path $mod.FullName -Pattern '"client":"gametest"' -Quiet) -and (Select-String -Path $mod.FullName -Pattern 'bridge.disconnect' -Quiet)) { $result = "gametest passed"; break }
}
$result
if ($result -ne "gametest passed") {
  $l = Get-Content "$env:TEMP\mcd-gametest.log"
  $l | Select-String -Pattern "AssertionError|Exception|FAILED" -Context 0,4 | Select-Object -First 4 | ForEach-Object { $_.ToString().Substring(0, [Math]::Min(1200, $_.ToString().Length)) }
}
