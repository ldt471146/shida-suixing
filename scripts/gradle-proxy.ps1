function Get-CampusGradleProxyArguments {
    $campusProxyValue = $env:HTTPS_PROXY
    if (!$campusProxyValue) { $campusProxyValue = $env:HTTP_PROXY }
    if (!$campusProxyValue) { return @() }
    $campusProxyUri = $null
    if (![Uri]::TryCreate($campusProxyValue, [UriKind]::Absolute, [ref]$campusProxyUri) -or
        !$campusProxyUri.Host -or $campusProxyUri.UserInfo) {
        throw 'Configure an HTTP proxy without embedded credentials for Gradle, or unset HTTP_PROXY/HTTPS_PROXY.'
    }
    return @(
        ('-Dhttps.proxyHost=' + $campusProxyUri.Host),
        ('-Dhttps.proxyPort=' + $campusProxyUri.Port),
        ('-Dhttp.proxyHost=' + $campusProxyUri.Host),
        ('-Dhttp.proxyPort=' + $campusProxyUri.Port)
    )
}
