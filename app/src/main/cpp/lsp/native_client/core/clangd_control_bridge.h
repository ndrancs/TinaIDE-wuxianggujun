#ifndef TINAIDE_CLANGD_CONTROL_BRIDGE_H
#define TINAIDE_CLANGD_CONTROL_BRIDGE_H

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>
#include "lsp/native_client/bridge/json_rpc_converter.h"
#include "lsp/native_client/transport/control_channel.h"
#include "lsp_protocol_generated.h"

namespace tinaide {
namespace lsp {

class ClangdServer;

class ClangdControlBridge {
public:
    ClangdControlBridge(const ChannelConfig& config, ClangdServer* server, std::string work_dir);
    ~ClangdControlBridge();

    bool start();
    void stop();

private:
    void requestLoop();
    void responseLoop();
    bool readClangdMessage(std::string& out_json);
    bool parseHeaders(size_t header_end, size_t& body_offset, size_t& content_length);
    void handleRequest(const protocol::Request* request);
    void handleCancelRequest(uint64_t request_id);
    bool dispatchJson(protocol::Method method,
                      uint64_t request_id,
                      const std::string& json);
    bool sendResponse(protocol::Method method,
                      uint64_t request_id,
                      const std::string& json_body);

    bool getContext(uint32_t file_id, RequestContext& ctx);
    void updateContext(uint32_t file_id, const RequestContext& ctx);
    void removeContext(uint32_t file_id);

    static bool isNotification(protocol::Method method);
    bool performInitializeHandshake();
    bool sendRawJson(const std::string& json);
    bool readBlockingClangdMessage(std::string& out_json, int timeout_ms);

    ChannelConfig config_;
    ClangdServer* server_;
    std::string work_dir_;
    std::string root_uri_;
    std::shared_ptr<ControlChannel> control_channel_;
    std::atomic<bool> running_{false};
    std::thread request_thread_;
    std::thread response_thread_;

    JsonRpcConverter converter_;

    std::unordered_map<uint64_t, protocol::Method> pending_requests_;
    std::mutex pending_mutex_;

    std::unordered_map<uint32_t, RequestContext> file_contexts_;
    std::mutex context_mutex_;

    std::string clangd_buffer_;
    std::mutex clangd_write_mutex_;
    std::atomic<uint64_t> notification_sequence_{1};
};

} // namespace lsp
} // namespace tinaide

#endif // TINAIDE_CLANGD_CONTROL_BRIDGE_H
