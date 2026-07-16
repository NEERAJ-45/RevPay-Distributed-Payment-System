@echo off
title RevPay Orchestrator Launcher
echo Starting RevPay Orchestrator...
powershell -ExecutionPolicy Bypass -File "%~dp0start-services.ps1"
pause
