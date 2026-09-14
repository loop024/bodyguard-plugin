@echo off
setlocal

cd /d "%~dp0"

where mvn >nul 2>&1
if errorlevel 1 (
    echo Maven was not found in PATH.
    echo Install Maven and add its bin directory to PATH, then run this file again.
    pause
    exit /b 1
)

echo Building BodyGuard...
call mvn clean package
if errorlevel 1 (
    echo.
    echo Build failed. Check the Maven output above.
    pause
    exit /b 1
)

if not exist "target\BodyGuard.jar" (
    echo.
    echo Maven finished, but target\BodyGuard.jar was not found.
    pause
    exit /b 1
)

echo.
echo Build completed successfully.
echo Jar: %CD%\target\BodyGuard.jar
pause
endlocal
