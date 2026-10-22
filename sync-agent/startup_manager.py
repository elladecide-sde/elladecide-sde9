# ============================================================================
# startup_manager.py — Ejecución silenciosa (0 RAM en reposo) en Windows
# ============================================================================

from __future__ import annotations

import logging
import os
import subprocess
import sys

try:
    import winreg
except ImportError:
    winreg = None  # type: ignore

RUN_KEY_PATH = r"Software\Microsoft\Windows\CurrentVersion\Run"
APP_REG_NAME = "LolaAdmGlobalSyncAgent"
TASK_NAME = "LolaAdmGlobalSyncPeriodic"


def get_startup_command(script_path: str, background: bool = False) -> str:
    """Comando para iniciar con Windows: --background si es periódico residente o --once si corre una sola vez."""
    flag = "--background" if background else "--once"
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}" {flag}'
    pythonw = os.path.join(os.path.dirname(sys.executable), "pythonw.exe")
    exe = pythonw if os.path.exists(pythonw) else sys.executable
    return f'"{exe}" "{os.path.abspath(script_path)}" {flag}'


def get_silent_once_command(script_path: str) -> str:
    """Comando que ejecuta una corrida silenciosa única y termina el proceso."""
    return get_startup_command(script_path, background=False)


def set_run_on_startup(enabled: bool, script_path: str, is_periodic: bool = False) -> bool:
    """Activa o desactiva la ejecución con Windows (residente en fondo si es periódico o única si no)."""
    if winreg is None:
        return False
    try:
        key = winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            RUN_KEY_PATH,
            0,
            winreg.KEY_SET_VALUE | winreg.KEY_QUERY_VALUE,
        )
        try:
            if enabled:
                cmd = get_startup_command(script_path, background=is_periodic)
                winreg.SetValueEx(key, APP_REG_NAME, 0, winreg.REG_SZ, cmd)
                logging.info(f"Inicio con Windows configurado (background={is_periodic}): {cmd}")
            else:
                try:
                    winreg.DeleteValue(key, APP_REG_NAME)
                    logging.info("Inicio con Windows DESACTIVADO.")
                except FileNotFoundError:
                    pass
        finally:
            winreg.CloseKey(key)
        return True
    except Exception as e:
        logging.warning(f"No se pudo actualizar el registro de inicio de Windows: {e}")
        return False


def configure_periodic_task(enabled: bool, interval_minutes: int, script_path: str) -> bool:
    """
    Configura o elimina la tarea programada de Windows (`schtasks`) para que el
    agente solo exista en memoria durante los ~2 segundos que dura el chequeo.
    """
    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    if not enabled or interval_minutes <= 0:
        try:
            subprocess.run(
                ["schtasks", "/Delete", "/TN", TASK_NAME, "/F"],
                capture_output=True,
                text=True,
                creationflags=creationflags,
            )
            logging.info("Tarea programada periódica desactivada.")
        except Exception:
            pass
        return True

    cmd = get_silent_once_command(script_path)
    try:
        res = subprocess.run(
            [
                "schtasks",
                "/Create",
                "/F",
                "/SC",
                "MINUTE",
                "/MO",
                str(interval_minutes),
                "/TN",
                TASK_NAME,
                "/TR",
                cmd,
            ],
            capture_output=True,
            text=True,
            creationflags=creationflags,
        )
        if res.returncode == 0:
            logging.info(f"Tarea silenciosa programada cada {interval_minutes} min: {cmd}")
            return True
        logging.warning(f"schtasks devolvió código {res.returncode}: {res.stderr.strip()}")
        return False
    except Exception as e:
        logging.warning(f"No se pudo registrar la tarea programada: {e}")
        return False


def uninstall_all(script_path: str = "") -> bool:
    """
    Remueve completamente el agente del sistema Windows:
      1. Elimina la tarea programada en schtasks (y nombres anteriores).
      2. Elimina la clave de registro en HKCU\\...\\Run (y nombres anteriores).
    """
    ok_sch = configure_periodic_task(False, 0, script_path)
    creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    for legacy_task in ("LolaSyncPeriodic", "LolaSyncAgent"):
        try:
            subprocess.run(
                ["schtasks", "/Delete", "/TN", legacy_task, "/F"],
                capture_output=True,
                creationflags=creationflags,
            )
        except Exception:
            pass

    ok_reg = set_run_on_startup(False, script_path)
    if winreg is not None:
        for legacy_reg in ("SyncAgenteLola", "LolaAgent"):
            try:
                k = winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY_PATH, 0, winreg.KEY_SET_VALUE)
                winreg.DeleteValue(k, legacy_reg)
                winreg.CloseKey(k)
            except Exception:
                pass

    logging.info(f"Desinstalación del sistema ejecutada: Tarea={ok_sch}, Registro={ok_reg}")
    return ok_sch and ok_reg


def create_uninstall_batch(base_dir: str, exe_name: str = "SyncAgenteLola.exe") -> str:
    """
    Genera un script 'Desinstalar.bat' autónomo y limpio junto al ejecutable.
    Permite eliminar el agente y todas sus referencias sin dejar rastro en la VM.
    """
    bat_path = os.path.join(base_dir, "Desinstalar.bat")
    content = f"""@echo off
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
taskkill /F /T /IM "{exe_name}" >nul 2>&1
timeout /t 1 /nobreak >nul 2>&1

echo [2/4] Eliminando tareas programadas residuales (schtasks)...
schtasks /Delete /TN "{TASK_NAME}" /F >nul 2>&1
schtasks /Delete /TN "LolaSyncPeriodic" /F >nul 2>&1
schtasks /Delete /TN "LolaSyncAgent" /F >nul 2>&1

echo [3/4] Eliminando clave de registro de Inicio con Windows (HKCU Run)...
reg delete "HKCU\\{RUN_KEY_PATH}" /V "{APP_REG_NAME}" /F >nul 2>&1
reg delete "HKCU\\{RUN_KEY_PATH}" /V "SyncAgenteLola" /F >nul 2>&1
reg delete "HKCU\\{RUN_KEY_PATH}" /V "LolaAgent" /F >nul 2>&1

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
echo Desea eliminar tambien el ejecutable {exe_name} y dependencias? (S/N): 
set /p DEL_EXE="(S/N): "
if /i "%DEL_EXE%"=="S" (
    del /f /q "%~dp0{exe_name}" >nul 2>&1
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
"""
    try:
        with open(bat_path, "w", encoding="utf-8") as f:
            f.write(content)
        return bat_path
    except Exception as e:
        logging.warning(f"No se pudo crear Desinstalar.bat: {e}")
        return ""

