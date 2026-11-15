package com.example.arabicvideotranslator.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleParserTest {
    private val srt = "\uFEFF1\r\n00:00:01,000 --> 00:00:03,500\r\nHello <i>world</i>\r\n\r\n" +
        "2\r\n00:00:04,000 --> 00:00:06,250\r\nLine one\r\nLine two\r\n\r\n" +
        "3\r\n01:02:03,004 --> 01:02:05,000\r\n2024\r\n"

    @Test fun srtBasic() {
        val cues = SubtitleParser.parse(srt)
        assertEquals(3, cues.size)
        assertEquals(Cue(1000, 3500, "Hello world"), cues[0])
        assertEquals(Cue(4000, 6250, "Line one\nLine two"), cues[1])
        assertEquals(3_723_004L, cues[2].startMs)
        assertEquals(3_725_000L, cues[2].endMs)
    }
    @Test fun numericTextLineIsKept() = assertEquals("2024", SubtitleParser.parse(srt)[2].text)

    private val vtt = """WEBVTT - title

NOTE this is a comment
spanning lines

STYLE
::cue { color: red }

intro
00:01.000 --> 00:02.500 align:start position:0%
<v Speaker>Hi &amp; welcome</v>

00:00:03.000 --> 00:00:04.000
<c.yellow>مرحبا</c.yellow> بالعالم
"""
    @Test fun vttBasic() {
        val cues = SubtitleParser.parse(vtt)
        assertEquals(2, cues.size)
        assertEquals(Cue(1000, 2500, "Hi & welcome"), cues[0])
        assertEquals(Cue(3000, 4000, "مرحبا بالعالم"), cues[1])
    }
    @Test fun vttHeaderNotesAndStyleAreNotCues() {
        val text = SubtitleParser.toPlainText(vtt)
        assertTrue(!text.contains("WEBVTT")); assertTrue(!text.contains("comment")); assertTrue(!text.contains("color"))
        assertTrue(!text.contains("-->"))
    }
    @Test fun plainTextRemovesTimestampsAndIndexes() =
        assertEquals("Hello world\nLine one\nLine two\n2024", SubtitleParser.toPlainText(srt))

    @Test fun vttHeaderImmediatelyFollowedByCueWithoutBlankLine() {
        val cues = SubtitleParser.parse("WEBVTT\n00:00.000 --> 00:01.000\nx\n")
        assertEquals(listOf(Cue(0, 1000, "x")), cues)
    }
    @Test fun sortsByStart() {
        val cues = SubtitleParser.parse("00:00:05,000 --> 00:00:06,000\nb\n\n00:00:01,000 --> 00:00:02,000\na\n")
        assertEquals(listOf("a", "b"), cues.map { it.text })
    }
    @Test fun shortFractionIsPadded() =
        assertEquals(500L, SubtitleParser.parse("00:00:00,5 --> 00:00:01,5\nx")[0].startMs)
    @Test fun skipsBrokenBlocksAndEmptyBodies() {
        val cues = SubtitleParser.parse("1\nnot a time --> x\ntext\n\n2\n00:00:02,000 --> 00:00:01,000\nreversed\n\n3\n00:00:03,000 --> 00:00:04,000\n<b></b>\n\n4\n00:00:05,000 --> 00:00:06,000\nok")
        assertEquals(listOf("ok"), cues.map { it.text })
    }
    @Test fun emptyAndGarbage() {
        assertTrue(SubtitleParser.parse("").isEmpty())
        assertTrue(SubtitleParser.parse("just some text\nwithout timing").isEmpty())
        assertEquals("", SubtitleParser.toPlainText("WEBVTT\n"))
    }
    @Test fun ssaStyleTagsRemoved() =
        assertEquals("text", SubtitleParser.parse("00:00:01,000 --> 00:00:02,000\n{\\an8}text")[0].text)
}
