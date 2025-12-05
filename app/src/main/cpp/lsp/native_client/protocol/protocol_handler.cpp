// ProtocolHandler 实现
#include "protocol_handler.h"
#include <android/log.h>

#define LOG_TAG "ProtocolHandler"
#include "utils/logging.h"

namespace tinaide {
namespace lsp {

using namespace protocol;

// ============================================================================
// 请求构建
// ============================================================================

std::vector<uint8_t> ProtocolHandler::buildHoverRequest(
    uint64_t request_id,
    uint32_t file_id,
    uint32_t line,
    uint32_t character,
    uint32_t file_version
) {
    resetBuilder();

    // 创建 Position
    auto position = Position(line, character);

    // 创建 HoverRequest
    auto hover_req = CreateHoverRequest(builder_, file_id, &position, file_version);

    // 创建 Request（使用 Union）
    auto request = CreateRequest(
        builder_,
        request_id,
        Method::HOVER,
        Priority::NORMAL,
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count(),
        RequestData::HoverRequest,
        hover_req.Union()
    );

    FinishRequestBuffer(builder_, request);

    // 提取数据
    uint8_t* buf = builder_.GetBufferPointer();
    size_t size = builder_.GetSize();

    return std::vector<uint8_t>(buf, buf + size);
}

std::vector<uint8_t> ProtocolHandler::buildCompletionRequest(
    uint64_t request_id,
    uint32_t file_id,
    uint32_t line,
    uint32_t character,
    uint32_t file_version,
    uint8_t trigger_kind,
    const std::string& trigger_character
) {
    resetBuilder();

    auto position = Position(line, character);
    auto trigger_char = trigger_character.empty() ? 0 : builder_.CreateString(trigger_character);

    auto comp_req = CreateCompletionRequest(
        builder_,
        file_id,
        &position,
        file_version,
        trigger_kind,
        trigger_char
    );

    auto request = CreateRequest(
        builder_,
        request_id,
        Method::COMPLETION,
        Priority::NORMAL,
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count(),
        RequestData::CompletionRequest,
        comp_req.Union()
    );

    FinishRequestBuffer(builder_, request);

    uint8_t* buf = builder_.GetBufferPointer();
    size_t size = builder_.GetSize();

    return std::vector<uint8_t>(buf, buf + size);
}

std::vector<uint8_t> ProtocolHandler::buildDefinitionRequest(
    uint64_t request_id,
    uint32_t file_id,
    uint32_t line,
    uint32_t character,
    uint32_t file_version
) {
    resetBuilder();

    auto position = Position(line, character);
    auto def_req = CreateDefinitionRequest(builder_, file_id, &position, file_version);

    auto request = CreateRequest(
        builder_,
        request_id,
        Method::DEFINITION,
        Priority::NORMAL,
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count(),
        RequestData::DefinitionRequest,
        def_req.Union()
    );

    FinishRequestBuffer(builder_, request);

    uint8_t* buf = builder_.GetBufferPointer();
    size_t size = builder_.GetSize();

    return std::vector<uint8_t>(buf, buf + size);
}

std::vector<uint8_t> ProtocolHandler::buildReferencesRequest(
    uint64_t request_id,
    uint32_t file_id,
    uint32_t line,
    uint32_t character,
    uint32_t file_version,
    bool include_declaration
) {
    resetBuilder();

    auto position = Position(line, character);
    auto ref_req = CreateReferencesRequest(
        builder_,
        file_id,
        &position,
        file_version,
        include_declaration
    );

    auto request = CreateRequest(
        builder_,
        request_id,
        Method::REFERENCES,
        Priority::NORMAL,
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count(),
        RequestData::ReferencesRequest,
        ref_req.Union()
    );

    FinishRequestBuffer(builder_, request);

    uint8_t* buf = builder_.GetBufferPointer();
    size_t size = builder_.GetSize();

    return std::vector<uint8_t>(buf, buf + size);
}

std::vector<uint8_t> ProtocolHandler::buildDidOpenNotification(
    uint64_t request_id,
    uint32_t file_id,
    const std::string& file_uri,
    const std::string& language_id,
    uint32_t version,
    const std::string& content
) {
    resetBuilder();

    auto uri_offset = builder_.CreateString(file_uri);
    auto lang_offset = language_id.empty() ? 0 : builder_.CreateString(language_id);
    auto content_offset = builder_.CreateString(content);

    auto open_notif = CreateDidOpenTextDocumentNotification(
        builder_,
        file_id,
        uri_offset,
        lang_offset,
        version,
        content_offset
    );

    auto request = CreateRequest(
        builder_,
        request_id,
        Method::DID_OPEN,
        Priority::NORMAL,
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count(),
        RequestData::DidOpenTextDocumentNotification,
        open_notif.Union()
    );

    FinishRequestBuffer(builder_, request);
    uint8_t* buf = builder_.GetBufferPointer();
    size_t size = builder_.GetSize();
    return std::vector<uint8_t>(buf, buf + size);
}

std::vector<uint8_t> ProtocolHandler::buildDidChangeNotification(
    uint64_t request_id,
    uint32_t file_id,
    uint32_t version,
    const std::string& content
) {
    resetBuilder();

    auto content_offset = builder_.CreateString(content);
    auto change_notif = CreateDidChangeTextDocumentNotification(
        builder_,
        file_id,
        version,
        content_offset
    );

    auto request = CreateRequest(
        builder_,
        request_id,
        Method::DID_CHANGE,
        Priority::NORMAL,
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count(),
        RequestData::DidChangeTextDocumentNotification,
        change_notif.Union()
    );

    FinishRequestBuffer(builder_, request);
    uint8_t* buf = builder_.GetBufferPointer();
    size_t size = builder_.GetSize();
    return std::vector<uint8_t>(buf, buf + size);
}

std::vector<uint8_t> ProtocolHandler::buildDidCloseNotification(
    uint64_t request_id,
    uint32_t file_id
) {
    resetBuilder();

    auto close_notif = CreateDidCloseTextDocumentNotification(builder_, file_id);
    auto request = CreateRequest(
        builder_,
        request_id,
        Method::DID_CLOSE,
        Priority::NORMAL,
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()
        ).count(),
        RequestData::DidCloseTextDocumentNotification,
        close_notif.Union()
    );

    FinishRequestBuffer(builder_, request);
    uint8_t* buf = builder_.GetBufferPointer();
    size_t size = builder_.GetSize();
    return std::vector<uint8_t>(buf, buf + size);
}

std::vector<uint8_t> ProtocolHandler::buildCancelRequest(uint64_t request_id) {
    resetBuilder();

    // 创建控制消息（取消类型）
    auto cancel_msg = CreateControlMessage(
        builder_,
        ControlMessageType::CANCEL,
        request_id
    );

    builder_.Finish(cancel_msg);

    uint8_t* buf = builder_.GetBufferPointer();
    size_t size = builder_.GetSize();

    return std::vector<uint8_t>(buf, buf + size);
}

// ============================================================================
// 响应解析
// ============================================================================

std::optional<const Response*> ProtocolHandler::parseResponse(const std::vector<uint8_t>& data) {
    if (!isValidResponse(data)) {
        LOGE("Invalid response data");
        return std::nullopt;
    }

    auto response = flatbuffers::GetRoot<Response>(data.data());
    return response;
}

std::optional<ProtocolHandler::HoverResult> ProtocolHandler::parseHoverResponse(
    const Response* response
) {
    if (!response || response->data_type() != ResponseData::HoverResponse) {
        return std::nullopt;
    }

    auto hover_resp = response->data_as_HoverResponse();
    if (!hover_resp) {
        return std::nullopt;
    }

    HoverResult result;
    result.content = hover_resp->content() ? hover_resp->content()->str() : "";
    result.has_content = hover_resp->has_content();

    if (hover_resp->range()) {
        result.start_line = hover_resp->range()->start().line();
        result.start_character = hover_resp->range()->start().character();
        result.end_line = hover_resp->range()->end().line();
        result.end_character = hover_resp->range()->end().character();
    } else {
        result.start_line = result.start_character = 0;
        result.end_line = result.end_character = 0;
    }

    return result;
}

std::optional<ProtocolHandler::CompletionResult> ProtocolHandler::parseCompletionResponse(
    const Response* response
) {
    if (!response || response->data_type() != ResponseData::CompletionResponse) {
        return std::nullopt;
    }

    auto comp_resp = response->data_as_CompletionResponse();
    if (!comp_resp) {
        return std::nullopt;
    }

    CompletionResult result;
    result.is_incomplete = comp_resp->is_incomplete();

    if (comp_resp->items()) {
        for (const auto* item : *comp_resp->items()) {
            CompletionItem ci;
            ci.label = item->label() ? item->label()->str() : "";
            ci.detail = item->detail() ? item->detail()->str() : "";
            ci.insert_text = item->insert_text() ? item->insert_text()->str() : "";
            ci.documentation = item->documentation() ? item->documentation()->str() : "";
            ci.kind = item->kind();
            ci.deprecated = item->deprecated();

            result.items.push_back(std::move(ci));
        }
    }

    return result;
}

std::optional<std::vector<ProtocolHandler::Location>> ProtocolHandler::parseDefinitionResponse(
    const Response* response
) {
    if (!response || response->data_type() != ResponseData::DefinitionResponse) {
        return std::nullopt;
    }

    auto def_resp = response->data_as_DefinitionResponse();
    if (!def_resp || !def_resp->locations()) {
        return std::nullopt;
    }

    std::vector<Location> locations;
    for (const auto* loc : *def_resp->locations()) {
        Location l;
        l.file_path = loc->file_path() ? loc->file_path()->str() : "";
        l.start_line = loc->range()->start().line();
        l.start_character = loc->range()->start().character();
        l.end_line = loc->range()->end().line();
        l.end_character = loc->range()->end().character();

        locations.push_back(std::move(l));
    }

    return locations;
}

std::optional<std::vector<ProtocolHandler::Location>> ProtocolHandler::parseReferencesResponse(
    const Response* response
) {
    if (!response || response->data_type() != ResponseData::ReferencesResponse) {
        return std::nullopt;
    }

    auto ref_resp = response->data_as_ReferencesResponse();
    if (!ref_resp || !ref_resp->locations()) {
        return std::nullopt;
    }

    std::vector<Location> locations;
    for (const auto* loc : *ref_resp->locations()) {
        Location l;
        l.file_path = loc->file_path() ? loc->file_path()->str() : "";
        l.start_line = loc->range()->start().line();
        l.start_character = loc->range()->start().character();
        l.end_line = loc->range()->end().line();
        l.end_character = loc->range()->end().character();

        locations.push_back(std::move(l));
    }

    return locations;
}

std::optional<ProtocolHandler::DiagnosticsResult> ProtocolHandler::parseDiagnosticsResponse(
    const Response* response
) {
    if (!response || response->data_type() != ResponseData::DiagnosticsNotification) {
        return std::nullopt;
    }

    auto diag_resp = response->data_as_DiagnosticsNotification();
    if (!diag_resp) {
        return std::nullopt;
    }

    DiagnosticsResult result;
    result.file_uri = diag_resp->file_uri() ? diag_resp->file_uri()->str() : "";
    result.version = diag_resp->version();

    if (diag_resp->diagnostics()) {
        for (const auto* diag : *diag_resp->diagnostics()) {
            if (!diag || !diag->range()) {
                continue;
            }
            Diagnostic entry;
            entry.start_line = diag->range()->start().line();
            entry.start_character = diag->range()->start().character();
            entry.end_line = diag->range()->end().line();
            entry.end_character = diag->range()->end().character();
            entry.severity = diag->severity();
            entry.message = diag->message() ? diag->message()->str() : "";
            entry.source = diag->source() ? diag->source()->str() : "";
            entry.code = diag->code() ? diag->code()->str() : "";
            result.diagnostics.push_back(std::move(entry));
        }
    }

    return result;
}

// ============================================================================
// 工具方法
// ============================================================================

bool ProtocolHandler::isValidRequest(const std::vector<uint8_t>& data) const {
    if (data.empty()) return false;

    flatbuffers::Verifier verifier(data.data(), data.size());
    return VerifyRequestBuffer(verifier);
}

bool ProtocolHandler::isValidResponse(const std::vector<uint8_t>& data) const {
    if (data.empty()) return false;

    flatbuffers::Verifier verifier(data.data(), data.size());
    return verifier.VerifyBuffer<Response>(RequestIdentifier());
}

std::optional<uint64_t> ProtocolHandler::getRequestId(const std::vector<uint8_t>& data) const {
    if (!isValidRequest(data)) return std::nullopt;

    auto request = GetRequest(data.data());
    return request->request_id();
}

std::optional<uint64_t> ProtocolHandler::getResponseId(const std::vector<uint8_t>& data) const {
    if (!isValidResponse(data)) return std::nullopt;

    auto response = flatbuffers::GetRoot<Response>(data.data());
    return response->request_id();
}

std::optional<Method> ProtocolHandler::getMethod(const std::vector<uint8_t>& data) const {
    if (!isValidRequest(data)) return std::nullopt;

    auto request = GetRequest(data.data());
    return request->method();
}

void ProtocolHandler::resetBuilder() {
    builder_.Clear();
}

} // namespace lsp
} // namespace tinaide
