@echo off
title SCS Frontend (5173)
cd /d "%~dp0frontend"
echo ========================================================
echo   Starting SCS Frontend (Vite: 5173)...
echo ========================================================
call pnpm dev
pause
