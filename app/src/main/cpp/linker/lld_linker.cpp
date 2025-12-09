// LLD 链接器实现（进程隔离版本）
//
// 由于 LLVM 17 的 LLD 存在全局状态问题，多次调用 lld::elf::link 会导致
// "duplicate symbol" 错误。本实现使用 fork() 在子进程中执行链接，确保
// 每次链接都有干净的全局状态。
//
// 架构：
// 1. 父进程：构建链接参数，fork 子进程，等待结果
// 2. 子进程：调用 lld::elf::link，输出诊断信息到管道，退出
// 3. 父进程：收集子进程输出，解析结果

#include "lld_linker.h"
#include "../utils/file_utils.h"
#include "../utils/logging.h"

#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <signal.h>
#include <poll.h>
#include <errno.h>
#include <cstring>
#include <sstream>
#include <sys/stat.h>

#if LLVM_HEADERS_AVAILABLE
#include "llvm/ADT/ArrayRef.h"
#include "llvm/Support/raw_ostream.h"
#endif

// LLD 前向声明
#if LLVM_HEADERS_AVAILABLE && defined(LLD_LINK_ENABLED)
namespace llvm {
    template <typename T> class ArrayRef;
    class raw_ostream;
}
namespace lld {
namespace elf {
    bool link(llvm::ArrayRef<const char*>, llvm::raw_ostream&,
              llvm::raw_ostream&, bool, bool);
}
}
#endif

namespace tinaide {
namespace linker {

#if !LLVM_HEADERS_AVAILABLE || !defined(LLD_LINK_ENABLED)

// LLD 不可用时的占位实现
LinkResult linkExecutable(const std::string& objPath, const std::string& exePath,
                          const LinkOptions& options) {
    LinkResult result;
    result.success = false;
    result.errorMessage = "UNAVAILABLE: LLD not available";
    result.exitCode = -1;
    return result;
}

LinkResult linkExecutableMany(const std::vector<std::string>& objPaths,
                              const std::string& exePath,
                              const LinkOptions& options) {
    LinkResult result;
    result.success = false;
    result.errorMessage = "UNAVAILABLE: LLD not available";
    result.exitCode = -1;
    return result;
}

LinkResult linkSharedLibrary(const std::string& objPath, const std::string& soPath,
                             const LinkOptions& options) {
    LinkResult result;
    result.success = false;
    result.errorMessage = "UNAVAILABLE: LLD not available";
    result.exitCode = -1;
    return result;
}

LinkResult linkSharedLibraryMany(const std::vector<std::string>& objPaths,
                                 const std::string& soPath,
                                 const LinkOptions& options) {
    LinkResult result;
    result.success = false;
    result.errorMessage = "UNAVAILABLE: LLD not available";
    result.exitCode = -1;
    return result;
}

#else

// ============================================================================
// 内部辅助函数
// ============================================================================

// 验证 sysroot 库目录和必需文件
static bool validateSysroot(const std::string& sysroot, const std::string& target,
                            bool isCxx, bool isShared, std::string& errorOut) {
    std::string tripleBase = utils::deriveTripleBase(target);
    std::string apiLevel = utils::deriveApiLevel(target);
    std::string libDir = sysroot + "/usr/lib/" + tripleBase + "/" + apiLevel;

    // 检查库目录
    if (!utils::dirExists(libDir)) {
        std::ostringstream oss;
        oss << "[TinaIDE] Sysroot library directory missing: " << libDir
            << "\n请先同步嵌入式 NDK 资源";
        errorOut = oss.str();
        return false;
    }

    // 检查必需文件
    std::vector<std::string> missing;
    std::string crtBegin = libDir + (isShared ? "/crtbegin_so.o" : "/crtbegin_dynamic.o");
    std::string crtEnd = libDir + (isShared ? "/crtend_so.o" : "/crtend_android.o");

    if (!utils::fileExists(crtBegin)) {
        missing.push_back(isShared ? "crtbegin_so.o" : "crtbegin_dynamic.o");
    }
    if (!utils::fileExists(crtEnd)) {
        missing.push_back(isShared ? "crtend_so.o" : "crtend_android.o");
    }

    if (!isShared) {
        if (!utils::fileExists(libDir + "/libc.so")) missing.push_back("libc.so");
        if (!utils::fileExists(libDir + "/libm.so")) missing.push_back("libm.so");
        if (!utils::fileExists(libDir + "/liblog.so")) missing.push_back("liblog.so");
        if (!utils::fileExists(libDir + "/libandroid.so")) missing.push_back("libandroid.so");
    }

    // 检查 C++ 运行时
    if (isCxx) {
        std::string libcxxSharedApi = libDir + "/libc++_shared.so";
        std::string libcxxSharedRoot = sysroot + "/usr/lib/" + tripleBase + "/libc++_shared.so";
        if (!utils::fileExists(libcxxSharedApi) && !utils::fileExists(libcxxSharedRoot)) {
            missing.push_back("libc++_shared.so");
        }
    }

    if (!missing.empty()) {
        std::ostringstream oss;
        oss << "[TinaIDE] 链接所需的 NDK stub/crt 缺失于: " << libDir << "\n缺失: ";
        for (size_t i = 0; i < missing.size(); ++i) {
            if (i > 0) oss << ", ";
            oss << missing[i];
        }
        errorOut = oss.str();
        return false;
    }

    return true;
}

// 构建链接参数（可执行文件）
static std::vector<std::string> buildExecutableArgs(
    const std::vector<std::string>& objPaths,
    const std::string& exePath,
    const LinkOptions& options) {

    std::vector<std::string> args;
    std::string tripleBase = utils::deriveTripleBase(options.target);
    std::string apiLevel = utils::deriveApiLevel(options.target);
    std::string libDir = options.sysroot + "/usr/lib/" + tripleBase + "/" + apiLevel;
    std::string libDirRoot = options.sysroot + "/usr/lib/" + tripleBase;

    // 基础参数
    args.push_back("ld.lld");
    args.push_back("-pie");
    args.push_back("-z");
    args.push_back("now");
    args.push_back("-z");
    args.push_back("relro");

    // 库搜索路径
    args.push_back("-L");
    args.push_back(libDir);
    args.push_back("-L");
    args.push_back(libDirRoot);

    // 动态链接器
    const char* dynLinker = (tripleBase.find("64") != std::string::npos)
        ? "/system/bin/linker64"
        : "/system/bin/linker";
    args.push_back("-dynamic-linker");
    args.push_back(dynLinker);

    // CRT 启动对象
    std::string crtBegin = libDir + "/crtbegin_dynamic.o";
    args.push_back(crtBegin);

    // 输入的目标文件
    for (const auto& objPath : objPaths) {
        args.push_back(objPath);
    }

    // 输出文件
    args.push_back("-o");
    args.push_back(exePath);

    // C++ 运行时
    if (options.isCxx) {
        args.push_back("-lc++");
    }

    // 系统库
    args.push_back("-lc");
    args.push_back("-lm");
    args.push_back("-llog");
    args.push_back("-landroid");

    // 额外的库搜索路径
    for (const auto& dir : options.libDirs) {
        if (!dir.empty()) {
            args.push_back("-L");
            args.push_back(dir);
        }
    }

    // 额外的链接库
    for (const auto& lib : options.libs) {
        if (!lib.empty()) {
            args.push_back("-l" + lib);
        }
    }

    // CRT 结束对象
    std::string crtEnd = libDir + "/crtend_android.o";
    args.push_back(crtEnd);

    return args;
}

// 构建链接参数（共享库）
static std::vector<std::string> buildSharedLibraryArgs(
    const std::vector<std::string>& objPaths,
    const std::string& soPath,
    const LinkOptions& options) {

    std::vector<std::string> args;
    std::string tripleBase = utils::deriveTripleBase(options.target);
    std::string apiLevel = utils::deriveApiLevel(options.target);
    std::string libDir = options.sysroot + "/usr/lib/" + tripleBase + "/" + apiLevel;
    std::string libDirRoot = options.sysroot + "/usr/lib/" + tripleBase;

    // 基础参数
    args.push_back("ld.lld");
    args.push_back("-shared");

    // 库搜索路径
    args.push_back("-L");
    args.push_back(libDir);
    args.push_back("-L");
    args.push_back(libDirRoot);

    // CRT 启动对象
    std::string crtBegin = libDir + "/crtbegin_so.o";
    args.push_back(crtBegin);

    // 输入的目标文件
    for (const auto& objPath : objPaths) {
        args.push_back(objPath);
    }

    // 输出文件
    args.push_back("-o");
    args.push_back(soPath);

    // C++ 运行时
    if (options.isCxx) {
        args.push_back("-lc++");
    }

    // 系统库
    args.push_back("-lc");
    args.push_back("-lm");
    args.push_back("-llog");
    args.push_back("-landroid");

    // 额外的库搜索路径
    for (const auto& dir : options.libDirs) {
        if (!dir.empty()) {
            args.push_back("-L");
            args.push_back(dir);
        }
    }

    // 额外的链接库
    for (const auto& lib : options.libs) {
        if (!lib.empty()) {
            args.push_back("-l" + lib);
        }
    }

    // CRT 结束对象
    std::string crtEnd = libDir + "/crtend_so.o";
    args.push_back(crtEnd);

    return args;
}

// 在子进程中执行链接（子进程入口）
// 这个函数在 fork() 后的子进程中调用，不会返回
static void executeLinkerInChild(const std::vector<std::string>& argStrings,
                                  const std::string& outputPath) {
    // 转换为 C 风格参数数组
    std::vector<const char*> args;
    args.reserve(argStrings.size());
    for (const auto& arg : argStrings) {
        args.push_back(arg.c_str());
    }

    // 捕获诊断输出到 stderr（已重定向到管道）
    std::string diagStr;
    llvm::raw_string_ostream diagStream(diagStr);

    // 调用 LLD
    bool success = lld::elf::link(args, diagStream, diagStream, false, false);
    diagStream.flush();

    // 输出诊断信息
    if (!diagStr.empty()) {
        fprintf(stderr, "%s", diagStr.c_str());
    }

    if (success) {
        // 设置可执行权限
        chmod(outputPath.c_str(), 0755);
        _exit(0);
    } else {
        _exit(1);
    }
}

// 在隔离进程中执行链接（核心函数）
static LinkResult linkIsolated(const std::vector<std::string>& argStrings,
                                const std::string& outputPath,
                                int timeoutMs) {
    LinkResult result;

    // 创建管道用于捕获子进程输出
    int pipefd[2];
    if (pipe(pipefd) != 0) {
        result.errorMessage = std::string("pipe failed: ") + strerror(errno);
        return result;
    }

    // Fork 子进程
    pid_t pid = fork();
    if (pid < 0) {
        result.errorMessage = std::string("fork failed: ") + strerror(errno);
        close(pipefd[0]);
        close(pipefd[1]);
        return result;
    }

    if (pid == 0) {
        // ====== 子进程 ======
        // 重定向标准输出和错误到管道
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[0]);
        close(pipefd[1]);

        // 执行链接（不会返回）
        executeLinkerInChild(argStrings, outputPath);
        _exit(127); // 不应该到达这里
    }

    // ====== 父进程 ======
    // 关闭写端
    close(pipefd[1]);

    // 读取子进程输出
    std::string output;
    output.reserve(4096);

    const int readFd = pipefd[0];
    int elapsed = 0;
    char buffer[1024];

    while (true) {
        // 使用 poll 检查是否有数据可读
        struct pollfd pfd;
        pfd.fd = readFd;
        pfd.events = POLLIN;
        pfd.revents = 0;

        int pollTimeout = 100; // 100ms 步长
        int pollResult = poll(&pfd, 1, pollTimeout);

        if (pollResult > 0 && (pfd.revents & POLLIN)) {
            ssize_t n = read(readFd, buffer, sizeof(buffer));
            if (n > 0) {
                output.append(buffer, buffer + n);
                continue;
            }
            if (n == 0) {
                break; // EOF
            }
        }

        elapsed += pollTimeout;

        // 检查子进程状态
        int status = 0;
        pid_t waitResult = waitpid(pid, &status, WNOHANG);
        if (waitResult == pid) {
            // 子进程已退出，继续读取剩余数据
            while (true) {
                ssize_t n = read(readFd, buffer, sizeof(buffer));
                if (n <= 0) break;
                output.append(buffer, buffer + n);
            }

            if (WIFEXITED(status)) {
                result.exitCode = WEXITSTATUS(status);
            } else if (WIFSIGNALED(status)) {
                result.exitCode = 128 + WTERMSIG(status);
            }
            break;
        }

        // 超时检查
        if (elapsed >= timeoutMs) {
            LOGW("linkIsolated: timeout (elapsed=%dms, limit=%dms), killing pid %d", elapsed, timeoutMs, pid);
            kill(pid, SIGKILL);
            int killStatus = 0;
            waitpid(pid, &killStatus, 0);
            result.exitCode = 124; // 超时返回码
            result.errorMessage = "link timeout";
            close(readFd);
            return result;
        }
    }

    close(readFd);

    // 解析结果
    result.success = (result.exitCode == 0);
    if (!result.success) {
        result.errorMessage = output.empty() ? "link failed" : output;
    }

    return result;
}

// ============================================================================
// 公共接口实现
// ============================================================================

LinkResult linkExecutable(const std::string& objPath, const std::string& exePath,
                          const LinkOptions& options) {
    LOGI("linkExecutable: %s -> %s (timeout=%dms)", objPath.c_str(), exePath.c_str(), options.timeoutMs);

    // 验证 sysroot
    std::string validationError;
    if (!validateSysroot(options.sysroot, options.target, options.isCxx, false, validationError)) {
        LinkResult result;
        result.errorMessage = validationError;
        return result;
    }

    // 构建参数
    std::vector<std::string> objPaths = {objPath};
    auto args = buildExecutableArgs(objPaths, exePath, options);

    // 在隔离进程中执行链接
    return linkIsolated(args, exePath, options.timeoutMs);
}

LinkResult linkExecutableMany(const std::vector<std::string>& objPaths,
                              const std::string& exePath,
                              const LinkOptions& options) {
    LOGI("linkExecutableMany: %zu objects -> %s (timeout=%dms)", objPaths.size(), exePath.c_str(), options.timeoutMs);

    // 验证 sysroot
    std::string validationError;
    if (!validateSysroot(options.sysroot, options.target, options.isCxx, false, validationError)) {
        LinkResult result;
        result.errorMessage = validationError;
        return result;
    }

    // 构建参数
    auto args = buildExecutableArgs(objPaths, exePath, options);

    // 在隔离进程中执行链接
    return linkIsolated(args, exePath, options.timeoutMs);
}

LinkResult linkSharedLibrary(const std::string& objPath, const std::string& soPath,
                             const LinkOptions& options) {
    LOGI("linkSharedLibrary: %s -> %s (timeout=%dms)", objPath.c_str(), soPath.c_str(), options.timeoutMs);

    // 验证 sysroot
    std::string validationError;
    if (!validateSysroot(options.sysroot, options.target, options.isCxx, true, validationError)) {
        LinkResult result;
        result.errorMessage = validationError;
        return result;
    }

    // 构建参数
    std::vector<std::string> objPaths = {objPath};
    auto args = buildSharedLibraryArgs(objPaths, soPath, options);

    // 在隔离进程中执行链接
    return linkIsolated(args, soPath, options.timeoutMs);
}

LinkResult linkSharedLibraryMany(const std::vector<std::string>& objPaths,
                                 const std::string& soPath,
                                 const LinkOptions& options) {
    LOGI("linkSharedLibraryMany: %zu objects -> %s (timeout=%dms)", objPaths.size(), soPath.c_str(), options.timeoutMs);

    // 验证 sysroot
    std::string validationError;
    if (!validateSysroot(options.sysroot, options.target, options.isCxx, true, validationError)) {
        LinkResult result;
        result.errorMessage = validationError;
        return result;
    }

    // 构建参数
    auto args = buildSharedLibraryArgs(objPaths, soPath, options);

    // 在隔离进程中执行链接
    return linkIsolated(args, soPath, options.timeoutMs);
}

#endif // LLVM_HEADERS_AVAILABLE && LLD_LINK_ENABLED

} // namespace linker
} // namespace tinaide
