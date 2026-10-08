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
    # The embedded school page is a JavaScript asset no Gradle task covers, so its suite runs here.
    # It used to run nowhere, and a bridge that filled nothing shipped as a result.
    if ($Task -eq 'testDebugUnitTest') {
        $campusNode = Get-Command node -ErrorAction SilentlyContinue
        if ($campusNode) {
            & $campusNode.Source --test (Join-Path $campusRoot 'scripts\test-portal-bridge.cjs')
            if ($LASTEXITCODE -ne 0) { throw "The official-page bridge checks failed ($LASTEXITCODE)." }
        } else {
            Write-Warning 'node was not found; skipped scripts/test-portal-bridge.cjs. CI runs it.'
        }
    }
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
