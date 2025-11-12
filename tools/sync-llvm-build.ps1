Param(
  [ValidateSet('arm64-v8a','x86_64')]
  [string]$Abi = 'x86_64',
  [int]$ApiLevel = 28,
  [string]$BuildOutputRoot = 'docker/llvm-build/build-output',
  [string]$AppJniLibs = 'app/src/main/jniLibs',
  [string]$AppAssetsSysroot = 'app/src/main/assets/sysroot',
  [ValidateSet('none','zip','mirror')]
  [string]$SysrootMode = 'zip',
  [bool]$CopyLibcxxToJni = $true,
  [bool]$CopyLlvmToJni   = $false,
  [string]$ToolBinSource = '',
  # New: do NOT inject host-built tool binaries (cmake/ninja) into sysroot by default
  # Because Android SELinux denies exec() from app private dirs (execute_no_trans)
  [bool]$InjectToolsToSysroot = $false,
  # New: copy in-process tool runners (shared objects) into jniLibs for app-side JNI loading
  [bool]$CopyToolRunnersToJni = $true
)

Write-Host "== Sync LLVM build artifacts (ABI=$Abi) ==" -ForegroundColor Cyan

# 1) Headers (LLVM/Clang) for build-time
& ./tools/sync-llvm-headers.ps1 -Abi $Abi -ApiLevel $ApiLevel

# 2) Shared libraries to jniLibs
$srcLibDir = Join-Path (Join-Path $BuildOutputRoot $Abi) (Join-Path 'libs' $Abi)
$dstLibDir = Join-Path $AppJniLibs $Abi
if (Test-Path $srcLibDir) {
  New-Item -ItemType Directory -Force -Path $dstLibDir | Out-Null
  # Pre-clean only our managed libraries to avoid leftovers; remove stale static archives as well
  $patterns = @('libclang-cpp*.so','libLLVM*.so','liblld*.so','libc++_shared.so')
  foreach($pat in $patterns){
    Get-ChildItem -Path $dstLibDir -Filter $pat -File -ErrorAction SilentlyContinue | ForEach-Object {
      Write-Host "Remove old: $($_.FullName)" -ForegroundColor DarkYellow
      Remove-Item -Force $_.FullName
    }
  }
  # Remove any static archives (*.a) accidentally present under jniLibs (APK 不需要 .a，且会膨胀体积)
  Get-ChildItem -Path $dstLibDir -Filter *.a -File -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host "Remove static archive: $($_.FullName)" -ForegroundColor DarkYellow
    Remove-Item -Force $_.FullName
  }
  # Only copy shared objects into jniLibs
  robocopy $srcLibDir $dstLibDir *.so /NFL /NDL /NJH /NJS /NP | Out-Null
  # Optionally remove libc++_shared.so (we rely on sysroot-side dynamic runtime)
  if (-not $CopyLibcxxToJni) {
    Get-ChildItem -Path $dstLibDir -Filter libc++_shared.so -File -ErrorAction SilentlyContinue | ForEach-Object {
      Write-Host "Remove libc++_shared from jniLibs (using sysroot version): $($_.FullName)" -ForegroundColor DarkYellow
      Remove-Item -Force $_.FullName
    }
  }
  # Optionally remove LLVM/Clang runtime from jniLibs (we will load from sysroot runtime path)
  if (-not $CopyLlvmToJni) {
    foreach($pat in @('libLLVM*.so','libclang-cpp*.so')){
      Get-ChildItem -Path $dstLibDir -Filter $pat -File -ErrorAction SilentlyContinue | ForEach-Object {
        Write-Host "Remove LLVM runtime from jniLibs (using sysroot version): $($_.FullName)" -ForegroundColor DarkYellow
        Remove-Item -Force $_.FullName
      }
    }
  }
  Write-Host "Copied .so libraries -> $dstLibDir" -ForegroundColor Green

  # Ensure libc++_shared.so is present in jniLibs when requested, even if not provided by prebuilt libs
  if ($CopyLibcxxToJni) {
    $dstLibCxx = Join-Path $dstLibDir 'libc++_shared.so'
    if (-not (Test-Path $dstLibCxx)) {
      Write-Host "INFO: libc++_shared.so not found under $dstLibDir - attempting to copy from local NDK" -ForegroundColor Yellow
      # Discover Android SDK/NDK roots
      $repoRoot = (Resolve-Path '.').Path
      $localPropsPath = Join-Path $repoRoot 'local.properties'
      $SdkDir = $null; $NdkDir = $null
      if (Test-Path $localPropsPath) {
        Get-Content $localPropsPath | ForEach-Object {
          if ($_ -match '^(sdk.dir)=(.*)$') { $SdkDir = $Matches[2].Trim() }
          if ($_ -match '^(ndk.dir)=(.*)$') { $NdkDir = $Matches[2].Trim() }
        }
      }
      if (-not $SdkDir) { $SdkDir = $env:ANDROID_SDK_ROOT }
      if (-not $SdkDir) { $SdkDir = $env:ANDROID_HOME }
      if (-not $NdkDir -and $SdkDir) {
        $ndkParent = Join-Path $SdkDir 'ndk'
        if (Test-Path $ndkParent) {
          $cand = Get-ChildItem -Path $ndkParent -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
          if ($cand) { $NdkDir = $cand.FullName }
        }
      }
      $copied = $false
      if ($NdkDir) {
        # Primary location (NDK r21+): sources/cxx-stl/llvm-libc++/libs/<abi>/libc++_shared.so
        $srcCxx = Join-Path (Join-Path $NdkDir 'sources/cxx-stl/llvm-libc++/libs') (Join-Path $Abi 'libc++_shared.so')
        if (Test-Path $srcCxx) {
          Copy-Item $srcCxx -Destination $dstLibCxx -Force
          Write-Host "INFO: Copied libc++_shared.so from $srcCxx to $dstLibCxx" -ForegroundColor Green
          $copied = $true
        } else {
          # Fallback (some layouts): toolchains/llvm/prebuilt/*/sysroot/usr/lib/<triple>/libc++_shared.so
          $triple = if ($Abi -eq 'arm64-v8a') { 'aarch64-linux-android' } else { 'x86_64-linux-android' }
          $preb = Get-ChildItem -Path (Join-Path $NdkDir 'toolchains/llvm/prebuilt') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
          if ($preb) {
            $cand1 = Join-Path $preb.FullName ("sysroot/usr/lib/$triple/libc++_shared.so")
            if (Test-Path $cand1) {
              Copy-Item $cand1 -Destination $dstLibCxx -Force
              Write-Host "INFO: Copied libc++_shared.so from $cand1 to $dstLibCxx" -ForegroundColor Green
              $copied = $true
            }
          }
        }
      }
      if (-not $copied) {
        Write-Host "[!] Unable to locate libc++_shared.so in prebuilt libs or local NDK. Ensure NDK installed and ANDROID_SDK_ROOT/ndk.dir set." -ForegroundColor Red
      }
    }
  }

  # Optionally copy tool runner .so (libninja_runner.so / libcmake_runner.so) from build-output tools/bin
  if ($CopyToolRunnersToJni) {
    try {
      $toolsBin = Join-Path (Join-Path $BuildOutputRoot $Abi) 'tools/bin'
      if (Test-Path $toolsBin) {
        $runners = @('libninja_runner.so','libcmake_runner.so','libclangd_runner.so')
        foreach($r in $runners){
          $src = Join-Path $toolsBin $r
          if (Test-Path $src) {
            $dst = Join-Path $dstLibDir $r
            Copy-Item $src -Destination $dst -Force
            Write-Host "INFO: Copied $r -> $dst" -ForegroundColor Green
          }
        }
      }
    } catch {
      Write-Host "[w] Failed to copy tool runners to jniLibs: $($_.Exception.Message)" -ForegroundColor Yellow
    }
  }
} else {
  Write-Host "Skip libs: $srcLibDir not found" -ForegroundColor DarkYellow
}

# 3) Sysroot to assets（可选）。当 SysrootMode=none 时，不打包也不镜像，并清理已存在的资产。
$srcSysroot = Join-Path (Join-Path $BuildOutputRoot $Abi) 'sysroot'
$assetsRoot = Split-Path -Parent $AppAssetsSysroot
$zipPath = Join-Path $assetsRoot 'sysroot.zip'
if ($SysrootMode -eq 'none') {
  if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
  if (Test-Path $AppAssetsSysroot) { Remove-Item -Recurse -Force $AppAssetsSysroot }
  Write-Host "[i] Sysroot packaging disabled (mode=none). Cleaned assets sysroot." -ForegroundColor Yellow
  return
}
if ($srcSysroot -and (Test-Path $srcSysroot)) {
  if ($SysrootMode -eq 'zip') {
    $triple = if ($Abi -eq 'arm64-v8a') { 'aarch64-linux-android' } else { 'x86_64-linux-android' }
    # Optional tool binaries injection disabled by default; enable with -InjectToolsToSysroot
    if ($InjectToolsToSysroot) {
      if (-not $ToolBinSource -or -not (Test-Path $ToolBinSource)) {
        $auto = Join-Path (Join-Path $BuildOutputRoot $Abi) 'tools/bin'
        if (Test-Path $auto) { $ToolBinSource = $auto }
      }
      if ($ToolBinSource -and (Test-Path $ToolBinSource)) {
        $dstBin = Join-Path $srcSysroot 'usr/bin'
        New-Item -ItemType Directory -Force -Path $dstBin | Out-Null
        Get-ChildItem -LiteralPath $ToolBinSource -File | ForEach-Object {
          Copy-Item $_.FullName -Destination (Join-Path $dstBin $_.Name) -Force
        }
        Write-Host "INFO: Injected tool binaries -> $dstBin" -ForegroundColor Green
      } else {
        Write-Host "INFO: ToolBinSource not provided or missing; skip tool injection" -ForegroundColor Yellow
      }
    }
    # 注入运行时 clang/LLVM 共享库到 sysroot（运行期从此处 System.load）。
    $triple = if ($Abi -eq 'arm64-v8a') { 'aarch64-linux-android' } else { 'x86_64-linux-android' }
    $dstRuntime = Join-Path $srcSysroot ("usr/lib/$triple/runtime")
    $prebuiltLibs = Join-Path (Join-Path $BuildOutputRoot $Abi) ("libs/$Abi")
    if (Test-Path $prebuiltLibs) {
      New-Item -ItemType Directory -Force -Path $dstRuntime | Out-Null
      $need = @('libclang-cpp.so')
      $llvmSo = Get-ChildItem -LiteralPath $prebuiltLibs -Filter 'libLLVM-*.so' -File -ErrorAction SilentlyContinue | Select-Object -First 1
      if ($llvmSo) { $need += $llvmSo.Name } else { Write-Host "[w] libLLVM-*.so not found under $prebuiltLibs" -ForegroundColor Yellow }
      # Do NOT include any LLD shared libs in sysroot runtime (LLD linked statically at build time)
      foreach($n in $need){
        $src = Join-Path $prebuiltLibs $n
        if (Test-Path $src) {
          Copy-Item $src -Destination (Join-Path $dstRuntime $n) -Force
        }
      }
      Write-Host "INFO: Injected runtime libs -> $dstRuntime" -ForegroundColor Green
    } else {
      Write-Host "[w] Prebuilt libs not found at $prebuiltLibs; skip runtime injection" -ForegroundColor Yellow
    }
    $srcTripleApiDir = Join-Path $srcSysroot ("usr/lib/$triple/$ApiLevel")
    $required = @('crtbegin_dynamic.o','crtend_android.o','libc.so','libm.so','liblog.so','libandroid.so','libc++_shared.so')
    $missing = @(); foreach($f in $required){ if (-not (Test-Path (Join-Path $srcTripleApiDir $f))) { $missing += $f } }
    if ($missing.Count -gt 0) { Write-Host "[!] source sysroot incomplete at: $srcTripleApiDir" -ForegroundColor Red; Write-Host ("    missing: " + ($missing -join ', ')) -ForegroundColor Red; throw "Source sysroot incomplete" }
    $assetsRoot = Split-Path -Parent $AppAssetsSysroot
    $zipPath = Join-Path $assetsRoot 'sysroot.zip'
    if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
    try { Add-Type -AssemblyName System.IO.Compression.FileSystem } catch { }
    $fs = [System.IO.File]::Open($zipPath, [System.IO.FileMode]::CreateNew)
    try {
      $zip = New-Object System.IO.Compression.ZipArchive($fs, [System.IO.Compression.ZipArchiveMode]::Create, $false)
      $root = (Resolve-Path $srcSysroot).Path
      $rootLen = $root.Length
      Get-ChildItem -LiteralPath $root -Recurse -File | ForEach-Object {
        if ($_.Extension -ieq '.a') { return }
        $rel = $_.FullName.Substring($rootLen).TrimStart([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
        $relZip = $rel -replace '\\','/'
        $null = [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $relZip, [System.IO.Compression.CompressionLevel]::Optimal)
      }
    } finally { if ($zip) { $zip.Dispose() }; if ($fs) { $fs.Dispose() } }
    Write-Host "INFO: Packaged sysroot.zip -> $zipPath" -ForegroundColor Green
    # Optionally copy tool runners into sysroot runtime too (for archival/absolute System.load)
    if ($CopyToolRunnersToSysroot) {
      try {
        $toolsBin = Join-Path (Join-Path $BuildOutputRoot $Abi) 'tools/bin'
        if (Test-Path $toolsBin) {
          $runners = @('libninja_runner.so','libcmake_runner.so','libclangd_runner.so')
          foreach($r in $runners){
            $src = Join-Path $toolsBin $r
            if (Test-Path $src) {
              Copy-Item $src -Destination (Join-Path $dstRuntime $r) -Force
              Write-Host "INFO: Archived $r into sysroot runtime" -ForegroundColor Green
            }
          }
        }
      } catch {
        Write-Host "[w] Failed to archive tool runners into sysroot runtime: $($_.Exception.Message)" -ForegroundColor Yellow
      }
    }
  } else {
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
  Write-Host "Mirrored sysroot -> $AppAssetsSysroot" -ForegroundColor Green
  }

} else {
  Write-Host "Skip sysroot packaging: source sysroot path not found or not set" -ForegroundColor DarkYellow
}

Write-Host "== Done ==" -ForegroundColor Cyan
