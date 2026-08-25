package com.freqcast.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiGeneratorTest {
    @Test
    fun `getEmojiForStation always returns the default radio emoji`() {
        assertEquals("📻", EmojiGenerator.getEmojiForStation("Jazz FM", "https://example.com/jazz"))
        assertEquals("📻", EmojiGenerator.getEmojiForStation(""))
    }

    @Test
    fun `pickerEmojis exposes a non-empty curated list including the default`() {
        assertTrue(EmojiGenerator.pickerEmojis.isNotEmpty())
        assertTrue(EmojiGenerator.pickerEmojis.contains("📻"))
    }
}
