package com.luna.storynote.literacy

import kotlin.math.abs

internal data class SpokenUnit(
    val char: Char,
    val tonelessPinyin: String,
    val fuzzyPinyin: String,
    val clauseIdx: Int,
    val indexInClause: Int,
    val clauseSpokenCount: Int,
)

/**
 * Pure character + toneless/fuzzy-pinyin sequence aligner (Needleman-Wunsch with automatic
 * banded DP fallback for very long texts). Zero external dependencies and deterministic offline.
 */
internal object PhoneticSequenceAligner {
    private const val SCORE_EXACT_CHAR = 4
    private const val SCORE_EXACT_PINYIN = 3
    private const val SCORE_FUZZY_PINYIN = 1
    private const val SCORE_MISMATCH = -3
    private const val SCORE_GAP = -2

    const val WEIGHT_EXACT_CHAR = 1.0
    const val WEIGHT_EXACT_PINYIN = 0.95
    const val WEIGHT_FUZZY_PINYIN = 0.55

    private const val MAX_FULL_DP_CELLS = 1_500_000L

    fun extractSpokenUnits(clauseTexts: List<String>): List<SpokenUnit> {
        val out = mutableListOf<SpokenUnit>()
        for ((clauseIdx, text) in clauseTexts.withIndex()) {
            val clauseStart = out.size
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (StoryBookFormatter.isCjk(ch)) {
                    val runStart = i
                    while (i < text.length && StoryBookFormatter.isCjk(text[i])) i++
                    val run = text.substring(runStart, i)
                    val pinyins = PinyinAnnotator.annotateHanziRun(run)
                    for (k in run.indices) {
                        val toneless = stripToneMarks(pinyins.getOrElse(k) { "" })
                        out.add(
                            SpokenUnit(
                                char = run[k],
                                tonelessPinyin = toneless,
                                fuzzyPinyin = normalizeFuzzyPinyin(toneless),
                                clauseIdx = clauseIdx,
                                indexInClause = 0,
                                clauseSpokenCount = 0,
                            ),
                        )
                    }
                } else if (ch.isLetterOrDigit()) {
                    val lower = ch.lowercaseChar()
                    out.add(
                        SpokenUnit(
                            char = lower,
                            tonelessPinyin = lower.toString(),
                            fuzzyPinyin = lower.toString(),
                            clauseIdx = clauseIdx,
                            indexInClause = 0,
                            clauseSpokenCount = 0,
                        ),
                    )
                    i++
                } else {
                    i++
                }
            }
            val count = out.size - clauseStart
            for (k in 0 until count) {
                out[clauseStart + k] = out[clauseStart + k].copy(indexInClause = k, clauseSpokenCount = count)
            }
        }
        return out
    }

    fun matchWeight(a: SpokenUnit, b: SpokenUnit): Double = when {
        a.char == b.char -> WEIGHT_EXACT_CHAR
        a.tonelessPinyin.isNotEmpty() && a.tonelessPinyin == b.tonelessPinyin -> WEIGHT_EXACT_PINYIN
        a.fuzzyPinyin.isNotEmpty() && a.fuzzyPinyin == b.fuzzyPinyin -> WEIGHT_FUZZY_PINYIN
        else -> 0.0
    }

    private fun matchScore(a: SpokenUnit, b: SpokenUnit): Int = when {
        a.char == b.char -> SCORE_EXACT_CHAR
        a.tonelessPinyin.isNotEmpty() && a.tonelessPinyin == b.tonelessPinyin -> SCORE_EXACT_PINYIN
        a.fuzzyPinyin.isNotEmpty() && a.fuzzyPinyin == b.fuzzyPinyin -> SCORE_FUZZY_PINYIN
        else -> SCORE_MISMATCH
    }

    /**
     * Returns `oldIndex` for each `newIndex` (or -1 if unaligned/mismatched).
     */
    fun align(oldUnits: List<SpokenUnit>, newUnits: List<SpokenUnit>): IntArray {
        val n = oldUnits.size
        val m = newUnits.size
        val result = IntArray(m) { -1 }
        if (n == 0 || m == 0) return result

        val useBanded = n.toLong() * m.toLong() > MAX_FULL_DP_CELLS
        val band = if (useBanded) maxOf(abs(n - m) + 160, 320) else maxOf(n, m)
        val negInf = Int.MIN_VALUE / 4
        val rowWidth = if (useBanded) (2 * band + 1) else (m + 1)
        val dp = IntArray((n + 1) * rowWidth) { negInf }
        val trace = ByteArray((n + 1) * rowWidth) // 1=diag, 2=up(del old), 3=left(ins new)

        fun cellIndex(i: Int, j: Int): Int {
            if (!useBanded) return i * rowWidth + j
            val offset = j - i + band
            return if (offset in 0 until rowWidth) i * rowWidth + offset else -1
        }

        dp[cellIndex(0, 0)] = 0
        for (j in 1..m) {
            val idx = cellIndex(0, j)
            if (idx < 0) break
            dp[idx] = j * SCORE_GAP
            trace[idx] = 3
        }
        for (i in 1..n) {
            val idx = cellIndex(i, 0)
            if (idx < 0) break
            dp[idx] = i * SCORE_GAP
            trace[idx] = 2
        }

        for (i in 1..n) {
            val jStart = if (useBanded) maxOf(1, i - band) else 1
            val jEnd = if (useBanded) minOf(m, i + band) else m
            val oldU = oldUnits[i - 1]
            for (j in jStart..jEnd) {
                val diagIdx = cellIndex(i - 1, j - 1)
                val upIdx = cellIndex(i - 1, j)
                val leftIdx = cellIndex(i, j - 1)

                val diagVal = if (diagIdx >= 0 && dp[diagIdx] > negInf / 2) {
                    dp[diagIdx] + matchScore(oldU, newUnits[j - 1])
                } else {
                    negInf
                }
                val upVal = if (upIdx >= 0 && dp[upIdx] > negInf / 2) dp[upIdx] + SCORE_GAP else negInf
                val leftVal = if (leftIdx >= 0 && dp[leftIdx] > negInf / 2) dp[leftIdx] + SCORE_GAP else negInf

                val curIdx = cellIndex(i, j)
                if (diagVal >= upVal && diagVal >= leftVal) {
                    dp[curIdx] = diagVal
                    trace[curIdx] = 1
                } else if (upVal >= leftVal) {
                    dp[curIdx] = upVal
                    trace[curIdx] = 2
                } else {
                    dp[curIdx] = leftVal
                    trace[curIdx] = 3
                }
            }
        }

        var i = n
        var j = m
        while (i > 0 || j > 0) {
            val curIdx = cellIndex(i, j)
            val dir = if (curIdx >= 0) trace[curIdx].toInt() else 0
            when (dir) {
                1 -> {
                    if (matchWeight(oldUnits[i - 1], newUnits[j - 1]) > 0.0) {
                        result[j - 1] = i - 1
                    }
                    i--
                    j--
                }
                2 -> i--
                3 -> j--
                else -> if (i > 0) i-- else j--
            }
        }
        return result
    }

    fun textSimilarity(oldText: String, newText: String): Double {
        val oldUnits = extractSpokenUnits(listOf(oldText))
        val newUnits = extractSpokenUnits(listOf(newText))
        if (oldUnits.isEmpty() || newUnits.isEmpty()) return 0.0
        val aligned = align(oldUnits, newUnits)
        var totalWeight = 0.0
        for (i in aligned.indices) {
            val oi = aligned[i]
            if (oi >= 0) totalWeight += matchWeight(oldUnits[oi], newUnits[i])
        }
        return totalWeight / maxOf(oldUnits.size, newUnits.size).toDouble()
    }

    fun stripToneMarks(pinyin: String): String {
        if (pinyin.isBlank()) return ""
        val sb = StringBuilder(pinyin.length)
        for (ch in pinyin.lowercase()) {
            when (ch) {
                'ā', 'á', 'ǎ', 'à' -> sb.append('a')
                'ō', 'ó', 'ǒ', 'ò' -> sb.append('o')
                'ē', 'é', 'ě', 'è' -> sb.append('e')
                'ī', 'í', 'ǐ', 'ì' -> sb.append('i')
                'ū', 'ú', 'ǔ', 'ù' -> sb.append('u')
                'ǖ', 'ǘ', 'ǚ', 'ǜ', 'ü' -> sb.append('v')
                in 'a'..'z' -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun normalizeFuzzyPinyin(toneless: String): String {
        if (toneless.isEmpty()) return ""
        var s = when {
            toneless.startsWith("zh") -> "z" + toneless.substring(2)
            toneless.startsWith("ch") -> "c" + toneless.substring(2)
            toneless.startsWith("sh") -> "s" + toneless.substring(2)
            toneless.startsWith("l") && toneless.length > 1 -> "n" + toneless.substring(1)
            else -> toneless
        }
        if (s.endsWith("ing") || s.endsWith("eng") || s.endsWith("ang")) {
            s = s.dropLast(1)
        }
        return s
    }
}
