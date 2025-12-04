param(
    [string]$Version = "v24.3.25",
    [string]$InstallDir = (Join-Path $PSScriptRoot "..\\external\\flatbuffers-prebuilt")
)

$ErrorActionPreference = "Stop"
$InstallDir = [System.IO.Path]::GetFullPath($InstallDir)

function Write-FlatcStatus {
    param([string]$Message)
    Write-Host "[flatc] $Message" -ForegroundColor Cyan
}

function Resolve-PlatformKey {
    $runtime = [System.Runtime.InteropServices.RuntimeInformation]
    $os = if ($runtime::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::Windows)) {
        "windows"
    } elseif ($runtime::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::Linux)) {
        "linux"
    } elseif ($runtime::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::OSX)) {
        "mac"
    } else {
        throw "Unsupported host OS. Please install flatc manually."
    }

    $archValue = $null
    try {
        $archValue = $runtime::ProcessArchitecture
    } catch {
        $archValue = $null
    }

    $arch = if ($archValue) {
        $archValue.ToString().ToLowerInvariant()
    } else {
        if ([IntPtr]::Size -eq 8) { "x64" } else { "x86" }
    }

    switch ($arch) {
        "x64"   { $arch = "x86_64" }
        "arm64" { $arch = "arm64" }
        default { throw "Unsupported architecture '$arch'. Please install flatc manually." }
    }

    switch ($os) {
        "windows" { return "windows-$arch" }
        "linux"   { return "linux-$arch" }
        "mac"     {
            if ($arch -eq "x86_64") { return "mac-x86_64" }
            if ($arch -eq "arm64")  { return "mac-arm64" }
            throw "Unsupported mac architecture '$arch'."
        }
    }
}

$platformKey = Resolve-PlatformKey
$assetTable = @{
    "windows-x86_64" = @{ Asset = "Windows.flatc.binary.zip"; Binary = "flatc.exe" }
    "linux-x86_64"   = @{ Asset = "Linux.flatc.binary.g++-13.zip"; Binary = "flatc" }
    "mac-arm64"      = @{ Asset = "Mac.flatc.binary.zip"; Binary = "flatc" }
    "mac-x86_64"     = @{ Asset = "MacIntel.flatc.binary.zip"; Binary = "flatc" }
}

if (-not $assetTable.ContainsKey($platformKey)) {
    throw "No prebuilt flatc asset defined for '$platformKey'. Install flatc manually and set FLATC_HOST_PATH."
}

$platformDir = [System.IO.Path]::GetFullPath((Join-Path $InstallDir $platformKey))
$binaryName  = $assetTable[$platformKey].Binary
$flatcPath   = [System.IO.Path]::GetFullPath((Join-Path $platformDir $binaryName))

if (Test-Path $flatcPath) {
    Write-FlatcStatus "flatc 已存在：$flatcPath"
    return
}

New-Item -ItemType Directory -Force -Path $platformDir | Out-Null

$tempRoot   = Join-Path ([System.IO.Path]::GetTempPath()) ("flatc-download-" + [Guid]::NewGuid().ToString("N"))
$archiveDir = Join-Path $tempRoot "archive"
New-Item -ItemType Directory -Force -Path $archiveDir | Out-Null

$assetName = $assetTable[$platformKey].Asset
$downloadUrl = "https://github.com/google/flatbuffers/releases/download/$Version/$assetName"
$archivePath = Join-Path $tempRoot $assetName

Write-FlatcStatus "下载 flatc ($platformKey) → $downloadUrl"
Invoke-WebRequest -Uri $downloadUrl -UseBasicParsing -OutFile $archivePath

try {
    Expand-Archive -Path $archivePath -DestinationPath $archiveDir -Force
    $downloadedBinary = Get-ChildItem -Path $archiveDir -Recurse -Filter $binaryName | Select-Object -First 1
    if (-not $downloadedBinary) {
        throw "在下载的压缩包中未找到 $binaryName"
    }

    Copy-Item -Path $downloadedBinary.FullName -Destination $flatcPath -Force
    Write-FlatcStatus "flatc 已安装到 $flatcPath"
}
finally {
    if (Test-Path $tempRoot) {
        Remove-Item -Path $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
