package org.hermesnative.client.feature.entry.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The supported untrusted-content contract: Gateway message content is parsed into a
 * closed set of inert blocks and spans, so nothing in the source text can become
 * executable content.
 */
class SafeMarkdownTest {
    @Test
    fun paragraph_emphasis_renders_as_plain_text_without_markers() {
        val blocks = parseSafeMarkdown("Safe **Markdown** text")

        assertEquals("Safe Markdown text", blocks.plainText())
        assertEquals(listOf("Markdown"), blocks.spans().filter { it.bold }.map { it.text })
        assertTrue(blocks.spans().none { it.text.contains("**") })
    }

    @Test
    fun italic_emphasis_renders_as_plain_text_without_markers() {
        val blocks = parseSafeMarkdown("Use *care* when reading")

        assertEquals("Use care when reading", blocks.plainText())
        assertEquals(listOf("care"), blocks.spans().filter { it.italic }.map { it.text })
        assertTrue(blocks.spans().none { it.text.contains("*") })
    }

    @Test
    fun bullet_list_keeps_items_and_drops_the_bullet_markers() {
        val blocks = parseSafeMarkdown("- first item\n- second **item**\n- third item")

        assertTrue(blocks.single() is MarkdownBlock.BulletList)
        val list = blocks.single() as MarkdownBlock.BulletList
        assertEquals(listOf("first item", "second item", "third item"), list.items.map { it.text() })
        assertEquals(listOf("item"), list.items.flatten().filter { it.bold }.map { it.text })
    }

    @Test
    fun numbered_list_renders_the_authored_ordinals() {
        val blocks = parseSafeMarkdown("1. step one\n2. step two")

        assertTrue(blocks.single() is MarkdownBlock.NumberedList)
        val list = blocks.single() as MarkdownBlock.NumberedList
        assertEquals(listOf("1.", "2."), list.items.map { it.marker })
        assertEquals(listOf("step one", "step two"), list.items.map { it.spans.text() })
    }

    @Test
    fun a_numbered_list_never_invents_numbering_the_content_did_not_have() {
        val blocks = parseSafeMarkdown("5. five\n9. nine")

        val list = blocks.single() as MarkdownBlock.NumberedList
        assertEquals(listOf("5.", "9."), list.items.map { it.marker })
    }

    @Test
    fun a_paragraph_between_lists_keeps_its_own_block() {
        val blocks = parseSafeMarkdown("- first item\n\nAfter the list.\n\n1. step one")

        assertEquals(
            listOf("BulletList", "Paragraph", "NumberedList"),
            blocks.map { it::class.simpleName },
        )
        assertEquals("After the list.", blocks.plainText().lines()[1])
    }

    @Test
    fun fenced_code_block_keeps_its_source_literal_and_unparsed() {
        val blocks = parseSafeMarkdown("Before.\n\n```kotlin\nval x = \"**not bold**\"\n```\n\nAfter.")

        assertEquals(listOf("Paragraph", "CodeBlock", "Paragraph"), blocks.map { it::class.simpleName })
        val code = blocks[1] as MarkdownBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = \"**not bold**\"", code.code)
    }

    @Test
    fun a_fence_like_line_does_not_close_a_longer_fenced_code_block() {
        val blocks =
            parseSafeMarkdown(
                "````kotlin\n```\n````python\n**not bold** [x](https://example.com)\n````",
            )

        val code = blocks.single() as MarkdownBlock.CodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("```\n````python\n**not bold** [x](https://example.com)", code.code)
    }

    @Test
    fun an_unterminated_fence_keeps_its_lines_as_code_while_a_response_streams() {
        val blocks = parseSafeMarkdown("```\nstreaming line 1\nstreaming line 2")

        assertEquals(
            "streaming line 1\nstreaming line 2",
            (blocks.single() as MarkdownBlock.CodeBlock).code,
        )
    }

    @Test
    fun inline_code_stays_literal() {
        val blocks = parseSafeMarkdown("Run `esbuild --minify` now")

        assertEquals("Run esbuild --minify now", blocks.plainText())
        assertEquals(listOf("esbuild --minify"), blocks.spans().filter { it.code }.map { it.text })
    }

    @Test
    fun embedded_html_and_script_stay_literal_text() {
        val blocks = parseSafeMarkdown("<script>alert(1)</script> <b>bold</b>")

        assertEquals("<script>alert(1)</script> <b>bold</b>", blocks.plainText())
        assertTrue(blocks.spans().all { it.link == null })
    }

    @Test
    fun long_content_is_parsed_without_truncation() {
        val content = (1..500).joinToString(separator = "\n\n") { index -> "Paragraph $index with **bold** text" }

        val blocks = parseSafeMarkdown(content)

        assertEquals(500, blocks.size)
        assertEquals("Paragraph 500 with bold text", blocks.plainText().lines().last())
    }

    @Test
    fun https_link_keeps_its_label_and_its_destination() {
        val blocks = parseSafeMarkdown("See [the release notes](https://example.com/notes?x=1#top) for details.")

        assertEquals("See the release notes for details.", blocks.plainText())
        assertEquals(listOf("https://example.com/notes?x=1#top"), blocks.spans().mapNotNull { it.link })
    }

    @Test
    fun blocked_link_schemes_render_as_inert_label_text() {
        val content =
            """
            [a](javascript:alert(1))
            [b](data:text/html,<script>alert(1)</script>)
            [c](file:///etc/passwd)
            [d](content://com.example/secret)
            [e](http://example.com/insecure)
            [f](mailto:person@example.com)
            [g](https:/example.com/single-slash)
            """.trimIndent()

        val blocks = parseSafeMarkdown(content)
        val text = blocks.plainText()

        assertTrue(blocks.spans().all { it.link == null })
        assertEquals("a b c d e f g", text)
        listOf("javascript:", "data:", "file:", "content:", "http:", "mailto:", "single-slash").forEach { blocked ->
            assertTrue("Rendered text still contains $blocked", !text.contains(blocked))
        }
    }

    @Test
    fun an_image_construct_stays_literal_instead_of_becoming_a_link() {
        val blocks = parseSafeMarkdown("Shot: ![chart](https://example.com/chart.png)")

        assertEquals("Shot: ![chart](https://example.com/chart.png)", blocks.plainText())
        assertTrue(blocks.spans().all { it.link == null })
    }

    @Test
    fun unpaired_link_syntax_stays_literal_text() {
        val blocks = parseSafeMarkdown("Broken [link(https://example.com) and [label] without a target")

        assertEquals("Broken [link(https://example.com) and [label] without a target", blocks.plainText())
        assertTrue(blocks.spans().all { it.link == null })
    }

    @Test
    fun a_bare_url_stays_inert_text_and_is_never_linkified() {
        val blocks = parseSafeMarkdown("See https://example.com/secure for details")

        assertEquals("See https://example.com/secure for details", blocks.plainText())
        assertTrue(blocks.spans().all { it.link == null })
    }

    @Test
    fun emphasis_renders_the_links_and_emphasis_nested_inside_it() {
        val blocks = parseSafeMarkdown("**see [x](https://example.com/y)** and **a *b* c**")
        val spans = blocks.spans()

        assertEquals("see x and a b c", blocks.plainText())
        assertTrue(spans.none { span -> span.text.any { it in "*[(" } })
        assertEquals(listOf("x"), spans.filter { it.bold && it.link != null }.map { it.text })
        assertEquals(listOf("https://example.com/y"), spans.mapNotNull { it.link })
        assertEquals(listOf("b"), spans.filter { it.bold && it.italic }.map { it.text })
    }

    @Test
    fun a_link_label_keeps_its_own_emphasis_and_a_blocked_label_stays_inert() {
        val blocks = parseSafeMarkdown("[*release*](https://example.com) and [*blocked*](javascript:alert(1))")
        val spans = blocks.spans()

        assertEquals("release and blocked", blocks.plainText())
        assertEquals(listOf("release"), spans.filter { it.italic && it.link != null }.map { it.text })
        assertEquals(listOf("blocked"), spans.filter { it.italic && it.link == null }.map { it.text })
    }

    @Test
    fun adversarial_delimiter_and_bracket_runs_parse_without_failing_or_losing_text() {
        val content = "*a **b [c](d) ".repeat(2_000) + "tail-marker"

        val blocks = parseSafeMarkdown(content)

        assertTrue(blocks.isNotEmpty())
        assertTrue("The parse lost its trailing content", blocks.plainText().endsWith("tail-marker"))
    }

    @Test(timeout = 2_000)
    fun many_unmatched_link_brackets_do_not_make_parsing_quadratic() {
        val content = "[".repeat(500_000)

        val blocks = parseSafeMarkdown(content)

        assertEquals(content, blocks.plainText())
    }

    // Every opening bracket reaches the same closing bracket, so its unclosed destination must
    // not be scanned again for each of them. The size is chosen so that a repeated scan exceeds
    // the timeout while a single pass stays far below it.
    @Test(timeout = 1_500)
    fun link_candidates_sharing_a_closing_bracket_do_not_rescan_the_destination() {
        val content = "[".repeat(60_000) + "](https://example.com/" + "(".repeat(60_000)

        val blocks = parseSafeMarkdown(content)

        assertEquals(content, blocks.plainText())
    }

    // Many candidates that each own an unclosed destination, so caching one failed scan is not
    // enough: no candidate may scan the text at all.
    @Test(timeout = 1_500)
    fun many_unclosed_link_destinations_do_not_make_parsing_quadratic() {
        val content = "[]((".repeat(38_000)

        val blocks = parseSafeMarkdown(content)

        assertEquals(content, blocks.plainText())
    }
}

private fun List<MarkdownSpan>.text(): String = joinToString(separator = "") { it.text }

private fun List<MarkdownBlock>.spans(): List<MarkdownSpan> =
    flatMap { block ->
        when (block) {
            is MarkdownBlock.Paragraph -> block.spans
            is MarkdownBlock.BulletList -> block.items.flatten()
            is MarkdownBlock.NumberedList -> block.items.flatMap { it.spans }
            is MarkdownBlock.CodeBlock -> emptyList()
        }
    }

private fun List<MarkdownBlock>.plainText(): String =
    joinToString(separator = "\n") { block ->
        when (block) {
            is MarkdownBlock.Paragraph -> block.spans.joinToString(separator = "") { it.text }
            is MarkdownBlock.BulletList -> block.items.joinToString(separator = "\n") { item -> item.text() }
            is MarkdownBlock.NumberedList -> block.items.joinToString(separator = "\n") { item -> item.spans.text() }
            is MarkdownBlock.CodeBlock -> block.code
        }
    }
