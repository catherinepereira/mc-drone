# Runs Minecraft with the mod built from source.
# -Arena opens a test world with the drone and an arena ready, so scripts can drive it without creating a world by hand
param([switch]$Arena, [int]$Hold = 86400)
$mod = Join-Path (Split-Path $PSScriptRoot -Parent) "mc-drone-mod"
if (-not $env:JAVA_HOME) {
  $jdk = Get-ChildItem "$env:USERPROFILE\.jdks" -Directory -Filter "jdk-25*" -ErrorAction SilentlyContinue | Select-Object -First 1
  if ($jdk) { $env:JAVA_HOME = $jdk.FullName } else { throw "set JAVA_HOME to a JDK 25" }
}
if ($Arena) {
  & "$mod\gradlew.bat" -p $mod runClientGameTest "-Pe2eHold=$Hold" --console=plain
} else {
  & "$mod\gradlew.bat" -p $mod runClient --console=plain
}
