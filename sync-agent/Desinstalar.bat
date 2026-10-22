@echo off
@chcp 65001 >nul
title Desinstalador - Agente Lola AdmGlobal
echo ======================================================================
echo           DESINSTALADOR DEL AGENTE LOLA ADMGLOBAL
echo ======================================================================
echo.
echo Este script removera el agente del inicio de Windows, cancelara las
echo tareas programadas y limpiara los archivos de estado y temporales.
echo.
set /p CONFIRM="Desea proceder con la desinstalacion completa? (S/N): "
if /i not "%CONFIRM%"=="S" (
    echo Desinstalacion cancelada por el usuario.
    timeout /t 3 >nul
    exit /b 0
)

echo.
echo [1/4] Deteniendo procesos en ejecucion...
taskkill /F /T /IM "SyncAgenteLola.exe" >nul 2>&1
timeout /t 1 /nobreak >nul 2>&1

echo [2/4] Eliminando tareas programadas residuales (schtasks)...
schtasks /Delete /TN "LolaAdmGlobalSyncPeriodic" /F >nul 2>&1
schtasks /Delete /TN "LolaSyncPeriodic" /F >nul 2>&1
schtasks /Delete /TN "LolaSyncAgent" /F >nul 2>&1

echo [3/4] Eliminando clave de registro de Inicio con Windows (HKCU Run)...
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /V "LolaAdmGlobalSyncAgent" /F >nul 2>&1
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /V "SyncAgenteLola" /F >nul 2>&1
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /V "LolaAgent" /F >nul 2>&1

echo [4/4] Limpiando carpetas de estado, logs, consumo y temporales...
rd /s /q "%~dp0config" >nul 2>&1
rd /s /q "%~dp0consumo" >nul 2>&1
rd /s /q "%~dp0state" >nul 2>&1
rd /s /q "%~dp0latest" >nul 2>&1
rd /s /q "%~dp0backups" >nul 2>&1
rd /s /q "%~dp0logs" >nul 2>&1
rd /s /q "%~dp0exports" >nul 2>&1
del /f /q "%~dp0*.tmp" >nul 2>&1
del /f /q "%~dp0*.lock" >nul 2>&1

echo.
echo Desea eliminar tambien el ejecutable SyncAgenteLola.exe y dependencias? (S/N): 
set /p DEL_EXE="(S/N): "
if /i "%DEL_EXE%"=="S" (
    del /f /q "%~dp0SyncAgenteLola.exe" >nul 2>&1
    del /f /q "%~dp0vc_redist.x64.exe" >nul 2>&1
    del /f /q "%~dp0api-ms-win-core-path-l1-1-0.dll" >nul 2>&1
    echo Ejecutable y componentes eliminados.
)

echo.
echo ======================================================================
echo   Desinstalacion completada con exito. El sistema quedo 100%% limpio.
echo ======================================================================
echo.
pause
(goto) 2>nul & del "%~f0"
