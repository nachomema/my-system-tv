# Instala por primera vez el launcher con el panel de futbol en el tele, guardando y devolviendo
# tu configuracion (config.json, fondos de video). Se corre UNA sola vez; las actualizaciones
# siguientes son solo:  .\adb install -r my-system-tv-futbol.apk
#
# Uso, desde la carpeta platform-tools (con adb.exe y my-system-tv-futbol.apk adentro):
#   powershell -ExecutionPolicy Bypass -File .\instalar-futbol.ps1 192.168.100.5
param([string]$TvIp = "192.168.100.5")
$ErrorActionPreference = "Stop"
$adb = Join-Path $PSScriptRoot "adb.exe"
$apk = Join-Path $PSScriptRoot "my-system-tv-futbol.apk"
$pkg = "com.gathin.tvhome"
$files = "/sdcard/Android/data/$pkg/files"
$backup = Join-Path $PSScriptRoot "respaldo-launcher"

if (-not (Test-Path $adb)) { throw "No encuentro adb.exe en $PSScriptRoot" }
if (-not (Test-Path $apk)) { throw "No encuentro my-system-tv-futbol.apk en $PSScriptRoot" }

function Adb { & $adb @args; if ($LASTEXITCODE -ne 0) { throw "Fallo: adb $args" } }

Write-Host "1/7 Conectando con el tele $TvIp ..."
& $adb connect "$($TvIp):5555" | Out-Host

Write-Host "2/7 Guardando tu configuracion y tus fondos en $backup ..."
if (Test-Path $backup) { Remove-Item -Recurse -Force $backup }
New-Item -ItemType Directory -Path $backup | Out-Null
& $adb pull "$files/." $backup | Out-Host

Write-Host "3/7 Volviendo a activar el inicio de Google un momento (para que el tele no quede sin inicio) ..."
& $adb shell pm enable com.google.android.tvlauncher | Out-Host

Write-Host "4/7 Reemplazando el launcher (la version nueva tiene otra firma) ..."
& $adb uninstall $pkg | Out-Host
Adb install $apk

Write-Host "5/7 Devolviendo tu configuracion y tus fondos ..."
Adb shell mkdir -p $files
Get-ChildItem $backup | ForEach-Object { Adb push $_.FullName "$files/" }

Write-Host "6/7 Permisos y boton Home ..."
& $adb shell pm grant $pkg android.permission.WRITE_SECURE_SETTINGS | Out-Host
& $adb shell pm grant $pkg android.permission.ACCESS_FINE_LOCATION | Out-Host
& $adb shell settings put secure enabled_accessibility_services "$pkg/$pkg.HomeKeyService" | Out-Host
& $adb shell settings put secure accessibility_enabled 1 | Out-Host
& $adb shell cmd package set-home-activity "$pkg/.MainActivity" | Out-Host

Write-Host "7/7 Dejando tu launcher como unico inicio ..."
& $adb shell pm disable-user --user 0 com.google.android.tvlauncher | Out-Host
& $adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME | Out-Host

Write-Host ""
Write-Host "Listo. Apreta Home en el control: arriba, debajo del clima, aparecen los resultados."
Write-Host "Para elegir equipos: Ajustes del lanzador > Futbol."
