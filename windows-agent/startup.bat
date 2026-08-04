@echo off
chcp 65001 >nul
title JARVIS -- Windows AI Agent
color 0A

cd /d "%~dp0"

python main.py

pause
