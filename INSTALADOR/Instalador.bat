@echo off
rem K-Snag: este archivo ya no instala nada. Solo abre el sitio oficial de descarga.
rem El instalador nuevo se descarga desde https://ksnagapp.vercel.app/descargar
title K-Snag
echo.
echo   K-Snag 2.0 tiene un instalador nuevo, simple y seguro.
echo.
echo   Abriendo el sitio de descarga en tu navegador...
echo   Si no se abre solo, copia este enlace en tu navegador:
echo.
echo       https://ksnagapp.vercel.app/descargar
echo.
start "" "https://ksnagapp.vercel.app/descargar"
echo   Puedes cerrar esta ventana.
timeout /t 20 >nul
exit /b 0
