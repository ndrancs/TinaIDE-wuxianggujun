[CmdletBinding()]
param(
    [ValidateSet("arm64", "x86_64")]
    [string]$Abi = "arm64",
    [string]$ReleaseDir,
    [string]$SysrootDir,
    [string]$ArchiveDir,
    [switch]$ArchiveOnly,
    [switch]$SkipSysroot,
    [switch]$Clean
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host "[sync-assets] $Message" -ForegroundColor Cyan
}

function Resolve-RepoRoot {
    $root = Resolve-Path (Join-Path $PSScriptRoot "..")
    return $root.Path
}

function Resolve-InputPath {
    param(
        [string]$RepoRoot,
        [string]$InputPath
    )
    if ([string]::IsNullOrWhiteSpace($InputPath)) { return $null }
    if ([System.IO.Path]::IsPathRooted($InputPath)) { return $InputPath }
    return Join-Path $RepoRoot $InputPath
}

function Read-PropertiesFile {
    param([string]$Path)
    $map = @{}
    Get-Content -LiteralPath $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if (-not $line) { return }
        if ($line.StartsWith("#")) { return }
        $kv = $line.Split("=", 2)
        if ($kv.Count -ne 2) { return }
        $key = $kv[0].Trim()
        $value = $kv[1].Trim()
        if ($key) { $map[$key] = $value }
    }
    return $map
}

function Move-StaleFile {
    param(
        [System.IO.FileInfo]$File,
        [string]$ArchiveDir
    )

    New-Item -ItemType Directory -Force -Path $ArchiveDir | Out-Null
    $destination = Join-Path $ArchiveDir $File.Name
    if (Test-Path -LiteralPath $destination) {
        $baseName = [System.IO.Path]::GetFileNameWithoutExtension($File.Name)
        $extension = [System.IO.Path]::GetExtension($File.Name)
        $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
        $destination = Join-Path $ArchiveDir ("{0}-{1}{2}" -f $baseName, $stamp, $extension)
    }

    Move-Item -LiteralPath $File.FullName -Destination $destination -Force
    Write-Host ("  ARCHIVE {0} -> {1}" -f $File.Name, $destination) -ForegroundColor Yellow
}

$repoRoot = Resolve-RepoRoot
$releaseRoot = (Resolve-InputPath -RepoRoot $repoRoot -InputPath $ReleaseDir)
if (-not $releaseRoot) { $releaseRoot = Join-Path $repoRoot "build/tina-toolchain/release" }

$sysrootRoot = (Resolve-InputPath -RepoRoot $repoRoot -InputPath $SysrootDir)
if (-not $sysrootRoot) { $sysrootRoot = Join-Path $repoRoot "build/tina-toolchain/_tmp_sysroot_out" }

$archiveRoot = (Resolve-InputPath -RepoRoot $repoRoot -InputPath $ArchiveDir)
if (-not $archiveRoot) { $archiveRoot = Join-Path $repoRoot "app/.local/toolchain-archive/$Abi" }
$appSrcRoot = Join-Path $repoRoot "app/src"
$archiveRootFull = [System.IO.Path]::GetFullPath($archiveRoot)
$appSrcRootFull = [System.IO.Path]::GetFullPath($appSrcRoot)
if ($archiveRootFull.StartsWith($appSrcRootFull, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "ArchiveDir must be outside app/src to avoid packaging into APK: $archiveRootFull"
}

$assetsRoot = Join-Path $repoRoot "app/src/$Abi/assets/tina-toolchain"
$sysrootAssetsRoot = Join-Path $repoRoot "app/src/$Abi/assets/android-sysroot"
$specPath = Join-Path $assetsRoot "current.properties"
$toolchainArchiveRoot = Join-Path $archiveRoot "tina-toolchain"
$sysrootArchiveRoot = Join-Path $archiveRoot "android-sysroot"
$sysrootFileName = switch ($Abi) {
    "arm64" { "android-sysroot-arm64-all.tar.xz" }
    "x86_64" { "android-sysroot-x86_64-all.tar.xz" }
}

if (-not (Test-Path -LiteralPath $specPath)) {
    throw "Toolchain spec not found: $specPath (expected in flavor assets)"
}
if ($ArchiveOnly) {
    $Clean = $true
}
if (-not $ArchiveOnly -and -not (Test-Path -LiteralPath $releaseRoot)) {
    throw "Toolchain release directory not found: $releaseRoot (run toolchain builder first)"
}
if (-not $ArchiveOnly -and -not $SkipSysroot -and -not (Test-Path -LiteralPath $sysrootRoot)) {
    throw "Sysroot directory not found: $sysrootRoot (run toolchain builder first)"
}

$spec = Read-PropertiesFile -Path $specPath
$requiredKeys = @("version", "arch")
$missingKeys = @()
foreach ($k in $requiredKeys) {
    if ([string]::IsNullOrWhiteSpace($spec[$k])) {
        $missingKeys += $k
    }
}
if ($missingKeys.Count -gt 0) {
    throw "Invalid spec file (missing: $($missingKeys -join ', ')): $specPath"
}

$version = $spec["version"]
$arch = $spec["arch"]
$full = if ([string]::IsNullOrWhiteSpace($spec["full"])) { $null } else { $spec["full"] }
$base = if ([string]::IsNullOrWhiteSpace($spec["base"])) { $null } else { $spec["base"] }
$tools = if ([string]::IsNullOrWhiteSpace($spec["tools"])) { $null } else { $spec["tools"] }
$sha256 = $spec["sha256"]

if ([string]::IsNullOrWhiteSpace($full) -and [string]::IsNullOrWhiteSpace($base)) {
    throw "Invalid spec file: one of 'full' or 'base' is required: $specPath"
}

if (-not [string]::IsNullOrWhiteSpace($full) -and -not [string]::IsNullOrWhiteSpace($tools)) {
    Write-Host "  WARN full+tools both set in spec, tools will be ignored: $specPath" -ForegroundColor Yellow
}

$expectedArch = switch ($Abi) {
    "arm64" { "aarch64" }
    "x86_64" { "x86_64" }
}
if ($arch -ne $expectedArch) {
    throw "Spec arch mismatch: abi=$Abi expects arch=$expectedArch, but current.properties has arch=$arch"
}

$mainArchive = if (-not [string]::IsNullOrWhiteSpace($full)) { $full } else { $base }
$mode = if (-not [string]::IsNullOrWhiteSpace($full)) { "full" } else { "split" }
$needed = @(
    $mainArchive,
    $(if ($mode -eq "split") { $tools } else { $null }),
    $(if ([string]::IsNullOrWhiteSpace($sha256)) { $null } else { $sha256 })
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique

Write-Step "Sync tina-toolchain assets: abi=$Abi version=$version arch=$arch mode=$mode"
Write-Host "  Spec:     $specPath" -ForegroundColor DarkGray
Write-Host "  Release:  $releaseRoot" -ForegroundColor DarkGray
Write-Host "  Assets:   $assetsRoot" -ForegroundColor DarkGray
Write-Host "  Archive:  $archiveRoot" -ForegroundColor DarkGray
if (-not $SkipSysroot) {
    Write-Host "  Sysroot:  $sysrootRoot" -ForegroundColor DarkGray
    Write-Host "  SysAsset: $sysrootAssetsRoot" -ForegroundColor DarkGray
}

foreach ($name in $needed) {
    if (-not $ArchiveOnly) {
        $src = Join-Path $releaseRoot $name
        if (-not (Test-Path -LiteralPath $src)) {
            throw "Missing release asset: $src (expected by current.properties)"
        }
    }
}
if (-not $ArchiveOnly -and -not $SkipSysroot) {
    $sysrootSrc = Join-Path $sysrootRoot $sysrootFileName
    if (-not (Test-Path -LiteralPath $sysrootSrc)) {
        throw "Missing sysroot asset: $sysrootSrc"
    }
}

New-Item -ItemType Directory -Force -Path $assetsRoot | Out-Null
if (-not $SkipSysroot) {
    New-Item -ItemType Directory -Force -Path $sysrootAssetsRoot | Out-Null
}

if ($Clean) {
    Write-Step "Archive stale assets"
    Get-ChildItem -LiteralPath $assetsRoot -File |
        Where-Object { $_.Name -ne "current.properties" -and ($needed -notcontains $_.Name) } |
        ForEach-Object { Move-StaleFile -File $_ -ArchiveDir $toolchainArchiveRoot }
    if (-not $SkipSysroot) {
        Get-ChildItem -LiteralPath $sysrootAssetsRoot -File |
            Where-Object { $_.Name -ne $sysrootFileName } |
            ForEach-Object { Move-StaleFile -File $_ -ArchiveDir $sysrootArchiveRoot }
    }
}

if (-not $ArchiveOnly) {
    foreach ($name in $needed) {
        $src = Join-Path $releaseRoot $name
        $dst = Join-Path $assetsRoot $name
        $srcResolved = (Resolve-Path -LiteralPath $src).Path
        $dstResolved = if (Test-Path -LiteralPath $dst) { (Resolve-Path -LiteralPath $dst).Path } else { $dst }
        if ($srcResolved -eq $dstResolved) {
            Write-Host ("  SKIP {0,-55} {1}" -f $name, "source==destination") -ForegroundColor DarkYellow
            continue
        }

        Copy-Item -LiteralPath $src -Destination $assetsRoot -Force
        $sizeMiB = "{0:N2} MiB" -f ((Get-Item -LiteralPath $dst).Length / 1MB)
        Write-Host ("  OK  {0,-55} {1,10}" -f $name, $sizeMiB) -ForegroundColor Green
    }
}

if (-not $ArchiveOnly -and -not $SkipSysroot) {
    $sysrootSrc = Join-Path $sysrootRoot $sysrootFileName
    $sysrootDst = Join-Path $sysrootAssetsRoot $sysrootFileName
    $sysrootSrcResolved = (Resolve-Path -LiteralPath $sysrootSrc).Path
    $sysrootDstResolved = if (Test-Path -LiteralPath $sysrootDst) { (Resolve-Path -LiteralPath $sysrootDst).Path } else { $sysrootDst }
    if ($sysrootSrcResolved -ne $sysrootDstResolved) {
        Copy-Item -LiteralPath $sysrootSrc -Destination $sysrootDst -Force
    } else {
        Write-Host ("  SKIP {0,-55} {1}" -f $sysrootFileName, "source==destination") -ForegroundColor DarkYellow
    }

    $sysrootMiB = "{0:N2} MiB" -f ((Get-Item -LiteralPath $sysrootDst).Length / 1MB)
    Write-Host ("  OK  {0,-55} {1,10}" -f $sysrootFileName, $sysrootMiB) -ForegroundColor Green
}

Write-Step "Done."

