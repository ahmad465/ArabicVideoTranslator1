package com.example.arabicvideotranslator.core

import org.junit.Assert.*
import org.junit.Test

class TranslationPipelineTest {
    private fun cues(n: Int) = (0 until n).map { Cue(it * 1000L, it * 1000L + 900, "t$it") }

    private class FakeProvider(val behavior: (call: Int, texts: List<String>) -> List<String>) : TranslationProvider {
        var calls = 0
        val batches = ArrayList<List<String>>()
        override fun translate(texts: List<String>, sourceLang: String?, targetLang: String): List<String> {
            batches += texts
            return behavior(calls++, texts)
        }
    }

    @Test fun batchesAndPreservesTimingAndOrder() {
        val p = FakeProvider { _, t -> t.map { "ar:$it" } }
        val progress = ArrayList<Int>()
        val r = TranslationPipeline(p, batchSize = 2).translate(cues(5), "en") { d, _ -> progress += d }
        assertTrue(r.isComplete)
        assertEquals(listOf(2, 2, 1), p.batches.map { it.size })
        assertEquals(listOf(2, 4, 5), progress)
        assertEquals(Cue(3000, 3900, "ar:t3"), r.translated[3])
    }
    @Test fun retriesThenSucceeds() {
        val sleeps = ArrayList<Long>()
        val p = FakeProvider { call, t -> if (call < 2) throw RetryableProviderException("503") else t.map { "ar$it" } }
        val r = TranslationPipeline(p, batchSize = 10, maxRetries = 3, baseDelayMs = 100, sleeper = { sleeps += it })
            .translate(cues(3), null)
        assertTrue(r.isComplete); assertEquals(3, p.calls); assertEquals(listOf(100L, 200L), sleeps)
    }
    @Test fun exhaustedRetriesMarkOnlyThatBatchFailed() {
        val p = FakeProvider { _, t -> if (t.contains("t0")) throw RetryableProviderException("down") else t.map { "ar$it" } }
        val r = TranslationPipeline(p, batchSize = 2, maxRetries = 1, sleeper = {}).translate(cues(4), "en")
        assertEquals(setOf(0, 1), r.failures.keys)
        assertNull(r.translated[0]); assertNotNull(r.translated[2]); assertEquals(2, r.successCount)
        assertFalse(r.isComplete)
        assertEquals(1 + 1 + 1, p.calls) // batch 0: 2 attempts, batch 1: 1
    }
    @Test fun fatalErrorStopsEverything() {
        val p = FakeProvider { _, _ -> throw FatalProviderException("bad key") }
        try { TranslationPipeline(p, batchSize = 1, sleeper = {}).translate(cues(3), "en"); fail() }
        catch (e: FatalProviderException) { assertEquals(1, p.calls) }
    }
    @Test fun sizeMismatchIsFatal() {
        val p = FakeProvider { _, _ -> listOf("only one") }
        try { TranslationPipeline(p, batchSize = 5, sleeper = {}).translate(cues(3), "en"); fail() }
        catch (e: FatalProviderException) { assertTrue(e.message!!.contains("لا يطابق")) }
    }
    @Test fun emptyInput() {
        val r = TranslationPipeline(FakeProvider { _, t -> t }).translate(emptyList(), "en")
        assertTrue(r.isComplete); assertEquals(0, r.successCount)
    }
}
