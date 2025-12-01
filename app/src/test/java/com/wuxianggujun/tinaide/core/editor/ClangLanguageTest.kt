package com.wuxianggujun.tinaide.core.editor

import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.junit.Assert.assertEquals
import org.junit.Test

class ClangLanguageTest {

    @Test
    fun buildStyles_highlightsTokenRanges() {
        val text = "int main() {\n    return value;\n}"
        val styles = buildStylesForText(
            text,
            listOf(
                lexemeToken(text, "int", TokenType.TYPE),
                lexemeToken(text, "main", TokenType.FUNCTION),
                lexemeToken(text, "return", TokenType.KEYWORD)
            )
        )

        val reader = styles.spans.read()

        val line0 = reader.getSpansOnLine(0)
        assertEquals(EditorColorScheme.LITERAL, TextStyle.getForegroundColorId(line0[0].style))
        assertEquals(0, line0[0].column)
        assertEquals(EditorColorScheme.TEXT_NORMAL, TextStyle.getForegroundColorId(line0[1].style))
        assertEquals(3, line0[1].column)
        assertEquals(EditorColorScheme.FUNCTION_NAME, TextStyle.getForegroundColorId(line0[2].style))
        assertEquals(4, line0[2].column)
        assertEquals(EditorColorScheme.TEXT_NORMAL, TextStyle.getForegroundColorId(line0[3].style))
        assertEquals(8, line0[3].column)

        val line1 = reader.getSpansOnLine(1)
        assertEquals(EditorColorScheme.TEXT_NORMAL, TextStyle.getForegroundColorId(line1[0].style))
        assertEquals(0, line1[0].column)
        assertEquals(EditorColorScheme.KEYWORD, TextStyle.getForegroundColorId(line1[1].style))
        assertEquals(4, line1[1].column)
        assertEquals(EditorColorScheme.TEXT_NORMAL, TextStyle.getForegroundColorId(line1[2].style))
        assertEquals(10, line1[2].column)
    }

    @Test
    fun buildStyles_handlesMultilineTokens() {
        val text = "/* comment line 1\ncomment line 2 */\nint value;"
        val commentStart = text.indexOf("/*")
        val commentEnd = text.indexOf("*/") + 2
        val styles = buildStylesForText(
            text,
            listOf(
                SemanticToken(commentStart, commentEnd - commentStart, TokenType.COMMENT)
            )
        )

        val reader = styles.spans.read()

        val firstLine = reader.getSpansOnLine(0)
        val firstLineLength = text.substringBefore('\n').length
        assertEquals(EditorColorScheme.COMMENT, TextStyle.getForegroundColorId(firstLine[0].style))
        assertEquals(0, firstLine[0].column)
        assertEquals(EditorColorScheme.TEXT_NORMAL, TextStyle.getForegroundColorId(firstLine[1].style))
        assertEquals(firstLineLength, firstLine[1].column)

        val secondLine = reader.getSpansOnLine(1)
        val secondLineText = text.substringAfter('\n').substringBefore('\n')
        val commentEndColumn = secondLineText.indexOf("*/") + 2
        assertEquals(EditorColorScheme.COMMENT, TextStyle.getForegroundColorId(secondLine[0].style))
        assertEquals(0, secondLine[0].column)
        assertEquals(EditorColorScheme.TEXT_NORMAL, TextStyle.getForegroundColorId(secondLine[1].style))
        assertEquals(commentEndColumn, secondLine[1].column)

        val thirdLine = reader.getSpansOnLine(2)
        assertEquals(EditorColorScheme.TEXT_NORMAL, TextStyle.getForegroundColorId(thirdLine[0].style))
        assertEquals(0, thirdLine[0].column)
    }

    private fun buildStylesForText(text: String, tokens: List<SemanticToken>): Styles {
        val content = Content(text, false)
        val reference = ContentReference(content)
        val language = ClangLanguage("", "test.cpp", "", emptyList())
        val manager = language.ClangAnalyzeManager()

        val method = manager.javaClass.getDeclaredMethod(
            "buildStyles",
            ContentReference::class.java,
            java.util.List::class.java
        )
        method.isAccessible = true

        return try {
            method.invoke(manager, reference, tokens) as Styles
        } finally {
            manager.destroy()
        }
    }

    private fun lexemeToken(text: String, lexeme: String, type: Int): SemanticToken {
        val offset = text.indexOf(lexeme)
        require(offset >= 0) { "Lexeme '$lexeme' not found in text" }
        return SemanticToken(offset, lexeme.length, type)
    }
}
