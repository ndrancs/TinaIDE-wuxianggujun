#ifndef TINAIDE_JSON_RPC_CONVERTER_H
#define TINAIDE_JSON_RPC_CONVERTER_H

#include <string>
#include <vector>
#include "lsp_protocol_generated.h"

namespace tinaide {
namespace lsp {

struct RequestContext {
    std::string file_uri;
    std::string language_id;
};

class JsonRpcConverter {
public:
    JsonRpcConverter() = default;

    bool buildJsonRequest(const protocol::Request* request,
                          const RequestContext& ctx,
                          std::string& out_json,
                          std::string& error) const;

    bool buildResponse(protocol::Method method,
                       uint64_t request_id,
                       const std::string& json_body,
                       std::vector<uint8_t>& out_buffer,
                       std::string& error) const;
};

} // namespace lsp
} // namespace tinaide

#endif // TINAIDE_JSON_RPC_CONVERTER_H
