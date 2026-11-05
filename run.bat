@echo off
setlocal
title TelescopeControl Launcher

set "PROJECT_DIR=%~dp0"
set "POM=%PROJECT_DIR%pom.xml"

if not exist "%POM%" (
    echo ERROR: pom.xml was not found.
    echo Extract the complete project archive first.
    pause
    exit /b 1
)

set "JAVA_EXE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"

if not defined JAVA_EXE (
    for /f "delims=" %%J in ('where.exe java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%J"
)

if not defined JAVA_EXE (
    echo ERROR: Java was not found.
    echo Install JDK 21 and configure JAVA_HOME or PATH.
    pause
    exit /b 1
)

echo Java runtime:
"%JAVA_EXE%" -version
echo.

where.exe mvn >nul 2>nul
if errorlevel 1 (
    echo ERROR: Maven was not found in PATH.
    echo.
    echo Open this project folder in IntelliJ IDEA.
    echo Select the folder containing pom.xml.
    echo Wait for Maven import to finish.
    echo Then run Maven - Plugins - javafx - javafx:run.
    echo.
    pause
    exit /b 1
)

cd /d "%PROJECT_DIR%"
echo Starting JavaFX application...
call mvn -f "%POM%" javafx:run
if errorlevel 1 (
    echo.
    echo ERROR: Maven could not start the application.
    echo Check the Maven output above.
    pause
    exit /b 1
)

endlocal
