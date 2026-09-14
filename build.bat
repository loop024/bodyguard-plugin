@echo off
setlocal

cd /d "%~dp0"

set "MAVEN_CMD="
if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MAVEN_CMD=%MAVEN_HOME%\bin\mvn.cmd"
if not defined MAVEN_CMD (
    for /f "delims=" %%M in ('where mvn 2^>nul') do if not defined MAVEN_CMD set "MAVEN_CMD=%%M"
)

rem Use Maven bundled with IntelliJ IDEA when it is available.
if not defined MAVEN_CMD (
    for /d %%D in ("%ProgramFiles%\JetBrains\IntelliJ IDEA*") do (
        if exist "%%~fD\plugins\maven\lib\maven3\bin\mvn.cmd" if not defined MAVEN_CMD set "MAVEN_CMD=%%~fD\plugins\maven\lib\maven3\bin\mvn.cmd"
    )
)
if not defined MAVEN_CMD (
    for /d %%D in ("%LOCALAPPDATA%\Programs\JetBrains\IntelliJ IDEA*") do (
        if exist "%%~fD\plugins\maven\lib\maven3\bin\mvn.cmd" if not defined MAVEN_CMD set "MAVEN_CMD=%%~fD\plugins\maven\lib\maven3\bin\mvn.cmd"
    )
)

if not defined MAVEN_CMD (
    echo Maven was not found.
    echo Install Maven and add its bin directory to PATH,
    echo or set MAVEN_HOME to the Maven installation directory.
    echo If IntelliJ IDEA is installed, make sure its Maven plugin is enabled.
    pause
    exit /b 1
)

echo Building BodyGuard...
call "%MAVEN_CMD%" clean package
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
