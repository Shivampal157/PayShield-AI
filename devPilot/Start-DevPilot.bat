@echo off
title DevPilot - AI GitHub Repository RAG Assistant
cd /d "%~dp0client"
echo Starting DevPilot on http://localhost:3000 ...
start "" "http://localhost:3000"
npm run dev
