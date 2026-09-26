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

/** A conservative bound for a destination carried in an intent; longer targets stay inert. */
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
                index++
                while (index < lines.size && !isFenceLine(lines[index])) {
                    code += lines[index]
                    index++
                }
                val terminated = index < lines.size
                if (terminated) index++
                if (!terminated) {
                    // A response still streaming its code block cannot have a closing
                    // fence yet; the newline that ended the message is not code.
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

/** The parsed spans of a bullet line, or null when the line is not a bullet item. */
private fun bulletItem(line: String): List<MarkdownSpan>? {
    val trimmed = line.trimStart()
    if (!trimmed.startsWith("- ") && !trimmed.startsWith("* ")) return null
    return parseInline(trimmed.drop(2).trim())
}

/** The ordered item of a numbered line, or null when the line is not a numbered item. */
private fun numberedItem(line: String): NumberedItem? {
    val trimmed = line.trimStart()
    val match = NUMBERED_ITEM.find(trimmed) ?: return null
    return NumberedItem(
        marker = match.value.trim(),
        spans = parseInline(trimmed.drop(match.value.length).trim()),
    )
}

/** The consecutive list items starting at [startIndex], with the index that follows them. */
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

/**
 * The content range of a delimiter pair that starts at [index], or null when the
 * delimiter is unpaired or empty and must therefore stay literal text.
 */
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

/**
 * Parses the inline constructs of one text run. An emphasised range is parsed in turn, so
 * emphasis, code, and links nested inside it render natively instead of leaking their markers
 * as literal text. Nesting stays shallow by construction: a delimiter pair closes at the
 * nearest delimiter of its own kind, so a range can hold at most one inner pair of the other
 * delimiter, and code spans are never parsed further.
 */
private fun parseInline(text: String): List<MarkdownSpan> {
    val spans = mutableListOf<MarkdownSpan>()
    val literal = StringBuilder()

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
                // An image is not a supported construct: it stays inert source text
                // instead of turning into a link that carries the image target.
                val image = linkAt(text, index + IMAGE_PREFIX.length)
                val end = image?.endIndex ?: index + IMAGE_PREFIX.length
                literal.append(text, index, end)
                index = end
            }
            text.startsWith(LINK_OPEN, index) -> {
                val link = linkAt(text, index)
                if (link == null) {
                    literal.append(text[index])
                    index++
                } else {
                    flushLiteral()
                    val destination = allowedExternalLinkDestination(link.destination)
                    // A blocked destination leaves the label inert, with no link annotation.
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

/**
 * The link construct that starts at [index], or null when the brackets or the
 * parentheses are unpaired and the text must stay literal. The destination scan
 * balances parentheses, so a `)` inside the target cannot end it early.
 */
private fun linkAt(
    text: String,
    index: Int,
): LinkMatch? {
    if (!text.startsWith(LINK_OPEN, index)) return null
    val labelEnd = text.indexOf(']', startIndex = index + 1)
    if (labelEnd < 0) return null
    val destinationStart = labelEnd + 2
    if (destinationStart >= text.length || text[labelEnd + 1] != '(') return null

    var depth = 1
    var cursor = destinationStart
    while (cursor < text.length && depth > 0) {
        when (text[cursor]) {
            '(' -> depth++
            ')' -> depth--
        }
        cursor++
    }
    if (depth != 0) return null
    return LinkMatch(
        label = text.substring(index + 1, labelEnd),
        destination = text.substring(destinationStart, cursor - 1),
        endIndex = cursor,
    )
}

/** Whether the line opens or closes a fenced code block. */
private fun isFenceLine(line: String): Boolean = line.trimStart().startsWith(CODE_FENCE)

/** The optional info string of a fence line, which names the code language. */
private fun fenceLanguage(line: String): String? = line.trimStart().drop(CODE_FENCE.length).trim().substringBefore(' ').ifEmpty { null }
