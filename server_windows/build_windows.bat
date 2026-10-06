@echo off
setlocal enabledelayedexpansion

echo ================================================================
echo    ZENITH AUDIO ENGINE - WINDOWS 11 SERVER BUILD SCRIPT
echo ================================================================

where cmake >nul 2>nul
if %ERRORLEVEL% neq 0 (
    echo [ERROR] CMake is not found in PATH. Please install CMake or run from Developer Command Prompt.
    pause
    exit /b 1
)

mkdir build 2>nul
cd build

echo [*] Generating build configuration...
cmake .. -DCMAKE_BUILD_TYPE=Release

if %ERRORLEVEL% neq 0 (
    echo [ERROR] CMake configuration failed.
    pause
    exit /b 1
)

echo [*] Compiling zenith-server-win11.exe...
cmake --build . --config Release

if %ERRORLEVEL% neq 0 (
    echo [ERROR] Compilation failed.
    pause
    exit /b 1
)

echo.
echo ================================================================
echo [SUCCESS] Built zenith-server-win11.exe successfully!
echo Run 'Release\zenith-server-win11.exe' to start audio streaming.
echo ================================================================
pause
