Param(
  [Alias('Abi')]
  [string[]]$AbiValues = @(),
  [int]$ApiLevel = 28,
  [string]$BuildOutputRoot = 'docker/llvm-build/build-output',
  [string]$AppJniLibs = 'app/src/main/jniLibs',
  [string]$AppAssetsSysroot = 'app/src/main/assets/sysroot',
  [ValidateSet('none','zip','mirror')]
  [string]$SysrootMode = 'zip'
)

$validAbis = @('arm64-v8a','x86_64')
function Normalize-AbiList {
  param([string[]]$values)
  $result = @()
  foreach ($value in $values) {
    if (-not $value) { continue }
    $parts = $value.Split(',', [System.StringSplitOptions]::RemoveEmptyEntries)
    foreach ($part in $parts) {
      $trimmed = $part.Trim()
      if ($trimmed) { $result += $trimmed }
    }
  }
  return $result
}
$normalizedAbi = Normalize-AbiList -values $AbiValues
if (-not $normalizedAbi -or $normalizedAbi.Count -eq 0) {
  $targetAbis = $validAbis
} else {
  foreach ($candidateAbi in $normalizedAbi) {
    if ($validAbis -notcontains $candidateAbi) {
      throw "Unsupported ABI: $candidateAbi. Valid values: $($validAbis -join ', ')"
    }
  }
  $targetAbis = $normalizedAbi
}

$assetsRoot = Split-Path -Parent $AppAssetsSysroot
$zipCleanupPatterns = @("sysroot-*.zip")
if ($SysrootMode -eq 'none') {
  foreach ($pattern in $zipCleanupPatterns) {
    Get-ChildItem -Path $assetsRoot -Filter $pattern -File -ErrorAction SilentlyContinue | ForEach-Object {
      Remove-Item -Force $_.FullName
    }
  }
  if (Test-Path $AppAssetsSysroot) {
    Remove-Item -Recurse -Force $AppAssetsSysroot
  }
  Write-Host "[i] Sysroot packaging disabled (mode=none). Cleaned assets sysroot/zips." -ForegroundColor Yellow
  return
}

# Headers are ABI-agnostic, sync once up-front
Write-Host "== Sync LLVM headers (ABI-independent) ==" -ForegroundColor Cyan
& ./tools/sync-llvm-headers.ps1 -ApiLevel $ApiLevel

$isMultiAbi = $targetAbis.Count -gt 1

foreach ($abiEntry in $targetAbis) {
  $currentAbi = ([string]$abiEntry).Trim()
  if (-not $currentAbi) { continue }
  Write-Host "== Sync LLVM build artifacts (ABI=$currentAbi) ==" -ForegroundColor Cyan


# Mirror common headers到工程可引用的位置（external/llvm-build-libs/common-headers）
$srcCommonHeaders = Join-Path $BuildOutputRoot 'common-headers'
$dstCommonHeaders = Join-Path 'external/llvm-build-libs' 'common-headers'
if (Test-Path $srcCommonHeaders) {
  New-Item -ItemType Directory -Force -Path $dstCommonHeaders | Out-Null
  robocopy $srcCommonHeaders $dstCommonHeaders /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
  Write-Host "Synced compiler headers -> $dstCommonHeaders" -ForegroundColor Green
} else {
  Write-Host "[w] Skip header sync: $srcCommonHeaders not found (run docker build first)" -ForegroundColor Yellow
}

# 2) Clean jniLibs (no LLVM/Clang runtime packaged in APK)
$abiOutputRoot = Join-Path -Path $BuildOutputRoot -ChildPath $currentAbi
$abiLibRoot = Join-Path -Path $abiOutputRoot -ChildPath 'libs'
$srcLibDir = Join-Path -Path $abiLibRoot -ChildPath $currentAbi
$dstLibDir = Join-Path -Path $AppJniLibs -ChildPath $currentAbi
if (Test-Path $dstLibDir) {
  $patterns = @('libclang*.so','libLLVM*.so','liblld*.so','libc++_shared.so')
  foreach($pat in $patterns){
    Get-ChildItem -Path $dstLibDir -Filter $pat -File -ErrorAction SilentlyContinue | ForEach-Object {
      Write-Host "Remove stale jniLibs entry: $($_.FullName)" -ForegroundColor DarkYellow
      Remove-Item -Force $_.FullName
    }
  }
  Get-ChildItem -Path $dstLibDir -Filter *.a -File -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Host "Remove static archive from jniLibs: $($_.FullName)" -ForegroundColor DarkYellow
    Remove-Item -Force $_.FullName
  }
  if (-not (Get-ChildItem -Path $dstLibDir -File -ErrorAction SilentlyContinue)) {
    Remove-Item -Recurse -Force $dstLibDir
    Write-Host "Removed empty jniLibs/$currentAbi directory" -ForegroundColor DarkGray
  } else {
    Write-Host "jniLibs/$currentAbi retained (non-LLVM libs present)" -ForegroundColor DarkGray
  }
} else {
  Write-Host "Skip jniLibs cleanup (directory missing): $dstLibDir" -ForegroundColor DarkGray
}

  # 3) Sysroot to assets（可选）。当 SysrootMode=none 时，不打包也不镜像，并清理已存在的资产。
  $srcSysroot = Join-Path -Path $abiOutputRoot -ChildPath 'sysroot'
  if ($srcSysroot -and (Test-Path $srcSysroot)) {
    $triple = if ($currentAbi -eq 'arm64-v8a') { 'aarch64-linux-android' } else { 'x86_64-linux-android' }
    if ($SysrootMode -eq 'zip') {
      # 注入运行时 clang/LLVM 共享库到 sysroot（运行期从此处 System.load）。
      $dstRuntime = Join-Path $srcSysroot ("usr/lib/$triple/runtime")
      $prebuiltLibs = Join-Path $abiLibRoot $currentAbi
      if (Test-Path $prebuiltLibs) {
        New-Item -ItemType Directory -Force -Path $dstRuntime | Out-Null
        $need = @('libclang-cpp.so', 'libclang.so', 'libclangd.so')
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
      
      # 关键：也复制 libc++_shared.so 到 runtime 目录，供运行期加载
      $srcTripleApiDir = Join-Path $srcSysroot ("usr/lib/$triple/$ApiLevel")
      $libcxxShared = Join-Path $srcTripleApiDir "libc++_shared.so"
      if (Test-Path $libcxxShared) {
        New-Item -ItemType Directory -Force -Path $dstRuntime | Out-Null
        Copy-Item $libcxxShared -Destination (Join-Path $dstRuntime "libc++_shared.so") -Force
        Write-Host "INFO: Copied libc++_shared.so to runtime dir" -ForegroundColor Green
      } else {
        Write-Host "[w] libc++_shared.so not found at $libcxxShared" -ForegroundColor Yellow
      }
      $srcTripleApiDir = Join-Path $srcSysroot ("usr/lib/$triple/$ApiLevel")
      $required = @('crtbegin_dynamic.o','crtend_android.o','libc.so','libm.so','liblog.so','libandroid.so','libc++_shared.so')
      $missing = @(); foreach($f in $required){ if (-not (Test-Path (Join-Path $srcTripleApiDir $f))) { $missing += $f } }
      if ($missing.Count -gt 0) { Write-Host "[!] source sysroot incomplete at: $srcTripleApiDir" -ForegroundColor Red; Write-Host ("    missing: " + ($missing -join ', ')) -ForegroundColor Red; throw "Source sysroot incomplete" }
      $zipName = if ($isMultiAbi) { "sysroot-$currentAbi.zip" } else { 'sysroot.zip' }
      $zipPath = Join-Path $assetsRoot $zipName
      if (Test-Path $zipPath) { Remove-Item -Force $zipPath }
      # 确保加载压缩程序集
      Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
      Add-Type -AssemblyName System.IO.Compression -ErrorAction SilentlyContinue
      $fs = [System.IO.File]::Open($zipPath, [System.IO.FileMode]::CreateNew)
      try {
        $zip = New-Object System.IO.Compression.ZipArchive($fs, 'Create', $false)
        $root = (Resolve-Path $srcSysroot).Path
        $rootLen = $root.Length
        $prunedBinFiles = @('cmake','ninja','llvm-symbolizer-host','llvm-objdump-host','llvm-dwarfdump-host','libc++_shared.so')
        # 需要保留的静态库（C++ 运行时依赖）
        $requiredStaticLibs = @('libunwind.a', 'libc++abi.a')
        Get-ChildItem -LiteralPath $root -Recurse -File | ForEach-Object {
          # 排除大部分静态库，但保留 C++ 运行时必需的
          if ($_.Extension -ieq '.a' -and $requiredStaticLibs -notcontains $_.Name) { return }
          $rel = $_.FullName.Substring($rootLen).TrimStart([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
          $relZip = $rel -replace '\\','/'
          if ($relZip -match '^usr/bin/(.+)$') {
            $name = $matches[1]
            if ($prunedBinFiles -contains $name) {
              return
            }
          }
          $null = [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $relZip, [System.IO.Compression.CompressionLevel]::Optimal)
        }
      } finally { if ($zip) { $zip.Dispose() }; if ($fs) { $fs.Dispose() } }
      Write-Host "INFO: Packaged sysroot archive -> $zipPath" -ForegroundColor Green
    } else {
      $assetsSysrootTarget = if ($isMultiAbi) { Join-Path $assetsRoot ("sysroot-$currentAbi") } else { $AppAssetsSysroot }
      New-Item -ItemType Directory -Force -Path $assetsSysrootTarget | Out-Null
      robocopy $srcSysroot $assetsSysrootTarget /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
      # Warn if target triple directory for selected API is missing
      $tripleDir = Join-Path $assetsSysrootTarget ("usr/lib/$triple/$ApiLevel")
      $srcStubRoot = Join-Path -Path (Join-Path -Path $abiOutputRoot -ChildPath 'sysroot/usr/lib') -ChildPath $triple
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
        $srcTripleDir = Join-Path -Path $srcStubRoot -ChildPath $ApiLevel
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
          Write-Host "    hint: ./docker/llvm-build/build-local.ps1 -Abi $currentAbi -ApiLevel $ApiLevel" -ForegroundColor Yellow
          # 同时打印 build-output 源目录的前若干项，帮助排查为何 assets 为空
          $srcTripleDir = Join-Path -Path $srcStubRoot -ChildPath $ApiLevel
          if (Test-Path $srcTripleDir) {
            Write-Host "[i] build-output source listing:" -ForegroundColor Yellow
            Get-ChildItem -Force $srcTripleDir | Select-Object -First 30 | Format-Table -AutoSize | Out-String | Write-Host
          } else {
            Write-Host "[i] build-output source dir not found: $srcTripleDir" -ForegroundColor Yellow
          }
          throw "Sysroot incomplete — aborting copy to avoid shipping a broken assets/sysroot"
        }
      }
      $libcppHdr = Join-Path $assetsSysrootTarget 'usr/include/c++/v1/__ios/fpos.h'
      if (-not (Test-Path $libcppHdr)) {
        Write-Host "[w] libc++ header missing: $libcppHdr — please rebuild libs (build-local.ps1 -Mode libs -Abi $currentAbi -ApiLevel $ApiLevel) to refresh sysroot c++/v1" -ForegroundColor Yellow
      }
      # Ensure clang resource headers exist under assets (stdarg.h)
      $clangRes = Join-Path $assetsSysrootTarget 'lib/clang/17/include/stdarg.h'
      if (-not (Test-Path $clangRes)) {
        Write-Host "[w] clang resource header missing: $clangRes — attempting fallback copy from local source tree" -ForegroundColor Yellow
        $hdrSrc = Resolve-Path 'docker/llvm-build/dev-work/src/llvm-project/clang/lib/Headers' -ErrorAction SilentlyContinue
        if ($hdrSrc) {
          $dstDir = Join-Path $assetsSysrootTarget 'lib/clang/17/include'
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
      Write-Host "Mirrored sysroot -> $assetsSysrootTarget" -ForegroundColor Green
    }
  } else {
    Write-Host "Skip sysroot packaging: source sysroot path not found or not set" -ForegroundColor DarkYellow
  }

}

Write-Host "== Done ==" -ForegroundColor Cyan
