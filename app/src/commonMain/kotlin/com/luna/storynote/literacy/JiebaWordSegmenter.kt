package com.luna.storynote.literacy

import com.huaban.analysis.jieba.JiebaSegmenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class WordTokenSpan(
    val word: String,
    val startOffset: Int,
    val endOffset: Int,
)

object JiebaWordSegmenter {
    private val initLock = Any()

    @Volatile
    private var segmenterInstance: JiebaSegmenter? = null

    @Volatile
    private var warmUpStarted = false

    // Common children's storybook compounds absent or split in Jieba's default news dictionary.
    // Merged across adjacent Jieba tokens in segment() and upgraded on single-char matches in findWordAt().
    private val CHILDREN_COMPOUND_WORDS = listOf(
        "宇宙飞船", "太阳公公", "月亮婆婆",
        "棉花糖", "冰淇淋", "棒棒糖", "爆米花", "霸王龙", "三角龙", "喷火龙",
        "热气球", "潜水艇", "消防车", "救护车", "过山车", "独角兽", "美人鱼",
        "毛茸茸", "亮晶晶", "胖乎乎", "长颈鹿", "暴风雨", "龙卷风",
        "小恐龙", "大森林", "慢慢地", "慢慢的", "走啊走", "跑啊跑", "飞啊飞",
        "来到了", "大饼干", "小狐狸", "小朋友", "森林里", "大树下", "草地上",
        "走出", "走进", "来到", "房间", "河边", "开心", "快乐", "伤心", "害怕", "勇敢", "突然", "终于",
    )
    private val CHILDREN_COMPOUND_SET = CHILDREN_COMPOUND_WORDS.toHashSet()
    private val MAX_COMPOUND_LEN = CHILDREN_COMPOUND_WORDS.maxOf { it.length }
    private val SIZE_PREFIXES = setOf("小", "大", "老")
    private val V_LINKERS = setOf("啊", "了", "一")
    private val ADJ_PARTICLES = setOf("地", "的")
    private val NON_NOUN_AFTER_SIZE_PREFIX = setOf(
        "起来", "出去", "下来", "上来", "过去", "过来", "开心", "快乐", "伤心",
        "害怕", "勇敢", "突然", "终于", "来到", "走出", "走进", "已经", "正在",
        "一直", "一起", "一定", "一样", "知道", "觉得", "喜欢", "看见", "听见", "发现",
    )

    private fun getOrInitSegmenter(): JiebaSegmenter {
        segmenterInstance?.let { return it }
        return synchronized(initLock) {
            segmenterInstance ?: JiebaSegmenter().also { segmenterInstance = it }
        }
    }

    fun warmUpAsync(scope: CoroutineScope) {
        if (segmenterInstance != null || warmUpStarted) return
        warmUpStarted = true
        scope.launch(Dispatchers.IO) {
            getOrInitSegmenter()
        }
    }

    fun ensureWarmedUp() {
        getOrInitSegmenter()
    }

    fun segment(text: String): List<WordTokenSpan> {
        if (text.isEmpty()) return emptyList()
        val seg = getOrInitSegmenter()
        val rawTokens = seg.process(text, JiebaSegmenter.SegMode.SEARCH)
        val out = ArrayList<WordTokenSpan>(rawTokens.size)
        for (tok in rawTokens) {
            val start = tok.startOffset.coerceIn(0, text.length)
            val end = tok.endOffset.coerceIn(start, text.length)
            if (end > start) {
                out.add(
                    WordTokenSpan(
                        word = text.substring(start, end),
                        startOffset = start,
                        endOffset = end,
                    ),
                )
            }
        }
        return mergeChildrenPatterns(text, mergeChildrenCompounds(text, out))
    }

    private fun mergeChildrenPatterns(text: String, spans: List<WordTokenSpan>): List<WordTokenSpan> {
        if (spans.size < 2) return spans
        val merged = ArrayList<WordTokenSpan>(spans.size)
        var i = 0
        while (i < spans.size) {
            val s0 = spans[i]
            if (i + 2 < spans.size) {
                val s1 = spans[i + 1]
                val s2 = spans[i + 2]
                if (s1.startOffset == s0.endOffset &&
                    s2.startOffset == s1.endOffset &&
                    s0.word.length == 1 &&
                    StoryBookFormatter.isCjk(s0.word[0]) &&
                    s1.word in V_LINKERS &&
                    s2.word == s0.word
                ) {
                    merged.add(WordTokenSpan(text.substring(s0.startOffset, s2.endOffset), s0.startOffset, s2.endOffset))
                    i += 3
                    continue
                }
            }
            if (i + 1 < spans.size) {
                val s1 = spans[i + 1]
                if (s1.startOffset == s0.endOffset) {
                    val isAaParticle = s0.word.length == 2 &&
                        s0.word[0] == s0.word[1] &&
                        StoryBookFormatter.isCjk(s0.word[0]) &&
                        s1.word in ADJ_PARTICLES
                    val isSizedNoun = s0.word in SIZE_PREFIXES &&
                        s1.word.length == 2 &&
                        s1.word.all { StoryBookFormatter.isCjk(it) } &&
                        s1.word !in NON_NOUN_AFTER_SIZE_PREFIX
                    if (isAaParticle || isSizedNoun) {
                        merged.add(WordTokenSpan(text.substring(s0.startOffset, s1.endOffset), s0.startOffset, s1.endOffset))
                        i += 2
                        continue
                    }
                }
            }
            merged.add(s0)
            i++
        }
        return merged
    }

    private fun mergeChildrenCompounds(text: String, spans: List<WordTokenSpan>): List<WordTokenSpan> {
        if (spans.size < 2) return spans
        val merged = ArrayList<WordTokenSpan>(spans.size)
        var i = 0
        while (i < spans.size) {
            val first = spans[i]
            var bestEndIdx = i
            if (first.word.length < MAX_COMPOUND_LEN) {
                var j = i + 1
                while (j < spans.size) {
                    val prev = spans[j - 1]
                    val curr = spans[j]
                    if (curr.startOffset != prev.endOffset) break
                    val combinedLen = curr.endOffset - first.startOffset
                    if (combinedLen > MAX_COMPOUND_LEN) break
                    val candidate = text.substring(first.startOffset, curr.endOffset)
                    if (candidate in CHILDREN_COMPOUND_SET) {
                        bestEndIdx = j
                    }
                    j++
                }
            }
            if (bestEndIdx > i) {
                val endOffset = spans[bestEndIdx].endOffset
                merged.add(
                    WordTokenSpan(
                        word = text.substring(first.startOffset, endOffset),
                        startOffset = first.startOffset,
                        endOffset = endOffset,
                    ),
                )
                i = bestEndIdx + 1
            } else {
                merged.add(first)
                i++
            }
        }
        return merged
    }

    fun tokenRanges(text: String): List<IntRange> =
        segment(text).map { it.startOffset until it.endOffset }

    /**
     * Phrase-first token lookup around `clickedCharIndex`:
     * - Returns null if `clickedCharIndex` is out of bounds or not a CJK character.
     * - Uses `JiebaSegmenter` (`SegMode.SEARCH`) and preserves exact source substring.
     * - If Jieba emits a single-character CJK token that sits inside a known children's compound
     *   (e.g., `"棉花糖"`), upgrades to that compound while preserving Jieba's standard words (`"恐龙"`).
     */
    fun findWordAt(sentence: String, clickedCharIndex: Int): WordTokenSpan? {
        if (clickedCharIndex !in sentence.indices) return null
        if (!StoryBookFormatter.isCjk(sentence[clickedCharIndex])) return null

        val tokens = segment(sentence)
        val matched = tokens
            .filter { clickedCharIndex in it.startOffset until it.endOffset && it.word.all { ch -> StoryBookFormatter.isCjk(ch) } }
            .maxWithOrNull(compareBy<WordTokenSpan> { if (it.word.length >= 2) 1 else 0 }.thenBy { it.word.length })

        if (matched != null) {
            if (matched.word.length >= 2) {
                return matched
            }
            // Upgrade single-char split only for known children's multi-char compounds
            findChildrenCompoundAt(sentence, clickedCharIndex)?.let { return it }
            return matched
        }

        findChildrenCompoundAt(sentence, clickedCharIndex)?.let { return it }
        return WordTokenSpan(
            word = sentence[clickedCharIndex].toString(),
            startOffset = clickedCharIndex,
            endOffset = clickedCharIndex + 1,
        )
    }

    private fun findChildrenCompoundAt(sentence: String, clickedCharIndex: Int): WordTokenSpan? {
        var best: WordTokenSpan? = null
        for (compound in CHILDREN_COMPOUND_WORDS) {
            if (best != null && compound.length <= best.word.length) continue
            val minStart = (clickedCharIndex - compound.length + 1).coerceAtLeast(0)
            val maxStart = clickedCharIndex.coerceAtMost(sentence.length - compound.length)
            if (minStart > maxStart) continue
            for (s in minStart..maxStart) {
                if (sentence.regionMatches(s, compound, 0, compound.length)) {
                    best = WordTokenSpan(compound, s, s + compound.length)
                    break
                }
            }
        }
        return best
    }
}
