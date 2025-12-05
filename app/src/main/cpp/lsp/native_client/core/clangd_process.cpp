#include "clangd_process.h"
#include <android/log.h>
#include <cstdlib>
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

    use_mock_ = true;
    if (const char* env = std::getenv("TINAIDE_NATIVE_LSP_USE_MOCK")) {
        use_mock_ = std::string(env) != "0";
    }

    if (use_mock_) {
        if (!startMockServer(config)) {
            LOGW("Mock server failed to start on %s", socket_path_.c_str());
            return false;
        }
        LOGI("Mock LSP server ready on %s", socket_path_.c_str());
        running_ = true;
        return true;
    }

    if (startRealProcess(clangd_path, work_dir, config)) {
        running_ = true;
        return true;
    }

    LOGW("ClangdProcess: real server not available, fallback failed");
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
    if (mock_server_) {
        mock_server_->stop();
        mock_server_.reset();
    }
    running_ = false;
    socket_path_.clear();
}

bool ClangdProcess::startMockServer(const ChannelConfig& config) {
    mock_server_ = std::make_unique<MockLspServer>(config);
    if (!mock_server_->start()) {
        mock_server_.reset();
        return false;
    }
    return true;
}

bool ClangdProcess::startRealProcess(const std::string& clangd_path,
                                     const std::string& work_dir,
                                     const ChannelConfig& config) {
    clangd_server_ = std::make_unique<ClangdServer>();
    std::vector<std::string> extra_args;
    if (!work_dir.empty()) {
        extra_args.push_back("--compile-commands-dir=" + work_dir);
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
