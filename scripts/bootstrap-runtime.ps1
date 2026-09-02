#Requires -Version 5.1

[CmdletBinding()]
param(
    [switch]$CheckOnly,
    [switch]$ForceDownload
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$GStreamerVersion = '1.28.5'
$GStreamerUrl = "https://gstreamer.freedesktop.org/data/pkg/windows/$GStreamerVersion/msvc/gstreamer-1.0-msvc-x86_64-$GStreamerVersion.exe"
$GStreamerSha256 = '51ee5eaec33008e8409d8cf6f6884457f22aa3bd515f8856f993a3eaab903530'
$Workspace = Split-Path -Parent $PSScriptRoot
$RuntimeRoot = Join-Path $Workspace '.runtime'
$InstallRoot = Join-Path $RuntimeRoot 'gstreamer'
$DownloadRoot = Join-Path $RuntimeRoot 'downloads'
$Installer = Join-Path $DownloadRoot "gstreamer-$GStreamerVersion.exe"
$EmbeddedJava = Join-Path $Workspace 'runtime\bin\java.exe'
$JavaExecutable = if (Test-Path -LiteralPath $EmbeddedJava) {
    (Resolve-Path -LiteralPath $EmbeddedJava).Path
} else {
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCommand) { $javaCommand.Source } else { $null }
}

function Test-Java25 {
    if (!$JavaExecutable) {
        throw 'Java was not found. Use a complete release package or install JDK 25.'
    }
    try {
        $versionOutput = (& cmd.exe /d /c "`"$JavaExecutable`" -version 2>&1" | Out-String)
    } catch {
        throw "Unable to execute Java at '$JavaExecutable'."
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to execute Java at '$JavaExecutable'."
    }
    if ($versionOutput -notmatch 'version\s+"25(\.|\")') {
        throw "Java 25 is required. Current output: $($versionOutput.Trim())"
    }
    Write-Host "[OK] Java 25 is available at $JavaExecutable." -ForegroundColor Green
}

function Find-GStreamerBin([string]$Root) {
    $candidates = @(
        (Join-Path $Root 'bin'),
        (Join-Path $Root '1.0\msvc_x86_64\bin'),
        (Join-Path $Root 'msvc_x86_64\bin')
    )
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'gst-inspect-1.0.exe')) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    return $null
}

function Find-SystemGStreamerBin {
    $roots = @(
        [Environment]::GetEnvironmentVariable('GSTREAMER_1_0_ROOT_MSVC_X86_64', 'Process'),
        [Environment]::GetEnvironmentVariable('GSTREAMER_1_0_ROOT_MSVC_X86_64', 'User'),
        [Environment]::GetEnvironmentVariable('GSTREAMER_1_0_ROOT_MSVC_X86_64', 'Machine')
    )
    if ($env:LOCALAPPDATA) {
        $roots += (Join-Path $env:LOCALAPPDATA 'Programs\gstreamer\1.0\msvc_x86_64')
    }
    if ($env:ProgramFiles) {
        $roots += (Join-Path $env:ProgramFiles 'gstreamer\1.0\msvc_x86_64')
    }
    foreach ($root in $roots) {
        if (!$root) { continue }
        $bin = Find-GStreamerBin $root
        if ($bin) { return $bin }
    }
    return $null
}

function Get-ClashProxy {
    try {
        $version = Invoke-RestMethod -Uri 'http://127.0.0.1:9090/version' -TimeoutSec 5
        $config = Invoke-RestMethod -Uri 'http://127.0.0.1:9090/configs' -TimeoutSec 5
        $mixedPort = [int]$config.'mixed-port'
        if ($mixedPort -le 0) { return $null }
        $proxy = "http://127.0.0.1:$mixedPort"
        $probe = Invoke-WebRequest -Uri 'https://services.gradle.org/versions/current' -Proxy $proxy -UseBasicParsing -TimeoutSec 15
        if ($probe.StatusCode -eq 200) {
            Write-Host "[OK] FlClash $($version.version) proxy is available at $proxy." -ForegroundColor Green
            return $proxy
        }
    } catch {
        Write-Host "[WARN] FlClash proxy check failed: $($_.Exception.Message)" -ForegroundColor Yellow
    }
    return $null
}

function Get-FileSha256([string]$Path) {
    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $stream = [System.IO.File]::OpenRead($Path)
        try {
            $hashBytes = $sha256.ComputeHash($stream)
            return [System.BitConverter]::ToString($hashBytes).Replace('-', '').ToLowerInvariant()
        } finally {
            $stream.Dispose()
        }
    } finally {
        $sha256.Dispose()
    }
}

function Test-GStreamerElement([string]$Inspect, [string]$Element) {
    # Windows PowerShell 5.1 converts native stderr into error records. This local
    # override lets a missing element return false instead of terminating the script.
    $ErrorActionPreference = 'Continue'
    & $Inspect $Element *> $null
    return $LASTEXITCODE -eq 0
}

function Test-GStreamerPlugins([string]$Bin) {
    $inspect = Join-Path $Bin 'gst-inspect-1.0.exe'
    $required = @('appsrc', 'clocksync', 'h264parse', 'avdec_h264', 'h265parse', 'avdec_h265', 'avdec_aac', 'avdec_alac', 'autovideosink', 'autoaudiosink')
    foreach ($plugin in $required) {
        if (!(Test-GStreamerElement $inspect $plugin)) {
            throw "The project-local GStreamer runtime is missing required plugin '$plugin'."
        }
    }
    $d3d12Available = Test-GStreamerElement $inspect 'd3d12h264dec'
    $d3d11Available = Test-GStreamerElement $inspect 'd3d11h264dec'
    $nvdecAvailable = Test-GStreamerElement $inspect 'nvh264dec'
    $d3d12HevcAvailable = Test-GStreamerElement $inspect 'd3d12h265dec'
    $d3d11HevcAvailable = Test-GStreamerElement $inspect 'd3d11h265dec'
    $nvdecHevcAvailable = Test-GStreamerElement $inspect 'nvh265dec'
    if ($d3d12Available) { Write-Host '[OK] D3D12 hardware H.264 decoding is available.' -ForegroundColor Green }
    if ($nvdecAvailable) { Write-Host '[OK] NVIDIA NVDEC H.264 decoding is available.' -ForegroundColor Green }
    if ($d3d11Available) { Write-Host '[OK] D3D11 hardware H.264 decoding is available.' -ForegroundColor Green }
    if (!$d3d12Available -and !$nvdecAvailable -and !$d3d11Available) {
        Write-Host '[WARN] No Windows hardware H.264 decoder was found; avdec_h264 will be used.' -ForegroundColor Yellow
    }
    if ($d3d12HevcAvailable) { Write-Host '[OK] D3D12 hardware HEVC decoding is available.' -ForegroundColor Green }
    if ($nvdecHevcAvailable) { Write-Host '[OK] NVIDIA NVDEC HEVC decoding is available.' -ForegroundColor Green }
    if ($d3d11HevcAvailable) { Write-Host '[OK] D3D11 hardware HEVC decoding is available.' -ForegroundColor Green }
    if (!$d3d12HevcAvailable -and !$nvdecHevcAvailable -and !$d3d11HevcAvailable) {
        Write-Host '[WARN] No Windows hardware HEVC decoder was found; experimental HEVC uses avdec_h265.' -ForegroundColor Yellow
    }
    Write-Host '[OK] Required GStreamer video and audio plugins are available.' -ForegroundColor Green
}

function Copy-SystemGStreamer([string]$SystemBin) {
    $sourceRoot = Split-Path -Parent $SystemBin
    if (!(Test-Path -LiteralPath $RuntimeRoot)) {
        New-Item -ItemType Directory -Path $RuntimeRoot | Out-Null
    }
    Write-Host "Copying existing GStreamer runtime from '$sourceRoot' to '$InstallRoot'..."
    & robocopy $sourceRoot $InstallRoot /E /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -gt 7) {
        throw "Unable to copy GStreamer runtime. Robocopy exit code: $LASTEXITCODE"
    }
}

function Install-GStreamer {
    if (!(Test-Path -LiteralPath $DownloadRoot)) {
        New-Item -ItemType Directory -Path $DownloadRoot -Force | Out-Null
    }
    $proxy = Get-ClashProxy
    if (!(Test-Path -LiteralPath $Installer) -or $ForceDownload) {
        Write-Host "Downloading GStreamer $GStreamerVersion to '$Installer'..."
        if ($proxy) {
            Invoke-WebRequest -Uri $GStreamerUrl -OutFile $Installer -Proxy $proxy -UseBasicParsing
        } else {
            Invoke-WebRequest -Uri $GStreamerUrl -OutFile $Installer -UseBasicParsing
        }
    }
    $actualHash = Get-FileSha256 $Installer
    if ($actualHash -ne $GStreamerSha256) {
        throw "GStreamer installer checksum mismatch. Expected $GStreamerSha256 but got $actualHash."
    }
    Write-Host '[OK] GStreamer installer checksum verified.' -ForegroundColor Green

    if (!(Test-Path -LiteralPath $InstallRoot)) {
        New-Item -ItemType Directory -Path $InstallRoot -Force | Out-Null
    }
    $arguments = @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART', '/SP-', "/DIR=`"$InstallRoot`"")
    $process = Start-Process -FilePath $Installer -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        throw "GStreamer installer failed with exit code $($process.ExitCode)."
    }
}

Test-Java25
$localBin = Find-GStreamerBin $InstallRoot
if (!$localBin -and $CheckOnly) {
    throw "Project-local GStreamer is missing. Run '$PSScriptRoot\bootstrap-runtime.ps1' without -CheckOnly."
}
if (!$localBin) {
    $systemBin = Find-SystemGStreamerBin
    if ($systemBin -and !$ForceDownload) {
        Copy-SystemGStreamer $systemBin
    } else {
        Install-GStreamer
    }
    $localBin = Find-GStreamerBin $InstallRoot
}
if (!$localBin) {
    throw "GStreamer installation completed but gst-inspect-1.0.exe was not found below '$InstallRoot'."
}

Test-GStreamerPlugins $localBin
Write-Host "[OK] Project-local runtime: $localBin" -ForegroundColor Green
Write-Output $localBin
