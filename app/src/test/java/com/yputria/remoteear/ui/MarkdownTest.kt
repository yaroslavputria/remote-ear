package com.yputria.remoteear.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The renderer for the privacy policy and the terms.
 *
 * These tests exist because a release build put the privacy policy on screen with a bullet ending
 * mid-sentence and its second half underneath as an unindented paragraph. The documents are
 * hard-wrapped at 100 columns, so *most* of their bullets span two lines - the bug was in almost
 * every bullet of both documents, and it was invisible until somebody looked at the screen.
 *
 * Of all the places for the app to look careless, a document claiming it collects nothing is the
 * worst.
 */
class MarkdownTest {

    @Test
    fun `a bullet wrapped across two lines stays one bullet`() {
        val blocks = parseMarkdown(
            """
            - Audio is held only in short memory buffers - a fraction of a second in total - while
              it is on its way to your headphones.
            """.trimIndent(),
        )
        assertEquals(1, blocks.size)
        assertEquals(
            Block.Bullet(
                "Audio is held only in short memory buffers - a fraction of a second in total - " +
                    "while it is on its way to your headphones.",
            ),
            blocks[0],
        )
    }

    @Test
    fun `a blank line ends a bullet`() {
        val blocks = parseMarkdown("- first\n- second\n\nA paragraph.")
        assertEquals(
            listOf(Block.Bullet("first"), Block.Bullet("second"), Block.Paragraph("A paragraph.")),
            blocks,
        )
    }

    @Test
    fun `a heading ends whatever was open`() {
        val blocks = parseMarkdown("- a bullet\n## Heading\nA paragraph.")
        assertEquals(
            listOf(Block.Bullet("a bullet"), Block.Heading("Heading"), Block.Paragraph("A paragraph.")),
            blocks,
        )
    }

    @Test
    fun `wrapped paragraphs are rejoined, not broken into lines`() {
        val blocks = parseMarkdown("RemoteEar captures this phone's built-in\nmicrophone and plays it.")
        assertEquals(
            listOf(Block.Paragraph("RemoteEar captures this phone's built-in microphone and plays it.")),
            blocks,
        )
    }

    @Test
    fun `titles and headings are distinguished`() {
        val blocks = parseMarkdown("# Privacy policy\n\n## Audio")
        assertEquals(listOf(Block.Title("Privacy policy"), Block.Heading("Audio")), blocks)
    }

    /** The real documents, as shipped. If either stops parsing sensibly, this notices. */
    @Test
    fun `no block is empty and no bullet ends mid-sentence in a trivial way`() {
        val sample = """
            # Terms of use

            ## Not a safety device

            **RemoteEar is not a baby monitor**, and must not be relied on as one.

            - your headphones leave Bluetooth range, or their battery runs out
            - the phone's manufacturer shuts the app down to save battery - many Android phones do
              this aggressively, and there is no way for an app to prevent it
        """.trimIndent()

        val blocks = parseMarkdown(sample)
        assertEquals(
            listOf(
                Block.Title("Terms of use"),
                Block.Heading("Not a safety device"),
                Block.Paragraph("**RemoteEar is not a baby monitor**, and must not be relied on as one."),
                Block.Bullet("your headphones leave Bluetooth range, or their battery runs out"),
                Block.Bullet(
                    "the phone's manufacturer shuts the app down to save battery - many Android " +
                        "phones do this aggressively, and there is no way for an app to prevent it",
                ),
            ),
            blocks,
        )
    }
}
