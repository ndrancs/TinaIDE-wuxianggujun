#include "json_rpc_converter.h"

#include <android/log.h>
#include <chrono>
#include <utility>
#include <llvm/Support/JSON.h>
#include <llvm/Support/raw_ostream.h>
#include "lsp_protocol_generated.h"

#define LOG_TAG "JsonRpcConverter"
#include "utils/logging.h"

namespace tinaide {
namespace lsp {
namespace {

using llvm::json::Array;
using llvm::json::Object;
using llvm::json::Value;

constexpr const char* kJsonRpcVersion = "2.0";

uint64_t nowMillis() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(system_clock::now().time_since_epoch()).count();
}

llvm::json::Object makePositionObject(uint32_t line, uint32_t character) {
    llvm::json::Object position;
    position["line"] = static_cast<int64_t>(line);
    position["character"] = static_cast<int64_t>(character);
    return position;
}

llvm::json::Object makeRangeObject(uint32_t start_line,
                                   uint32_t start_char,
                                   uint32_t end_line,
                                   uint32_t end_char) {
    llvm::json::Object range;
    range["start"] = makePositionObject(start_line, start_char);
    range["end"] = makePositionObject(end_line, end_char);
    return range;
}

std::string toJsonString(llvm::json::Value value) {
    std::string result;
    llvm::raw_string_ostream os(result);
    os << value;
    os.flush();
    return result;
}

std::string valueToString(const Value* v) {
    if (!v) {
        return std::string();
    }

    if (auto number = v->getAsInteger()) {
        return std::to_string(*number);
    }

    if (auto s = v->getAsString()) {
        return s->str();
    }

    if (auto obj = v->getAsObject()) {
        if (auto value = obj->getString("value")) {
            return value->str();
        }
    }

    if (auto array = v->getAsArray()) {
        for (const auto& entry : *array) {
            if (auto as_string = entry.getAsString()) {
                return as_string->str();
            }
            if (auto as_object = entry.getAsObject()) {
                if (auto val = as_object->getString("value")) {
                    return val->str();
                }
            }
        }
    }

    return std::string();
}

bool parsePosition(const Object* obj, uint32_t& line, uint32_t& character) {
    if (!obj) {
        return false;
    }

    auto line_value = obj->get("line");
    auto char_value = obj->get("character");
    if (!line_value || !char_value) {
        return false;
    }

    if (auto line_number = line_value->getAsInteger()) {
        line = static_cast<uint32_t>(*line_number);
    } else {
        return false;
    }

    if (auto char_number = char_value->getAsInteger()) {
        character = static_cast<uint32_t>(*char_number);
    } else {
        return false;
    }

    return true;
}

bool parseRange(const Object* range_obj,
                uint32_t& start_line,
                uint32_t& start_char,
                uint32_t& end_line,
                uint32_t& end_char) {
    if (!range_obj) {
        return false;
    }

    auto start = range_obj->getObject("start");
    auto end = range_obj->getObject("end");
    if (!parsePosition(start, start_line, start_char)) {
        return false;
    }
    if (!parsePosition(end, end_line, end_char)) {
        return false;
    }
    return true;
}

bool buildHoverResponsePayload(const Value& result,
                               flatbuffers::FlatBufferBuilder& builder,
                               flatbuffers::Offset<void>& out_data,
                               protocol::ResponseData& out_type,
                               std::string& error) {
    const Object* result_obj = result.getAsObject();

    const Value* contents = nullptr;
    uint32_t start_line = 0, start_char = 0, end_line = 0, end_char = 0;

    if (result_obj) {
        if (auto it = result_obj->get("contents")) {
            contents = it;
        } else if (auto value = result_obj->get("value")) {
            contents = value;
        }
        if (auto range_obj = result_obj->getObject("range")) {
            if (!parseRange(range_obj, start_line, start_char, end_line, end_char)) {
                LOGW("Hover range parsing failed, fallback to defaults");
            }
        }
    } else {
        contents = &result;
    }

    std::string content = valueToString(contents);

    protocol::Position start(start_line, start_char);
    protocol::Position end(end_line, end_char);
    protocol::Range range(start, end);

    auto content_offset = builder.CreateString(content);
    auto hover_resp = protocol::CreateHoverResponse(builder, content_offset, &range, !content.empty());
    out_type = protocol::ResponseData::HoverResponse;
    out_data = hover_resp.Union();
    return true;
}

std::string extractDocumentation(const Value* documentation) {
    if (!documentation) {
        return std::string();
    }

    if (auto s = documentation->getAsString()) {
        return s->str();
    }
    if (auto obj = documentation->getAsObject()) {
        if (auto value = obj->getString("value")) {
            return value->str();
        }
    }
    return std::string();
}

bool buildCompletionResponsePayload(const Value& value,
                                    flatbuffers::FlatBufferBuilder& builder,
                                    flatbuffers::Offset<void>& out_data,
                                    protocol::ResponseData& out_type,
                                    std::string& error) {
    const Object* result_obj = value.getAsObject();
    const Array* items_array = nullptr;
    bool is_incomplete = false;

    if (result_obj) {
        items_array = result_obj->getArray("items");
        if (!items_array) {
            error = "completion result missing items array";
            return false;
        }
        if (auto flag = result_obj->get("isIncomplete")) {
            if (auto boolean = flag->getAsBoolean()) {
                is_incomplete = *boolean;
            } else if (auto number = flag->getAsInteger()) {
                is_incomplete = (*number) != 0;
            }
        }
    } else {
        items_array = value.getAsArray();
        if (!items_array) {
            error = "completion result must be an object or array";
            return false;
        }
        is_incomplete = false;
    }

    std::vector<flatbuffers::Offset<protocol::CompletionItem>> items;
    items.reserve(items_array->size());
    for (const auto& entry : *items_array) {
        const Object* item_obj = entry.getAsObject();
        if (!item_obj) {
            continue;
        }

        std::string label = item_obj->getString("label") ? item_obj->getString("label")->str() : "";
        std::string detail = item_obj->getString("detail") ? item_obj->getString("detail")->str() : "";
        std::string insert_text = item_obj->getString("insertText") ? item_obj->getString("insertText")->str() : label;
        std::string documentation = extractDocumentation(item_obj->get("documentation"));

        int kind = 0;
        if (auto kind_value = item_obj->get("kind")) {
            if (auto ints = kind_value->getAsInteger()) {
                kind = static_cast<int>(*ints);
            }
        }

        bool deprecated = false;
        if (auto dep = item_obj->get("deprecated")) {
            deprecated = dep->getAsBoolean().value_or(false);
        }

        auto label_offset = builder.CreateString(label);
        auto detail_offset = builder.CreateString(detail);
        auto insert_offset = builder.CreateString(insert_text);
        auto documentation_offset = builder.CreateString(documentation);

        auto completion_item = protocol::CreateCompletionItem(
            builder,
            label_offset,
            static_cast<uint8_t>(kind),
            detail_offset,
            insert_offset,
            0,
            0,
            documentation_offset,
            deprecated
        );
        items.push_back(completion_item);
    }

    auto items_vec = builder.CreateVector(items);
    auto completion_resp = protocol::CreateCompletionResponse(builder, items_vec, is_incomplete);
    out_type = protocol::ResponseData::CompletionResponse;
    out_data = completion_resp.Union();
    return true;
}

bool readLocation(const Value& value,
                  const std::string& default_uri,
                  flatbuffers::FlatBufferBuilder& builder,
                  flatbuffers::Offset<protocol::Location>& out_loc) {
    const Object* obj = value.getAsObject();
    if (!obj) {
        return false;
    }

    std::string uri = obj->getString("uri") ? obj->getString("uri")->str() : default_uri;
    auto range_obj = obj->getObject("range");

    uint32_t start_line = 0, start_char = 0, end_line = 0, end_char = 0;
    if (range_obj) {
        parseRange(range_obj, start_line, start_char, end_line, end_char);
    }

    protocol::Position start(start_line, start_char);
    protocol::Position end(end_line, end_char);
    protocol::Range range(start, end);

    auto file_offset = builder.CreateString(uri);
    out_loc = protocol::CreateLocation(builder, file_offset, &range);
    return true;
}

bool buildLocationArrayResponse(const Value& value,
                                flatbuffers::FlatBufferBuilder& builder,
                                flatbuffers::Offset<void>& out_data,
                                protocol::ResponseData& out_type,
                                std::string& error,
                                bool allow_single_object) {
    std::vector<flatbuffers::Offset<protocol::Location>> locations;

    if (allow_single_object && value.getAsObject()) {
        flatbuffers::Offset<protocol::Location> location;
        if (readLocation(value, std::string(), builder, location)) {
            locations.push_back(location);
        }
    }

    if (auto array = value.getAsArray()) {
        locations.reserve(array->size());
        for (const auto& entry : *array) {
            flatbuffers::Offset<protocol::Location> location;
            if (readLocation(entry, std::string(), builder, location)) {
                locations.push_back(location);
            }
        }
    }

    if (locations.empty()) {
        error = "location response is empty";
        return false;
    }

    auto loc_vec = builder.CreateVector(locations);
    auto resp = protocol::CreateDefinitionResponse(builder, loc_vec);
    out_type = protocol::ResponseData::DefinitionResponse;
    out_data = resp.Union();
    return true;
}

bool buildReferencesPayload(const Value& value,
                            flatbuffers::FlatBufferBuilder& builder,
                            flatbuffers::Offset<void>& out_data,
                            protocol::ResponseData& out_type,
                            std::string& error) {
    std::vector<flatbuffers::Offset<protocol::Location>> locations;

    if (auto array = value.getAsArray()) {
        locations.reserve(array->size());
        for (const auto& entry : *array) {
            flatbuffers::Offset<protocol::Location> location;
            if (readLocation(entry, std::string(), builder, location)) {
                locations.push_back(location);
            }
        }
    }

    if (locations.empty()) {
        error = "references response is empty";
        return false;
    }

    auto loc_vec = builder.CreateVector(locations);
    auto resp = protocol::CreateReferencesResponse(builder, loc_vec);
    out_type = protocol::ResponseData::ReferencesResponse;
    out_data = resp.Union();
    return true;
}

bool buildDiagnosticsNotificationPayload(const Value& value,
                                         flatbuffers::FlatBufferBuilder& builder,
                                         flatbuffers::Offset<void>& out_data,
                                         protocol::ResponseData& out_type,
                                         std::string& error) {
    const Object* params = value.getAsObject();
    if (!params) {
        error = "diagnostics params missing";
        return false;
    }

    std::string file_uri;
    uint32_t version = 0;
    if (auto text_doc = params->getObject("textDocument")) {
        if (auto uri = text_doc->getString("uri")) {
            file_uri = uri->str();
        }
        if (auto ver = text_doc->get("version")) {
            if (auto ver_int = ver->getAsInteger()) {
                version = static_cast<uint32_t>(*ver_int);
            }
        }
    }

    std::vector<flatbuffers::Offset<protocol::Diagnostic>> diagnostics_vec;
    if (auto diagnostics = params->getArray("diagnostics")) {
        diagnostics_vec.reserve(diagnostics->size());
        for (const auto& entry : *diagnostics) {
            const Object* diag_obj = entry.getAsObject();
            if (!diag_obj) {
                continue;
            }
            auto range_obj = diag_obj->getObject("range");
            uint32_t start_line = 0, start_char = 0, end_line = 0, end_char = 0;
            if (!parseRange(range_obj, start_line, start_char, end_line, end_char)) {
                continue;
            }
            uint8_t severity = 0;
            if (auto sev = diag_obj->get("severity")) {
                if (auto sev_int = sev->getAsInteger()) {
                    severity = static_cast<uint8_t>(*sev_int);
                }
            }
            std::string message = diag_obj->getString("message") ? diag_obj->getString("message")->str() : "";
            std::string source = diag_obj->getString("source") ? diag_obj->getString("source")->str() : "";
            std::string code = valueToString(diag_obj->get("code"));

            protocol::Position start(start_line, start_char);
            protocol::Position end(end_line, end_char);
            protocol::Range range(start, end);

            auto message_offset = builder.CreateString(message);
            auto source_offset = builder.CreateString(source);
            auto code_offset = builder.CreateString(code);
            auto diag_fb = protocol::CreateDiagnostic(builder, &range, severity, message_offset, source_offset, code_offset);
            diagnostics_vec.push_back(diag_fb);
        }
    }

    auto uri_offset = builder.CreateString(file_uri);
    auto diagnostics_offset = builder.CreateVector(diagnostics_vec);
    auto notif = protocol::CreateDiagnosticsNotification(builder, uri_offset, version, diagnostics_offset);
    out_type = protocol::ResponseData::DiagnosticsNotification;
    out_data = notif.Union();
    return true;
}

bool isNotificationMethod(protocol::Method method) {
    return method == protocol::Method::DID_OPEN ||
           method == protocol::Method::DID_CHANGE ||
           method == protocol::Method::DID_CLOSE;
}

} // namespace

bool JsonRpcConverter::buildJsonRequest(const protocol::Request* request,
                                         const RequestContext& ctx,
                                         std::string& out_json,
                                         std::string& error) const {
    if (!request) {
        error = "request is null";
        return false;
    }

    llvm::json::Object root;
    root["jsonrpc"] = kJsonRpcVersion;

    const protocol::Method method = request->method();

    auto add_id = [&]() {
        if (!isNotificationMethod(method)) {
            root["id"] = static_cast<int64_t>(request->request_id());
        }
    };

    auto append_params = [&](llvm::json::Object params) {
        root["params"] = std::move(params);
    };

    switch (method) {
        case protocol::Method::HOVER: {
            auto hover_req = request->data_as_HoverRequest();
            if (!hover_req || hover_req->position() == nullptr) {
                error = "invalid hover request payload";
                return false;
            }
            if (ctx.file_uri.empty()) {
                error = "missing file uri for hover request";
                return false;
            }

            llvm::json::Object params;
            llvm::json::Object text_document;
            text_document["uri"] = ctx.file_uri;
            params["textDocument"] = std::move(text_document);
            params["position"] = makePositionObject(
                hover_req->position()->line(),
                hover_req->position()->character()
            );

            root["method"] = "textDocument/hover";
            add_id();
            append_params(std::move(params));
            break;
        }
        case protocol::Method::COMPLETION: {
            auto comp_req = request->data_as_CompletionRequest();
            if (!comp_req || comp_req->position() == nullptr) {
                error = "invalid completion request payload";
                return false;
            }
            if (ctx.file_uri.empty()) {
                error = "missing file uri for completion";
                return false;
            }
            llvm::json::Object params;
            llvm::json::Object text_document;
            text_document["uri"] = ctx.file_uri;
            params["textDocument"] = std::move(text_document);
            params["position"] = makePositionObject(
                comp_req->position()->line(),
                comp_req->position()->character()
            );
            if (comp_req->trigger_kind() != 0 || (comp_req->trigger_character() && comp_req->trigger_character()->size() > 0)) {
                llvm::json::Object context_json;
                context_json["triggerKind"] = static_cast<int64_t>(comp_req->trigger_kind());
                if (comp_req->trigger_character() && comp_req->trigger_character()->size() > 0) {
                    context_json["triggerCharacter"] = comp_req->trigger_character()->str();
                }
                params["context"] = std::move(context_json);
            }
            root["method"] = "textDocument/completion";
            add_id();
            append_params(std::move(params));
            break;
        }
        case protocol::Method::DEFINITION: {
            auto def_req = request->data_as_DefinitionRequest();
            if (!def_req || def_req->position() == nullptr) {
                error = "invalid definition request";
                return false;
            }
            if (ctx.file_uri.empty()) {
                error = "missing file uri for definition";
                return false;
            }
            llvm::json::Object params;
            llvm::json::Object text_document;
            text_document["uri"] = ctx.file_uri;
            params["textDocument"] = std::move(text_document);
            params["position"] = makePositionObject(
                def_req->position()->line(),
                def_req->position()->character()
            );
            root["method"] = "textDocument/definition";
            add_id();
            append_params(std::move(params));
            break;
        }
        case protocol::Method::REFERENCES: {
            auto ref_req = request->data_as_ReferencesRequest();
            if (!ref_req || ref_req->position() == nullptr) {
                error = "invalid references request";
                return false;
            }
            if (ctx.file_uri.empty()) {
                error = "missing file uri for references";
                return false;
            }
            llvm::json::Object params;
            llvm::json::Object text_document;
            text_document["uri"] = ctx.file_uri;
            params["textDocument"] = std::move(text_document);
            params["position"] = makePositionObject(
                ref_req->position()->line(),
                ref_req->position()->character()
            );
            llvm::json::Object context_json;
            context_json["includeDeclaration"] = ref_req->include_declaration();
            params["context"] = std::move(context_json);
            root["method"] = "textDocument/references";
            add_id();
            append_params(std::move(params));
            break;
        }
        case protocol::Method::DID_OPEN: {
            auto open_req = request->data_as_DidOpenTextDocumentNotification();
            if (!open_req || !open_req->file_uri()) {
                error = "invalid didOpen payload";
                return false;
            }
            llvm::json::Object params;
            llvm::json::Object text_document;
            text_document["uri"] = open_req->file_uri()->str();
            std::string language = open_req->language_id() ? open_req->language_id()->str() : ctx.language_id;
            if (!language.empty()) {
                text_document["languageId"] = language;
            }
            text_document["version"] = static_cast<int64_t>(open_req->version());
            text_document["text"] = open_req->content() ? open_req->content()->str() : std::string();
            params["textDocument"] = std::move(text_document);
            root["method"] = "textDocument/didOpen";
            append_params(std::move(params));
            break;
        }
        case protocol::Method::DID_CHANGE: {
            auto change_req = request->data_as_DidChangeTextDocumentNotification();
            if (!change_req) {
                error = "invalid didChange payload";
                return false;
            }
            llvm::json::Object params;
            llvm::json::Object text_document;
            text_document["uri"] = ctx.file_uri;
            text_document["version"] = static_cast<int64_t>(change_req->version());
            params["textDocument"] = std::move(text_document);
            llvm::json::Array content_changes;
            llvm::json::Object single_change;
            single_change["text"] = change_req->content() ? change_req->content()->str() : std::string();
            content_changes.push_back(std::move(single_change));
            params["contentChanges"] = std::move(content_changes);
            root["method"] = "textDocument/didChange";
            append_params(std::move(params));
            break;
        }
        case protocol::Method::DID_CLOSE: {
            auto close_req = request->data_as_DidCloseTextDocumentNotification();
            if (!close_req) {
                error = "invalid didClose payload";
                return false;
            }
            llvm::json::Object params;
            llvm::json::Object text_document;
            text_document["uri"] = ctx.file_uri;
            params["textDocument"] = std::move(text_document);
            root["method"] = "textDocument/didClose";
            append_params(std::move(params));
            break;
        }
        default:
            error = "method not supported for JSON conversion";
            return false;
    }

    out_json = toJsonString(llvm::json::Value(std::move(root)));
    return true;
}

bool JsonRpcConverter::buildResponse(protocol::Method method,
                                      uint64_t request_id,
                                      const std::string& json_body,
                                      std::vector<uint8_t>& out_buffer,
                                      std::string& error) const {
    auto parsed = llvm::json::parse(json_body);
    if (!parsed) {
        error = "failed to parse JSON response";
        LOGE("JSON parse error: %s", llvm::toString(parsed.takeError()).c_str());
        return false;
    }

    const Object* root = parsed->getAsObject();
    if (!root) {
        error = "response root must be object";
        return false;
    }

    protocol::Status status = protocol::Status::SUCCESS;
    std::string error_message;
    const Value* result_value = nullptr;

    if (method == protocol::Method::PUBLISH_DIAGNOSTICS) {
        result_value = root->get("params");
        if (!result_value) {
            error = "diagnostics notification missing params";
            return false;
        }
    } else if (auto error_obj = root->getObject("error")) {
        status = protocol::Status::ERROR;
        if (auto message = error_obj->getString("message")) {
            error_message = message->str();
        }
    } else {
        result_value = root->get("result");
        if (!result_value) {
            error = "response missing result";
            return false;
        }
    }

    flatbuffers::FlatBufferBuilder builder(1024);
    flatbuffers::Offset<void> data_offset = 0;
    protocol::ResponseData data_type = protocol::ResponseData::NONE;

    if (status == protocol::Status::SUCCESS) {
        bool ok = false;
        switch (method) {
            case protocol::Method::HOVER:
                ok = buildHoverResponsePayload(*result_value, builder, data_offset, data_type, error);
                break;
            case protocol::Method::COMPLETION:
                ok = buildCompletionResponsePayload(*result_value, builder, data_offset, data_type, error);
                break;
            case protocol::Method::DEFINITION:
                ok = buildLocationArrayResponse(*result_value, builder, data_offset, data_type, error, true);
                break;
            case protocol::Method::REFERENCES:
                ok = buildReferencesPayload(*result_value, builder, data_offset, data_type, error);
                break;
            case protocol::Method::PUBLISH_DIAGNOSTICS:
                ok = buildDiagnosticsNotificationPayload(*result_value, builder, data_offset, data_type, error);
                break;
            default:
                error = "method not supported for response conversion";
                ok = false;
                break;
        }

        if (!ok) {
            return false;
        }
    }

    auto error_offset = error_message.empty() ? 0 : builder.CreateString(error_message);
    auto response = protocol::CreateResponse(
        builder,
        request_id,
        status,
        method,
        nowMillis(),
        error_offset,
        data_type,
        data_offset,
        false,
        -1,
        0
    );

    builder.Finish(response, protocol::RequestIdentifier());
    out_buffer.assign(builder.GetBufferPointer(), builder.GetBufferPointer() + builder.GetSize());
    return true;
}

} // namespace lsp
} // namespace tinaide
