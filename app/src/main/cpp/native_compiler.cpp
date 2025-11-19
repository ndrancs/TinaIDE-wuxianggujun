// JNI & logging
#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>
#include <poll.h>
#include <errno.h>
#include <typeinfo>
#include <cxxabi.h>

#if LLVM_HEADERS_AVAILABLE
// Clang/LLVM headers for in-process compilation
#include "clang/Frontend/CompilerInstance.h"
#include "clang/Frontend/CompilerInvocation.h"
#include "clang/Frontend/TextDiagnosticPrinter.h"
#include "clang/FrontendTool/Utils.h"
#include "clang/Basic/Diagnostic.h"
#include "clang/Basic/DiagnosticOptions.h"
#include "llvm/ADT/IntrusiveRefCntPtr.h"
#include "llvm/Support/Host.h"
#include "llvm/Support/TargetSelect.h"
#include "llvm/Support/VirtualFileSystem.h"
#include "llvm/Support/raw_ostream.h"
#endif

// Forward declaration to avoid depending on LLD headers at build time.
#if LLVM_HEADERS_AVAILABLE
// 注意：为避免直接依赖 LLD 头文件，这里仅做前向声明。
// LLVM 17（本项目打包的 LLD 静态库）对外入口原型：
//   bool lld::elf::link(ArrayRef<const char*>, raw_ostream& /*stdout*/, raw_ostream& /*stderr*/, bool /*disableOutput*/, bool /*exitEarly*/)
// 该签名由打包的 liblldELF.a 导出符号验证（见 llvm-nm 输出）。
namespace llvm { template <typename T> class ArrayRef; class raw_ostream; }
namespace lld { namespace elf { bool link(llvm::ArrayRef<const char*>, llvm::raw_ostream&, llvm::raw_ostream&, bool, bool); } }
#endif

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "native_compiler", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  "native_compiler", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "native_compiler", __VA_ARGS__)



// ---- Minimal per-arch LLVM target initialization (avoid unresolved symbols) ----
#if LLVM_HEADERS_AVAILABLE
extern "C" {
#if defined(__aarch64__)
void LLVMInitializeAArch64TargetInfo();
void LLVMInitializeAArch64Target();
void LLVMInitializeAArch64TargetMC();
void LLVMInitializeAArch64AsmParser();
void LLVMInitializeAArch64AsmPrinter();
#elif defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
void LLVMInitializeX86TargetInfo();
void LLVMInitializeX86Target();
void LLVMInitializeX86TargetMC();
void LLVMInitializeX86AsmParser();
void LLVMInitializeX86AsmPrinter();
#endif
}

static inline void initLLVMTargetsOnce() {
    static bool inited = false; if (inited) return; inited = true;
#if defined(__aarch64__)
    LLVMInitializeAArch64TargetInfo();
    LLVMInitializeAArch64Target();
    LLVMInitializeAArch64TargetMC();
    LLVMInitializeAArch64AsmParser();
    LLVMInitializeAArch64AsmPrinter();
#elif defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
    LLVMInitializeX86TargetInfo();
    LLVMInitializeX86Target();
    LLVMInitializeX86TargetMC();
    LLVMInitializeX86AsmParser();
    LLVMInitializeX86AsmPrinter();
#else
    // Fallback: do nothing (avoid unresolved refs for unknown arch)
#endif
}
#endif // LLVM_HEADERS_AVAILABLE

extern "C" JNIEXPORT jstring JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NativeCompiler_getClangVersion(
        JNIEnv* env,
        jclass /*clazz*/) {
    // 未打包 LLVM/Clang 头文件时，返回简化版本字符串以验证 JNI 调用链
    const char* v = "LLVM 17 (runtime libs bundled)";
    LOGI("llvm version (placeholder): %s", v);
    return env->NewStringUTF(v);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NativeCompiler_syntaxCheck(
        JNIEnv* env,
        jclass /*clazz*/, jstring jSysroot, jstring jSrc, jstring jTarget, jboolean jIsCxx) {
#if !LLVM_HEADERS_AVAILABLE
    const char* msg = "UNAVAILABLE: syntaxCheck requires LLVM headers (in-process)";
    return env->NewStringUTF(msg);
#else
    auto toStr = [&](jstring s){ const char* c = s? env->GetStringUTFChars(s,nullptr):nullptr; std::string o=c?std::string(c):std::string(); if(c) env->ReleaseStringUTFChars(s,c); return o; };
    const std::string sysroot = toStr(jSysroot);
    const std::string srcPath = toStr(jSrc);
    const std::string target  = toStr(jTarget);
    const bool isCxx = jIsCxx == JNI_TRUE;
    // Ensure LLVM target backends are registered so cc1 can emit/parse for the target
    static bool sTargetsInitedA = false;
    if (!sTargetsInitedA) {
        // 仅初始化与当前 ABI 匹配的 LLVM 目标，避免链接期未定义符号
        initLLVMTargetsOnce();
        sTargetsInitedA = true;
    }
    // Derive arch triple without API suffix, e.g. "x86_64-linux-android24" → "x86_64-linux-android"
    auto deriveTripleBase = [&](const std::string& t){
        if (t.empty()) return std::string();
        std::string r = t;
        while (!r.empty() && isdigit(static_cast<unsigned char>(r.back()))) r.pop_back();
        return r;
    };
    const std::string tripleBase = deriveTripleBase(target.empty()? llvm::sys::getDefaultTargetTriple(): target);

    // Build tokens first, then materialize stable argv pointers to avoid dangling char*
    std::vector<std::string> tokens;
    auto push  = [&](std::string s){ tokens.emplace_back(std::move(s)); };
    auto push2 = [&](const char* a, std::string b){ tokens.emplace_back(a); tokens.emplace_back(std::move(b)); };
    push("-cc1");
    push2("-triple", target.empty()? llvm::sys::getDefaultTargetTriple(): target);
    push("-fsyntax-only");
    push("-nobuiltininc");
    push2("-isysroot", sysroot);
    // Provide Clang resource-dir so builtin headers like <stdarg.h> are found under -nobuiltininc
    push2("-resource-dir", sysroot+"/lib/clang/17");
    // Also add the resource include as an internal system include for cc1 to pick it up reliably
    push("-internal-isystem"); push(sysroot+"/lib/clang/17/include");
    push("-x");
    push(isCxx ? std::string("c++") : std::string("c"));
    if (isCxx) push("-std=c++17");
    push("-DANDROID"); push("-D__ANDROID__");
    if(!sysroot.empty()){
        push2("-isystem", sysroot+"/usr/include");
        if (!tripleBase.empty()) push2("-isystem", sysroot+"/usr/include/"+tripleBase);
        push2("-I", sysroot+"/usr/include/c++/v1");
    }
    tokens.emplace_back(srcPath);

    std::vector<const char*> args; args.reserve(tokens.size());
    for (auto& t : tokens) args.push_back(t.c_str());

    llvm::IntrusiveRefCntPtr<clang::DiagnosticOptions> dopt = new clang::DiagnosticOptions();
    std::string diag; llvm::raw_string_ostream os(diag);
    llvm::IntrusiveRefCntPtr<clang::DiagnosticIDs> did(new clang::DiagnosticIDs());
    auto printer = std::make_unique<clang::TextDiagnosticPrinter>(os, &*dopt);
    clang::DiagnosticsEngine diags(did, &*dopt, printer.get(), false);
    std::unique_ptr<clang::CompilerInvocation> CI(new clang::CompilerInvocation());
    if (!clang::CompilerInvocation::CreateFromArgs(*CI, args, diags)) { os.flush(); return env->NewStringUTF(diag.empty()?"create invocation failed":diag.c_str()); }

    clang::CompilerInstance Clang; Clang.setInvocation(std::move(CI));
    Clang.createDiagnostics(printer.release(), true);
    bool ok = clang::ExecuteCompilerInvocation(&Clang);
    os.flush();
    if (!ok) return env->NewStringUTF(diag.empty()?"syntax check failed":diag.c_str());
    return env->NewStringUTF("");
#endif
}

static std::string jstringToUtf8(JNIEnv* env, jstring s) {
    if (!s) return std::string();
    const char* utf = env->GetStringUTFChars(s, nullptr);
    std::string out = utf ? std::string(utf) : std::string();
    if (utf) env->ReleaseStringUTFChars(s, utf);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NativeCompiler_emitObj(
        JNIEnv* env,
        jclass /*clazz*/,
        jstring jSysroot,
        jstring jSrc,
        jstring jObjOut,
        jstring jTarget,
        jboolean jIsCxx,
        jobjectArray jFlags,
        jobjectArray jIncludeDirs) {
#if !LLVM_HEADERS_AVAILABLE
    const char* msg = "UNAVAILABLE: LLVM headers not found (run tools/sync-llvm-headers.ps1)";
    return env->NewStringUTF(msg);
#else
    auto toStr = [&](jstring s){ const char* c = s? env->GetStringUTFChars(s,nullptr):nullptr; std::string o=c?std::string(c):std::string(); if(c) env->ReleaseStringUTFChars(s,c); return o; };
    const std::string sysroot = toStr(jSysroot);
    const std::string srcPath = toStr(jSrc);
    const std::string objOut  = toStr(jObjOut);
    const std::string target  = toStr(jTarget);
    const bool isCxx = jIsCxx == JNI_TRUE;
    // Ensure LLVM target backends are registered so cc1 can emit objects
    static bool sTargetsInitedB = false;
    if (!sTargetsInitedB) {
        // 同上
        initLLVMTargetsOnce();
        sTargetsInitedB = true;
    }
    auto deriveTripleBase = [&](const std::string& t){
        if (t.empty()) return std::string();
        std::string r = t;
        while (!r.empty() && isdigit(static_cast<unsigned char>(r.back()))) r.pop_back();
        return r;
    };
    const std::string tripleBase = deriveTripleBase(target.empty()? llvm::sys::getDefaultTargetTriple(): target);

    // Build tokens then stable argv
    std::vector<std::string> tokens;
    auto push  = [&](std::string s){ tokens.emplace_back(std::move(s)); };
    auto push2 = [&](const char* a, std::string b){ tokens.emplace_back(a); tokens.emplace_back(std::move(b)); };
    push("-cc1");
    push2("-triple", target.empty()? llvm::sys::getDefaultTargetTriple(): target);
    push("-emit-obj"); push("-O2"); push("-nobuiltininc");
    // Android 要求：.o 必须 PIC (Position Independent Code)
    // 注意：-cc1 模式下使用 -mrelocation-model pic 而不是 -fPIC
    push("-mrelocation-model"); push("pic");
    push2("-isysroot", sysroot);
    // Provide Clang resource-dir so builtin headers like <stdarg.h> are found under -nobuiltininc
    push2("-resource-dir", sysroot+"/lib/clang/17");
    // Also add the resource include as an internal system include for cc1 to pick it up reliably
    push("-internal-isystem"); push(sysroot+"/lib/clang/17/include");
    push("-x");
    push(isCxx ? std::string("c++") : std::string("c"));
    if (isCxx) push("-std=c++17");
    push("-DANDROID"); push("-D__ANDROID__");
    if(!sysroot.empty()){
        push2("-isystem", sysroot+"/usr/include");
        if (!tripleBase.empty()) push2("-isystem", sysroot+"/usr/include/"+tripleBase);
        push2("-I", sysroot+"/usr/include/c++/v1");
    }
    if (jIncludeDirs){ jsize n=env->GetArrayLength(jIncludeDirs); for(jsize i=0;i<n;++i){ jstring s=(jstring)env->GetObjectArrayElement(jIncludeDirs,i); std::string p=toStr(s); if(!p.empty()) push2("-I",p); env->DeleteLocalRef(s);} }
    if (jFlags){ jsize n=env->GetArrayLength(jFlags); for(jsize i=0;i<n;++i){ jstring s=(jstring)env->GetObjectArrayElement(jFlags,i); std::string f=toStr(s); if(!f.empty()) push(std::move(f)); env->DeleteLocalRef(s);} }
    push2("-o", objOut); tokens.emplace_back(srcPath);

    std::vector<const char*> args; args.reserve(tokens.size());
    for (auto& t : tokens) args.push_back(t.c_str());

    // Debug only: dump cc1 args to log once per invocation
#ifndef NDEBUG
    {
        std::ostringstream oss; oss << "cc1 args (" << args.size() << "):";
        for (auto* a : args) { oss << " " << (a ? a : "<null>"); }
        LOGI("%s", oss.str().c_str());
    }
#endif

    llvm::IntrusiveRefCntPtr<clang::DiagnosticOptions> dopt = new clang::DiagnosticOptions();
    std::string diag; llvm::raw_string_ostream os(diag);
    llvm::IntrusiveRefCntPtr<clang::DiagnosticIDs> did(new clang::DiagnosticIDs());
    auto printer = std::make_unique<clang::TextDiagnosticPrinter>(os, &*dopt);
    clang::DiagnosticsEngine diags(did, &*dopt, printer.get(), false);
    std::unique_ptr<clang::CompilerInvocation> CI(new clang::CompilerInvocation());
    if (!clang::CompilerInvocation::CreateFromArgs(*CI, args, diags)) { os.flush(); return env->NewStringUTF(diag.empty()?"create invocation failed":diag.c_str()); }

    clang::CompilerInstance Clang; Clang.setInvocation(std::move(CI));
    Clang.createDiagnostics(printer.release(), true);
    bool ok = clang::ExecuteCompilerInvocation(&Clang);
    os.flush();
    if (!ok) return env->NewStringUTF(diag.empty()?"compile failed":diag.c_str());
    return env->NewStringUTF("");
#endif
}

// Link object to a PIE executable using LLD (in-process)
extern "C" JNIEXPORT jstring JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NativeCompiler_linkExe(
        JNIEnv* env, jclass /*clazz*/, jstring jSysroot, jstring jObj, jstring jOut,
        jstring jTarget, jboolean jIsCxx) {
#if !LLVM_HEADERS_AVAILABLE
    const char* msg = "UNAVAILABLE: LLD not available";
    return env->NewStringUTF(msg);
#elif !defined(LLD_LINK_ENABLED)
    const char* msg = "UNAVAILABLE: LLD link disabled at build time";
    return env->NewStringUTF(msg);
#else
    auto toStr = [&](jstring s){ const char* c = s? env->GetStringUTFChars(s,nullptr):nullptr; std::string o=c?std::string(c):std::string(); if(c) env->ReleaseStringUTFChars(s,c); return o; };
    const std::string sysroot = toStr(jSysroot);
    const std::string objPath = toStr(jObj);
    const std::string outExe  = toStr(jOut);
    const std::string target  = toStr(jTarget);
    const bool isCxx = jIsCxx == JNI_TRUE;

    auto deriveTripleBase = [&](const std::string& t){ std::string r=t; while(!r.empty() && isdigit((unsigned char)r.back())) r.pop_back(); return r; };
    const std::string tripleBase = deriveTripleBase(target);
    auto deriveApi = [&](const std::string& t){
        std::string digits;
        for (auto it = t.rbegin(); it != t.rend(); ++it) {
            if (!isdigit(static_cast<unsigned char>(*it))) break;
            digits.push_back(*it);
        }
        std::reverse(digits.begin(), digits.end());
        return digits.empty() ? std::string("24") : digits;
    };
    const std::string api = deriveApi(target);
    const std::string libDir = sysroot+"/usr/lib/"+tripleBase+"/"+api;

    // 检查 sysroot 库目录是否存在
    struct stat st;
    if (stat(libDir.c_str(), &st) != 0 || !S_ISDIR(st.st_mode)) {
        std::string err =
            "[TinaIDE] Sysroot library directory missing: " + libDir +
            "\n请先同步嵌入式 NDK 资源："
            "\n 1) ./docker/llvm-build/build-local.ps1 -Abi " + (tripleBase.find("aarch64") != std::string::npos ? "arm64-v8a" : "x86_64") + " -ApiLevel " + api +
            "\n 2) ./tools/sync-llvm-build.ps1 -Abi " + (tripleBase.find("aarch64") != std::string::npos ? "arm64-v8a" : "x86_64") +
            "\n这些步骤会把 NDK 的 stub 库与 crt 对象复制到 assets/sysroot 下。";
        return env->NewStringUTF(err.c_str());
    }

    // 进一步预检：关键 crt 与系统库是否存在，避免到 LLD 阶段才失败
    auto exists = [](const std::string& p){ struct stat s; return stat(p.c_str(), &s) == 0 && S_ISREG(s.st_mode); };
    const std::string crtBegin = libDir + "/crtbegin_dynamic.o";
    const std::string crtEnd   = libDir + "/crtend_android.o";
    const std::string libcSo   = libDir + "/libc.so";
    const std::string libmSo   = libDir + "/libm.so";
    const std::string liblogSo = libDir + "/liblog.so";
    const std::string libandroidSo = libDir + "/libandroid.so";
    std::vector<std::string> missing;
    if (!exists(crtBegin))      missing.push_back("crtbegin_dynamic.o");
    if (!exists(crtEnd))        missing.push_back("crtend_android.o");
    if (!exists(libcSo))        missing.push_back("libc.so");
    if (!exists(libmSo))        missing.push_back("libm.so");
    if (!exists(liblogSo))      missing.push_back("liblog.so");
    if (!exists(libandroidSo))  missing.push_back("libandroid.so");
    if (jIsCxx == JNI_TRUE) {
        // 动态 C++ 运行时必需：libc++_shared.so 可在 API 目录或 triple 根目录
        const std::string libcxxSharedApi  = libDir + "/libc++_shared.so";
        const std::string libcxxSharedRoot = (sysroot+"/usr/lib/"+tripleBase) + "/libc++_shared.so";
        if (!exists(libcxxSharedApi) && !exists(libcxxSharedRoot)) {
            missing.push_back("libc++_shared.so");
        }
    }
    if (!missing.empty()) {
        std::ostringstream oss;
        oss << "[TinaIDE] 链接所需的 NDK stub/crt 缺失于: " << libDir << "\n"
            << "缺失: ";
        for (size_t i=0;i<missing.size();++i) { if(i) oss<<", "; oss<<missing[i]; }
        oss << "\n请执行:"
            << "\n 1) ./docker/llvm-build/build-local.ps1 -Abi "
            << (tripleBase.find("aarch64") != std::string::npos ? "arm64-v8a" : "x86_64")
            << " -ApiLevel " << api
            << "\n 2) ./tools/sync-llvm-build.ps1 -Abi "
            << (tripleBase.find("aarch64") != std::string::npos ? "arm64-v8a" : "x86_64")
            << "\n或从本机 NDK 复制上述文件到 assets/sysroot 相应目录后重试。";
        const std::string msg = oss.str();
        return env->NewStringUTF(msg.c_str());
    }
    
    // 先构建所有字符串到 keep，避免 vector 扩容导致 c_str() 失效
    std::vector<std::string> keep;
    keep.reserve(20);  // 预分配空间
    
    keep.push_back("ld.lld");
    keep.push_back("-pie");
    keep.push_back("-z");
    keep.push_back("now");
    keep.push_back("-z");
    keep.push_back("relro");
    keep.push_back("-L");
    keep.push_back(libDir);
    // Also search triple root where NDK places libc++_shared.so (r23+)
    const std::string libDirRoot = sysroot+"/usr/lib/"+tripleBase;
    // Library search paths (link-time)
    keep.push_back("-L");
    keep.push_back(libDirRoot);
    // Do not inject rpath; rely on app-preloaded libc++_shared to avoid duplicate runtimes
    // Explicit dynamic linker for Android
    const char* dynLinker = (tripleBase.find("64") != std::string::npos) ? "/system/bin/linker64" : "/system/bin/linker";
    keep.push_back("-dynamic-linker");
    keep.push_back(dynLinker);
    // Inputs and output
    keep.push_back(crtBegin);
    keep.push_back(objPath);
    keep.push_back("-o");
    keep.push_back(outExe);
    if (isCxx) {
        // 强制使用动态 C++ 运行时（由 NDK 链接脚本把 -lc++ 解析为 -lc++_shared）
        keep.push_back("-lc++");
    }
    keep.push_back("-lc");
    keep.push_back("-lm");
    keep.push_back("-llog");
    keep.push_back("-landroid");
    keep.push_back(crtEnd);
    
    // 然后构建 args 指针数组
    std::vector<const char*> args;
    args.reserve(keep.size());
    for (const auto& s : keep) {
        args.push_back(s.c_str());
    }

    #if LLD_LINK_ENABLED
    std::string diag;
    llvm::raw_string_ostream os(diag);
    // 调用打包库签名 (args, stdout, stderr, disableOutput, exitEarly)
    bool ok = lld::elf::link(args, os, os, /*disableOutput=*/false, /*exitEarly=*/false);
    os.flush();
    if (!ok) return env->NewStringUTF(diag.c_str());
    // chmod +x
    chmod(outExe.c_str(), 0755);
    return env->NewStringUTF("");
    #else
    // No fallback: dynamic-only mode requires shared LLD libraries present at build/runtime.
    return env->NewStringUTF("UNAVAILABLE: In-process LLD disabled at build-time. Build liblldELF.a/liblldCommon.a (static) and resync build artifacts.");
    #endif
#endif
}

// Link multiple objects to a PIE executable using in-process LLD
extern "C" JNIEXPORT jstring JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NativeCompiler_linkExeMany(
        JNIEnv* env, jclass /*clazz*/, jstring jSysroot, jobjectArray jObjs, jstring jOut,
        jstring jTarget, jboolean jIsCxx, jobjectArray jLibDirs, jobjectArray jLibs) {
#if !LLVM_HEADERS_AVAILABLE
    const char* msg = "UNAVAILABLE: LLD not available";
    return env->NewStringUTF(msg);
#elif !defined(LLD_LINK_ENABLED)
    const char* msg = "UNAVAILABLE: LLD link disabled at build time";
    return env->NewStringUTF(msg);
#else
    auto toStr = [&](jstring s){ const char* c = s? env->GetStringUTFChars(s,nullptr):nullptr; std::string o=c?std::string(c):std::string(); if(c) env->ReleaseStringUTFChars(s,c); return o; };
    const std::string sysroot = toStr(jSysroot);
    const std::string outExe  = toStr(jOut);
    const std::string target  = toStr(jTarget);
    const bool isCxx = jIsCxx == JNI_TRUE;

    auto deriveTripleBase = [&](const std::string& t){ std::string r=t; while(!r.empty() && isdigit((unsigned char)r.back())) r.pop_back(); return r; };
    const std::string tripleBase = deriveTripleBase(target);
    auto deriveApi = [&](const std::string& t){ std::string digits; for (auto it=t.rbegin(); it!=t.rend(); ++it){ if(!isdigit((unsigned char)*it)) break; digits.push_back(*it);} std::reverse(digits.begin(), digits.end()); return digits.empty()? std::string("24"):digits; };
    const std::string api = deriveApi(target);
    const std::string libDir = sysroot+"/usr/lib/"+tripleBase+"/"+api;

    // Basic sysroot checks (same as single-object variant)
    struct stat st;
    if (stat(libDir.c_str(), &st) != 0 || !S_ISDIR(st.st_mode)) {
        std::string err = "[TinaIDE] Sysroot library directory missing: "+libDir;
        return env->NewStringUTF(err.c_str());
    }
    auto exists = [](const std::string& p){ struct stat s; return stat(p.c_str(), &s) == 0 && S_ISREG(s.st_mode); };
    const std::string crtBegin = libDir + "/crtbegin_dynamic.o";
    const std::string crtEnd   = libDir + "/crtend_android.o";
    const std::string libcSo   = libDir + "/libc.so";
    const std::string libmSo   = libDir + "/libm.so";
    const std::string liblogSo = libDir + "/liblog.so";
    const std::string libandroidSo = libDir + "/libandroid.so";
    std::vector<std::string> missing;
    if (!exists(crtBegin))      missing.push_back("crtbegin_dynamic.o");
    if (!exists(crtEnd))        missing.push_back("crtend_android.o");
    if (!exists(libcSo))        missing.push_back("libc.so");
    if (!exists(libmSo))        missing.push_back("libm.so");
    if (!exists(liblogSo))      missing.push_back("liblog.so");
    if (!exists(libandroidSo))  missing.push_back("libandroid.so");
    if (jIsCxx == JNI_TRUE) {
        const std::string libcxxSharedApi  = libDir + "/libc++_shared.so";
        const std::string libcxxSharedRoot = (sysroot+"/usr/lib/"+tripleBase) + "/libc++_shared.so";
        if (!exists(libcxxSharedApi) && !exists(libcxxSharedRoot)) { missing.push_back("libc++_shared.so"); }
    }
    if (!missing.empty()) {
        std::ostringstream oss; oss << "[TinaIDE] 链接所需的 NDK stub/crt 缺失于: " << libDir << "\n缺失: ";
        for (size_t i=0;i<missing.size();++i){ if(i) oss<<", "; oss<<missing[i]; }
        return env->NewStringUTF(oss.str().c_str());
    }

    std::vector<std::string> keep; keep.reserve(32);
    keep.push_back("ld.lld");
    keep.push_back("-pie"); keep.push_back("-z"); keep.push_back("now"); keep.push_back("-z"); keep.push_back("relro");
    keep.push_back("-L"); keep.push_back(libDir);
    const std::string libDirRoot = sysroot+"/usr/lib/"+tripleBase;
    keep.push_back("-L"); keep.push_back(libDirRoot);
    // No rpath to sysroot runtime; ensure single C++ runtime in process
    // Explicit dynamic linker for Android
    const char* dynLinker = (tripleBase.find("64") != std::string::npos) ? "/system/bin/linker64" : "/system/bin/linker";
    keep.push_back("-dynamic-linker");
    keep.push_back(dynLinker);
    keep.push_back(crtBegin);

    // Append all object files
    if (jObjs != nullptr) {
        jsize n = env->GetArrayLength(jObjs);
        for (jsize i=0;i<n;++i) {
            jstring s = (jstring)env->GetObjectArrayElement(jObjs, i);
            const char* c = s? env->GetStringUTFChars(s,nullptr):nullptr;
            if (c) { keep.emplace_back(c); env->ReleaseStringUTFChars(s,c); }
            if (s) env->DeleteLocalRef(s);
        }
    }

    keep.push_back("-o"); keep.push_back(outExe);
    if (isCxx) { keep.push_back("-lc++"); }
    keep.push_back("-lc"); keep.push_back("-lm"); keep.push_back("-llog"); keep.push_back("-landroid");

    // Extra lib search dirs
    if (jLibDirs != nullptr) {
        jsize n = env->GetArrayLength(jLibDirs);
        for (jsize i=0;i<n;++i){
            jstring s=(jstring)env->GetObjectArrayElement(jLibDirs,i);
            const char* c = s? env->GetStringUTFChars(s,nullptr):nullptr;
            if (c) { keep.push_back("-L"); keep.emplace_back(c); env->ReleaseStringUTFChars(s,c); }
            if (s) env->DeleteLocalRef(s);
        }
    }
    // Extra libs, e.g. -lfoo
    if (jLibs != nullptr) {
        jsize n = env->GetArrayLength(jLibs);
        for (jsize i=0;i<n;++i){
            jstring s=(jstring)env->GetObjectArrayElement(jLibs,i);
            const char* c = s? env->GetStringUTFChars(s,nullptr):nullptr;
            if (c) { keep.emplace_back(std::string("-l")+c); env->ReleaseStringUTFChars(s,c); }
            if (s) env->DeleteLocalRef(s);
        }
    }

    keep.push_back(crtEnd);

    std::vector<const char*> args; args.reserve(keep.size());
    for (const auto& s : keep) args.push_back(s.c_str());

    #if LLD_LINK_ENABLED
    std::string diag; llvm::raw_string_ostream os(diag);
    bool ok = lld::elf::link(args, os, os, /*disableOutput=*/false, /*exitEarly=*/false);
    os.flush(); if (!ok) return env->NewStringUTF(diag.c_str());
    chmod(outExe.c_str(), 0755);
    return env->NewStringUTF("");
    #else
    return env->NewStringUTF("UNAVAILABLE: In-process LLD disabled at build-time. Build liblldELF.a/liblldCommon.a (static) and resync build artifacts.");
    #endif
#endif
}

// Link a single object into a shared library (.so) for in-process loading
extern "C" JNIEXPORT jstring JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NativeCompiler_linkSo(
        JNIEnv* env, jclass /*clazz*/, jstring jSysroot, jstring jObj, jstring jOutSo,
        jstring jTarget, jboolean jIsCxx) {
#if !LLVM_HEADERS_AVAILABLE
    const char* msg = "UNAVAILABLE: LLD not available";
    return env->NewStringUTF(msg);
#elif !defined(LLD_LINK_ENABLED)
    const char* msg = "UNAVAILABLE: LLD link disabled at build time";
    return env->NewStringUTF(msg);
#else
    auto toStr = [&](jstring s){ const char* c = s? env->GetStringUTFChars(s,nullptr):nullptr; std::string o=c?std::string(c):std::string(); if(c) env->ReleaseStringUTFChars(s,c); return o; };
    const std::string sysroot = toStr(jSysroot);
    const std::string objPath = toStr(jObj);
    const std::string outSo   = toStr(jOutSo);
    const std::string target  = toStr(jTarget);
    const bool isCxx = jIsCxx == JNI_TRUE;

    auto deriveTripleBase = [&](const std::string& t){ std::string r=t; while(!r.empty() && isdigit((unsigned char)r.back())) r.pop_back(); return r; };
    const std::string tripleBase = deriveTripleBase(target);
    auto deriveApi = [&](const std::string& t){ std::string digits; for (auto it=t.rbegin(); it!=t.rend(); ++it){ if(!isdigit((unsigned char)*it)) break; digits.push_back(*it);} std::reverse(digits.begin(), digits.end()); return digits.empty()? std::string("24"):digits; };
    const std::string api = deriveApi(target);

    const std::string libDir = sysroot+"/usr/lib/"+tripleBase+"/"+api;
    struct stat st; if (stat(libDir.c_str(), &st) != 0 || !S_ISDIR(st.st_mode)) {
        std::string err = "[TinaIDE] Sysroot library directory missing: "+libDir;
        return env->NewStringUTF(err.c_str());
    }
    auto exists = [](const std::string& p){ struct stat s; return stat(p.c_str(), &s) == 0 && S_ISREG(s.st_mode); };
    const std::string crtBegin = libDir + "/crtbegin_so.o";
    const std::string crtEnd   = libDir + "/crtend_so.o";
    if (!exists(crtBegin) || !exists(crtEnd)) {
        std::string err = "[TinaIDE] Missing shared CRT objects: crtbegin_so.o/crtend_so.o in "+libDir;
        return env->NewStringUTF(err.c_str());
    }

    std::vector<std::string> keep; keep.reserve(32);
    keep.push_back("ld.lld");
    keep.push_back("-shared");
    // Library search paths
    const std::string libDirRoot = sysroot+"/usr/lib/"+tripleBase;
    keep.push_back("-L"); keep.push_back(libDir);
    keep.push_back("-L"); keep.push_back(libDirRoot);
    // Avoid rpath; use global libc++_shared preloaded by app
    // Inputs/outputs
    keep.push_back(crtBegin);
    keep.push_back(objPath);
    keep.push_back("-o"); keep.push_back(outSo);
    // Basic libs
    if (isCxx) keep.push_back("-lc++");
    keep.push_back("-lc"); keep.push_back("-lm"); keep.push_back("-llog"); keep.push_back("-landroid");

    std::vector<const char*> args; args.reserve(keep.size());
    for (const auto& s : keep) args.push_back(s.c_str());

    std::string diag; llvm::raw_string_ostream os(diag);
    bool ok = lld::elf::link(args, os, os, /*disableOutput=*/false, /*exitEarly=*/false);
    os.flush(); if (!ok) return env->NewStringUTF(diag.c_str());
    // For .so not strictly necessary, but keep consistent perms
    chmod(outSo.c_str(), 0755);
    return env->NewStringUTF("");
#endif
}

// Link multiple objects into a shared library (.so)
extern "C" JNIEXPORT jstring JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NativeCompiler_linkSoMany(
        JNIEnv* env, jclass /*clazz*/, jstring jSysroot, jobjectArray jObjs, jstring jOutSo,
        jstring jTarget, jboolean jIsCxx, jobjectArray jLibDirs, jobjectArray jLibs) {
#if !LLVM_HEADERS_AVAILABLE
    const char* msg = "UNAVAILABLE: LLD not available";
    return env->NewStringUTF(msg);
#elif !defined(LLD_LINK_ENABLED)
    const char* msg = "UNAVAILABLE: LLD link disabled at build time";
    return env->NewStringUTF(msg);
#else
    auto toStr = [&](jstring s){ const char* c = s? env->GetStringUTFChars(s,nullptr):nullptr; std::string o=c?std::string(c):std::string(); if(c) env->ReleaseStringUTFChars(s,c); return o; };
    const std::string sysroot = toStr(jSysroot);
    const std::string outSo   = toStr(jOutSo);
    const std::string target  = toStr(jTarget);
    const bool isCxx = jIsCxx == JNI_TRUE;

    auto deriveTripleBase = [&](const std::string& t){ std::string r=t; while(!r.empty() && isdigit((unsigned char)r.back())) r.pop_back(); return r; };
    const std::string tripleBase = deriveTripleBase(target);
    auto deriveApi = [&](const std::string& t){ std::string digits; for (auto it=t.rbegin(); it!=t.rend(); ++it){ if(!isdigit((unsigned char)*it)) break; digits.push_back(*it);} std::reverse(digits.begin(), digits.end()); return digits.empty()? std::string("24"):digits; };
    const std::string api = deriveApi(target);
    const std::string libDir = sysroot+"/usr/lib/"+tripleBase+"/"+api;

    struct stat st; if (stat(libDir.c_str(), &st) != 0 || !S_ISDIR(st.st_mode)) {
        std::string err = "[TinaIDE] Sysroot library directory missing: "+libDir;
        return env->NewStringUTF(err.c_str());
    }
    auto exists = [](const std::string& p){ struct stat s; return stat(p.c_str(), &s) == 0 && S_ISREG(s.st_mode); };
    const std::string crtBegin = libDir + "/crtbegin_so.o";
    const std::string crtEnd   = libDir + "/crtend_so.o";
    if (!exists(crtBegin) || !exists(crtEnd)) {
        std::string err = "[TinaIDE] Missing shared CRT objects: crtbegin_so.o/crtend_so.o in "+libDir;
        return env->NewStringUTF(err.c_str());
    }

    std::vector<std::string> keep; keep.reserve(48);
    keep.push_back("ld.lld");
    keep.push_back("-shared");
    // Search paths
    keep.push_back("-L"); keep.push_back(libDir);
    const std::string libDirRoot = sysroot+"/usr/lib/"+tripleBase;
    keep.push_back("-L"); keep.push_back(libDirRoot);
    // No rpath here either to prevent duplicated libc++_shared in separate namespaces

    keep.push_back(crtBegin);

    // Append objects
    if (jObjs != nullptr) {
        jsize n = env->GetArrayLength(jObjs);
        for (jsize i=0;i<n;++i) {
            jstring s = (jstring)env->GetObjectArrayElement(jObjs, i);
            const char* c = s? env->GetStringUTFChars(s,nullptr):nullptr;
            if (c) { keep.emplace_back(c); env->ReleaseStringUTFChars(s,c); }
            if (s) env->DeleteLocalRef(s);
        }
    }

    // Output and basic libs
    keep.push_back("-o"); keep.push_back(outSo);
    if (isCxx) keep.push_back("-lc++");
    keep.push_back("-lc"); keep.push_back("-lm"); keep.push_back("-llog"); keep.push_back("-landroid");

    // Extra lib search dirs
    if (jLibDirs != nullptr) {
        jsize n = env->GetArrayLength(jLibDirs);
        for (jsize i=0;i<n;++i){
            jstring s=(jstring)env->GetObjectArrayElement(jLibDirs,i);
            const char* c = s? env->GetStringUTFChars(s,nullptr):nullptr;
            if (c) { keep.push_back("-L"); keep.emplace_back(c); env->ReleaseStringUTFChars(s,c); }
            if (s) env->DeleteLocalRef(s);
        }
    }
    // Extra libs
    if (jLibs != nullptr) {
        jsize n = env->GetArrayLength(jLibs);
        for (jsize i=0;i<n;++i){
            jstring s=(jstring)env->GetObjectArrayElement(jLibs,i);
            const char* c = s? env->GetStringUTFChars(s,nullptr):nullptr;
            if (c) { keep.emplace_back(std::string("-l")+c); env->ReleaseStringUTFChars(s,c); }
            if (s) env->DeleteLocalRef(s);
        }
    }

    keep.push_back(crtEnd);

    std::vector<const char*> args; args.reserve(keep.size());
    for (const auto& s : keep) args.push_back(s.c_str());

    std::string diag; llvm::raw_string_ostream os(diag);
    bool ok = lld::elf::link(args, os, os, /*disableOutput=*/false, /*exitEarly=*/false);
    os.flush(); if (!ok) return env->NewStringUTF(diag.c_str());
    chmod(outSo.c_str(), 0755);
    return env->NewStringUTF("");
#endif
}

// Load a shared library and call a no-arg entry symbol (e.g., run_main)
static void tina_install_handlers_once() {
    static bool done = false; if (done) return; done = true;
    auto term = []() noexcept {
        const std::type_info* t = abi::__cxa_current_exception_type();
        const char* name = t ? t->name() : nullptr;
        int status = 0;
        char* dem = name ? abi::__cxa_demangle(name, nullptr, nullptr, &status) : nullptr;
        const char* typeName = dem ? dem : (name ? name : "<unknown>");
        // Logcat for developer visibility
        LOGE("std::terminate: uncaught exception: %s", typeName);
        // Also write to stderr so runSharedIsolated can capture it for UI
        fprintf(stderr, "std::terminate: uncaught exception: %s\n", typeName);
        if (dem) free(dem);
        _Exit(134);
    };
    std::set_terminate(term);

    auto siglog = [](int sig){ LOGE("caught signal %d", sig); _Exit(128+sig); };
    struct sigaction sa; memset(&sa, 0, sizeof(sa));
    sa.sa_handler = siglog; sigemptyset(&sa.sa_mask);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGBUS,  &sa, nullptr);
    sigaction(SIGILL,  &sa, nullptr);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NativeCompiler_runShared(
        JNIEnv* env, jclass /*clazz*/, jstring jSoPath, jstring jSym) {
    auto toStr = [&](jstring s){ const char* c = s? env->GetStringUTFChars(s,nullptr):nullptr; std::string o=c?std::string(c):std::string(); if(c) env->ReleaseStringUTFChars(s,c); return o; };
    const std::string soPath = toStr(jSoPath);
    const std::string sym    = toStr(jSym);

    // Install terminate/signal handlers before touching user code
    tina_install_handlers_once();

    void* handle = dlopen(soPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
    if (!handle) {
        const char* e = dlerror();
        std::string err = std::string("dlopen failed: ") + (e?e:"unknown");
        LOGW("%s", err.c_str());
        return -127; // signal error
    }
    using EntryNoArg = int (*)();
    using EntryWithArgs = int (*)(int, char**);

    if (sym.empty()) {
        LOGW("runShared: empty symbol name");
        dlclose(handle);
        return -125;
    }

    void* fp = dlsym(handle, sym.c_str());
    if (!fp) {
        // Try simple Itanium C++ mangling for global function names: _Z{len}{name}v and _Z{len}{name}iPPc
        if (!sym.empty()) {
            auto isSimpleIdent = [](const std::string& s){
                for (char c : s) {
                    if (!(std::isalnum((unsigned char)c) || c=='_' )) return false;
                }
                return true;
            };
            if (isSimpleIdent(sym)) {
                std::string len = std::to_string(sym.size());
                std::string m1 = std::string("_Z") + len + sym + "v";     // int f()
                dlerror(); fp = dlsym(handle, m1.c_str());
                if (!fp) {
                    std::string m2 = std::string("_Z") + len + sym + "iPPc"; // int f(int,char**)
                    dlerror(); fp = dlsym(handle, m2.c_str());
                }
            }
        }
        if (!fp) {
            const char* e = dlerror();
            std::string err = std::string("dlsym failed: ") + (e?e:"unknown");
            LOGW("%s", err.c_str());
            dlclose(handle);
            return -126;
        }
    }

    int rc = -125;
    try {
        rc = reinterpret_cast<EntryNoArg>(fp)();
    } catch (const std::bad_cast& e) {
        LOGW("unhandled std::bad_cast: %s", e.what());
        rc = -101;
    } catch (const std::exception& e) {
        LOGW("unhandled std::exception: %s", e.what());
        rc = -102;
    } catch (...) {
        LOGW("unhandled non-std exception");
        rc = -103;
    }
    dlclose(handle);
    return rc;
}

// Isolated run: fork child to dlopen and run symbol; parent captures stdout/stderr and returns combined log with RC prefix
extern "C" JNIEXPORT jstring JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NativeCompiler_runSharedIsolated(
        JNIEnv* env, jclass /*clazz*/, jstring jSoPath, jstring jSym, jint jTimeoutMs) {
    auto toStr = [&](jstring s){ const char* c = s? env->GetStringUTFChars(s,nullptr):nullptr; std::string o=c?std::string(c):std::string(); if(c) env->ReleaseStringUTFChars(s,c); return o; };
    const std::string soPath = toStr(jSoPath);
    const std::string sym    = toStr(jSym);
    int timeoutMs = jTimeoutMs <= 0 ? 15000 : jTimeoutMs;

    int pipefd[2]; if (pipe(pipefd) != 0) {
        std::string err = std::string("pipe failed: ")+strerror(errno);
        return env->NewStringUTF(err.c_str());
    }
    pid_t pid = fork();
    if (pid < 0) {
        std::string err = std::string("fork failed: ")+strerror(errno);
        close(pipefd[0]); close(pipefd[1]);
        return env->NewStringUTF(err.c_str());
    }
    if (pid == 0) {
        // Child
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[0]); close(pipefd[1]);
        // Install handlers in child
        tina_install_handlers_once();
        // dlopen + dlsym + call
        void* handle = dlopen(soPath.c_str(), RTLD_NOW | RTLD_GLOBAL);
        if (!handle) {
            const char* e = dlerror();
            fprintf(stderr, "dlopen failed: %s\n", e?e:"unknown");
            _exit(127);
        }
        fprintf(stderr, "[tina] dlopen ok\n");
        if (sym.empty()) {
            fprintf(stderr, "runSharedIsolated: empty symbol name\n");
            dlclose(handle); _exit(125);
        }
        void* fp = dlsym(handle, sym.c_str());
        
        // If not found, try a generic C++ Itanium mangling for simple identifiers
        if (!fp && !sym.empty()) {
            auto isSimpleIdent = [](const std::string& s){
                for (char c : s) {
                    if (!(std::isalnum((unsigned char)c) || c=='_' )) return false;
                }
                return true;
            };
            if (isSimpleIdent(sym)) {
                std::string len = std::to_string(sym.size());
                std::string m1 = std::string("_Z") + len + sym + "v";     // int f()
                dlerror(); fp = dlsym(handle, m1.c_str());
                if (fp) {
                    fprintf(stderr, "[tina] found as C++: %s\n", m1.c_str());
                } else {
                    std::string m2 = std::string("_Z") + len + sym + "iPPc"; // int f(int,char**)
                    dlerror(); fp = dlsym(handle, m2.c_str());
                    if (fp) fprintf(stderr, "[tina] found as C++: %s\n", m2.c_str());
                }
            }
            // No legacy main() fallbacks: enforce explicit, unique entry symbols (e.g., tina_ide_use_main)
        }
        
        if (!fp) {
            const char* e = dlerror();
            fprintf(stderr, "dlsym failed: %s\n", e?e:"unknown");
            dlclose(handle); _exit(126);
        }
        fprintf(stderr, "[tina] dlsym ok: %s\n", sym.c_str());
        using EntryNoArg = int (*)();
        int rc = -1;
        try {
            fprintf(stderr, "[tina] calling entry\n");
            rc = reinterpret_cast<EntryNoArg>(fp)();
            fprintf(stderr, "[tina] entry returned rc=%d\n", rc);
        } catch (const std::bad_cast& e) {
            // Provide a clearer hint for common RTTI/any_cast/dynamic_cast issues
            fprintf(stderr, "unhandled std::bad_cast: %s\n", e.what());
            rc = 101;
        } catch (const std::exception& e) {
            fprintf(stderr, "unhandled std::exception: %s\n", e.what());
            rc = 102;
        } catch (...) {
            fprintf(stderr, "unhandled non-std exception\n");
            rc = 103;
        }
        dlclose(handle);
        _exit(rc);
    }
    // Parent
    close(pipefd[1]);
    std::string out;
    out.reserve(4096);

    int rc = -1;
    const int fd = pipefd[0];
    int elapsed = 0;
    char buf[1024];
    while (true) {
        struct pollfd pfd; pfd.fd = fd; pfd.events = POLLIN; pfd.revents = 0;
        int to = 100; // 100ms step
        int pr = poll(&pfd, 1, to);
        if (pr > 0 && (pfd.revents & POLLIN)) {
            ssize_t n = read(fd, buf, sizeof(buf));
            if (n > 0) {
                out.append(buf, buf + n);
                continue;
            }
            if (n == 0) break; // EOF
        }
        elapsed += to;
        int status = 0; pid_t w = waitpid(pid, &status, WNOHANG);
        if (w == pid) {
            if (WIFEXITED(status)) rc = WEXITSTATUS(status);
            else if (WIFSIGNALED(status)) rc = 128 + WTERMSIG(status);
            break;
        }
        if (elapsed >= timeoutMs) {
            kill(pid, SIGKILL);
            int status2 = 0; waitpid(pid, &status2, 0);
            rc = 124; // timeout
            break;
        }
    }
    close(fd);
    std::ostringstream oss; oss << "RC=" << rc << "\n";
    if (!out.empty()) oss << out;
    return env->NewStringUTF(oss.str().c_str());
}

// ============================================================================
// Ninja Runner JNI Interface
// ============================================================================

// 声明 libninja_runner.so 中的函数
extern "C" int ninja_run(int argc, char** argv);

/**
 * 通过 JNI 调用 Ninja
 * 
 * @param workDir 工作目录
 * @param args Ninja 参数数组（包括 "ninja" 作为 argv[0]）
 * @return 退出码
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_wuxianggujun_tinaide_core_nativebridge_NinjaRunner_runNinja(
        JNIEnv* env, jobject /*thiz*/, jstring jWorkDir, jobjectArray jArgs) {
    
    // 获取工作目录
    const char* workDir = env->GetStringUTFChars(jWorkDir, nullptr);
    if (!workDir) {
        return -1;
    }
    
    // 切换到工作目录
    int chdirResult = chdir(workDir);
    env->ReleaseStringUTFChars(jWorkDir, workDir);
    
    if (chdirResult != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "NinjaRunner", 
            "Failed to chdir to %s: %s", workDir, strerror(errno));
        return -1;
    }
    
    // 转换参数数组
    jsize argc = env->GetArrayLength(jArgs);
    std::vector<char*> argv(argc + 1);
    std::vector<std::string> argStrings(argc);
    
    for (jsize i = 0; i < argc; i++) {
        jstring jArg = (jstring)env->GetObjectArrayElement(jArgs, i);
        if (!jArg) {
            argv[i] = nullptr;
            continue;
        }
        
        const char* argChars = env->GetStringUTFChars(jArg, nullptr);
        if (argChars) {
            argStrings[i] = argChars;
            argv[i] = const_cast<char*>(argStrings[i].c_str());
            env->ReleaseStringUTFChars(jArg, argChars);
        } else {
            argv[i] = nullptr;
        }
    }
    argv[argc] = nullptr;
    
    __android_log_print(ANDROID_LOG_INFO, "NinjaRunner", 
        "Calling ninja_run with %d arguments", (int)argc);
    
    // 调用 ninja_run
    int result = ninja_run((int)argc, argv.data());
    
    __android_log_print(ANDROID_LOG_INFO, "NinjaRunner", 
        "ninja_run returned %d", result);
    
    return result;
}
