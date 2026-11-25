package com.luna.storynote

import com.luna.storynote.literacy.JiebaWordSegmenter
import com.luna.storynote.literacy.LiteracyEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JiebaSegmentationTest {
    @Test
    fun phraseFirstTokenizationCoversChildrensStoryVocabulary() {
        JiebaWordSegmenter.ensureWarmedUp()

        val s1 = "从前有一只蓝色的小恐龙坐在火山上。"
        val kongSpan = LiteracyEngine.findWordAt(s1, s1.indexOf('恐'))
        assertNotNull(kongSpan)
        assertEquals("小恐龙", kongSpan!!.word)
        assertEquals(listOf("xiǎo", "kǒng", "lóng"), kongSpan.pinyins)

        val huoSpan = LiteracyEngine.findWordAt(s1, s1.indexOf('山'))
        assertNotNull(huoSpan)
        assertEquals("火山", huoSpan!!.word)

        val s2 = "他住在一座会喷棉花糖的火山上，每天坐宇宙飞船去探险。"
        val tangSpan = LiteracyEngine.findWordAt(s2, s2.indexOf('糖'))
        assertNotNull(tangSpan)
        assertEquals("棉花糖", tangSpan!!.word)

        val chuanSpan = LiteracyEngine.findWordAt(s2, s2.indexOf('飞'))
        assertNotNull(chuanSpan)
        val chuanWord = chuanSpan!!.word
        assertTrue(
            "Expected phrase containing 飞船, got $chuanWord",
            chuanWord == "飞船" || chuanWord == "宇宙飞船",
        )
    }

    @Test
    fun compoundWordsReturnFullCompoundForEveryCharacterIndex() {
        JiebaWordSegmenter.ensureWarmedUp()

        val cottonCandy = "我喜欢吃棉花糖和冰淇淋。"
        for (idx in 4..6) {
            val jiebaSpan = JiebaWordSegmenter.findWordAt(cottonCandy, idx)
            assertNotNull("Expected Jieba span at $idx", jiebaSpan)
            assertEquals("棉花糖", jiebaSpan!!.word)

            val span = LiteracyEngine.findWordAt(cottonCandy, idx)
            assertNotNull("Expected Literacy span at $idx", span)
            assertEquals("棉花糖", span!!.word)
        }

        val triceratops = "三角龙在草地上跑。"
        for (idx in 0..2) {
            val jiebaSpan = JiebaWordSegmenter.findWordAt(triceratops, idx)
            assertNotNull("Expected Jieba span at $idx", jiebaSpan)
            assertEquals("三角龙", jiebaSpan!!.word)

            val span = LiteracyEngine.findWordAt(triceratops, idx)
            assertNotNull("Expected Literacy span at $idx", span)
            assertEquals("三角龙", span!!.word)
        }

        val dinoSentence = "从前有一只蓝色的小恐龙坐在火山上。"
        assertEquals("小恐龙", LiteracyEngine.findWordAt(dinoSentence, dinoSentence.indexOf('恐'))?.word)
        assertEquals("小恐龙", LiteracyEngine.findWordAt(dinoSentence, dinoSentence.indexOf('龙'))?.word)
        assertEquals("小恐龙", LiteracyEngine.findWordAt(dinoSentence, dinoSentence.indexOf('小'))?.word)
    }

    @Test
    fun punctuationAndOutOfBoundsReturnNull() {
        val sent = "你好，小恐龙！"
        assertNull(LiteracyEngine.findWordAt(sent, sent.indexOf('，')))
        assertNull(LiteracyEngine.findWordAt(sent, -1))
        assertNull(LiteracyEngine.findWordAt(sent, sent.length))
    }

    @Test
    fun postWarmupLookupRunsInUnderFiveMilliseconds() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            JiebaWordSegmenter.warmUpAsync(scope)
            JiebaWordSegmenter.ensureWarmedUp()

            val sent = "有一天晚上小恐龙看着天上的月亮吃冰淇淋。"
            val t0 = System.nanoTime()
            repeat(50) {
                LiteracyEngine.findWordAt(sent, sent.indexOf('冰'))
            }
            val avgMs = (System.nanoTime() - t0) / 50_000_000.0
            assertTrue("Expected fast Jieba lookup (< 5ms avg), got ${avgMs}ms", avgMs < 5.0)
        } finally {
            scope.cancel()
        }
    }
}
