package com.luna.storynote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class PinyinAndStoryFormatterTest {
    @Test
    fun polyphonesAndNeutralTonesMatchGradeOneTextbook() {
        val cells = PinyinAnnotator.annotateToCells("一只小恐龙吃一块大饼干，慢慢长大了，看着月亮唱音乐，快乐地去睡觉。妈妈说不是吗？")
        val pairs = cells.filter { it.isHanzi }.map { "${it.charText}(${it.pinyin})" }.joinToString(" ")

        // Key textbook sandhi + polyphones: 一只 -> yì zhī, 一块 -> yí kuài, 饼干 -> bǐng gān, 长大了 -> zhǎng, 看着 -> zhe, 音乐 -> yuè, 快乐 -> lè, 睡觉 -> jiào, 妈妈 -> mā ma, 不是 -> bú shì
        assertTrue("Got: $pairs", pairs.contains("一(yì) 只(zhī)"))
        assertTrue("Got: $pairs", pairs.contains("一(yí) 块(kuài) 大(dà) 饼(bǐng) 干(gān)"))
        assertTrue("Got: $pairs", pairs.contains("长(zhǎng) 大(dà) 了(le)"))
        assertTrue("Got: $pairs", pairs.contains("看(kàn) 着(zhe)"))
        assertTrue("Got: $pairs", pairs.contains("月(yuè) 亮(liang)"))
        assertTrue("Got: $pairs", pairs.contains("音(yīn) 乐(yuè)"))
        assertTrue("Got: $pairs", pairs.contains("快(kuài) 乐(lè)"))
        assertTrue("Got: $pairs", pairs.contains("睡(shuì) 觉(jiào)"))
        assertTrue("Got: $pairs", pairs.contains("妈(mā) 妈(ma)"))
        assertTrue("Got: $pairs", pairs.contains("不(bú) 是(shì) 吗(ma)"))
    }

    @Test
    fun punctuationAttachesToClustersPreventingOrphanLineWraps() {
        val clusters = PinyinAnnotator.annotateParagraphToClusters("“太棒啦！”小龙说。")
        // Cluster 0 must bind opening quote `“` with `太`: `“太`
        assertEquals("“太", clusters.first().cells.joinToString("") { it.charText })
        // Cluster containing `啦` must bind `！”` so `！”` can never wrap alone onto a new line
        val bangCluster = clusters.first { c -> c.cells.any { it.charText == "啦" } }
        assertEquals("啦！”", bangCluster.cells.joinToString("") { it.charText })
        // Last cluster must bind `说。`
        assertEquals("说。", clusters.last().cells.joinToString("") { it.charText })
    }

    @Test
    fun spokenWallOfTextSplitsIntoShortBreathableParagraphs() {
        val raw = "从前有一只蓝色的小恐龙，他住在一座很高的棉花糖火山上，每天早上都会吃一朵甜甜的云朵。有一天早上，小恐龙醒来发现棉花糖全都不见了，他特别着急，背起小书包就出门去找。后来他在森林里遇到了一只聪明的小狐狸，他们一起坐飞船飞到了彩虹星球。"
        val paras = StoryBookFormatter.formatIntoParagraphs(raw)
        assertTrue("Expected >= 3 short paragraphs, got ${paras.size}: $paras", paras.size >= 3)
        assertTrue("Each paragraph should stay bite-sized: $paras", paras.all { it.length <= 65 })
    }

    @Test
    fun wavAppendConcatenatesAudioWithNaturalPause() {
        val dir = createTempDirectory("wav-append-test").toFile()
        try {
            val wav1 = File(dir, "part1.wav")
            val w1 = WavFileStreamWriter(wav1)
            w1.appendSilenceMs(1000) // 1.0s
            w1.close()

            val wav2 = File(dir, "part2.wav")
            val w2 = WavFileStreamWriter(wav2)
            w2.appendSilenceMs(1000) // 1.0s
            w2.close()

            val combinedSec = WavFileStreamWriter.appendWavFiles(wav1, wav2, pauseMs = 500)
            assertEquals(2.5, combinedSec, 0.02)
        } finally {
            dir.deleteRecursively()
        }
    }
}
