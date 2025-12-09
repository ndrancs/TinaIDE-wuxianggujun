Param(
  [ValidateSet('arm64-v8a','x86_64')]
  [string]$Abi = 'x86_64',
  [int]$ApiLevel = 26,
  [string]$BuildOutputRoot = 'docker/llvm-build/build-output',
  [string]$AppAssetsDir = 'app/src/main/assets',
  [string]$ZipName = ''
)

Write-Host "== Package sysroot as ZIP (ABI=$Abi, API=$ApiLevel) ==" -ForegroundColor Cyan

$srcSysroot = Join-Path (Join-Path $BuildOutputRoot $Abi) 'sysroot'
if (-not (Test-Path $srcSysroot)) {
  Write-Host "[!] Source sysroot not found: $srcSysroot" -ForegroundColor Red
  exit 2
}

New-Item -ItemType Directory -Force -Path $AppAssetsDir | Out-Null
$effectiveZipName = if ([string]::IsNullOrWhiteSpace($ZipName)) { "sysroot-$Abi.zip" } else { $ZipName }
$zipPath = Join-Path $AppAssetsDir $effectiveZipName
if (Test-Path $zipPath) { Remove-Item -Force $zipPath }

# 确保加载压缩程序集
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue

function Add-FileToZip([System.IO.Compression.ZipArchive]$zip, [string]$filePath, [string]$entryName) {
  # Create entry with Optimal compression, skip zero-length directories handled by directory creation
  $null = [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $filePath, $entryName, [System.IO.Compression.CompressionLevel]::Optimal)
}

# 需要保留的静态库（C++ 运行时依赖）
$requiredStaticLibs = @('libunwind.a', 'libc++abi.a')

$fs = [System.IO.File]::Open($zipPath, [System.IO.FileMode]::CreateNew)
try {
  $zip = New-Object System.IO.Compression.ZipArchive($fs, 'Create', $false)
  $root = (Resolve-Path $srcSysroot).Path
  $rootLen = $root.Length
  Get-ChildItem -LiteralPath $root -Recurse -File | ForEach-Object {
    $rel = $_.FullName.Substring($rootLen).TrimStart([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
    # Exclude most static archives (*.a) but keep required C++ runtime libs
    if ($_.Extension -ieq '.a' -and $requiredStaticLibs -notcontains $_.Name) { return }
    # Normalize to forward slashes for zip entries
    $relZip = $rel -replace '\\','/'
    Add-FileToZip -zip $zip -filePath $_.FullName -entryName $relZip
  }
} finally {
  if ($zip) { $zip.Dispose() }
  if ($fs) { $fs.Dispose() }
}

Write-Host "[i] Created: $zipPath" -ForegroundColor Green
Write-Host "== Done ==" -ForegroundColor Cyan
