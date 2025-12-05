// ClangdProcess - 管理 clangd 服务端生命周期
#ifndef TINAIDE_CLANGD_PROCESS_H
#define TINAIDE_CLANGD_PROCESS_H

#include "../transport/control_channel.h"
#include "clangd_control_bridge.h"
#include "lsp/clangd_server.h"
#include <memory>
#include <string>

namespace tinaide {
namespace lsp {

class ClangdProcess {
public:
    ClangdProcess();
    ~ClangdProcess();

    bool start(const std::string& clangd_path,
               const std::string& work_dir,
               const ChannelConfig& config);
    void stop();

    bool isRunning() const { return running_; }
    const std::string& socketPath() const { return socket_path_; }

private:
    bool startRealProcess(const std::string& clangd_path,
                          const std::string& work_dir,
                          const ChannelConfig& config);

    std::unique_ptr<ClangdServer> clangd_server_;
    std::unique_ptr<ClangdControlBridge> bridge_;
    bool running_ = false;
    std::string socket_path_;
};

} // namespace lsp
} // namespace tinaide

#endif // TINAIDE_CLANGD_PROCESS_H
