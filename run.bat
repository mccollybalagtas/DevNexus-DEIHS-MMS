@echo off
rem DEIHS MMS launcher for Windows.
rem
rem   run.bat
rem   set JAVA_OPTS=-Ddb.host=192.168.1.10 -Ddb.port=3306
rem   run.bat
rem
setlocal
cd /d "%~dp0"

set "JAR=target\deihs-mms-1.0.0.jar"

where java >nul 2>nul
if errorlevel 1 (
  echo Java was not found. Install JDK 21 and try again.
  exit /b 1
)

if not exist "%JAR%" (
  echo Building the application ^(first run^)...
  call mvn -q -B package -DskipTests
  if errorlevel 1 exit /b 1
)

java %JAVA_OPTS% -jar "%JAR%" %*
