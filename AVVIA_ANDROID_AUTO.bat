@echo off
title Android Auto - Desktop Head Unit
echo ========================================================
echo   Avvio Simulatore Android Auto su PC (TV Cast Browser)
echo ========================================================
echo.
echo 1. Inoltro porta 5277 al Google Pixel...
"C:\Users\casua\AppData\Local\Android\Sdk\platform-tools\adb.exe" -d forward --remove-all
"C:\Users\casua\AppData\Local\Android\Sdk\platform-tools\adb.exe" -d forward tcp:5277 tcp:5277
echo.
echo 2. Avvio del display dell'auto (720p Touch)...
cd /d "C:\Users\casua\AppData\Local\Android\Sdk\extras\google\auto"
desktop-head-unit.exe -c config\default_720p.ini -i touch
echo.
pause
