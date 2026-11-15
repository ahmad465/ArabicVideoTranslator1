package com.example.arabicvideotranslator.core

import org.junit.Assert.*
import org.junit.Test

class DubbingSchedulerTest {
    private val cues = listOf(Cue(1000, 2000, "a"), Cue(3000, 4000, "b"), Cue(5000, 6000, "c"))

    @Test fun speaksEachCueOnceInOrder() {
        val s = DubbingScheduler(cues)
        assertNull(s.poll(0)); assertNull(s.poll(900))
        assertEquals("a", s.poll(1000)?.text)
        assertNull(s.poll(1200)); assertNull(s.poll(2500))
        assertEquals("b", s.poll(3100)?.text)
        assertEquals("c", s.poll(5000)?.text)
        assertNull(s.poll(5500))
    }
    @Test fun skipsCuesMissedBySeekingForward() {
        val s = DubbingScheduler(cues)
        s.poll(0)
        assertEquals("c", s.poll(5200)?.text) // a وb فاتا ويُتخطيان؛ ينطق الأحدث فقط
        assertNull(s.poll(5300))
    }
    @Test fun lateCueBeyondGraceIsSkipped() {
        val s = DubbingScheduler(cues, graceMs = 400)
        assertNull(s.poll(2500)) // a انتهى قبل 500ms > grace
        assertEquals("b", s.poll(3000)?.text)
    }
    @Test fun lateWithinGraceStillSpoken() =
        assertEquals("a", DubbingScheduler(cues, graceMs = 400).poll(2300)?.text)

    @Test fun seekBackReplays() {
        val s = DubbingScheduler(cues)
        s.poll(1000); s.poll(3000); s.poll(5000)
        assertNull(s.poll(5100))
        assertNull(s.poll(500)) // قفز للخلف قبل a
        assertEquals("a", s.poll(1000)?.text)
        assertEquals("b", s.poll(3000)?.text)
    }
    @Test fun smallBackwardJitterDoesNotReplay() {
        val s = DubbingScheduler(cues)
        assertEquals("a", s.poll(1500)?.text)
        assertNull(s.poll(1300))
        assertNull(s.poll(1500))
    }
    @Test fun resetAllowsReplay() {
        val s = DubbingScheduler(cues); s.poll(1000); s.reset()
        assertEquals("a", s.poll(1000)?.text)
    }
    @Test fun emptyCues() = assertNull(DubbingScheduler(emptyList()).poll(1000))
}

class SpeechFitTest {
    @Test fun shortTextAtMinimumRate() {
        val f = SpeechFit.fit("مرحبا", 5000)
        assertEquals(SpeechFit.MIN_RATE, f.rate, 1e-6f); assertTrue(f.fits)
    }
    @Test fun speedsUpToFit() {
        // 10 كلمات ≈ 4000ms عند 1.0؛ المتاح 2000ms => 2.0
        val f = SpeechFit.fit("a b c d e f g h i j", 2000)
        assertEquals(2.0f, f.rate, 1e-3f); assertTrue(f.fits)
    }
    @Test fun midRate() {
        val f = SpeechFit.fit("a b c d e f g h i j", 3200) // 4000/3200 = 1.25
        assertEquals(1.25f, f.rate, 1e-3f); assertTrue(f.fits)
    }
    @Test fun overflowFlaggedAndClamped() {
        val f = SpeechFit.fit("a b c d e f g h i j", 1000)
        assertEquals(SpeechFit.MAX_RATE, f.rate, 1e-6f); assertFalse(f.fits)
    }
    @Test fun zeroDuration() = assertFalse(SpeechFit.fit("word", 0).fits)
    @Test fun emptyText() { val f = SpeechFit.fit("   ", 1000); assertTrue(f.fits); assertEquals(1f, f.rate, 0f) }
}
