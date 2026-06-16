package com.wuxianggujun.tinaide.plugin

internal object PluginVersionComparator {
    private val tokenPattern = Regex("""\d+|[A-Za-z]+""")

    fun compare(left: String, right: String): Int? {
        val parsedLeft = parse(left) ?: return null
        val parsedRight = parse(right) ?: return null

        val coreOrder = compareCoreTokens(parsedLeft.coreTokens, parsedRight.coreTokens)
        if (coreOrder != 0) return coreOrder

        return comparePreReleaseTokens(
            parsedLeft.preReleaseTokens,
            parsedRight.preReleaseTokens,
        )
    }

    private fun parse(raw: String): ParsedVersion? {
        val sanitized = raw.trim()
            .substringBefore('+')
            .takeIf { it.isNotBlank() }
            ?: return null

        val parts = sanitized.split('-', limit = 2)
        val coreTokens = tokenize(parts.first())
        if (coreTokens.none { it.numericValue != null }) return null

        val preReleaseTokens = parts.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let(::tokenize)
            .orEmpty()

        return ParsedVersion(
            coreTokens = coreTokens,
            preReleaseTokens = preReleaseTokens,
        )
    }

    private fun tokenize(raw: String): List<VersionToken> = tokenPattern.findAll(raw)
        .map { token ->
            VersionToken(
                raw = token.value,
                numericValue = token.value.toLongOrNull(),
            )
        }
        .toList()

    private fun compareCoreTokens(
        left: List<VersionToken>,
        right: List<VersionToken>,
    ): Int {
        val maxSize = maxOf(left.size, right.size)
        for (index in 0 until maxSize) {
            val order = compareCoreToken(
                left = left.getOrNull(index),
                right = right.getOrNull(index),
            )
            if (order != 0) return order
        }
        return 0
    }

    private fun compareCoreToken(
        left: VersionToken?,
        right: VersionToken?,
    ): Int {
        if (left == null && right == null) return 0
        if (left == null) {
            return when (val rightNumber = right?.numericValue) {
                null -> -1
                0L -> 0
                else -> -1
            }
        }
        if (right == null) {
            return when (val leftNumber = left.numericValue) {
                null -> 1
                0L -> 0
                else -> 1
            }
        }
        return compareTokenValues(
            left = left,
            right = right,
            numericIsLowerThanAlpha = false,
        )
    }

    private fun comparePreReleaseTokens(
        left: List<VersionToken>,
        right: List<VersionToken>,
    ): Int {
        if (left.isEmpty() && right.isEmpty()) return 0
        if (left.isEmpty()) return 1
        if (right.isEmpty()) return -1

        val maxSize = maxOf(left.size, right.size)
        for (index in 0 until maxSize) {
            val leftToken = left.getOrNull(index)
            val rightToken = right.getOrNull(index)
            if (leftToken == null && rightToken == null) break
            if (leftToken == null) return -1
            if (rightToken == null) return 1

            val order = compareTokenValues(
                left = leftToken,
                right = rightToken,
                numericIsLowerThanAlpha = true,
            )
            if (order != 0) return order
        }
        return 0
    }

    private fun compareTokenValues(
        left: VersionToken,
        right: VersionToken,
        numericIsLowerThanAlpha: Boolean,
    ): Int {
        val leftNumber = left.numericValue
        val rightNumber = right.numericValue
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> if (numericIsLowerThanAlpha) -1 else 1
            rightNumber != null -> if (numericIsLowerThanAlpha) 1 else -1
            else -> left.raw.compareTo(right.raw, ignoreCase = true)
        }
    }

    private data class ParsedVersion(
        val coreTokens: List<VersionToken>,
        val preReleaseTokens: List<VersionToken>,
    )

    private data class VersionToken(
        val raw: String,
        val numericValue: Long?,
    )
}
