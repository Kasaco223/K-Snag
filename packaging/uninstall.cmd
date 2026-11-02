@echo off
rem K-Snag - desinstalador. Lo usa Windows desde Aplicaciones (Desinstalar).
rem Opciones: --quiet (sin preguntas; conserva tus datos), --keep-data, --delete-data.
setlocal DisableDelayedExpansion
if not exist "%~dp0runtime\python.exe" goto kd_fallback
rem Todo en una sola linea: el archivo se borra durante la desinstalacion y cmd no debe releerlo.
"%~dp0runtime\python.exe" "%~dp0launch.pyw" --uninstall %* & exit /b
:kd_fallback
rem Sin runtime (instalacion danada): se quita lo basico solo si esta es la carpeta de K-Snag.
set "KD_ROOT=%~dp0"
if /i not "%KD_ROOT:~-14%"=="\K-Downloader\" exit /b 1
if not exist "%KD_ROOT%launch.pyw" exit /b 1
reg delete "HKCU\Software\Classes\kdownloader" /f >nul 2>&1
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\K-Downloader" /f >nul 2>&1
del /f /q "%APPDATA%\Microsoft\Windows\Start Menu\Programs\K-Snag.lnk" >nul 2>&1
del /f /q "%APPDATA%\Microsoft\Windows\Start Menu\Programs\K-Downloader.lnk" >nul 2>&1
del /f /q "%USERPROFILE%\Desktop\K-Snag.lnk" >nul 2>&1
del /f /q "%USERPROFILE%\Desktop\K-Downloader.lnk" >nul 2>&1
cd /d "%TEMP%"
start "" /min "%ComSpec%" /d /c "ping 127.0.0.1 -n 3 >nul & rmdir /s /q "%KD_ROOT:~0,-1%""
exit /b 0
