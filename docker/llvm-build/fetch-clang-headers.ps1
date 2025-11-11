Param(
  [string]$LlvmTag = 'llvmorg-17.0.6',
  [string]$ContainerName = 'tina-llvm-build'
)

$ErrorActionPreference = 'Stop'
function Info($m){ Write-Host "[i] $m" -ForegroundColor Cyan }
function Err ($m){ Write-Host "[!] $m" -ForegroundColor Red }

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $scriptRoot '..\..')
$buildOutput = (Resolve-Path (Join-Path $root 'docker/llvm-build/build-output')).Path

# Ensure dev container exists (reuse build-local.ps1 contract)
$running = (& docker ps --format '{{.Names}}' | Select-String -SimpleMatch $ContainerName) -ne $null
if (-not $running) {
  Info "Dev container '$ContainerName' not running. Starting via build-local.ps1 (libs, arm64-v8a) ..."
  & pwsh (Join-Path $root 'docker/llvm-build/build-local.ps1') -Abi arm64-v8a | Out-Null
}

# Clone clang headers in container (if missing) and copy to host mount
$cmd = @"
set -eux
if [ ! -d /work/src/llvm-project ]; then
  git clone --depth 1 --branch ${LlvmTag} https://github.com/llvm/llvm-project /work/src/llvm-project
fi
# 期望目录结构：/hostout/common-headers/clang/Basic/Version.h
mkdir -p /hostout/common-headers
cp -a /work/src/llvm-project/clang/include/. /hostout/common-headers/
"@

Info "Fetching clang C++ headers ($LlvmTag) in container '$ContainerName' ..."
& docker exec $ContainerName bash -lc $cmd

# Verify on host
$hdr = Join-Path $buildOutput 'common-headers/clang/Basic/Version.h'
if (Test-Path $hdr) {
  Info "OK: $hdr"
} else {
  Err "FAILED: not found $hdr"
  exit 1
}
