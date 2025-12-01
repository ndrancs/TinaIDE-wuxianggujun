package com.wuxianggujun.tinaide.core.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticTokenParserTest {

    @Test
    fun parse_validJson_returnsTokens() {
        val json = """[{"o":0,"l":4,"t":1},{"o":5,"l":3,"t":2}]"""
        val tokens = SemanticTokenParser.parse(json)

        assertEquals(2, tokens.size)
        assertEquals(0, tokens[0].offset)
        assertEquals(4, tokens[0].length)
        assertEquals(1, tokens[0].type)
        assertEquals(5, tokens[1].offset)
        assertEquals(3, tokens[1].length)
        assertEquals(2, tokens[1].type)
    }

    @Test
    fun parse_invalidJson_returnsEmptyList() {
        val tokens = SemanticTokenParser.parse("{invalid")
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun parse_blankInput_returnsEmptyList() {
        val tokens = SemanticTokenParser.parse("   ")
        assertTrue(tokens.isEmpty())
    }
}
