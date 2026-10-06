@echo off
setlocal
rem Open the already-built debug APK in labelled UI preview mode.
rem The PowerShell script reuses gxnu_preview when it is already running.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0preview-android.ps1" -Preview -NoBuild
if errorlevel 1 (
    echo.
    echo The campus preview could not be opened. Check the message above.
    pause
    exit /b 1
)
endlocal
