@echo off
title Launching SCS Services...
cd /d "%~dp0"
echo ========================================================
echo   Launching SCS Backend and Frontend...
echo ========================================================
start "" "%~dp0run_backend.bat"
start "" "%~dp0run_frontend.bat"
