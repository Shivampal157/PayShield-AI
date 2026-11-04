@echo off
title PayShield AI — Direct Launcher
echo =====================================================================
echo  PayShield AI — Intelligent Payments. Explainable Risk. Reliable Ledger.
echo =====================================================================
echo.
echo Starting PayShield AI embedded fintech platform on port 8080...
echo.

set "JAVA_CMD=C:\Users\SHIVAM PAL\.vscode\extensions\redhat.java-1.58.0-win32-x64\jre\25.0.4.1-win32-x86_64\bin\java.exe"

netstat -ano | findstr :8080 | findstr LISTENING >nul
if %errorlevel% equ 0 (
    echo [INFO] PayShield AI is already running on port 8080. Opening browser directly...
    start http://localhost:8080/
    exit /b 0
)

echo [INFO] Launching PayShield AI application...
start "PayShield AI Server" "%JAVA_CMD%" -jar "D:\Spring Project\target\payshield-ai-1.0.0.jar" --spring.profiles.active=local-embedded --server.port=8080

echo [INFO] Waiting for server initialization (5 seconds)...
timeout /t 5 /nobreak >nul

echo [INFO] Opening project directly in default browser...
start http://localhost:8080/

echo.
echo =====================================================================
echo  PayShield AI is now live at: http://localhost:8080/
echo  Close this window anytime. Server runs in its own window.
echo =====================================================================
exit /b 0
