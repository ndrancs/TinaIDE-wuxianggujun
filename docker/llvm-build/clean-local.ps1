Param(
  [ValidateSet('arm64-v8a','x86_64','all')][string]$Abi = 'all',
  [switch]$RemoveAssets,      # 删除 app/assets 下的 sysroot（谨慎）
  [switch]$RemoveJniLibs,     # 删除 jniLibs 下与 LLVM/Clang 相关的 .so（精准匹配）
  [switch]$PruneImages,       # 删除本地 Docker 镜像（llvm-build*）
  [switch]$Yes                # 跳过确认
)

$ErrorActionPreference = 'Stop'

function Write-Info($m){ Write-Host "[i] $m" -ForegroundColor Cyan }
function Write-Warn($m){ Write-Host "[w] $m" -ForegroundColor Yellow }
function Write-Err ($m){ Write-Host "[!] $m" -ForegroundColor Red }

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $scriptRoot '..\..')

function Confirm-Do($msg){
  if ($Yes) { return $true }
  $r = Read-Host "$msg [y/N]"
  return ($r -eq 'y' -or $r -eq 'Y')
}

function Remove-DirSafe($p){ if (Test-Path $p){ Write-Info "Remove: $p"; Remove-Item -Recurse -Force $p } }

function Clean-BuildOutput($abi){
  $base = Join-Path $root 'docker/llvm-build/build-output'
  if ($abi -eq 'all'){
    if (Confirm-Do "删除 $base 下所有 ABI 产物？") { Remove-DirSafe $base }
  } else {
    Remove-DirSafe (Join-Path $base $abi)
  }
}

function Clean-JniLibs($abi){
  if (-not $RemoveJniLibs){ Write-Info '跳过 jniLibs 清理（未指定 -RemoveJniLibs）'; return }
  $jniBase = Join-Path $root 'app/src/main/jniLibs'
  $abis = if ($abi -eq 'all'){ @('arm64-v8a','x86_64') } else { @($abi) }
  $patterns = @('libclang-cpp*.so','libLLVM*.so','liblld*.so','libc++_shared.so')
  foreach($a in $abis){
    $d = Join-Path $jniBase $a
    if (Test-Path $d){
      foreach($pat in $patterns){
        Get-ChildItem -Path $d -Filter $pat -File -ErrorAction SilentlyContinue | ForEach-Object {
          Write-Info "Remove jni so: $($_.FullName)"; Remove-Item -Force $_.FullName
        }
      }
    }
  }
}

function Clean-Assets(){
  if (-not $RemoveAssets){ Write-Info '跳过 assets 清理（未指定 -RemoveAssets）'; return }
  $assets = Join-Path $root 'app/src/main/assets'
  Remove-DirSafe (Join-Path $assets 'sysroot')
}

function Prune-Images($abi){
  if (-not $PruneImages){ Write-Info '跳过 Docker 镜像清理（未指定 -PruneImages）'; return }
  $images = (& docker images --format '{{.Repository}}:{{.Tag}}') | Where-Object {
    $_ -like 'llvm-build-dev:*'
  }
  if ($abi -ne 'all'){
    $images = $images | Where-Object { $_ -match "-$abi-" }
  }
  if (-not $images){ Write-Info '无可清理镜像'; return }
  if (-not (Confirm-Do ("删除镜像:\n" + ($images -join "`n")))){ return }
  foreach($img in $images){ Write-Info "docker rmi -f $img"; & docker rmi -f $img | Out-Null }
}

Write-Info "清理开始 (Abi=$Abi)"

Clean-BuildOutput $Abi
Clean-JniLibs $Abi
Clean-Assets

Prune-Images $Abi

Write-Info '清理完成。'

