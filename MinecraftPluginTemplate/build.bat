@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"

set "MAVEN_CMD="
if exist "%~dp0mvnw.cmd" set "MAVEN_CMD=%~dp0mvnw.cmd"
if not defined MAVEN_CMD if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" set "MAVEN_CMD=%MAVEN_HOME%\bin\mvn.cmd"
if not defined MAVEN_CMD (
    for /f "delims=" %%M in ('where mvn 2^>nul') do if not defined MAVEN_CMD set "MAVEN_CMD=%%M"
)

rem IntelliJ IDEA付属のMavenも自動で探します。
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
    echo Mavenが見つかりませんでした。
    echo MavenをPATHへ追加するか、IntelliJ IDEAのMavenプラグインを有効にしてください。
    pause
    exit /b 1
)

echo プラグインをビルドしています...
call "%MAVEN_CMD%" clean package
if errorlevel 1 (
    echo.
    echo ビルドに失敗しました。上に表示されたエラーを確認してください。
    pause
    exit /b 1
)

echo.
echo ビルドが完了しました。targetフォルダ内のJARをサーバーのpluginsへ入れてください。
explorer "%CD%\target"
pause
endlocal

