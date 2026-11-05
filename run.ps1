# TelescopeControl — универсальный запуск для Windows.
$ErrorActionPreference = "Stop"
$projectDir = $PSScriptRoot
$pom = Join-Path $projectDir "pom.xml"

Write-Host "=== TelescopeControl ===" -ForegroundColor Cyan

if (-not (Test-Path $pom)) {
    Write-Host "Не найден pom.xml. Распакуйте архив полностью и запустите скрипт из папки проекта." -ForegroundColor Red
    Read-Host "Enter для выхода"
    exit 1
}

$java = $null
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (Test-Path $candidate) { $java = $candidate }
}
if (-not $java) {
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd) { $java = $cmd.Source }
}
if (-not $java) {
    Write-Host "Java не найдена. Установите JDK 21 и настройте JAVA_HOME или PATH." -ForegroundColor Red
    Read-Host "Enter для выхода"
    exit 1
}

Write-Host "Java: $java"
& $java -version
if ($LASTEXITCODE -ne 0) {
    Read-Host "Не удалось запустить Java. Enter для выхода"
    exit 1
}

$maven = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $maven) {
    Write-Host "Maven (mvn) не найден в PATH." -ForegroundColor Red
    Write-Host "Откройте папку проекта в IntelliJ IDEA через File > Open, выбрав папку с pom.xml."
    Write-Host "После загрузки Maven запустите Maven > Plugins > javafx > javafx:run."
    Read-Host "Enter для выхода"
    exit 1
}

Set-Location $projectDir
Write-Host "Запуск JavaFX через Maven..." -ForegroundColor Green
& $maven.Source "-f" $pom "javafx:run"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Запуск завершился с ошибкой. Смотрите сообщения Maven выше." -ForegroundColor Red
    Read-Host "Enter для выхода"
    exit $LASTEXITCODE
}
