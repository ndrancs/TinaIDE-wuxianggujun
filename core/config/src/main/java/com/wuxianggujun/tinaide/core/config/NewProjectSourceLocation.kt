package com.wuxianggujun.tinaide.core.config

enum class NewProjectSourceLocation(val value: String) {
    PUBLIC("public"),
    PRIVATE("private");

    companion object {
        fun fromValue(raw: String?): NewProjectSourceLocation = entries.firstOrNull { it.value == raw } ?: PUBLIC
    }
}
