@echo off
setlocal enabledelayedexpansion

REM Script to sync DropTracker into a local RuneLite source checkout, build, and run on Windows
REM
REM Expected layout (recommended):
REM   droptracker-plugin\
REM     run.bat  (this file)
REM     src\...
REM   runelite\            (RuneLite source checkout)
REM     gradlew.bat
REM     runelite-client\...
REM
REM You can also pass a RuneLite path explicitly:
REM   run.bat "D:\path\to\runelite"
REM
REM Disable auto-clone (if you want to manage RuneLite yourself):
REM   run.bat --no-clone "D:\path\to\runelite"

echo.
echo ========================================
echo  DropTracker Plugin - Build ^& Run
echo ========================================
echo.

REM Define directories
set "SCRIPT_DIR=%~dp0"
set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

set "AUTOCLONE=1"
if /i "%~1"=="--no-clone" (
    set "AUTOCLONE=0"
    shift
)

set "RUNELITE_DIR=%~1"
if "%RUNELITE_DIR%"=="" set "RUNELITE_DIR=%SCRIPT_DIR%\runelite"
set "RUNELITE_GIT_URL=https://github.com/runelite/runelite.git"

set "DROPTRACKER_SRC=%SCRIPT_DIR%\src\main\java\io\droptracker"
set "DROPTRACKER_RES=%SCRIPT_DIR%\src\main\resources"
set "DROPTRACKER_RES_PKG=%DROPTRACKER_RES%\io\droptracker"

set "RUNELITE_PLUGIN=%RUNELITE_DIR%\runelite-client\src\main\java\net\runelite\client\plugins\droptracker"
set "RUNELITE_RESOURCES=%RUNELITE_DIR%\runelite-client\src\main\resources\net\runelite\client\plugins\droptracker"
set "RUNELITE_RESOURCES_ROOT=%RUNELITE_DIR%\runelite-client\src\main\resources"

REM Step 1: Check prerequisites
echo [Step 1] Checking directories...
if not exist "%DROPTRACKER_SRC%" goto :err_droptracker_missing

if exist "%RUNELITE_DIR%\runelite-client" goto :runelite_present

if "%AUTOCLONE%"=="0" goto :err_runelite_missing

echo RuneLite not found at %RUNELITE_DIR%
echo Attempting to clone RuneLite (one-time setup)...
echo.

if exist "%RUNELITE_DIR%" goto :err_runelite_dir_exists

where git >nul 2>&1
if errorlevel 1 goto :err_git_missing

git clone --depth 1 "%RUNELITE_GIT_URL%" "%RUNELITE_DIR%"
if errorlevel 1 goto :err_clone_failed
echo.

:runelite_present
if not exist "%RUNELITE_DIR%\gradlew.bat" goto :err_gradlew_missing

echo OK: Found DropTracker source and RuneLite checkout
echo.

REM Step 2: Sync plugin files (copy + transform package names)
echo [Step 2] Syncing plugin files...
if exist "%RUNELITE_PLUGIN%" rmdir /s /q "%RUNELITE_PLUGIN%"
mkdir "%RUNELITE_PLUGIN%"

for /r "%DROPTRACKER_SRC%" %%F in (*.java) do (
    set "SRC=%%F"
    set "REL=%%F"
    set "REL=!REL:%DROPTRACKER_SRC%\=!"
    set "DEST=%RUNELITE_PLUGIN%\!REL!"
    call :transform "!SRC!" "!DEST!"
)
echo OK: Plugin files synced
echo.

REM Step 2b: Sync resources
echo [Step 2b] Syncing resources...
if exist "%DROPTRACKER_RES_PKG%" (
    if exist "%RUNELITE_RESOURCES%" rmdir /s /q "%RUNELITE_RESOURCES%"
    mkdir "%RUNELITE_RESOURCES%"
    xcopy /s /y /q "%DROPTRACKER_RES_PKG%\*" "%RUNELITE_RESOURCES%\" >nul 2>&1
    echo OK: Package resources synced
)

REM Copy classpath-root resources required by the plugin
if exist "%DROPTRACKER_RES%\npc_drops.json" (
    copy /y "%DROPTRACKER_RES%\npc_drops.json" "%RUNELITE_RESOURCES_ROOT%\npc_drops.json" >nul 2>&1
    echo OK: npc_drops.json synced
)
echo.

REM Step 3: Build RuneLite
echo [Step 3] Building RuneLite...
echo This may take a few minutes on first run...
echo.

cd /d "%RUNELITE_DIR%"
call gradlew.bat :client:shadowJar
if errorlevel 1 (
    echo ERROR: Build failed!
    pause
    exit /b 1
)

REM Find the shadow JAR
for /f "delims=" %%i in ('dir /b /s "%RUNELITE_DIR%\runelite-client\build\libs\*-shaded.jar" 2^>nul') do set "SHADOW_JAR=%%i"

if not defined SHADOW_JAR (
    echo ERROR: Could not find shadow JAR. Build may have failed.
    pause
    exit /b 1
)

echo.
echo OK: Build complete!
echo JAR: %SHADOW_JAR%
echo.

REM Step 4: Launch RuneLite
echo [Step 4] Launching RuneLite...
echo.
echo After RuneLite launches:
echo   1. Click the wrench icon (Configuration)
echo   2. Search for 'DropTracker'
echo.

java -jar "%SHADOW_JAR%"
goto :eof

REM Function to copy and transform package names
:transform
set "SRC=%~1"
set "DEST=%~2"
if exist "%SRC%" (
    powershell -NoProfile -Command "$src='%SRC%'; $dest='%DEST%'; $dir=Split-Path -Parent $dest; New-Item -ItemType Directory -Force -Path $dir | Out-Null; $t=Get-Content -LiteralPath $src -Raw; $t=$t -replace 'package io\.droptracker', 'package net.runelite.client.plugins.droptracker' -replace 'import io\.droptracker', 'import net.runelite.client.plugins.droptracker'; [System.IO.File]::WriteAllText($dest, $t, (New-Object System.Text.UTF8Encoding($false)))"
)
goto :eof

:err_droptracker_missing
echo ERROR: DropTracker source not found at %DROPTRACKER_SRC%
pause
exit /b 1

:err_runelite_missing
echo ERROR: RuneLite source not found at %RUNELITE_DIR%
echo.
echo Fix:
echo   - Clone RuneLite into "%RUNELITE_DIR%"
echo   - Or run: run.bat "D:\path\to\runelite"
pause
exit /b 1

:err_runelite_dir_exists
echo ERROR: "%RUNELITE_DIR%" exists but is not a RuneLite checkout.
echo Delete/rename that folder or pass a different path.
pause
exit /b 1

:err_git_missing
echo ERROR: git is not installed or not on PATH.
echo Install Git for Windows, then re-run this script.
pause
exit /b 1

:err_clone_failed
echo ERROR: Failed to clone RuneLite.
pause
exit /b 1

:err_gradlew_missing
echo ERROR: gradlew.bat not found in %RUNELITE_DIR%
pause
exit /b 1
