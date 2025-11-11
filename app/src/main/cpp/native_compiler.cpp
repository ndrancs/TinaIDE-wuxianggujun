// JNI & logging
#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <android/log.h>
#include <dlfcn.h>
#include <unistd.h>
#include <sys/stat.h>

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
    const std::string api = "24";
    const std::string libDir = sysroot+"/usr/lib/"+tripleBase+"/"+api;

    std::vector<const char*> args;
    std::vector<std::string> keep;
    auto add=[&](const std::string& s){ keep.push_back(s); args.push_back(keep.back().c_str()); };
    auto add2=[&](const char* a,const std::string& b){ args.push_back(a); keep.push_back(b); args.push_back(keep.back().c_str()); };

    add("ld.lld");
    add("-pie"); add("-z"); add("now"); add("-z"); add("relro");
    add2("-L", libDir);
    // crt objects if available
    add(sysroot+"/usr/lib/"+tripleBase+"/"+api+"/crtbegin_dynamic.o");
    add(objPath);
    add2("-o", outExe);
    // standard libs
    if (isCxx) add("-lc++");
    add("-lc"); add("-lm"); add("-llog"); add("-landroid");
    add(sysroot+"/usr/lib/"+tripleBase+"/"+api+"/crtend_android.o");

    std::string diag;
    llvm::raw_string_ostream os(diag);
    // 调用打包库签名 (args, stdout, stderr, disableOutput, exitEarly)
    bool ok = lld::elf::link(args, os, os, /*disableOutput=*/false, /*exitEarly=*/false);
    os.flush();
    if (!ok) return env->NewStringUTF(diag.c_str());
    // chmod +x
    chmod(outExe.c_str(), 0755);
    return env->NewStringUTF("");
#endif
}
