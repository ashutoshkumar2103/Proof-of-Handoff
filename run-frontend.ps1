# Runs the HandOffly frontend dev server from the repo root.
#   .\run-frontend.ps1
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location "$scriptDir\frontend"
if (-not (Test-Path "node_modules")) {
    Write-Host "Installing frontend dependencies..." -ForegroundColor Cyan
    npm install
}
Write-Host "Starting frontend on http://localhost:5173 ..." -ForegroundColor Cyan
npm run dev
