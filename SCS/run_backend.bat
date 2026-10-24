@echo off
title SCS Backend (8080)
cd /d "%~dp0backend"
echo ========================================================
echo   Starting SCS Backend (Spring Boot: 8080)...
echo ========================================================
call "..\.tools\apache-maven-3.9.16\bin\mvn.cmd" -gs .mvn/settings.xml -s .mvn/settings.xml spring-boot:run
pause
