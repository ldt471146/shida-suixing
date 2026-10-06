param(
    [switch]$Preview,
    [switch]$NoBuild,
    [ValidateSet('READY','ONLINE','AUTH_ERROR','NO_WIFI','NEED_ACCOUNT','NEED_PROVIDER','NEED_PERMISSION','OUTSIDE_CAMPUS','UNREACHABLE','PREPARING','CHECKING','AUTHENTICATING','VERIFYING','CANCELLED')]
    [string]$State = 'READY',
    [string]$SdkPath = 'D:\Android\sdk',
    [string]$AvdPath = 'D:\Android\avd',
    [string]$JdkPath = 'C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot'
)
$ErrorActionPreference = 'Stop'
$campusRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$campusAdb = Join-Path $SdkPath 'platform-tools\adb.exe'
$campusEmulator = Join-Path $SdkPath 'emulator\emulator.exe'
$campusApk = Join-Path $campusRoot 'app\build\outputs\apk\debug\app-debug.apk'
$campusSerial = 'emulator-5556'
$campusOldJava = $env:JAVA_HOME
$campusOldAndroid = $env:ANDROID_HOME
$campusOldSdk = $env:ANDROID_SDK_ROOT
$campusOldAvd = $env:ANDROID_AVD_HOME
. (Join-Path $PSScriptRoot 'gradle-proxy.ps1')

function Invoke-CampusAdb {
    param([string[]]$Arguments)
    & $campusAdb -s $campusSerial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB command failed ($LASTEXITCODE)." }
}

try {
    if (!(Test-Path -LiteralPath $campusAdb)) { throw "Android SDK not found: $SdkPath" }
    $env:ANDROID_HOME = $SdkPath
    $env:ANDROID_SDK_ROOT = $SdkPath
    $env:ANDROID_AVD_HOME = $AvdPath
    if (!$NoBuild) {
        if (!(Test-Path -LiteralPath (Join-Path $JdkPath 'bin\java.exe'))) { throw "JDK 17 not found: $JdkPath" }
        $env:JAVA_HOME = $JdkPath
        $env:ANDROID_HOME = $SdkPath
        $env:ANDROID_SDK_ROOT = $SdkPath
        Push-Location $campusRoot
        try {
            $campusProxyArguments = Get-CampusGradleProxyArguments
            & (Join-Path $campusRoot 'gradlew.bat') ':app:assembleDebug' '--console=plain' @campusProxyArguments
            if ($LASTEXITCODE -ne 0) { throw 'Android build failed.' }
        } finally { Pop-Location }
    }
    if (!(Test-Path -LiteralPath $campusApk)) { throw 'APK missing; run without -NoBuild first.' }
    & $campusAdb start-server | Out-Null
    $campusDevices = & $campusAdb devices
    if ($campusDevices -match "^$campusSerial\s+device") {
        $campusAvd = & $campusAdb -s $campusSerial emu avd name
        if ($campusAvd -notcontains 'gxnu_preview') { throw 'Port 5556 belongs to a different emulator. It was left running.' }
    } else {
        $campusPorts = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
            Where-Object { $_.LocalPort -in @(5556,5557) }
        if ($campusPorts) { throw 'Emulator port 5556/5557 is already in use.' }
        $campusAvds = & $campusEmulator -list-avds
        if ($campusAvds -notcontains 'gxnu_preview') { throw 'The gxnu_preview Android virtual device is missing.' }
        Write-Output 'Opening gxnu_preview on your desktop...'
        $campusLogDir = Join-Path $campusRoot ('.local\emulator-logs\gxnu-preview-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
        New-Item -ItemType Directory -Path $campusLogDir -Force | Out-Null
        Start-Process $campusEmulator -ArgumentList @('-avd','gxnu_preview','-port','5556','-no-boot-anim','-gpu','software') `
            -WindowStyle Normal -RedirectStandardOutput (Join-Path $campusLogDir 'emulator.out.log') `
            -RedirectStandardError (Join-Path $campusLogDir 'emulator.err.log') | Out-Null
    }
    $campusDeadline = (Get-Date).AddMinutes(4)
    do {
        $campusBoot = & $campusAdb -s $campusSerial shell getprop sys.boot_completed 2>$null
        if ($campusBoot -eq '1') { break }
        if ((Get-Date) -gt $campusDeadline) { throw 'The emulator has not finished booting. Check its visible window.' }
        Start-Sleep -Seconds 2
    } while ($true)
    Write-Output 'Installing the Android App (saved settings are preserved)...'
    Invoke-CampusAdb -Arguments @('install','-r','-t',$campusApk)
    $campusLaunch = @('shell','am','start','-S','-n','cn.gxnu.campus/.MainActivity','--ez','campus.preview', $(if ($Preview) {'true'} else {'false'}))
    if ($Preview) { $campusLaunch += @('--es','campus.preview.status',$State) }
    Invoke-CampusAdb -Arguments $campusLaunch
    # Bring this task's already-running preview to the front as well as launching the Activity.
    $campusPreviewWindow = Get-Process -Name 'qemu-system-x86_64' -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowTitle -eq 'Android Emulator - gxnu_preview:5556' } |
        Select-Object -First 1
    if ($campusPreviewWindow -and $campusPreviewWindow.MainWindowHandle -ne 0) {
        if (-not ('CampusPreviewWindow' -as [type])) {
            Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class CampusPreviewWindow {
    [DllImport("user32.dll")] public static extern bool ShowWindowAsync(IntPtr window, int command);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr window);
}
'@
        }
        [CampusPreviewWindow]::ShowWindowAsync($campusPreviewWindow.MainWindowHandle, 9) | Out-Null
        [CampusPreviewWindow]::SetForegroundWindow($campusPreviewWindow.MainWindowHandle) | Out-Null
    }
    Write-Output $(if ($Preview) {'App opened in clearly labelled UI preview mode.'} else {'App opened. Campus authentication requires physical GXNU-YC Wi-Fi.'})
} finally {
    $env:JAVA_HOME = $campusOldJava
    $env:ANDROID_HOME = $campusOldAndroid
    $env:ANDROID_SDK_ROOT = $campusOldSdk
    $env:ANDROID_AVD_HOME = $campusOldAvd
}
