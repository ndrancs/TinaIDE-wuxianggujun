#include "clangd_process.h"
#include <android/log.h>
#include <sys/stat.h>
#include <vector>

#define LOG_TAG "ClangdProcess"
#include "utils/logging.h"

namespace tinaide {
namespace lsp {

ClangdProcess::ClangdProcess() = default;

ClangdProcess::~ClangdProcess() {
    stop();
}

bool ClangdProcess::start(const std::string& clangd_path,
                          const std::string& work_dir,
                          const ChannelConfig& config) {
    stop();
    socket_path_ = config.socket_path;

    if (startRealProcess(clangd_path, work_dir, config)) {
        running_ = true;
        return true;
    }

    LOGE("ClangdProcess: failed to start clangd server");
    return false;
}

void ClangdProcess::stop() {
    if (bridge_) {
        bridge_->stop();
        bridge_.reset();
    }
    if (clangd_server_) {
        clangd_server_->stop();
        clangd_server_.reset();
    }
    running_ = false;
    socket_path_.clear();
}

bool ClangdProcess::startRealProcess(const std::string& clangd_path,
                                     const std::string& work_dir,
                                     const ChannelConfig& config) {
    clangd_server_ = std::make_unique<ClangdServer>();
    std::vector<std::string> extra_args;
    if (!work_dir.empty()) {
        // compile_commands.json 生成在 build/debug 或 build/release 目录
        // 优先检查 debug 目录，然后是 release，最后是项目根目录
        std::string compile_commands_dir = work_dir;
        std::string debug_path = work_dir + "/build/debug/compile_commands.json";
        std::string release_path = work_dir + "/build/release/compile_commands.json";
        std::string root_path = work_dir + "/compile_commands.json";
        
        struct stat st;
        if (stat(debug_path.c_str(), &st) == 0) {
            compile_commands_dir = work_dir + "/build/debug";
            LOGI("Found compile_commands.json in build/debug");
        } else if (stat(release_path.c_str(), &st) == 0) {
            compile_commands_dir = work_dir + "/build/release";
            LOGI("Found compile_commands.json in build/release");
        } else if (stat(root_path.c_str(), &st) == 0) {
            LOGI("Found compile_commands.json in project root");
        } else {
            LOGW("compile_commands.json not found, clangd may not provide accurate completions");
        }
        
        extra_args.push_back("--compile-commands-dir=" + compile_commands_dir);
    }
    std::string error = clangd_server_->start(clangd_path, extra_args);
    if (!error.empty()) {
        LOGE("Failed to start clangd: %s", error.c_str());
        clangd_server_->stop();
        clangd_server_.reset();
        return false;
    }

    bridge_ = std::make_unique<ClangdControlBridge>(config, clangd_server_.get(), work_dir);
    if (!bridge_->start()) {
        LOGE("Failed to start control bridge");
        bridge_.reset();
        clangd_server_->stop();
        clangd_server_.reset();
        return false;
    }

    LOGI("Real clangd server ready on %s", config.socket_path.c_str());
    return true;
}

} // namespace lsp
} // namespace tinaide
