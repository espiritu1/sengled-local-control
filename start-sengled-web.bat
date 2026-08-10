@echo off
REM Start the Sengled web control panel in the background.
REM Place this file next to the SengledTools folder.
cd /d "%~dp0SengledTools"
start "Sengled Web Panel" /min ".venv\Scripts\python.exe" sengled-web.py
