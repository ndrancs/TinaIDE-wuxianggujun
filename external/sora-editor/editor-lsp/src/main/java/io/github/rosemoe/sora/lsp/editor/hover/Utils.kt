/*******************************************************************************
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2025  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 ******************************************************************************/

package io.github.rosemoe.sora.lsp.editor.hover

import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.jsonrpc.messages.Either

fun Hover.hasContent(): Boolean {
    return renderMarkdownContent().isNotBlank()
}

fun Hover.renderMarkdownContent(): String {
    val hoverContents = contents ?: return ""
    return if (hoverContents.isLeft) {
        formatLegacyHoverList(hoverContents.left)
    } else {
        formatMarkupContent(hoverContents.right).orEmpty()
    }
}

private fun formatLegacyHoverList(items: List<*>?): String {
    val legacyItems = extractLegacyEntries(items)
    if (legacyItems.isEmpty()) {
        return ""
    }
    return legacyItems.mapNotNull { entry ->
        if (entry.isLeft) {
            entry.left
        } else {
            entry.extractLegacyMarkdown()
        }
    }.filter { it.isNotBlank() }
        .joinToString("\n\n")
}

private fun extractLegacyEntries(items: List<*>?): List<Either<String, *>> {
    if (items.isNullOrEmpty()) {
        return emptyList()
    }
    return items.filterIsInstance<Either<String, *>>()
}

private fun Either<String, *>.extractLegacyMarkdown(): String? {
    val rawValue = rawRightValue() ?: return null
    if (rawValue is CharSequence) {
        val text = rawValue.toString()
        return text.ifBlank { null }
    }
    val content = rawValue.readMarkedStringProperty("value")?.takeIf { it.isNotBlank() } ?: return null
    val language = rawValue.readMarkedStringProperty("language")
    return if (language.isNullOrBlank()) {
        content
    } else {
        "```$language\n$content\n```"
    }
}

@Suppress("UNCHECKED_CAST")
private fun Either<String, *>.rawRightValue(): Any? {
    return (this as? Either<String, Any?>)?.right
}

private fun Any.readMarkedStringProperty(propertyName: String): String? {
    val getterName = buildGetterName(propertyName)
    return runCatching {
        javaClass.getMethod(getterName).invoke(this) as? String
    }.getOrNull() ?: runCatching {
        javaClass.getDeclaredField(propertyName).apply { isAccessible = true }.get(this) as? String
    }.getOrNull()
}

private fun buildGetterName(propertyName: String): String {
    if (propertyName.isEmpty()) {
        return "get"
    }
    val first = propertyName[0]
    val suffix = if (propertyName.length > 1) propertyName.substring(1) else ""
    return buildString(propertyName.length + 3) {
        append("get")
        append(first.uppercaseChar())
        append(suffix)
    }
}

fun formatMarkupContent(markupContent: MarkupContent?): String? {
    return markupContent?.value
}
