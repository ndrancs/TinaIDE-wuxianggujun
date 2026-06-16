[CmdletBinding()]
param(
    [ValidateSet("arm64", "x86_64")]
    [string]$Abi = "arm64",
    [string]$ReleaseDir,
    [string]$SysrootDir,
    [string]$SysrootProfileId,
    [string]$SysrootProfileName,
    [string]$SysrootAssetName,
    [string]$ArchiveDir,
    [switch]$ArchiveOnly,
    [switch]$SkipToolchain,
    [switch]$SkipSysroot,
    [switch]$SetDefaultSysrootProfile,
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

function Write-Utf8NoBom {
    param(
        [string]$Path,
        [string]$Text
    )
    $encoding = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($Path, $Text, $encoding)
}

function Read-JsonFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $content = Get-Content -LiteralPath $Path -Raw -Encoding UTF8
    if ([string]::IsNullOrWhiteSpace($content)) { return $null }
    return $content | ConvertFrom-Json
}

function Read-SysrootVersionFromArchive {
    param([string]$ArchivePath)
    $output = & tar -xOf $ArchivePath "android-sysroot/.version" 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to read android-sysroot/.version from $ArchivePath`n$output"
    }
    $props = @{}
    $output | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#")) { return }
        $kv = $line.Split("=", 2)
        if ($kv.Count -eq 2) {
            $props[$kv[0].Trim()] = $kv[1].Trim()
        }
    }
    return $props
}

function Get-SysrootApiLevels {
    param([hashtable]$Props)
    $raw = [string]$Props["API_LEVELS"]
    if ([string]::IsNullOrWhiteSpace($raw)) { return @() }
    return @($raw -split "[,\s]+" | Where-Object { $_ -match "^\d+$" } | ForEach-Object { [int]$_ })
}

function Get-SysrootManifestArch {
    param([string]$Abi)
    switch ($Abi) {
        "arm64" { return "ARM64" }
        "x86_64" { return "X86_64" }
    }
}

function Get-DefaultSysrootProfileId {
    param(
        [string]$Abi,
        [string]$NdkVersion
    )
    if ([string]::IsNullOrWhiteSpace($NdkVersion)) {
        throw "Cannot infer sysroot profile id because NDK_VERSION is missing from archive .version"
    }
    return "builtin-ndk-$NdkVersion-$Abi"
}

function Get-DefaultSysrootProfileName {
    param([string]$NdkVersion)
    if ([string]::IsNullOrWhiteSpace($NdkVersion)) {
        throw "Cannot infer sysroot profile name because NDK_VERSION is missing from archive .version"
    }
    return "NDK $NdkVersion"
}

function Get-SysrootManifestAssetPaths {
    param([object]$Manifest)
    if (-not $Manifest -or -not $Manifest.profiles) { return @() }
    return @($Manifest.profiles |
        ForEach-Object { [string]$_.assetPath } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { [System.IO.Path]::GetFileName($_) })
}

function Update-SysrootProfileManifest {
    param(
        [string]$ManifestPath,
        [string]$Abi,
        [string]$ProfileId,
        [string]$ProfileName,
        [string]$AssetFileName,
        [string]$ArchivePath,
        [switch]$SetDefault
    )

    $versionProps = Read-SysrootVersionFromArchive -ArchivePath $ArchivePath
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ArchivePath).Hash.ToLowerInvariant()
    $apiLevels = @(Get-SysrootApiLevels -Props $versionProps)
    $manifest = Read-JsonFile -Path $ManifestPath
    $existingProfiles = if ($manifest -and $manifest.profiles) { @($manifest.profiles) } else { @() }
    $profiles = @($existingProfiles | Where-Object { [string]$_.id -ne $ProfileId })
    $isDefault = $SetDefault.IsPresent -or -not $manifest -or [string]::IsNullOrWhiteSpace([string]$manifest.defaultProfileId)
    $defaultProfileId = if ($isDefault) { $ProfileId } else { [string]$manifest.defaultProfileId }

    $profile = [ordered]@{
        id = $ProfileId
        name = $ProfileName
        arch = Get-SysrootManifestArch -Abi $Abi
        assetPath = "android-sysroot/$AssetFileName"
        sha256 = $hash
        apiLevels = @($apiLevels)
        toolchainTriple = [string]$versionProps["TOOLCHAIN_TRIPLE"]
        createdAt = [string]$versionProps["CREATED_AT"]
        default = $isDefault
    }
    if (-not [string]::IsNullOrWhiteSpace([string]$versionProps["NDK_VERSION"])) {
        $profile["ndkVersion"] = [string]$versionProps["NDK_VERSION"]
    }
    $ndkLlvmVersion = if (-not [string]::IsNullOrWhiteSpace([string]$versionProps["NDK_LLVM_VERSION"])) {
        [string]$versionProps["NDK_LLVM_VERSION"]
    } else {
        [string]$versionProps["LLVM_VERSION"]
    }
    if (-not [string]::IsNullOrWhiteSpace($ndkLlvmVersion)) {
        $profile["ndkLlvmVersion"] = $ndkLlvmVersion
    }

    $profiles += [pscustomobject]$profile
    $profiles = @($profiles | ForEach-Object {
        $copy = [ordered]@{}
        foreach ($property in $_.PSObject.Properties) {
            if ($property.Name -eq "default") {
                $copy[$property.Name] = ([string]$_.id -eq $defaultProfileId)
            } else {
                $copy[$property.Name] = $property.Value
            }
        }
        [pscustomobject]$copy
    } | Sort-Object id)

    $nextManifest = [ordered]@{
        schemaVersion = 1
        defaultProfileId = $defaultProfileId
        profiles = @($profiles)
    }
    $json = $nextManifest | ConvertTo-Json -Depth 8
    Write-Utf8NoBom -Path $ManifestPath -Text ($json + [Environment]::NewLine)
    Write-Host ("  OK  {0,-55} {1}" -f "profiles.json", "profiles=$($profiles.Count) default=$defaultProfileId") -ForegroundColor Green
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
$expectedArch = switch ($Abi) {
    "arm64" { "aarch64" }
    "x86_64" { "x86_64" }
}
$manifestPath = Join-Path $sysrootAssetsRoot "profiles.json"
$existingManifest = Read-JsonFile -Path $manifestPath
if (-not $SkipSysroot) {
    if ([string]::IsNullOrWhiteSpace($SysrootAssetName)) {
        throw "SysrootAssetName is required; old android-sysroot-*-all.tar.xz defaults have been removed"
    }
    if ($SysrootAssetName -match '[\\/]' -or -not $SysrootAssetName.EndsWith(".tar.xz")) {
        throw "SysrootAssetName must be a .tar.xz file name without path separators: $SysrootAssetName"
    }
    if (-not [string]::IsNullOrWhiteSpace($SysrootProfileId) -and $SysrootProfileId -notmatch '^[a-zA-Z0-9._-]+$') {
        throw "SysrootProfileId contains unsupported characters: $SysrootProfileId"
    }
}

if (-not $SkipToolchain -and -not (Test-Path -LiteralPath $specPath)) {
    throw "Toolchain spec not found: $specPath (expected in flavor assets)"
}
if ($ArchiveOnly) {
    $Clean = $true
}
if (-not $ArchiveOnly -and -not $SkipToolchain -and -not (Test-Path -LiteralPath $releaseRoot)) {
    throw "Toolchain release directory not found: $releaseRoot (run toolchain builder first)"
}
if (-not $ArchiveOnly -and -not $SkipSysroot -and -not (Test-Path -LiteralPath $sysrootRoot)) {
    throw "Sysroot directory not found: $sysrootRoot (run toolchain builder first)"
}

if (-not $SkipToolchain) {
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
} else {
    $version = "sysroot-only"
    $arch = $expectedArch
    $mode = "skip-toolchain"
    $needed = @()
}

Write-Step "Sync tina-toolchain assets: abi=$Abi version=$version arch=$arch mode=$mode"
if (-not $SkipToolchain) {
    Write-Host "  Spec:     $specPath" -ForegroundColor DarkGray
    Write-Host "  Release:  $releaseRoot" -ForegroundColor DarkGray
    Write-Host "  Assets:   $assetsRoot" -ForegroundColor DarkGray
} else {
    Write-Host "  Toolchain assets: skipped" -ForegroundColor DarkGray
}
Write-Host "  Archive:  $archiveRoot" -ForegroundColor DarkGray
if (-not $SkipSysroot) {
    Write-Host "  Sysroot:  $sysrootRoot" -ForegroundColor DarkGray
    Write-Host "  SysAsset: $sysrootAssetsRoot" -ForegroundColor DarkGray
    Write-Host "  SysProf:  $SysrootProfileId -> $SysrootAssetName" -ForegroundColor DarkGray
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
    $sysrootSrc = Join-Path $sysrootRoot $SysrootAssetName
    if (-not (Test-Path -LiteralPath $sysrootSrc)) {
        throw "Missing sysroot asset: $sysrootSrc"
    }
    $sysrootVersionPropsForDefaults = Read-SysrootVersionFromArchive -ArchivePath $sysrootSrc
    $sysrootNdkVersionForDefaults = [string]$sysrootVersionPropsForDefaults["NDK_VERSION"]
    if ([string]::IsNullOrWhiteSpace($SysrootProfileId)) {
        $SysrootProfileId = Get-DefaultSysrootProfileId -Abi $Abi -NdkVersion $sysrootNdkVersionForDefaults
    }
    if ([string]::IsNullOrWhiteSpace($SysrootProfileName)) {
        $SysrootProfileName = Get-DefaultSysrootProfileName -NdkVersion $sysrootNdkVersionForDefaults
    }
    if ($SysrootProfileId -notmatch '^[a-zA-Z0-9._-]+$') {
        throw "SysrootProfileId contains unsupported characters: $SysrootProfileId"
    }
} elseif (-not $SkipSysroot) {
    if ([string]::IsNullOrWhiteSpace($SysrootProfileId)) {
        throw "SysrootProfileId is required when ArchiveOnly is used"
    }
    if ([string]::IsNullOrWhiteSpace($SysrootProfileName)) {
        $SysrootProfileName = $SysrootProfileId
    }
    if ($SysrootProfileId -notmatch '^[a-zA-Z0-9._-]+$') {
        throw "SysrootProfileId contains unsupported characters: $SysrootProfileId"
    }
}

if (-not $SkipToolchain) {
    New-Item -ItemType Directory -Force -Path $assetsRoot | Out-Null
}
if (-not $SkipSysroot) {
    New-Item -ItemType Directory -Force -Path $sysrootAssetsRoot | Out-Null
}

if ($Clean) {
    Write-Step "Archive stale assets"
    if (-not $SkipToolchain) {
        Get-ChildItem -LiteralPath $assetsRoot -File |
            Where-Object { $_.Name -ne "current.properties" -and ($needed -notcontains $_.Name) } |
            ForEach-Object { Move-StaleFile -File $_ -ArchiveDir $toolchainArchiveRoot }
    }
    if (-not $SkipSysroot) {
        $manifestAssetNames = Get-SysrootManifestAssetPaths -Manifest $existingManifest
        $keptSysrootNames = @("profiles.json", $SysrootAssetName) + $manifestAssetNames |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Select-Object -Unique
        Get-ChildItem -LiteralPath $sysrootAssetsRoot -File |
            Where-Object { $keptSysrootNames -notcontains $_.Name } |
            ForEach-Object { Move-StaleFile -File $_ -ArchiveDir $sysrootArchiveRoot }
    }
}

if (-not $ArchiveOnly -and -not $SkipToolchain) {
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
    $sysrootSrc = Join-Path $sysrootRoot $SysrootAssetName
    $sysrootDst = Join-Path $sysrootAssetsRoot $SysrootAssetName
    $sysrootSrcResolved = (Resolve-Path -LiteralPath $sysrootSrc).Path
    $sysrootDstResolved = if (Test-Path -LiteralPath $sysrootDst) { (Resolve-Path -LiteralPath $sysrootDst).Path } else { $sysrootDst }
    if ($sysrootSrcResolved -ne $sysrootDstResolved) {
        Copy-Item -LiteralPath $sysrootSrc -Destination $sysrootDst -Force
    } else {
        Write-Host ("  SKIP {0,-55} {1}" -f $SysrootAssetName, "source==destination") -ForegroundColor DarkYellow
    }

    $sysrootMiB = "{0:N2} MiB" -f ((Get-Item -LiteralPath $sysrootDst).Length / 1MB)
    Write-Host ("  OK  {0,-55} {1,10}" -f $SysrootAssetName, $sysrootMiB) -ForegroundColor Green
    Update-SysrootProfileManifest `
        -ManifestPath $manifestPath `
        -Abi $Abi `
        -ProfileId $SysrootProfileId `
        -ProfileName $SysrootProfileName `
        -AssetFileName $SysrootAssetName `
        -ArchivePath $sysrootDst `
        -SetDefault:$SetDefaultSysrootProfile
}

Write-Step "Done."

