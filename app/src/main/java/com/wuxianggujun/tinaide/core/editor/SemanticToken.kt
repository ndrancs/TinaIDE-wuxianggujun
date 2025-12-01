package com.wuxianggujun.tinaide.core.editor

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 语义 Token 数据类
 * @param offset 字符偏移量
 * @param length Token 长度
 * @param type Token 类型 (见 TokenType)
 */
data class SemanticToken(
    val offset: Int,
    val length: Int,
    val type: Int
)

/**
 * Token 类型常量
 * 与 native_compiler.cpp 中的 SemanticTokenType 枚举保持一致
 */
object TokenType {
    const val KEYWORD = 0
    const val TYPE = 1
    const val FUNCTION = 2
    const val VARIABLE = 3
    const val PARAMETER = 4
    const val MEMBER = 5
    const val MACRO = 6
    const val STRING = 7
    const val NUMBER = 8
    const val COMMENT = 9
    const val OPERATOR = 10
    const val NAMESPACE = 11
    const val CLASS = 12
    const val ENUM = 13
    const val ENUM_MEMBER = 14
}

/**
 * JSON 解析工具
 */
object SemanticTokenParser {
    private val gson = Gson()
    
    /**
     * 解析 getSemanticTokens 返回的 JSON
     * JSON 格式: [{"o":offset,"l":length,"t":type}, ...]
     */
    fun parse(json: String): List<SemanticToken> {
        if (json.isBlank() || json == "[]") {
            return emptyList()
        }
        
        return try {
            // 使用简化的 JSON 键名
            data class RawToken(val o: Int, val l: Int, val t: Int)
            
            val type = object : TypeToken<List<RawToken>>() {}.type
            val rawTokens: List<RawToken> = gson.fromJson(json, type)
            
            rawTokens.map { SemanticToken(it.o, it.l, it.t) }
        } catch (e: Exception) {
            android.util.Log.e("SemanticTokenParser", "Failed to parse tokens: ${e.message}")
            emptyList()
        }
    }
}
