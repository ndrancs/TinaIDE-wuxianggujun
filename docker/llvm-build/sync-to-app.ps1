Param(
  [ValidateSet('arm64-v8a','x86_64','auto')][string]$Abi = 'auto',
  [int]$ApiLevel = 24,
  [switch]$Clean,          # 清理目标后再复制（精准清理相关 .so）
  [switch]$DryRun          # 仅显示将执行的操作
)

$ErrorActionPreference = 'Stop'
function Info($m){ Write-Host "[i] $m" -ForegroundColor Cyan }
function Warn($m){ Write-Host "[w] $m" -ForegroundColor Yellow }
function Err ($m){ Write-Host "[!] $m" -ForegroundColor Red }

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $scriptRoot '..\..')

function Detect-Abi(){
  if ($Abi -ne 'auto') { return $Abi }
  $buildOutput = Join-Path $root 'docker/llvm-build/build-output'
  $x = Test-Path (Join-Path $buildOutput 'x86_64/libs/x86_64')
  $a = Test-Path (Join-Path $buildOutput 'arm64-v8a/libs/arm64-v8a')
  if ($x) { return 'x86_64' }
  if ($a) { return 'arm64-v8a' }
  Err "无法自动检测 ABI，请通过 -Abi 指定 ('arm64-v8a' 或 'x86_64')"; exit 1
}

function Get-Triple([string]$abi){
  switch ($abi) {
    'arm64-v8a' { return 'aarch64-linux-android' }
    'x86_64'    { return 'x86_64-linux-android' }
    default     { return '' }
  }
}

function Copy-Tree($from,$to){
  Info "Sync: $from -> $to"
  if ($DryRun) { return }
  New-Item -ItemType Directory -Force -Path $to | Out-Null
  Copy-Item "$from\*" -Destination $to -Recurse -Force
}

function Clean-Libs-Targets($dst){
  $patterns = @('libclang-cpp*.so','libLLVM*.so','liblld*.so','libc++_shared.so')
  foreach($pat in $patterns){
    Get-ChildItem -Path $dst -Filter $pat -File -ErrorAction SilentlyContinue | ForEach-Object {
      Info "Remove: $($_.FullName)"
      if (-not $DryRun) { Remove-Item -Force $_.FullName }
    }
  }
}

function Clean-Dir($path){ if (Test-Path $path) { Info "Remove dir: $path"; if (-not $DryRun) { Remove-Item -Recurse -Force $path } } }

$abi = Detect-Abi
Info "Abi=$abi (Clean=$Clean DryRun=$DryRun)"

$srcSo  = Join-Path $root "docker/llvm-build/build-output/$abi/libs/$abi"
$srcSys = Join-Path $root "docker/llvm-build/build-output/$abi/sysroot"
$dstSo  = Join-Path $root "app/src/main/jniLibs/$abi"
$dstSys = Join-Path $root 'app/src/main/assets/sysroot'

if (-not (Test-Path $srcSo))  { Err "缺少源目录: $srcSo";  exit 1 }
if (-not (Test-Path $srcSys)) { Err "缺少源目录: $srcSys"; exit 1 }

# Always pre-clean managed libraries to avoid leftovers
Clean-Libs-Targets $dstSo
# 仅复制 .so 文件，跳过 .a
Info "Sync .so from $srcSo to $dstSo"
if (-not $DryRun) {
  New-Item -ItemType Directory -Force -Path $dstSo | Out-Null
  Get-ChildItem -Path $srcSo -Filter *.so* -File | ForEach-Object {
    Copy-Item $_.FullName -Destination $dstSo -Force
  }
}
if ($Clean) { Clean-Dir $dstSys }
# Mirror sysroot to avoid stale files but only within sysroot directory
Info "Mirror sysroot from $srcSys to $dstSys"
if (-not $DryRun) {
  New-Item -ItemType Directory -Force -Path $dstSys | Out-Null
  robocopy $srcSys $dstSys /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
}
# 校验 sysroot 中 triple/api 目录是否存在
$triple = Get-Triple $abi
if ($triple) {
  $tripleDir = Join-Path $dstSys ("usr/lib/$triple/$ApiLevel")
  if (-not (Test-Path $tripleDir)) { Warn "sysroot 缺少 $triple/$ApiLevel 目录：$tripleDir" }
}
Info "Done: 已同步 .so 到 $dstSo，sysroot 到 $dstSys"
