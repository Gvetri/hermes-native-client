package org.hermesnative.client.feature.entry.presentation

/**
 * A Gateway message rendered as a closed set of inert blocks. The model has no node
 * that can carry executable content: every span is literal text, and [MarkdownSpan.link]
 * only ever holds an allowed HTTPS destination.
 */
internal sealed interface MarkdownBlock {
    data class Paragraph(
        val spans: List<MarkdownSpan>,
    ) : MarkdownBlock

    data class BulletList(
        val items: List<List<MarkdownSpan>>,
    ) : MarkdownBlock

    data class NumberedList(
        val items: List<NumberedItem>,
    ) : MarkdownBlock

    data class CodeBlock(
        val language: String?,
        val code: String,
    ) : MarkdownBlock
}

/**
 * One ordered item with the ordinal its source authored. The renderer shows that marker as
 * written, so numbering the content never had is never invented.
 */
internal data class NumberedItem(
    val marker: String,
    val spans: List<MarkdownSpan>,
)

/**
 * One run of literal message text. [link] is set only for a destination that
 * [allowedExternalLinkDestination] accepts; every other link target stays as its
 * inert label text.
 */
internal data class MarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val link: String? = null,
)

/**
 * The HTTPS destination an external browser intent may be built for, or null when the
 * destination is not an absolute HTTPS URL. Only this function decides what a link may
 * carry out of the client, so no parser or renderer path can widen it.
 */
internal fun allowedExternalLinkDestination(destination: String): String? {
    val candidate = destination.trim()
    if (candidate.length > MAX_LINK_LENGTH) return null
    if (candidate.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
    if (!candidate.startsWith(HTTPS_SCHEME, ignoreCase = true)) return null
    val authority = candidate.drop(HTTPS_SCHEME.length).takeWhile { it !in "/?#" }
    if (authority.isEmpty()) return null
    return candidate
}

private const val HTTPS_SCHEME = "https://"

private const val MAX_LINK_LENGTH = 2_048

private const val BOLD_DELIMITER = "**"
private const val ITALIC_DELIMITER = "*"
private const val CODE_DELIMITER = "`"
private const val CODE_FENCE = "```"
private const val IMAGE_PREFIX = "!["
private const val LINK_OPEN = "["

/**
 * Parses the supported Markdown subset of untrusted Gateway content: paragraphs,
 * lists, emphasis, HTTPS links, and fenced code blocks. Unsupported constructs stay
 * literal text, so nothing in the source can become executable content.
 */
internal fun parseSafeMarkdown(content: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = content.lines()
    val paragraphLines = mutableListOf<String>()

    fun flushParagraph() {
        if (paragraphLines.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(parseInline(paragraphLines.joinToString(separator = " ")))
            paragraphLines.clear()
        }
    }

    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        when {
            isFenceLine(line) -> {
                flushParagraph()
                val code = mutableListOf<String>()
                val openingFenceLength = fenceLength(line)
                index++
                while (index < lines.size && !isClosingFence(lines[index], openingFenceLength)) {
                    code += lines[index]
                    index++
                }
                val terminated = index < lines.size
                if (terminated) index++
                if (!terminated) {
                    while (code.isNotEmpty() && code.last().isEmpty()) code.removeLast()
                }
                blocks += MarkdownBlock.CodeBlock(language = fenceLanguage(line), code = code.joinToString("\n"))
            }
            line.isBlank() -> {
                flushParagraph()
                index++
            }
            bulletItem(line) != null -> {
                flushParagraph()
                val (items, nextIndex) = readListItemRun(lines, index, ::bulletItem)
                blocks += MarkdownBlock.BulletList(items)
                index = nextIndex
            }
            numberedItem(line) != null -> {
                flushParagraph()
                val (items, nextIndex) = readListItemRun(lines, index, ::numberedItem)
                blocks += MarkdownBlock.NumberedList(items)
                index = nextIndex
            }
            else -> {
                paragraphLines += line.trim()
                index++
            }
        }
    }
    flushParagraph()
    return blocks
}

private val NUMBERED_ITEM = Regex("""^[0-9]{1,9}\.\s+""")

private fun bulletItem(line: String): List<MarkdownSpan>? {
    val trimmed = line.trimStart()
    if (!trimmed.startsWith("- ") && !trimmed.startsWith("* ")) return null
    return parseInline(trimmed.drop(2).trim())
}

private fun numberedItem(line: String): NumberedItem? {
    val trimmed = line.trimStart()
    val match = NUMBERED_ITEM.find(trimmed) ?: return null
    return NumberedItem(
        marker = match.value.trim(),
        spans = parseInline(trimmed.drop(match.value.length).trim()),
    )
}

private fun <T> readListItemRun(
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
    if (contentEnd <= contentStart) return null
    return contentStart until contentEnd
}

private fun parseInline(text: String): List<MarkdownSpan> {
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
    if (linkBrackets == null || !text.startsWith(LINK_OPEN, index)) return null
    if (labelEnd < 0) return null
    val destinationStart = labelEnd + 2
    if (destinationStart >= text.length || text[labelEnd + 1] != '(') return null

    val endIndex = linkBrackets.destinationEnds[labelEnd + 1]
    if (endIndex < 0) return null
    return LinkMatch(
        label = text.substring(index + 1, labelEnd),
        destination = text.substring(destinationStart, endIndex - 1),
        endIndex = endIndex,
    )
}

private fun fenceLength(line: String): Int = line.trimStart().takeWhile { it == '`' }.length

private fun isFenceLine(line: String): Boolean = fenceLength(line) >= CODE_FENCE.length

private fun isClosingFence(
    line: String,
    openingFenceLength: Int,
): Boolean {
    val trimmed = line.trim()
    val closingFenceLength = fenceLength(trimmed)
    return closingFenceLength >= openingFenceLength && trimmed.drop(closingFenceLength).isEmpty()
}

private fun fenceLanguage(line: String): String? {
    return line.trimStart().drop(fenceLength(line)).trim().substringBefore(' ').ifEmpty { null }
}
