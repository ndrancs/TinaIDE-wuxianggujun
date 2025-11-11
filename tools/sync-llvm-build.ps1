Param(
  [ValidateSet('arm64-v8a','x86_64')]
  [string]$Abi = 'x86_64',
  [int]$ApiLevel = 26,
  [string]$BuildOutputRoot = 'docker/llvm-build/build-output',
  [string]$AppJniLibs = 'app/src/main/jniLibs',
  [string]$AppAssetsSysroot = 'app/src/main/assets/sysroot'
)

Write-Host "== Sync LLVM build artifacts (ABI=$Abi) ==" -ForegroundColor Cyan

# 1) Headers (LLVM/Clang) for build-time
& ./tools/sync-llvm-headers.ps1 -Abi $Abi -ApiLevel $ApiLevel

# 2) Shared libraries to jniLibs
$srcLibDir = Join-Path (Join-Path $BuildOutputRoot $Abi) (Join-Path 'libs' $Abi)
$dstLibDir = Join-Path $AppJniLibs $Abi
if (Test-Path $srcLibDir) {
  New-Item -ItemType Directory -Force -Path $dstLibDir | Out-Null
  # Pre-clean only our managed libraries to avoid leftovers; do not touch other libs
  $patterns = @('libclang-cpp*.so','libLLVM*.so','liblld*.so','libc++_shared.so')
  foreach($pat in $patterns){
    Get-ChildItem -Path $dstLibDir -Filter $pat -File -ErrorAction SilentlyContinue | ForEach-Object {
      Write-Host "Remove old: $($_.FullName)" -ForegroundColor DarkYellow
      Remove-Item -Force $_.FullName
    }
  }
  robocopy $srcLibDir $dstLibDir *.so /NFL /NDL /NJH /NJS /NP | Out-Null
  Write-Host "Copied .so libraries → $dstLibDir" -ForegroundColor Green
} else {
  Write-Host "Skip libs: $srcLibDir not found" -ForegroundColor DarkYellow
}

# 3) Sysroot to assets
$srcSysroot = Join-Path (Join-Path $BuildOutputRoot $Abi) 'sysroot'
if (Test-Path $srcSysroot) {
  New-Item -ItemType Directory -Force -Path $AppAssetsSysroot | Out-Null
  robocopy $srcSysroot $AppAssetsSysroot /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
  # Warn if target triple directory for selected API is missing
  $triple = if ($Abi -eq 'arm64-v8a') { 'aarch64-linux-android' } else { 'x86_64-linux-android' }
  $tripleDir = Join-Path $AppAssetsSysroot ("usr/lib/$triple/$ApiLevel")
  $srcStubRoot = Join-Path (Join-Path $BuildOutputRoot $Abi) ("sysroot/usr/lib/$triple")
  $required = @('crtbegin_dynamic.o','crtend_android.o','libc.so','libm.so','liblog.so','libandroid.so')

  function Test-StubComplete($dir){
    if (-not (Test-Path $dir)) { return $false }
    foreach($f in $required){ if (-not (Test-Path (Join-Path $dir $f))) { return $false } }
    return $true
  }

  # If target API dir is missing or incomplete, try to normalize by copying from another available API dir
  $attemptNormalize = $false
  if (-not (Test-Path $tripleDir)) { $attemptNormalize = $true } else {
    $need = @(); foreach($f in $required){ if (-not (Test-Path (Join-Path $tripleDir $f))) { $need += $f } }
    if ($need.Count -gt 0) { $attemptNormalize = $true }
  }
  if ($attemptNormalize) {
    $candidates = @($ApiLevel,21,26,29,33 | Select-Object -Unique)
    $chosen = $null
    foreach($lvl in $candidates){
      $srcCand = Join-Path $srcStubRoot $lvl
      if (Test-StubComplete $srcCand) { $chosen = $srcCand; break }
    }
    if ($null -ne $chosen) {
      New-Item -ItemType Directory -Force -Path $tripleDir | Out-Null
      Write-Host "[i] Normalizing assets sysroot: filling $triple/$ApiLevel from source $(Split-Path -Leaf $chosen)" -ForegroundColor Yellow
      robocopy $chosen $tripleDir /E /NFL /NDL /NJH /NJS /NP | Out-Null
    }
  }

  if (-not (Test-Path $tripleDir)) {
    Write-Host "[!] sysroot missing triple/api: $triple/$ApiLevel at $tripleDir" -ForegroundColor Red
    # 打印来源 build-output 的对应目录方便定位
    $srcTripleDir = Join-Path (Join-Path $BuildOutputRoot $Abi) ("sysroot/usr/lib/$triple/$ApiLevel")
    if (Test-Path $srcTripleDir) {
      Write-Host "[i] build-output source dir exists but assets missing. Listing source:" -ForegroundColor Yellow
      Get-ChildItem -Force $srcTripleDir | Select-Object -First 20 | Format-Table -AutoSize | Out-String | Write-Host
    } else {
      Write-Host "[i] build-output source dir not found: $srcTripleDir" -ForegroundColor Yellow
    }
    throw "Sysroot libraries not found — run docker/llvm-build/build-local.ps1 then re-run this script."
  }
  $missing = @()
  foreach($f in $required){ if (-not (Test-Path (Join-Path $tripleDir $f))) { $missing += $f } }
  if ($missing.Count -gt 0) {
    Write-Host "[!] sysroot libraries incomplete at: $tripleDir" -ForegroundColor Red
    Write-Host ("    missing: " + ($missing -join ', ')) -ForegroundColor Red
    # Try one more normalization from build-output if possible
    $srcTripleDir = Join-Path $srcStubRoot $ApiLevel
    if (-not (Test-StubComplete $srcTripleDir)) {
      foreach($lvl in (21,26,29,33)){
        $srcCand = Join-Path $srcStubRoot $lvl
        if (Test-StubComplete $srcCand) {
          Write-Host "[i] Normalizing (2nd pass): filling $triple/$ApiLevel from source $lvl" -ForegroundColor Yellow
          robocopy $srcCand $tripleDir /E /NFL /NDL /NJH /NJS /NP | Out-Null
          break
        }
      }
    }
    # Recompute missing after attempted normalization
    $missing = @(); foreach($f in $required){ if (-not (Test-Path (Join-Path $tripleDir $f))) { $missing += $f } }
    if ($missing.Count -gt 0) {
      Write-Host ("    missing: " + ($missing -join ', ')) -ForegroundColor Red
      Write-Host "    hint: ./docker/llvm-build/build-local.ps1 -Abi $Abi -ApiLevel $ApiLevel" -ForegroundColor Yellow
      # 同时打印 build-output 源目录的前若干项，帮助排查为何 assets 为空
      $srcTripleDir = Join-Path (Join-Path $BuildOutputRoot $Abi) ("sysroot/usr/lib/$triple/$ApiLevel")
      if (Test-Path $srcTripleDir) {
        Write-Host "[i] build-output source listing:" -ForegroundColor Yellow
        Get-ChildItem -Force $srcTripleDir | Select-Object -First 30 | Format-Table -AutoSize | Out-String | Write-Host
      } else {
        Write-Host "[i] build-output source dir not found: $srcTripleDir" -ForegroundColor Yellow
      }
      throw "Sysroot incomplete — aborting copy to avoid shipping a broken assets/sysroot"
    }
  }
  $libcppHdr = Join-Path $AppAssetsSysroot 'usr/include/c++/v1/__ios/fpos.h'
  if (-not (Test-Path $libcppHdr)) {
    Write-Host "[w] libc++ header missing: $libcppHdr — please rebuild libs (build-local.ps1 -Mode libs -Abi $Abi -ApiLevel $ApiLevel) to refresh sysroot c++/v1" -ForegroundColor Yellow
  }
  # Ensure clang resource headers exist under assets (stdarg.h)
  $clangRes = Join-Path $AppAssetsSysroot 'lib/clang/17/include/stdarg.h'
  if (-not (Test-Path $clangRes)) {
    Write-Host "[w] clang resource header missing: $clangRes — attempting fallback copy from local source tree" -ForegroundColor Yellow
    $hdrSrc = Resolve-Path 'docker/llvm-build/dev-work/src/llvm-project/clang/lib/Headers' -ErrorAction SilentlyContinue
    if ($hdrSrc) {
      $dstDir = Join-Path $AppAssetsSysroot 'lib/clang/17/include'
      New-Item -ItemType Directory -Force -Path $dstDir | Out-Null
      Copy-Item (Join-Path $hdrSrc '/*') -Destination $dstDir -Recurse -Force
      if (Test-Path $clangRes) {
        Write-Host "[i] Fallback copied clang headers → $dstDir" -ForegroundColor Green
      } else {
        Write-Host "[!] Fallback failed: stdarg.h still missing at $clangRes" -ForegroundColor Red
      }
    } else {
      Write-Host "[!] No local clang/lib/Headers found. Run build-local.ps1 to generate resource headers." -ForegroundColor Red
    }
  }
  Write-Host "Mirrored sysroot → $AppAssetsSysroot" -ForegroundColor Green
} else {
  Write-Host "Skip sysroot: $srcSysroot not found" -ForegroundColor DarkYellow
}

Write-Host "== Done ==" -ForegroundColor Cyan
