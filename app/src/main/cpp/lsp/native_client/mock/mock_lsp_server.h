// MockLspServer - 一个简易的本地 LSP 服务端实现
// 仅用于 Stage 2 之前验证 ControlChannel/SharedMemoryTransport 流程
#ifndef TINAIDE_NATIVE_CLIENT_MOCK_LSP_SERVER_H
#define TINAIDE_NATIVE_CLIENT_MOCK_LSP_SERVER_H

#include "../transport/control_channel.h"
#include "lsp_protocol_generated.h"
#include <atomic>
#include <condition_variable>
#include <memory>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace tinaide {
namespace lsp {

class MockLspServer {
public:
    explicit MockLspServer(ChannelConfig config);
    ~MockLspServer();

    bool start();
    void stop();

    bool isRunning() const { return running_.load(); }
    const std::string& socketPath() const { return config_.socket_path; }

private:
    struct FileEntry {
        std::string uri;
        std::string content;
        uint32_t version = 0;
    };

    void run();
    bool handleMessage(ControlChannel& channel, const Message& msg);
    bool buildResponse(const protocol::Request* request, std::vector<uint8_t>& out_buffer);
    std::vector<uint8_t> buildHoverResponse(const protocol::Request* request);
    std::vector<uint8_t> buildCompletionResponse(const protocol::Request* request);
    std::vector<uint8_t> buildDefinitionResponse(const protocol::Request* request);
    std::vector<uint8_t> buildReferencesResponse(const protocol::Request* request);

    void handleDidOpen(const protocol::DidOpenTextDocumentNotification* notif);
    void handleDidChange(const protocol::DidChangeTextDocumentNotification* notif);
    void handleDidClose(const protocol::DidCloseTextDocumentNotification* notif);

    ChannelConfig config_;
    std::thread thread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> ready_{false};
    std::mutex ready_mutex_;
    std::condition_variable ready_cv_;
    std::unordered_map<uint32_t, FileEntry> files_;
};

} // namespace lsp
} // namespace tinaide

#endif // TINAIDE_NATIVE_CLIENT_MOCK_LSP_SERVER_H
