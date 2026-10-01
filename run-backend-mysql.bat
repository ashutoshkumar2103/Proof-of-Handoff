@echo off
REM Runs the HandOffly backend against MySQL, forcing the 'mysql' profile.
REM Requires the 'handoffly' database + user (run db\setup-mysql.sql once in Workbench).
setlocal

echo Freeing port 8080 (stopping any previous backend)...
for /f "tokens=5" %%p in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do taskkill /F /PID %%p >nul 2>&1

if exist "C:\Program Files\Java\jdk-25\bin\java.exe" (
    set "JAVA_HOME=C:\Program Files\Java\jdk-25"
)
echo Using JAVA_HOME=%JAVA_HOME%
echo Starting backend against MySQL on http://localhost:8080 ...
cd /d "%~dp0backend"
call mvn spring-boot:run -Dspring-boot.run.profiles=mysql
endlocal
