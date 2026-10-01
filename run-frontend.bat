@echo off
REM Runs the HandOffly frontend dev server on http://localhost:5173
setlocal
cd /d "%~dp0frontend"
if not exist node_modules (
    echo Installing frontend dependencies...
    call npm install
)
echo Starting frontend on http://localhost:5173 ...
call npm run dev
endlocal
