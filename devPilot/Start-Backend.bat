@echo off
title DevPilot Spring Boot Backend (Port 8080)
cd /d "%~dp0"

if exist ".env" (
  for /f "usebackq tokens=1,* delims==" %%A in (".env") do (
    echo %%A | findstr /r "^#" >nul
    if errorlevel 1 (
      if not "%%A"=="" set "%%A=%%B"
    )
  )
)

cd /d "%~dp0backend"
echo Starting DevPilot Backend on http://localhost:8080 ...
call mvnw.cmd spring-boot:run
pause
