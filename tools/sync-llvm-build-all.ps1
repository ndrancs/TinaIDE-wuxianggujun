Param(
  [string[]]$Abi,
  [int]$ApiLevel = 28,
  [string]$BuildOutputRoot = 'docker/llvm-build/build-output',
  [string]$AppJniLibs = 'app/src/main/jniLibs',
  [string]$AppAssetsSysroot = 'app/src/main/assets/sysroot',
  [ValidateSet('none','zip','mirror')]
  [string]$SysrootMode = 'zip',
  [bool]$CopyLibcxxToJni = $true,
  [bool]$CopyLlvmToJni   = $false,
  [string]$ToolBinSource = '',
  [bool]$InjectToolsToSysroot = $false,
  [bool]$CopyToolRunnersToJni = $false,
  [bool]$CopyToolRunnersToSysroot = $true
)

$validAbis = @('arm64-v8a','x86_64')
$abiList = if (-not $Abi -or $Abi.Count -eq 0) { $validAbis } else { $Abi }
foreach ($entry in $abiList) {
  if ($validAbis -notcontains $entry) {
    throw "Unsupported ABI '$entry'. Valid values: $($validAbis -join ', ')"
  }
}

function Invoke-SyncSingleAbi {
  param(
    [string]$AbiValue
  )
  Write-Host "== Sync LLVM artifacts for ABI=$AbiValue ==" -ForegroundColor Cyan
  $args = @(
    '-File', (Join-Path '.' 'tools/sync-llvm-build.ps1'),
    '-Abi', $AbiValue,
    '-ApiLevel', $ApiLevel,
    '-BuildOutputRoot', $BuildOutputRoot,
    '-AppJniLibs', $AppJniLibs,
    '-AppAssetsSysroot', $AppAssetsSysroot,
    '-SysrootMode', $SysrootMode,
    "-CopyLibcxxToJni:$CopyLibcxxToJni",
    "-CopyLlvmToJni:$CopyLlvmToJni",
    '-ToolBinSource', $ToolBinSource,
    "-InjectToolsToSysroot:$InjectToolsToSysroot",
    "-CopyToolRunnersToJni:$CopyToolRunnersToJni",
    "-CopyToolRunnersToSysroot:$CopyToolRunnersToSysroot"
  )
  & pwsh -NoLogo @args
  if ($LASTEXITCODE -ne 0) {
    throw "sync-llvm-build.ps1 failed for ABI=$AbiValue (exit $LASTEXITCODE)"
  }
}

function Rename-SysrootZip {
  param(
    [string]$AbiValue
  )
  $assetsRoot = Split-Path -Parent $AppAssetsSysroot
  $targetZip = Join-Path $assetsRoot ("sysroot-$AbiValue.zip")
  $defaultZip = Join-Path $assetsRoot 'sysroot.zip'
  if (Test-Path $defaultZip) {
    if (Test-Path $targetZip) { Remove-Item -Force $targetZip }
    Move-Item -Force $defaultZip $targetZip
    Write-Host "Renamed $defaultZip -> $targetZip" -ForegroundColor Green
  } elseif (Test-Path $targetZip) {
    Write-Host "Sysroot archive already present -> $targetZip" -ForegroundColor DarkGray
  }
}

function Ensure-XmakeShareZip {
  $assetsRoot = Split-Path -Parent $AppAssetsSysroot
  $shareZip = Join-Path $assetsRoot 'xmake-share.zip'
  $shareSource = $null
  foreach ($abi in $abiList) {
    $candidate = Join-Path (Join-Path $BuildOutputRoot $abi) 'sysroot/usr/share/xmake'
    if (Test-Path $candidate) { $shareSource = $candidate; break }
  }
  if (-not $shareSource) {
    Write-Host "Skip xmake-share packaging: source directory not found." -ForegroundColor Yellow
    return
  }
  if (Test-Path $shareZip) { Remove-Item -Force $shareZip }
  try { Add-Type -AssemblyName System.IO.Compression.FileSystem } catch { }
  $fs = [System.IO.File]::Open($shareZip, [System.IO.FileMode]::CreateNew)
  try {
    $zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create, $false)
    $root = (Resolve-Path $shareSource).Path
    $rootLen = $root.Length
    Get-ChildItem -LiteralPath $root -Recurse -File | ForEach-Object {
      $rel = $_.FullName.Substring($rootLen).TrimStart([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
      $entryName = "usr/share/xmake/" + ($rel -replace '\\','/')
      [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $entryName, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
  }
  finally {
    if ($zip) { $zip.Dispose() }
    if ($fs) { $fs.Dispose() }
  }
  Write-Host "Packaged xmake-share.zip -> $shareZip" -ForegroundColor Green
}

foreach ($abi in $abiList) {
  Invoke-SyncSingleAbi -AbiValue $abi
  if ($SysrootMode -eq 'zip') {
    Rename-SysrootZip -AbiValue $abi
  }
}

if ($SysrootMode -eq 'zip') {
  Ensure-XmakeShareZip
}

Write-Host "== Completed multi-ABI sync ==" -ForegroundColor Cyan
