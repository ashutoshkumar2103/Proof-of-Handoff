@echo off
REM Runs the HandOffly backend on H2 (no MySQL needed).
setlocal
if exist "C:\Program Files\Java\jdk-25\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-25"
)
echo Using JAVA_HOME=%JAVA_HOME%
echo Starting backend on H2 on http://localhost:8080 ...
cd /d "%~dp0backend"
call mvn spring-boot:run -Dspring-boot.run.profiles=h2
endlocal
