Param(
  [string]$DockerRoot = "docker/llvm-build/dev-work",
  [string]$DestRoot = "docker/llvm-build/build-output/common-headers",
  [ValidateSet('arm64-v8a','x86_64')][string]$Abi = 'x86_64',
  [int]$ApiLevel = 28
)

$srcLlvm      = Join-Path $DockerRoot "src/llvm-project/llvm/include"
$srcBuildX64  = Join-Path $DockerRoot "build/android/x86_64-api$ApiLevel/include"
$srcBuildArm  = Join-Path $DockerRoot "build/android/arm64-v8a-api$ApiLevel/include"
$srcCfgX64    = Join-Path $srcBuildX64  "llvm/Config"
$srcCfgArm    = Join-Path $srcBuildArm  "llvm/Config"
$srcClangSrc  = Join-Path $DockerRoot "src/llvm-project/clang/include/clang"
$srcClangX64  = Join-Path $DockerRoot "build/android/x86_64-api$ApiLevel/tools/clang/include/clang"
$srcClangArm  = Join-Path $DockerRoot "build/android/arm64-v8a-api$ApiLevel/tools/clang/include/clang"

$dstLlvm = Join-Path $DestRoot "llvm"
$dstCfg  = Join-Path $DestRoot "llvm/llvm/Config"
$dstClang = Join-Path $DestRoot "clang"
${null} = New-Item -ItemType Directory -Force -Path $dstLlvm

# Specific generated headers that Clang headers require but are not in LLVM source tree
$dstOmpDir = Join-Path $DestRoot "llvm/llvm/Frontend/OpenMP"


Write-Host "Sync LLVM source headers..." -ForegroundColor Cyan
robocopy $srcLlvm $dstLlvm /E /NFL /NDL /NJH /NJS /NP | Out-Null

Write-Host "Sync LLVM Config headers..." -ForegroundColor Cyan
if (Test-Path $srcCfgX64) {
  New-Item -ItemType Directory -Force -Path $dstCfg | Out-Null
  robocopy $srcCfgX64 $dstCfg *.h *.def /NFL /NDL /NJH /NJS /NP | Out-Null
  Write-Host "  Copied from x86_64 build" -ForegroundColor Gray
} elseif (Test-Path $srcCfgArm) {
  New-Item -ItemType Directory -Force -Path $dstCfg | Out-Null
  robocopy $srcCfgArm $dstCfg *.h *.def /NFL /NDL /NJH /NJS /NP | Out-Null
  Write-Host "  Copied from arm64 build" -ForegroundColor Gray
}

Write-Host "Sync LLVM Frontend/OpenMP generated headers..." -ForegroundColor Cyan
$srcOmpX64 = Join-Path $srcBuildX64 "llvm/Frontend/OpenMP/OMP.inc"
$srcOmpArm = Join-Path $srcBuildArm "llvm/Frontend/OpenMP/OMP.inc"
if ((Test-Path $srcOmpX64) -or (Test-Path $srcOmpArm)) {
  New-Item -ItemType Directory -Force -Path $dstOmpDir | Out-Null
  $copied = $false
  if (Test-Path $srcOmpX64) {
    Copy-Item -Force $srcOmpX64 $dstOmpDir
    Write-Host "  Copied OMP.inc from x86_64 build" -ForegroundColor Gray
    $copied = $true
  }
  if (-not $copied -and (Test-Path $srcOmpArm)) {
    Copy-Item -Force $srcOmpArm $dstOmpDir
    Write-Host "  Copied OMP.inc from arm64 build" -ForegroundColor Gray
    $copied = $true
  }
} else {
  Write-Host "  Skipped: no OMP.inc found in build include" -ForegroundColor DarkYellow
}

Write-Host "Sync Clang source headers..." -ForegroundColor Cyan
if (-not (Test-Path $srcClangSrc)) {
  throw "Missing Clang source headers at $srcClangSrc (run docker build first)"
}
New-Item -ItemType Directory -Force -Path $dstClang | Out-Null
robocopy $srcClangSrc $dstClang /E /NFL /NDL /NJH /NJS /NP | Out-Null

Write-Host "Sync Clang generated headers..." -ForegroundColor Cyan
if (Test-Path $srcClangX64) {
  robocopy $srcClangX64 $dstClang /E /NFL /NDL /NJH /NJS /NP | Out-Null
  Write-Host "  Generated headers from x86_64 build" -ForegroundColor Gray
} elseif (Test-Path $srcClangArm) {
  robocopy $srcClangArm $dstClang /E /NFL /NDL /NJH /NJS /NP | Out-Null
  Write-Host "  Generated headers from arm64 build" -ForegroundColor Gray
} else {
  Write-Host "  [w] No generated clang headers found. Run build for at least one ABI." -ForegroundColor Yellow
}

Write-Host "Done! Headers synced to $DestRoot" -ForegroundColor Green
Write-Host "  - LLVM source: llvm/" -ForegroundColor Gray
Write-Host "  - LLVM config: llvm/llvm/Config/" -ForegroundColor Gray
Write-Host "  - LLVM Frontend/OpenMP: llvm/llvm/Frontend/OpenMP/OMP.inc" -ForegroundColor Gray
Write-Host "  - Clang generated: clang/" -ForegroundColor Gray

# Basic verification of essential generated headers
$cfgHdr = Join-Path $DestRoot 'llvm/llvm/Config/llvm-config.h'
$diagInc = Join-Path $DestRoot 'clang/Basic/DiagnosticCommonKinds.inc'
if (-not (Test-Path $cfgHdr)) { throw "Missing: $cfgHdr (ensure ApiLevel=$ApiLevel build ran)" }
if (-not (Test-Path $diagInc)) { throw "Missing: $diagInc (ensure clang generated headers are copied)" }
