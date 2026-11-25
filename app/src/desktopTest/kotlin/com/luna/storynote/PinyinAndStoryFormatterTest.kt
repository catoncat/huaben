package com.luna.storynote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class PinyinAndStoryFormatterTest {
    @Test
    fun spellingEngineHandlesTwoPartThreePartWholeAndUmlaut() {
        val kong = LiteracyEngine.analyzeSpelling("kǒng")
        assertEquals("k", kong.initial)
        assertEquals("", kong.medial)
        assertEquals("ǒng", kong.finalPart)
        assertFalse(kong.isWholeSyllable)

        val chuan = LiteracyEngine.analyzeSpelling("chuán")
        assertEquals("ch", chuan.initial)
        assertEquals("u", chuan.medial)
        assertEquals("án", chuan.finalPart)

        val yue = LiteracyEngine.analyzeSpelling("yuè")
        assertTrue(yue.isWholeSyllable)

        val qu = LiteracyEngine.analyzeSpelling("qù")
        assertEquals("q", qu.initial)
        assertEquals("ǜ", qu.finalPart) // restores ü dots after q!
    }

    @Test
    fun wordSegmentationAndHomophonesWorkOnStorySentence() {
        val sent = "从前有一只蓝色的小恐龙坐在火山上。"
        val kongIdx = sent.indexOf('恐')
        val span = LiteracyEngine.findWordAt(sent, kongIdx)
        assertNotNull(span)
        assertEquals("恐龙", span!!.word)
        assertEquals(listOf("kǒng", "lóng"), span.pinyins)

        val zuoIdx = sent.indexOf('坐')
        val zuoSpan = LiteracyEngine.findWordAt(sent, zuoIdx)
        assertNotNull(zuoSpan)
        val homophones = LiteracyEngine.suggestHomophones("坐", listOf("zuò"))
        assertTrue("Expected 座 or 做 in $homophones", homophones.contains("座") || homophones.contains("做"))

        val kongCharHomophones = LiteracyEngine.suggestHomophones("恐", listOf("kǒng"))
        assertTrue("Expected single-char homophones for 恐, got $kongCharHomophones", kongCharHomophones.isNotEmpty())
    }

    @Test
    fun diPolyphoneDistinguishesNounMapFromAdverbialParticle() {
        val cells = PinyinAnnotator.annotateToCells("拿着星星地图，开心地跑。")
        val dituIdx = cells.indexOfFirst { it.charText == "地" }
        assertEquals("dì", cells[dituIdx].pinyin)
        assertEquals("tú", cells[dituIdx + 1].pinyin)

        val deIdx = cells.indexOfLast { it.charText == "地" }
        assertEquals("xīn", cells[deIdx - 1].pinyin)
        assertEquals("de", cells[deIdx].pinyin)
    }

    @Test
    fun strokeRepositoryLoads3755StandardCharacters() {
        val stream = DesktopPlatformEnvironment().openStrokeAssetStream()
        assertNotNull("strokes.bin.gz missing from resources", stream)
        HanziStrokeRepository.ensureLoaded { stream }
        val kongGlyph = HanziStrokeRepository.getGlyph('恐')
        assertNotNull(kongGlyph)
        assertEquals(10, kongGlyph!!.strokes.size) // `恐` has 10 standard strokes!
    }

    @Test
    fun bookPaginatesSentencesAndReplacesInPlace() {
        val tmp = createTempDirectory("book-repo-test-").toFile()
        try {
            val repo = StoryBookRepository(tmp)
            val b0 = repo.createBlankBook()
            var afterId: String? = null
            repeat(6) { idx ->
                val (bNext, clip) = repo.insertSentenceClip(
                    bookId = b0.id,
                    afterSentenceId = afterId,
                    rawText = "这是第${idx + 1}句长长的童话故事内容，小恐龙在森林里快乐地奔跑着。",
                    tempWavFile = null,
                    durationSec = 2.0,
                )
                afterId = clip.id
                if (idx == 5) {
                    val pages = bNext.paginate()
                    assertTrue("Expected >= 2 pages, got ${pages.size}", pages.size >= 2)
                }
            }
            val latest = repo.getBook(b0.id)!!
            val targetId = latest.sentences[1].id
            val updated = repo.replaceSentenceClip(b0.id, targetId, "小霸王龙飞上了太空！", null, 1.5)!!
            assertEquals("小霸王龙飞上了太空！", updated.sentences[1].text)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
