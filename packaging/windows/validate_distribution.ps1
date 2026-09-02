#Requires -Version 5.1

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Root
)

$ErrorActionPreference = 'Stop'
$distributionRoot = (Resolve-Path -LiteralPath $Root).Path
$launcher = Join-Path $distributionRoot 'JavaAirPlayReceiver.exe'
$javaExecutable = Join-Path $distributionRoot 'runtime\bin\java.exe'
$gstreamerRoot = Join-Path $distributionRoot '.runtime\gstreamer'
$gstreamerBin = Join-Path $gstreamerRoot 'bin'
$gstreamerPlugins = Join-Path $gstreamerRoot 'lib\gstreamer-1.0'
$gstreamerScanner = Join-Path $gstreamerRoot 'libexec\gstreamer-1.0\gst-plugin-scanner.exe'
$gstreamerInspect = Join-Path $gstreamerBin 'gst-inspect-1.0.exe'

$requiredFiles = @(
    $launcher,
    (Join-Path $distributionRoot 'java-airplay-server.jar'),
    $javaExecutable,
    (Join-Path $distributionRoot 'runtime\bin\javaw.exe'),
    $gstreamerInspect,
    $gstreamerScanner
)
foreach ($requiredFile in $requiredFiles) {
    if (!(Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Distribution is missing required file: $requiredFile"
    }
}

$serverJars = @(Get-ChildItem -LiteralPath $distributionRoot -Filter 'java-airplay-server*.jar' -File)
if ($serverJars.Count -ne 1 -or $serverJars[0].Name -ne 'java-airplay-server.jar') {
    throw 'Distribution must contain exactly one server JAR named java-airplay-server.jar.'
}
if (!(Test-Path -LiteralPath $gstreamerPlugins -PathType Container)) {
    throw "Distribution is missing the GStreamer plugin directory: $gstreamerPlugins"
}

& $javaExecutable --version *> $null
if ($LASTEXITCODE -ne 0) {
    throw "Bundled Java runtime failed with exit code $LASTEXITCODE."
}

$launcherValidation = Start-Process -FilePath $launcher `
    -ArgumentList '--validate-installation' -Wait -PassThru
if ($launcherValidation.ExitCode -ne 0) {
    throw "Launcher validation failed with exit code $($launcherValidation.ExitCode)."
}

$environmentNames = @(
    'PATH',
    'GST_PLUGIN_PATH',
    'GST_PLUGIN_PATH_1_0',
    'GST_PLUGIN_SYSTEM_PATH',
    'GST_PLUGIN_SYSTEM_PATH_1_0',
    'GST_PLUGIN_SCANNER',
    'GST_PLUGIN_SCANNER_1_0',
    'GST_REGISTRY_1_0'
)
$previousEnvironment = @{}
foreach ($environmentName in $environmentNames) {
    $previousEnvironment[$environmentName] = [Environment]::GetEnvironmentVariable(
        $environmentName, 'Process')
}
$registryPath = Join-Path $env:TEMP "java-airplay-gstreamer-$([Guid]::NewGuid()).bin"

try {
    $env:PATH = "$gstreamerBin;$($previousEnvironment['PATH'])"
    $env:GST_PLUGIN_PATH = $gstreamerPlugins
    $env:GST_PLUGIN_PATH_1_0 = $gstreamerPlugins
    $env:GST_PLUGIN_SYSTEM_PATH = $gstreamerPlugins
    $env:GST_PLUGIN_SYSTEM_PATH_1_0 = $gstreamerPlugins
    $env:GST_PLUGIN_SCANNER = $gstreamerScanner
    $env:GST_PLUGIN_SCANNER_1_0 = $gstreamerScanner
    $env:GST_REGISTRY_1_0 = $registryPath

    $requiredPlugins = @(
        'appsrc',
        'clocksync',
        'h264parse',
        'avdec_h264',
        'h265parse',
        'avdec_h265',
        'avdec_aac',
        'avdec_alac',
        'autovideosink',
        'autoaudiosink'
    )
    foreach ($plugin in $requiredPlugins) {
        & $gstreamerInspect $plugin *> $null
        if ($LASTEXITCODE -ne 0) {
            throw "Bundled GStreamer runtime cannot load required plugin '$plugin'."
        }
    }
} finally {
    foreach ($environmentName in $environmentNames) {
        [Environment]::SetEnvironmentVariable(
            $environmentName,
            $previousEnvironment[$environmentName],
            'Process')
    }
    if (Test-Path -LiteralPath $registryPath) {
        Remove-Item -LiteralPath $registryPath -Force
    }
}

Write-Host "[OK] Windows distribution validated at $distributionRoot" -ForegroundColor Green
