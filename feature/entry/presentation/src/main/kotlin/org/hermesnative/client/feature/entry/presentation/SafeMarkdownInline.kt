package org.hermesnative.client.feature.entry.presentation

private const val BOLD_DELIMITER = "**"
private const val ITALIC_DELIMITER = "*"
private const val CODE_DELIMITER = "`"
private const val IMAGE_PREFIX = "!["
private const val LINK_OPEN = "["

private val NUMBERED_ITEM = Regex("""^[0-9]{1,9}\.\s+""")

internal fun bulletItem(line: String): List<MarkdownSpan>? {
    val trimmed = line.trimStart()
    if (!trimmed.startsWith("- ") && !trimmed.startsWith("* ")) return null
    return parseInline(trimmed.drop(2).trim())
}

internal fun numberedItem(line: String): NumberedItem? {
    val trimmed = line.trimStart()
    val match = NUMBERED_ITEM.find(trimmed) ?: return null
    return NumberedItem(
        marker = match.value.trim(),
        spans = parseInline(trimmed.drop(match.value.length).trim()),
    )
}

internal fun <T> readListItemRun(
    lines: List<String>,
    startIndex: Int,
    item: (String) -> T?,
): Pair<List<T>, Int> {
    val items = mutableListOf<T>()
    var index = startIndex
    while (index < lines.size) {
        val parsed = item(lines[index]) ?: break
        items += parsed
        index++
    }
    return items to index
}

private fun emphasisedRange(
    text: String,
    index: Int,
    delimiter: String,
): IntRange? {
    if (!text.startsWith(delimiter, index)) return null
    val contentStart = index + delimiter.length
    val contentEnd = text.indexOf(delimiter, startIndex = contentStart)
    return if (contentEnd > contentStart) contentStart until contentEnd else null
}

internal fun parseInline(text: String): List<MarkdownSpan> {
    val spans = mutableListOf<MarkdownSpan>()
    val literal = StringBuilder()
    val linkBrackets = if (text.indexOf(LINK_OPEN) >= 0) LinkBrackets(text) else null

    fun flushLiteral() {
        if (literal.isNotEmpty()) {
            spans += MarkdownSpan(text = literal.toString())
            literal.clear()
        }
    }

    var index = 0
    while (index < text.length) {
        val bold = emphasisedRange(text, index, BOLD_DELIMITER)
        val code = emphasisedRange(text, index, CODE_DELIMITER)
        val italic = emphasisedRange(text, index, ITALIC_DELIMITER)
        when {
            text.startsWith(IMAGE_PREFIX, index) -> {
                val labelBracket = index + 1
                val labelEnd = linkBrackets?.matchingLabelEnds?.get(labelBracket) ?: -1
                val image = linkAt(text, labelBracket, linkBrackets, labelEnd)
                val end = image?.endIndex ?: index + IMAGE_PREFIX.length
                literal.append(text, index, end)
                index = end
            }
            text.startsWith(LINK_OPEN, index) -> {
                val link = linkAt(text, index, linkBrackets)
                if (link == null) {
                    literal.append(text[index])
                    index++
                } else {
                    flushLiteral()
                    val destination = allowedExternalLinkDestination(link.destination)
                    spans += parseInline(link.label).map { span -> span.copy(link = destination) }
                    index = link.endIndex
                }
            }
            bold != null -> {
                flushLiteral()
                spans += parseInline(text.substring(bold)).map { span -> span.copy(bold = true) }
                index = bold.last + 1 + BOLD_DELIMITER.length
            }
            code != null -> {
                flushLiteral()
                spans += MarkdownSpan(text = text.substring(code), code = true)
                index = code.last + 1 + CODE_DELIMITER.length
            }
            italic != null -> {
                flushLiteral()
                spans += parseInline(text.substring(italic)).map { span -> span.copy(italic = true) }
                index = italic.last + 1 + ITALIC_DELIMITER.length
            }
            else -> {
                literal.append(text[index])
                index++
            }
        }
    }
    flushLiteral()
    return spans
}

private data class LinkMatch(
    val label: String,
    val destination: String,
    val endIndex: Int,
)

private class LinkBrackets(
    text: String,
) {
    /** The closing bracket that ends a label opened at each position, or -1 when there is none. */
    val labelEnds = IntArray(text.length)

    /**
     * The closing bracket that the bracket opened at each position balances against, counting the
     * brackets inside it, or -1 when they never balance. A label holding a link of its own has a
     * nearer closing bracket, so only this pairing ends the whole construct.
     */
    val matchingLabelEnds = IntArray(text.length) { -1 }

    /** The index just past the destination opened at each position, or -1 when it never closes. */
    val destinationEnds = IntArray(text.length) { -1 }

    init {
        var nextClosingBracket = -1
        for (position in text.lastIndex downTo 0) {
            if (text[position] == ']') nextClosingBracket = position
            labelEnds[position] = nextClosingBracket
        }

        val freeClosings = IntArray(text.length)
        var freeCount = 0
        for (position in text.lastIndex downTo 0) {
            when (text[position]) {
                ']' -> freeClosings[freeCount++] = position
                '[' -> if (freeCount > 0) matchingLabelEnds[position] = freeClosings[--freeCount]
            }
        }

        val unmatchedClosings = IntArray(text.length)
        var unmatchedCount = 0
        for (position in text.lastIndex downTo 0) {
            when (text[position]) {
                ')' -> unmatchedClosings[unmatchedCount++] = position
                '(' -> if (unmatchedCount > 0) destinationEnds[position] = unmatchedClosings[--unmatchedCount] + 1
            }
        }
    }
}

private fun linkAt(
    text: String,
    index: Int,
    linkBrackets: LinkBrackets?,
    labelEnd: Int = linkBrackets?.labelEnds?.get(index) ?: -1,
): LinkMatch? {
    val destinationStart = labelEnd + 2
    return when {
        linkBrackets == null || !text.startsWith(LINK_OPEN, index) -> null
        labelEnd < 0 -> null
        destinationStart >= text.length || text[labelEnd + 1] != '(' -> null
        linkBrackets.destinationEnds[labelEnd + 1] < 0 -> null
        else -> {
            val endIndex = linkBrackets.destinationEnds[labelEnd + 1]
            LinkMatch(
                label = text.substring(index + 1, labelEnd),
                destination = text.substring(destinationStart, endIndex - 1),
                endIndex = endIndex,
            )
        }
    }
}
