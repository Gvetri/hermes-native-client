package org.hermesnative.client.feature.entry.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Renders the supported Markdown subset of untrusted Gateway content as native Compose
 * text: paragraphs, lists, emphasis, HTTPS links, and fenced code blocks. The content is
 * never interpreted as markup beyond that subset, so no HTML, script, or other executable
 * content can run, and no WebView is involved.
 *
 * Link destinations are carried as annotations built from [allowedExternalLinkDestination];
 * a blocked destination is rendered as its inert label and cannot be selected.
 */
@Composable
internal fun MarkdownContent(
    markdown: String,
    actions: MessageActions,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalHermesDesignTokens.current.spacing
    val blocks = remember(markdown) { parseSafeMarkdown(markdown) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing.s),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> MarkdownParagraph(spans = block.spans, actions = actions)
                is MarkdownBlock.BulletList ->
                    MarkdownList(
                        items = block.items.map { item -> RenderedItem(marker = BULLET_MARKER, spans = item) },
                        actions = actions,
                    )
                is MarkdownBlock.NumberedList ->
                    MarkdownList(
                        items =
                            block.items.map { item ->
                                RenderedItem(marker = item.marker, spans = item.spans)
                            },
                        actions = actions,
                    )
                is MarkdownBlock.CodeBlock -> MarkdownCodeBlock(block = block, onCopy = actions.copy)
            }
        }
    }
}

@Composable
private fun MarkdownParagraph(
    spans: List<MarkdownSpan>,
    actions: MessageActions,
) {
    Text(
        text = spans.toAnnotatedString(linkColor = MaterialTheme.colorScheme.primary, onOpenLink = actions.openLink),
    )
}

@Composable
private fun MarkdownList(
    items: List<RenderedItem>,
    actions: MessageActions,
) {
    val spacing = LocalHermesDesignTokens.current.spacing
    val linkColor = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                Text(text = item.marker)
                Text(
                    text = item.spans.toAnnotatedString(linkColor = linkColor, onOpenLink = actions.openLink),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One list row: the authored ordinal or the bullet style, plus the item content. */
private data class RenderedItem(
    val marker: String,
    val spans: List<MarkdownSpan>,
)

private const val BULLET_MARKER = "•"

/**
 * A fenced code block: the code is literal text in a monospace face that scrolls
 * horizontally instead of wrapping, so long lines stay readable, and the explicit Copy
 * action carries exactly the code of this block.
 */
@Composable
private fun MarkdownCodeBlock(
    block: MarkdownBlock.CodeBlock,
    onCopy: (String) -> Unit,
) {
    val spacing = LocalHermesDesignTokens.current.spacing
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                )
                .padding(spacing.s),
        verticalArrangement = Arrangement.spacedBy(spacing.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onCopy(block.code) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = "Copy code")
            }
        }
        Text(
            text = block.code,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
        )
    }
}

private fun List<MarkdownSpan>.toAnnotatedString(
    linkColor: Color,
    onOpenLink: (String) -> Unit,
): AnnotatedString =
    buildAnnotatedString {
        this@toAnnotatedString.forEach { span ->
            val start = length
            append(span.text)
            val end = length
            if (start == end) return@forEach
            if (span.bold) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
            if (span.italic) addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
            if (span.code) addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, end)
            span.link?.let { destination ->
                addLink(
                    LinkAnnotation.Url(
                        url = destination,
                        styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        // The client's own action handles the selection, so the platform's
                        // default URI handling never opens anything by itself.
                        linkInteractionListener = { onOpenLink(destination) },
                    ),
                    start = start,
                    end = end,
                )
            }
        }
    }
