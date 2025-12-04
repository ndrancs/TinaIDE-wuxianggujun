// ProtocolHandler - LSP 二进制协议处理器
// 负责 FlatBuffers 序列化/反序列化和协议转换

#ifndef TINAIDE_PROTOCOL_HANDLER_H
#define TINAIDE_PROTOCOL_HANDLER_H

#include "lsp_protocol_generated.h"
#include <vector>
#include <string>
#include <memory>
#include <optional>
#include <cstdint>

namespace tinaide {
namespace lsp {

/**
 * 协议处理器
 *
 * 职责：
 * 1. 将高层请求对象序列化为 FlatBuffers 二进制格式
 * 2. 将 FlatBuffers 二进制数据反序列化为响应对象
 * 3. 提供类型安全的 API
 * 4. 处理协议版本兼容性
 */
class ProtocolHandler {
public:
    ProtocolHandler() = default;
    ~ProtocolHandler() = default;

    // ========================================================================
    // 请求构建与序列化
    // ========================================================================

    /**
     * 构建 Hover 请求
     * @param request_id 唯一请求 ID
     * @param file_id 文件 ID
     * @param line 行号
     * @param character 列号
     * @param file_version 文件版本号
     * @return 序列化后的二进制数据
     */
    std::vector<uint8_t> buildHoverRequest(
        uint64_t request_id,
        uint32_t file_id,
        uint32_t line,
        uint32_t character,
        uint32_t file_version
    );

    /**
     * 构建 Completion 请求
     */
    std::vector<uint8_t> buildCompletionRequest(
        uint64_t request_id,
        uint32_t file_id,
        uint32_t line,
        uint32_t character,
        uint32_t file_version,
        uint8_t trigger_kind = 1,
        const std::string& trigger_character = ""
    );

    /**
     * 构建 Definition 请求
     */
    std::vector<uint8_t> buildDefinitionRequest(
        uint64_t request_id,
        uint32_t file_id,
        uint32_t line,
        uint32_t character,
        uint32_t file_version
    );

    /**
     * 构建 References 请求
     */
    std::vector<uint8_t> buildReferencesRequest(
        uint64_t request_id,
        uint32_t file_id,
        uint32_t line,
        uint32_t character,
        uint32_t file_version,
        bool include_declaration = true
    );

    std::vector<uint8_t> buildDidOpenNotification(
        uint64_t request_id,
        uint32_t file_id,
        const std::string& file_uri,
        const std::string& language_id,
        uint32_t version,
        const std::string& content
    );

    std::vector<uint8_t> buildDidChangeNotification(
        uint64_t request_id,
        uint32_t file_id,
        uint32_t version,
        const std::string& content
    );

    std::vector<uint8_t> buildDidCloseNotification(
        uint64_t request_id,
        uint32_t file_id
    );

    /**
     * 构建取消请求
     */
    std::vector<uint8_t> buildCancelRequest(uint64_t request_id);

    // ========================================================================
    // 响应解析
    // ========================================================================

    /**
     * 解析通用响应
     * @param data 二进制数据
     * @return 响应对象（如果解析成功）
     */
    std::optional<const protocol::Response*> parseResponse(const std::vector<uint8_t>& data);

    /**
     * 解析 Hover 响应
     */
    struct HoverResult {
        std::string content;         // Markdown 内容
        uint32_t start_line;         // 范围起始行
        uint32_t start_character;    // 范围起始列
        uint32_t end_line;           // 范围结束行
        uint32_t end_character;      // 范围结束列
        bool has_content;            // 是否有内容
    };

    std::optional<HoverResult> parseHoverResponse(const protocol::Response* response);

    /**
     * 解析 Completion 响应
     */
    struct CompletionItem {
        std::string label;
        std::string detail;
        std::string insert_text;
        std::string documentation;
        uint8_t kind;
        bool deprecated;
    };

    struct CompletionResult {
        std::vector<CompletionItem> items;
        bool is_incomplete;
    };

    std::optional<CompletionResult> parseCompletionResponse(const protocol::Response* response);

    /**
     * 解析 Definition 响应
     */
    struct Location {
        std::string file_path;
        uint32_t start_line;
        uint32_t start_character;
        uint32_t end_line;
        uint32_t end_character;
    };

    std::optional<std::vector<Location>> parseDefinitionResponse(const protocol::Response* response);

    /**
     * 解析 References 响应
     */
    std::optional<std::vector<Location>> parseReferencesResponse(const protocol::Response* response);

    struct Diagnostic {
        uint32_t start_line;
        uint32_t start_character;
        uint32_t end_line;
        uint32_t end_character;
        uint8_t severity;
        std::string message;
        std::string source;
        std::string code;
    };

    struct DiagnosticsResult {
        std::string file_uri;
        uint32_t version;
        std::vector<Diagnostic> diagnostics;
    };

    std::optional<DiagnosticsResult> parseDiagnosticsResponse(const protocol::Response* response);

    // ========================================================================
    // 工具方法
    // ========================================================================

    /**
     * 验证数据格式是否有效
     */
    bool isValidRequest(const std::vector<uint8_t>& data) const;
    bool isValidResponse(const std::vector<uint8_t>& data) const;

    /**
     * 获取请求/响应的 ID
     */
    std::optional<uint64_t> getRequestId(const std::vector<uint8_t>& data) const;
    std::optional<uint64_t> getResponseId(const std::vector<uint8_t>& data) const;

    /**
     * 获取方法类型
     */
    std::optional<protocol::Method> getMethod(const std::vector<uint8_t>& data) const;

private:
    // FlatBuffers builder（复用以减少内存分配）
    flatbuffers::FlatBufferBuilder builder_{1024};

    // 辅助方法
    void resetBuilder();
};

} // namespace lsp
} // namespace tinaide

#endif // TINAIDE_PROTOCOL_HANDLER_H
