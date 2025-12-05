#include "mock_lsp_server.h"
#include <android/log.h>
#include <chrono>
#include <sstream>
#include <utility>

#define LOG_TAG "MockLspServer"
#include "utils/logging.h"

namespace {
uint64_t nowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}
}

namespace tinaide {
namespace lsp {

MockLspServer::MockLspServer(ChannelConfig config)
    : config_(std::move(config)) {}

MockLspServer::~MockLspServer() {
    stop();
}

bool MockLspServer::start() {
    if (running_.load()) {
        return true;
    }

    ready_.store(false);
    running_.store(true);
    thread_ = std::thread(&MockLspServer::run, this);

    std::unique_lock<std::mutex> lock(ready_mutex_);
    bool ready = ready_cv_.wait_for(lock, std::chrono::seconds(2), [this]() {
        return ready_.load();
    });

    if (!ready) {
        LOGE("MockLspServer failed to initialize within timeout");
        running_.store(false);
        if (thread_.joinable()) {
            thread_.join();
        }
        return false;
    }

    return true;
}

void MockLspServer::stop() {
    if (!running_.exchange(false)) {
        if (thread_.joinable()) {
            thread_.join();
        }
        return;
    }

    ControlChannel wake_channel(config_);
    wake_channel.connect(100);
    wake_channel.close();

    if (thread_.joinable()) {
        thread_.join();
    }
}

void MockLspServer::run() {
    ControlChannel server_channel(config_);
    ChannelError err = server_channel.createServer();
    if (err != ChannelError::SUCCESS) {
        LOGE("Mock server createServer failed: %s", server_channel.getLastError().c_str());
        ready_.store(true);
        ready_cv_.notify_all();
        return;
    }

    {
        std::lock_guard<std::mutex> lock(ready_mutex_);
        ready_.store(true);
    }
    ready_cv_.notify_all();

    err = server_channel.acceptClient();
    if (err != ChannelError::SUCCESS) {
        LOGE("Mock server accept failed: %s", server_channel.getLastError().c_str());
        server_channel.close();
        return;
    }

    LOGI("Mock LSP server ready on %s", config_.socket_path.c_str());

    while (running_.load()) {
        Message msg;
        err = server_channel.receive(msg, 200);
        if (err == ChannelError::TIMEOUT) {
            continue;
        }
        if (err != ChannelError::SUCCESS) {
            LOGW("Mock server receive failed: %s", server_channel.getLastError().c_str());
            break;
        }

        if (!handleMessage(server_channel, msg)) {
            break;
        }
    }

    server_channel.close();
    LOGI("Mock LSP server stopped");
}

bool MockLspServer::handleMessage(ControlChannel& channel, const Message& msg) {
    if (msg.header.type != static_cast<uint16_t>(MessageType::DATA)) {
        LOGW("Mock server received unsupported message type=%u", msg.header.type);
        return true;
    }

    if (msg.payload.empty()) {
        LOGW("Mock server received empty payload");
        return true;
    }

    flatbuffers::Verifier verifier(msg.payload.data(), msg.payload.size());
    if (!protocol::VerifyRequestBuffer(verifier)) {
        LOGE("Mock server received invalid request buffer");
        return true;
    }

    const protocol::Request* request = protocol::GetRequest(msg.payload.data());
    switch (request->method()) {
        case protocol::Method::DID_OPEN:
            handleDidOpen(request->data_as_DidOpenTextDocumentNotification());
            return true;
        case protocol::Method::DID_CHANGE:
            handleDidChange(request->data_as_DidChangeTextDocumentNotification());
            return true;
        case protocol::Method::DID_CLOSE:
            handleDidClose(request->data_as_DidCloseTextDocumentNotification());
            return true;
        default:
            break;
    }

    std::vector<uint8_t> response_data;
    if (!buildResponse(request, response_data)) {
        LOGE("Mock server failed to build response for method=%d", static_cast<int>(request->method()));
        return true;
    }

    Message response(MessageType::DATA, request->request_id(), response_data);
    ChannelError err = channel.send(response);
    if (err != ChannelError::SUCCESS) {
        LOGE("Mock server failed to send response: %s", channel.getLastError().c_str());
        return false;
    }

    return true;
}

bool MockLspServer::buildResponse(const protocol::Request* request, std::vector<uint8_t>& out_buffer) {
    if (!request) {
        return false;
    }

    std::vector<uint8_t> payload;

    switch (request->method()) {
        case protocol::Method::HOVER:
            payload = buildHoverResponse(request);
            break;
        case protocol::Method::COMPLETION:
            payload = buildCompletionResponse(request);
            break;
        case protocol::Method::DEFINITION:
            payload = buildDefinitionResponse(request);
            break;
        case protocol::Method::REFERENCES:
            payload = buildReferencesResponse(request);
            break;
        default:
            break;
    }

    if (!payload.empty()) {
        out_buffer = std::move(payload);
        return true;
    }

    flatbuffers::FlatBufferBuilder builder(256);
    auto error = builder.CreateString("Mock server: unsupported request");
    auto response = protocol::CreateResponse(
        builder,
        request->request_id(),
        protocol::Status::ERROR,
        request->method(),
        nowMs(),
        error,
        protocol::ResponseData::NONE,
        0,
        false,
        -1,
        0);
    builder.Finish(response, protocol::RequestIdentifier());
    out_buffer.assign(builder.GetBufferPointer(), builder.GetBufferPointer() + builder.GetSize());
    return true;
}

std::vector<uint8_t> MockLspServer::buildHoverResponse(const protocol::Request* request) {
    auto hover_req = request->data_as_HoverRequest();
    if (!hover_req || !hover_req->position()) {
        return {};
    }

    flatbuffers::FlatBufferBuilder builder(256);
    std::ostringstream oss;
    oss << "Mock hover: file #" << hover_req->file_id()
        << " line " << hover_req->position()->line();

    auto content = builder.CreateString(oss.str());
    protocol::Position start(hover_req->position()->line(), hover_req->position()->character());
    protocol::Position end(hover_req->position()->line(), hover_req->position()->character() + 1);
    protocol::Range range(start, end);

    auto hover_resp = protocol::CreateHoverResponse(builder, content, &range, true);
    auto response = protocol::CreateResponse(
        builder,
        request->request_id(),
        protocol::Status::SUCCESS,
        protocol::Method::HOVER,
        nowMs(),
        0,
        protocol::ResponseData::HoverResponse,
        hover_resp.Union(),
        false,
        -1,
        0);

    builder.Finish(response, protocol::RequestIdentifier());
    return {builder.GetBufferPointer(), builder.GetBufferPointer() + builder.GetSize()};
}

std::vector<uint8_t> MockLspServer::buildCompletionResponse(const protocol::Request* request) {
    auto comp_req = request->data_as_CompletionRequest();
    if (!comp_req || !comp_req->position()) {
        return {};
    }

    flatbuffers::FlatBufferBuilder builder(1024);
    std::vector<flatbuffers::Offset<protocol::CompletionItem>> item_offsets;
    item_offsets.reserve(3);

    for (int i = 0; i < 3; ++i) {
        std::ostringstream label;
        label << "symbol_" << i;
        auto label_offset = builder.CreateString(label.str());
        auto detail = builder.CreateString("Mock completion item");
        auto insert_text = builder.CreateString(label.str());
        auto sort_text = builder.CreateString(label.str());
        auto filter_text = builder.CreateString(label.str());
        auto documentation = builder.CreateString("Generated by MockLspServer");

        item_offsets.push_back(protocol::CreateCompletionItem(
            builder,
            label_offset,
            static_cast<uint8_t>(i + 2),
            detail,
            insert_text,
            sort_text,
            filter_text,
            documentation,
            false));
    }

    auto items_vec = builder.CreateVector(item_offsets);
    auto completion = protocol::CreateCompletionResponse(builder, items_vec, false);
    auto response = protocol::CreateResponse(
        builder,
        request->request_id(),
        protocol::Status::SUCCESS,
        protocol::Method::COMPLETION,
        nowMs(),
        0,
        protocol::ResponseData::CompletionResponse,
        completion.Union(),
        false,
        -1,
        0);

    builder.Finish(response, protocol::RequestIdentifier());
    return {builder.GetBufferPointer(), builder.GetBufferPointer() + builder.GetSize()};
}

std::vector<uint8_t> MockLspServer::buildDefinitionResponse(const protocol::Request* request) {
    auto def_req = request->data_as_DefinitionRequest();
    if (!def_req || !def_req->position()) {
        return {};
    }

    flatbuffers::FlatBufferBuilder builder(512);
    protocol::Position start(def_req->position()->line(), def_req->position()->character());
    protocol::Position end(def_req->position()->line(), def_req->position()->character() + 2);
    protocol::Range range(start, end);

    std::ostringstream path;
    path << "/mock/project/file_" << def_req->file_id() << ".cpp";
    auto file_path = builder.CreateString(path.str());

    auto location = protocol::CreateLocation(builder, file_path, &range);
    std::vector<flatbuffers::Offset<protocol::Location>> locs = {location};
    auto loc_vec = builder.CreateVector(locs);
    auto def_resp = protocol::CreateDefinitionResponse(builder, loc_vec);

    auto response = protocol::CreateResponse(
        builder,
        request->request_id(),
        protocol::Status::SUCCESS,
        protocol::Method::DEFINITION,
        nowMs(),
        0,
        protocol::ResponseData::DefinitionResponse,
        def_resp.Union(),
        false,
        -1,
        0);

    builder.Finish(response, protocol::RequestIdentifier());
    return {builder.GetBufferPointer(), builder.GetBufferPointer() + builder.GetSize()};
}

std::vector<uint8_t> MockLspServer::buildReferencesResponse(const protocol::Request* request) {
    auto ref_req = request->data_as_ReferencesRequest();
    if (!ref_req || !ref_req->position()) {
        return {};
    }

    flatbuffers::FlatBufferBuilder builder(768);
    std::vector<flatbuffers::Offset<protocol::Location>> locations;

    for (int i = 0; i < 2; ++i) {
        protocol::Position start(ref_req->position()->line() + i, ref_req->position()->character());
        protocol::Position end(ref_req->position()->line() + i, ref_req->position()->character() + 1);
        protocol::Range range(start, end);

        std::ostringstream path;
        path << "/mock/ref/ref_" << ref_req->file_id() << "_" << i << ".cpp";
        auto file_path = builder.CreateString(path.str());
        locations.push_back(protocol::CreateLocation(builder, file_path, &range));
    }

    auto loc_vec = builder.CreateVector(locations);
    auto ref_resp = protocol::CreateReferencesResponse(builder, loc_vec);
    auto response = protocol::CreateResponse(
        builder,
        request->request_id(),
        protocol::Status::SUCCESS,
        protocol::Method::REFERENCES,
        nowMs(),
        0,
        protocol::ResponseData::ReferencesResponse,
        ref_resp.Union(),
        false,
        -1,
        0);

    builder.Finish(response, protocol::RequestIdentifier());
    return {builder.GetBufferPointer(), builder.GetBufferPointer() + builder.GetSize()};
}

void MockLspServer::handleDidOpen(const protocol::DidOpenTextDocumentNotification* notif) {
    if (!notif) {
        return;
    }

    FileEntry entry;
    entry.uri = notif->file_uri() ? notif->file_uri()->str() : "";
    entry.content = notif->content() ? notif->content()->str() : "";
    entry.version = notif->version();
    files_[notif->file_id()] = std::move(entry);
    LOGI("Mock server didOpen file_id=%u uri=%s", notif->file_id(), entry.uri.c_str());
}

void MockLspServer::handleDidChange(const protocol::DidChangeTextDocumentNotification* notif) {
    if (!notif) {
        return;
    }

    auto it = files_.find(notif->file_id());
    if (it == files_.end()) {
        LOGW("didChange received for unknown file_id=%u", notif->file_id());
        return;
    }

    it->second.version = notif->version();
    it->second.content = notif->content() ? notif->content()->str() : "";
    LOGI("Mock server didChange file_id=%u version=%u", notif->file_id(), notif->version());
}

void MockLspServer::handleDidClose(const protocol::DidCloseTextDocumentNotification* notif) {
    if (!notif) {
        return;
    }

    files_.erase(notif->file_id());
    LOGI("Mock server didClose file_id=%u", notif->file_id());
}

} // namespace lsp
} // namespace tinaide
