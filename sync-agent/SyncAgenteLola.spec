# -*- mode: python ; coding: utf-8 -*-
# ============================================================================
# SyncAgenteLola.spec — Empaquetado en un único .exe sin consola (console=False)
#
# Para generar dist\SyncAgenteLola.exe:
#   cd sync-agent
#   py -m PyInstaller SyncAgenteLola.spec
# ============================================================================

a = Analysis(
    ['main.py'],
    pathex=[],
    binaries=[],
    datas=[],
    hiddenimports=['pyodbc', 'tkinter', 'winreg', 'pystray', 'pystray._win32', 'PIL', 'PIL.Image', 'PIL.ImageDraw'],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=['PySide6', 'PyQt5', 'matplotlib', 'numpy', 'pandas', 'scipy'],
    noarchive=False,
    optimize=1,
)
pyz = PYZ(a.pure)

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name='SyncAgenteLola',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=True,
    upx_exclude=[],
    runtime_tmpdir=None,
    console=False,  # CERO consola emergente: cuando corre con --once es 100% invisible
    disable_windowed_traceback=False,
    argv_emulation=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)
