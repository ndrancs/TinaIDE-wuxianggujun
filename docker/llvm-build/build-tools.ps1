Param(
  [ValidateSet('arm64-v8a','x86_64')][string]$Abi = 'x86_64',
  [int]$ApiLevel = 28,
  [string]$ContainerName = 'tina-llvm-build',
  [string]$OutputPath,
  # Build shared-object runner for Ninja (SELinux blocks exec in app sandbox)
  [bool]$BuildNinjaSo = $true
)

$ErrorActionPreference = 'Stop'

function Write-Info($msg) { Write-Host "[i] $msg" -ForegroundColor Cyan }
function Write-Err($msg)  { Write-Host "[!] $msg" -ForegroundColor Red }

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $scriptRoot '..\..')

if (-not $OutputPath -or [string]::IsNullOrWhiteSpace($OutputPath)) {
  $OutputPath = Join-Path $root 'docker/llvm-build/build-output'
}
$outBase = (Resolve-Path $OutputPath).Path
$outDirHost = Join-Path $outBase $Abi
New-Item -ItemType Directory -Force -Path (Join-Path $outDirHost 'tools/bin') | Out-Null

# Ensure dev container is running (re-use the one used by build-local.ps1)
$running = (& docker ps --format '{{.Names}}' | Select-String -SimpleMatch $ContainerName) -ne $null
if (-not $running) {
  Write-Err "Dev container '$ContainerName' not running. Please run docker/llvm-build/build-local.ps1 once to create it."
  exit 2
}

function Exec-In-Dev { param([string]$cmd) & docker exec $ContainerName bash -lc $cmd }

$assign = "ABI='$Abi'; API_LEVEL='$ApiLevel'; BUILD_NINJA_SO='$BuildNinjaSo';"
$session = @'
set -eux
case "${ABI}" in
  arm64-v8a) TRIPLE=aarch64-linux-android;;
  x86_64)    TRIPLE=x86_64-linux-android;;
  *) echo "Unsupported ABI: ${ABI}"; exit 1;;
esac

# Build Ninja for Android using CMake toolchain
if [ ! -d /work/src/ninja/.git ]; then
  git clone --depth=1 https://github.com/ninja-build/ninja.git /work/src/ninja
fi

cmake -S /work/src/ninja -B /work/build/tools/ninja-${ABI}-api${API_LEVEL} -G Ninja \
  -DCMAKE_SYSTEM_NAME=Android -DCMAKE_ANDROID_NDK=${ANDROID_NDK_HOME} \
  -DCMAKE_TOOLCHAIN_FILE=${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake \
  -DCMAKE_ANDROID_ARCH_ABI=${ABI} -DCMAKE_ANDROID_API=${API_LEVEL} \
  -DCMAKE_BUILD_TYPE=MinSizeRel \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DCMAKE_CXX_FLAGS="-fPIC"
ninja -C /work/build/tools/ninja-${ABI}-api${API_LEVEL} -j$(nproc) ninja

mkdir -p /hostout/${ABI}/tools/bin
cp -af /work/build/tools/ninja-${ABI}-api${API_LEVEL}/ninja /hostout/${ABI}/tools/bin/

# Strip to reduce size if available
if [ -x "${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" ]; then
  ${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip -S /hostout/${ABI}/tools/bin/ninja || true
fi
chmod 0755 /hostout/${ABI}/tools/bin/ninja

# Optionally build libninja_runner.so (in-process runner)
if [ "${BUILD_NINJA_SO}" = "True" ] || [ "${BUILD_NINJA_SO}" = "true" ] || [ "${BUILD_NINJA_SO}" = "1" ]; then
  case "${ABI}" in
    arm64-v8a) TRIPLE=aarch64-linux-android;;
    x86_64)    TRIPLE=x86_64-linux-android;;
  esac
  NDK_CLANGXX="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/linux-x86_64/bin/${TRIPLE}${API_LEVEL}-clang++"
  mkdir -p /work/build/tools/ninja-runner
  cat > /work/build/tools/ninja-runner/ninja_runner.cpp <<'EOF'
#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unistd.h>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "NinjaRunner", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "NinjaRunner", __VA_ARGS__)

extern "C" int main(int, char**);

// Export ninja_run for use by native_compiler.so
extern "C" __attribute__((visibility("default"))) int ninja_run(int argc, char** argv) {
  LOGI("========================================");
  LOGI("=== ninja_run START, argc=%d ===", argc);
  LOGI("========================================");
  
  for (int i = 0; i < argc; i++) {
    LOGI("  ninja argv[%d] = %s", i, argv[i] ? argv[i] : "(null)");
  }
  
  LOGI(">>> Calling Ninja main() NOW <<<");
  int result = main(argc, argv);
  LOGI("<<< Ninja main() returned: %d >>>", result);
  
  LOGI("========================================");
  LOGI("=== ninja_run END, result=%d ===", result);
  LOGI("========================================");
  
  return result;
}

// Provide C++ stub for browse functionality (matching browse.h signature)
// On Android, we don't have Python, so we provide a minimal implementation
// that logs a message and exits with error
struct State;
void RunBrowsePython(State* state, const char* ninja_command,
                     const char* input_file, int argc, char* argv[]) {
  __android_log_print(ANDROID_LOG_WARN, "NinjaRunner", 
    "Browse mode is not supported on Android (no Python runtime)");
  __android_log_print(ANDROID_LOG_INFO, "NinjaRunner",
    "To view build graph, use ninja -t graph or ninja -t targets");
  // Original function does not return on success, so we exit with error
  std::exit(1);
}

// JNI wrapper for running Ninja
extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NinjaRunner_runNinja(
    JNIEnv* env, jobject /* this */, jstring jWorkingDir, jobjectArray jArgs) {
  
  // Get working directory
  const char* workingDir = env->GetStringUTFChars(jWorkingDir, nullptr);
  if (!workingDir) {
    LOGE("Failed to get working directory");
    return -1;
  }
  
  // Change to working directory
  if (chdir(workingDir) != 0) {
    LOGE("Failed to change directory to: %s", workingDir);
    env->ReleaseStringUTFChars(jWorkingDir, workingDir);
    return -1;
  }
  env->ReleaseStringUTFChars(jWorkingDir, workingDir);
  
  // Convert Java string array to C argv
  jsize argc = env->GetArrayLength(jArgs);
  std::vector<char*> argv(argc + 1);  // +1 for NULL terminator
  std::vector<std::string> argStrings(argc);
  
  for (jsize i = 0; i < argc; i++) {
    jstring jArg = (jstring)env->GetObjectArrayElement(jArgs, i);
    const char* arg = env->GetStringUTFChars(jArg, nullptr);
    argStrings[i] = arg;
    argv[i] = const_cast<char*>(argStrings[i].c_str());
    env->ReleaseStringUTFChars(jArg, arg);
    env->DeleteLocalRef(jArg);
  }
  argv[argc] = nullptr;
  
  LOGI("Calling ninja main with %d arguments", argc);
  
  // Call ninja's main function
  int result = main(argc, argv.data());
  
  LOGI("Ninja returned: %d", result);
  return result;
}
EOF
  # Collect ALL object files from the ninja target EXCEPT browse.cc.o
  # browse.cc requires Python runtime which we don't have on Android
  # We provide a C++ stub implementation above to satisfy any references
  ninja_objs_core=$(find /work/build/tools/ninja-${ABI}-api${API_LEVEL}/CMakeFiles/libninja.dir -name '*.o' | xargs echo)
  ninja_objs_re2c=$(find /work/build/tools/ninja-${ABI}-api${API_LEVEL}/CMakeFiles/libninja-re2c.dir -name '*.o' | xargs echo)
  # Collect all ninja.dir objects EXCEPT browse.cc.o
  ninja_objs_main=$(find /work/build/tools/ninja-${ABI}-api${API_LEVEL}/CMakeFiles/ninja.dir -name '*.o' ! -name 'browse.cc.o' | xargs echo)
  if [ -n "${ninja_objs_core}" ] || [ -n "${ninja_objs_re2c}" ] || [ -n "${ninja_objs_main}" ]; then 
    ninja_objs="${ninja_objs_core} ${ninja_objs_re2c} ${ninja_objs_main}"
  else 
    ninja_objs=""
  fi
  echo "[i] Excluding browse.cc.o and providing C++ stub for RunBrowsePython"
  if [ -n "${ninja_objs}" ] && [ -x "${NDK_CLANGXX}" ]; then
    ${NDK_CLANGXX} -shared -fPIC -Wl,-z,now -Wl,-z,relro \
      -o /hostout/${ABI}/tools/bin/libninja_runner.so \
      ${ninja_objs} /work/build/tools/ninja-runner/ninja_runner.cpp -llog -landroid || \
      echo "[w] libninja_runner.so link failed; continuing"
  else
    echo "[w] Could not locate ninja objects or NDK clang++ for runner build; skipping"
  fi
fi

echo "[i] Ninja build complete."
echo "[i] To build CMake, use: pwsh docker/llvm-build/build-cmake-so-only.ps1"
'@

Write-Info "Building Ninja for ABI=$Abi (API=$ApiLevel) in container: $ContainerName"
# Build a single multi-line command (true newlines) for bash -lc
$cmd = @"
$assign
$session
"@
Exec-In-Dev $cmd
Write-Info "Ninja built. Output at: $(Join-Path $outDirHost 'tools/bin')"
Write-Info "Done."
