#include "clangd_control_bridge.h"

#include <android/log.h>
#include <algorithm>
#include <cctype>
#include <cstring>
#include <flatbuffers/flatbuffers.h>
#include <flatbuffers/verifier.h>
#include <llvm/Support/JSON.h>
#include "lsp/clangd_server.h"

#define LOG_TAG "ClangdControlBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace tinaide {
namespace lsp {
namespace {

constexpr const char* kHeaderDelimiter = "\r\n\r\n";

std::string buildLspMessage(const std::string& json) {
    std::string header = "Content-Length: " + std::to_string(json.size()) + "\r\n\r\n";
    return header + json;
}

bool parseRequestId(const llvm::json::Value& value, uint64_t& out_id) {
    if (auto number = value.getAsInteger()) {
        out_id = static_cast<uint64_t>(*number);
        return true;
    }
    if (auto str = value.getAsString()) {
        try {
            out_id = static_cast<uint64_t>(std::stoull(str->str()));
            return true;
        } catch (...) {
            return false;
        }
    }
    return false;
}

} // namespace

ClangdControlBridge::ClangdControlBridge(const ChannelConfig& config, ClangdServer* server)
    : config_(config), server_(server) {}

ClangdControlBridge::~ClangdControlBridge() {
    stop();
}

bool ClangdControlBridge::start() {
    stop();
    if (!server_) {
        LOGE("Clangd server is null, cannot start bridge");
        return false;
    }

    control_channel_ = std::make_shared<ControlChannel>(config_);
    if (control_channel_->createServer() != ChannelError::SUCCESS) {
        LOGE("Failed to create control channel server: %s", control_channel_->getLastError().c_str());
        control_channel_.reset();
        return false;
    }
    LOGI("Waiting for NativeLspClient to connect on %s", config_.socket_path.c_str());
    ChannelError err = control_channel_->acceptClient();
    if (err != ChannelError::SUCCESS) {
        LOGE("Failed to accept control channel client: %s", control_channel_->getLastError().c_str());
        control_channel_->close();
        control_channel_.reset();
        return false;
    }

    running_.store(true);
    request_thread_ = std::thread(&ClangdControlBridge::requestLoop, this);
    response_thread_ = std::thread(&ClangdControlBridge::responseLoop, this);
    LOGI("Clangd control bridge started");
    return true;
}

void ClangdControlBridge::stop() {
    running_.store(false);
    if (control_channel_) {
        control_channel_->close();
    }
    if (request_thread_.joinable()) {
        request_thread_.join();
    }
    if (response_thread_.joinable()) {
        response_thread_.join();
    }
    control_channel_.reset();
    pending_requests_.clear();
    file_contexts_.clear();
    clangd_buffer_.clear();
}

void ClangdControlBridge::requestLoop() {
    while (running_.load()) {
        if (!control_channel_) {
            break;
        }
        Message msg;
        ChannelError err = control_channel_->receive(msg, 200);
        if (err == ChannelError::TIMEOUT) {
            continue;
        }
        if (err != ChannelError::SUCCESS) {
            LOGW("Control channel receive failed: %s", control_channel_->getLastError().c_str());
            running_.store(false);
            break;
        }
        if (msg.header.type != static_cast<uint16_t>(MessageType::DATA)) {
            LOGW("Unsupported message type %u", msg.header.type);
            continue;
        }
        if (msg.payload.empty()) {
            continue;
        }
        flatbuffers::Verifier verifier(msg.payload.data(), msg.payload.size());
        if (!protocol::VerifyRequestBuffer(verifier)) {
            LOGW("Invalid FlatBuffers request received");
            continue;
        }
        const protocol::Request* request = protocol::GetRequest(msg.payload.data());
        handleRequest(request);
    }
}

void ClangdControlBridge::responseLoop() {
    while (running_.load()) {
        std::string json;
        if (!readClangdMessage(json)) {
            continue;
        }
        auto parsed = llvm::json::parse(json);
        if (!parsed) {
            LOGW("Failed to parse clangd JSON message: %s", llvm::toString(parsed.takeError()).c_str());
            continue;
        }
        const llvm::json::Object* obj = parsed->getAsObject();
        if (!obj) {
            continue;
        }
        if (const llvm::json::Value* id = obj->get("id")) {
            uint64_t request_id = 0;
            if (!parseRequestId(*id, request_id)) {
                LOGW("Response missing valid id field");
                continue;
            }
            protocol::Method method = protocol::Method::UNKNOWN;
            {
                std::lock_guard<std::mutex> lock(pending_mutex_);
                auto it = pending_requests_.find(request_id);
                if (it != pending_requests_.end()) {
                    method = it->second;
                    pending_requests_.erase(it);
                }
            }
            if (method == protocol::Method::UNKNOWN) {
                LOGW("No pending request found for id=%llu", static_cast<unsigned long long>(request_id));
                continue;
            }
            if (!sendResponse(method, request_id, json)) {
                LOGW("Failed to send response for request %llu", static_cast<unsigned long long>(request_id));
            }
        } else if (auto method_name = obj->getString("method")) {
            auto method_str = method_name->str();
            if (method_str == "textDocument/publishDiagnostics") {
                uint64_t diag_id = notification_sequence_.fetch_add(1, std::memory_order_relaxed);
                if (!sendResponse(protocol::Method::PUBLISH_DIAGNOSTICS, diag_id, json)) {
                    LOGW("Failed to forward diagnostics notification");
                }
            } else {
                LOGI("clangd notification: %s", method_str.c_str());
            }
        }
    }
}

bool ClangdControlBridge::readClangdMessage(std::string& out_json) {
    const size_t kReadBlock = 8192;
    while (running_.load()) {
        auto header_pos = clangd_buffer_.find(kHeaderDelimiter);
        if (header_pos != std::string::npos) {
            size_t body_offset = 0;
            size_t content_length = 0;
            if (!parseHeaders(header_pos, body_offset, content_length)) {
                clangd_buffer_.erase(0, header_pos + strlen(kHeaderDelimiter));
                continue;
            }
            if (clangd_buffer_.size() < body_offset + content_length) {
                // need more data
            } else {
                out_json.assign(clangd_buffer_.data() + body_offset, content_length);
                clangd_buffer_.erase(0, body_offset + content_length);
                return true;
            }
        }
        if (!server_) {
            return false;
        }
        std::vector<char> chunk = server_->readWithTimeout(static_cast<int>(kReadBlock), 200);
        if (chunk.empty()) {
            continue;
        }
        clangd_buffer_.append(chunk.begin(), chunk.end());
    }
    return false;
}

bool ClangdControlBridge::parseHeaders(size_t header_end,
                                       size_t& body_offset,
                                       size_t& content_length) {
    size_t cursor = 0;
    content_length = 0;
    while (cursor < header_end) {
        size_t line_end = clangd_buffer_.find("\r\n", cursor);
        if (line_end == std::string::npos || line_end > header_end) {
            return false;
        }
        if (line_end == cursor) {
            break;
        }
        std::string line = clangd_buffer_.substr(cursor, line_end - cursor);
        cursor = line_end + 2;
        auto colon = line.find(':');
        if (colon == std::string::npos) {
            continue;
        }
        std::string key = line.substr(0, colon);
        std::string value = line.substr(colon + 1);
        auto trim = [](std::string& text) {
            auto not_space = [](int ch) { return !std::isspace(ch); };
            text.erase(text.begin(), std::find_if(text.begin(), text.end(), not_space));
            text.erase(std::find_if(text.rbegin(), text.rend(), not_space).base(), text.end());
        };
        trim(key);
        trim(value);
        std::transform(key.begin(), key.end(), key.begin(), [](unsigned char c) {
            return static_cast<char>(std::tolower(c));
        });
        if (key == "content-length") {
            content_length = static_cast<size_t>(std::stoul(value));
        }
    }
    body_offset = header_end + strlen(kHeaderDelimiter);
    return content_length > 0;
}

void ClangdControlBridge::handleRequest(const protocol::Request* request) {
    if (!request) {
        LOGW("Null request");
        return;
    }
    const protocol::Method method = request->method();
    RequestContext ctx;
    uint32_t file_id = 0;
    switch (method) {
        case protocol::Method::DID_OPEN: {
            auto payload = request->data_as_DidOpenTextDocumentNotification();
            if (!payload || !payload->file_uri()) {
                LOGW("didOpen payload missing uri");
                return;
            }
            ctx.file_uri = payload->file_uri()->str();
            ctx.language_id = payload->language_id() ? payload->language_id()->str() : std::string();
            updateContext(payload->file_id(), ctx);
            break;
        }
        case protocol::Method::DID_CHANGE: {
            auto payload = request->data_as_DidChangeTextDocumentNotification();
            if (!payload) {
                LOGW("didChange payload invalid");
                return;
            }
            file_id = payload->file_id();
            if (!getContext(file_id, ctx)) {
                LOGW("didChange missing context for file %u", file_id);
                return;
            }
            break;
        }
        case protocol::Method::DID_CLOSE: {
            auto payload = request->data_as_DidCloseTextDocumentNotification();
            if (!payload) {
                LOGW("didClose payload invalid");
                return;
            }
            file_id = payload->file_id();
            if (!getContext(file_id, ctx)) {
                LOGW("didClose missing context for file %u", file_id);
                return;
            }
            removeContext(file_id);
            break;
        }
        case protocol::Method::HOVER: {
            auto payload = request->data_as_HoverRequest();
            if (!payload) {
                return;
            }
            file_id = payload->file_id();
            if (!getContext(file_id, ctx)) {
                LOGW("hover missing context for file %u", file_id);
                return;
            }
            break;
        }
        case protocol::Method::COMPLETION: {
            auto payload = request->data_as_CompletionRequest();
            if (!payload) {
                return;
            }
            file_id = payload->file_id();
            if (!getContext(file_id, ctx)) {
                LOGW("completion missing context for file %u", file_id);
                return;
            }
            break;
        }
        case protocol::Method::DEFINITION: {
            auto payload = request->data_as_DefinitionRequest();
            if (!payload) {
                return;
            }
            file_id = payload->file_id();
            if (!getContext(file_id, ctx)) {
                LOGW("definition missing context for file %u", file_id);
                return;
            }
            break;
        }
        case protocol::Method::REFERENCES: {
            auto payload = request->data_as_ReferencesRequest();
            if (!payload) {
                return;
            }
            file_id = payload->file_id();
            if (!getContext(file_id, ctx)) {
                LOGW("references missing context for file %u", file_id);
                return;
            }
            break;
        }
        default:
            LOGW("Method %d not supported by bridge", static_cast<int>(method));
            return;
    }

    std::string json;
    std::string error;
    if (!converter_.buildJsonRequest(request, ctx, json, error)) {
        LOGE("Failed to build JSON request: %s", error.c_str());
        return;
    }
    if (!dispatchJson(method, request->request_id(), json)) {
        LOGE("Failed to dispatch request %llu", static_cast<unsigned long long>(request->request_id()));
    }
}

bool ClangdControlBridge::dispatchJson(protocol::Method method,
                                        uint64_t request_id,
                                        const std::string& json) {
    if (!server_) {
        return false;
    }
    std::string framed = buildLspMessage(json);
    std::vector<char> data(framed.begin(), framed.end());
    {
        std::lock_guard<std::mutex> lock(clangd_write_mutex_);
        if (server_->write(data) <= 0) {
            LOGE("Failed to write request to clangd");
            return false;
        }
    }
    if (!isNotification(method)) {
        std::lock_guard<std::mutex> lock(pending_mutex_);
        pending_requests_[request_id] = method;
    }
    return true;
}

bool ClangdControlBridge::sendResponse(protocol::Method method,
                                        uint64_t request_id,
                                        const std::string& json_body) {
    std::vector<uint8_t> buffer;
    std::string error;
    if (!converter_.buildResponse(method, request_id, json_body, buffer, error)) {
        LOGE("Failed to convert response: %s", error.c_str());
        return false;
    }
    if (!control_channel_) {
        return false;
    }
    Message response(MessageType::DATA, request_id, buffer);
    ChannelError err = control_channel_->send(response);
    if (err != ChannelError::SUCCESS) {
        LOGE("Failed to send response through control channel: %s", control_channel_->getLastError().c_str());
        return false;
    }
    return true;
}

bool ClangdControlBridge::getContext(uint32_t file_id, RequestContext& ctx) {
    std::lock_guard<std::mutex> lock(context_mutex_);
    auto it = file_contexts_.find(file_id);
    if (it == file_contexts_.end()) {
        return false;
    }
    ctx = it->second;
    return true;
}

void ClangdControlBridge::updateContext(uint32_t file_id, const RequestContext& ctx) {
    std::lock_guard<std::mutex> lock(context_mutex_);
    file_contexts_[file_id] = ctx;
}

void ClangdControlBridge::removeContext(uint32_t file_id) {
    std::lock_guard<std::mutex> lock(context_mutex_);
    file_contexts_.erase(file_id);
}

bool ClangdControlBridge::isNotification(protocol::Method method) {
    return method == protocol::Method::DID_OPEN ||
           method == protocol::Method::DID_CHANGE ||
           method == protocol::Method::DID_CLOSE;
}

} // namespace lsp
} // namespace tinaide
