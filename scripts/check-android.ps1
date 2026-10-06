param(
    [ValidateSet('testDebugUnitTest','assembleDebug','assembleRelease','lintDebug','compileDebugKotlin')]
    [string]$Task = 'testDebugUnitTest',
    [string]$SdkPath = 'D:\Android\sdk',
    [string]$JdkPath = 'C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot'
)
$ErrorActionPreference = 'Stop'
$campusRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$campusOldJava = $env:JAVA_HOME
$campusOldAndroid = $env:ANDROID_HOME
$campusOldSdk = $env:ANDROID_SDK_ROOT
. (Join-Path $PSScriptRoot 'gradle-proxy.ps1')
try {
    $env:JAVA_HOME = $JdkPath
    $env:ANDROID_HOME = $SdkPath
    $env:ANDROID_SDK_ROOT = $SdkPath
    Push-Location $campusRoot
    try {
        $campusProxyArguments = Get-CampusGradleProxyArguments
        & (Join-Path $campusRoot 'gradlew.bat') (':app:' + $Task) '--console=plain' @campusProxyArguments
        $campusExitCode = $LASTEXITCODE
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $campusOldJava
    $env:ANDROID_HOME = $campusOldAndroid
    $env:ANDROID_SDK_ROOT = $campusOldSdk
}
exit $campusExitCode
