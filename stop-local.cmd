@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\local\Stop-Local.ps1"
if errorlevel 1 pause
