package com.luna.storynote

import com.github.houbb.pinyin.constant.enums.PinyinStyleEnum
import com.github.houbb.pinyin.util.PinyinHelper

/**
 * Single character cell inside a ruby-annotated unit.
 * Punctuation marks have `pinyin = ""` so their vertical baseline aligns cleanly with Hanzi.
 */
data class RubyCharCell(
    val charText: String,
    val pinyin: String,
    val isHanzi: Boolean,
)

/**
 * Non-breaking visual cluster respecting Chinese GB/T 15834 line-breaking rules (避头尾禁则):
 * opening quotes/brackets stay attached to the following character, and trailing commas/periods/quotes
 * stay attached to the preceding character so punctuation never dangles at the start of a line.
 */
data class RubyCluster(
    val cells: List<RubyCharCell>,
)

object PinyinAnnotator {
    private val OPENING_PUNCT = setOf('“', '‘', '《', '（', '(', '[', '【', '「', '『')
    private val TRAILING_PUNCT = setOf(
        '，', '。', '！', '？', '、', '；', '：', '”', '’', '》', '）', ')', ']', '】', '」', '』',
        '…', '—', ',', '.', '!', '?', ';', ':',
    )

    // Grade-1 textbook neutral-tone (轻声) second-syllable lexicon
    private val NEUTRAL_SECOND_SYLLABLE_WORDS = mapOf(
        "妈妈" to ("mā" to "ma"),
        "爸爸" to ("bà" to "ba"),
        "哥哥" to ("gē" to "ge"),
        "姐姐" to ("jiě" to "jie"),
        "弟弟" to ("dì" to "di"),
        "妹妹" to ("mèi" to "mei"),
        "爷爷" to ("yé" to "ye"),
        "奶奶" to ("nǎi" to "nai"),
        "宝宝" to ("bǎo" to "bao"),
        "娃娃" to ("wá" to "wa"),
        "星星" to ("xīng" to "xing"),
        "月亮" to ("yuè" to "liang"),
        "太阳" to ("tài" to "yang"),
        "云朵" to ("yún" to "duǒ"),
        "漂亮" to ("piào" to "liang"),
        "喜欢" to ("xǐ" to "huan"),
        "知道" to ("zhī" to "dao"),
        "告诉" to ("gào" to "su"),
        "时候" to ("shí" to "hou"),
        "地方" to ("dì" to "fang"),
        "东西" to ("dōng" to "xi"),
        "朋友" to ("péng" to "you"),
        "什么" to ("shén" to "me"),
        "怎么" to ("zěn" to "me"),
        "这么" to ("zhè" to "me"),
        "那么" to ("nà" to "me"),
        "我们" to ("wǒ" to "men"),
        "你们" to ("nǐ" to "men"),
        "他们" to ("tā" to "men"),
        "她们" to ("tā" to "men"),
        "它们" to ("tā" to "men"),
        "人们" to ("rén" to "men"),
        "尾巴" to ("wěi" to "ba"),
        "嘴巴" to ("zuǐ" to "ba"),
        "耳朵" to ("ěr" to "duo"),
        "眼睛" to ("yǎn" to "jing"),
        "鼻子" to ("bí" to "zi"),
        "肚子" to ("dù" to "zi"),
        "孩子" to ("hái" to "zi"),
        "猴子" to ("hóu" to "zi"),
        "兔子" to ("tù" to "zi"),
        "狮子" to ("shī" to "zi"),
        "桌子" to ("zhuō" to "zi"),
        "房子" to ("fáng" to "zi"),
        "盒子" to ("hé" to "zi"),
        "故事" to ("gù" to "shi"),
        "衣服" to ("yī" to "fu"),
        "狐狸" to ("hú" to "li"),
        "玻璃" to ("bō" to "li"),
        "聪明" to ("cōng" to "ming"),
        "暖和" to ("nuǎn" to "huo"),
        "热闹" to ("rè" to "nao"),
        "迷糊" to ("mí" to "hu"),
        "明白" to ("míng" to "bai"),
        "先生" to ("xiān" to "sheng"),
        "早上" to ("zǎo" to "shang"),
        "晚上" to ("wǎn" to "shang"),
        "天上" to ("tiān" to "shang"),
        "地上" to ("dì" to "shang"),
        "身上" to ("shēn" to "shang"),
        "头上" to ("tóu" to "shang"),
        "里边" to ("lǐ" to "bian"),
        "外边" to ("wài" to "bian"),
        "前边" to ("qián" to "bian"),
        "后边" to ("hòu" to "bian"),
        "上边" to ("shàng" to "bian"),
        "下边" to ("xià" to "bian"),
        "左边" to ("zuǒ" to "bian"),
        "右边" to ("yòu" to "bian"),
        "出来" to ("chū" to "lai"),
        "进来" to ("jìn" to "lai"),
        "回来" to ("huí" to "lai"),
        "起来" to ("qǐ" to "lai"),
        "过来" to ("guò" to "lai"),
        "下来" to ("xià" to "lai"),
        "上来" to ("shàng" to "lai"),
        "出去" to ("chū" to "qu"),
        "进去" to ("jìn" to "qu"),
        "回去" to ("huí" to "qu"),
        "过去" to ("guò" to "qu"),
        "下去" to ("xià" to "qu"),
        "上去" to ("shàng" to "qu"),
    )

    // Common children's story polyphone phrase overrides to guarantee textbook accuracy
    private val PHRASE_POLYPHONE_OVERRIDES = listOf(
        "长大了" to listOf("zhǎng", "dà", "le"),
        "长大" to listOf("zhǎng", "dà"),
        "船长" to listOf("chuán", "zhǎng"),
        "队长" to listOf("duì", "zhǎng"),
        "校长" to listOf("xiào", "zhǎng"),
        "家长" to listOf("jiā", "zhǎng"),
        "生长" to listOf("shēng", "zhǎng"),
        "长出" to listOf("zhǎng", "chū"),
        "睡着了" to listOf("shuì", "zháo", "le"),
        "睡着" to listOf("shuì", "zháo"),
        "着急" to listOf("zháo", "jí"),
        "着火" to listOf("zháo", "huǒ"),
        "找着了" to listOf("zhǎo", "zháo", "le"),
        "看着" to listOf("kàn", "zhe"),
        "听着" to listOf("tīng", "zhe"),
        "走着" to listOf("zǒu", "zhe"),
        "跑着" to listOf("pǎo", "zhe"),
        "飞着" to listOf("fēi", "zhe"),
        "笑着" to listOf("xiào", "zhe"),
        "唱着" to listOf("chàng", "zhe"),
        "坐着" to listOf("zuò", "zhe"),
        "拿着" to listOf("ná", "zhe"),
        "抱着" to listOf("bào", "zhe"),
        "背着" to listOf("bēi", "zhe"),
        "带着" to listOf("dài", "zhe"),
        "跟着" to listOf("gēn", "zhe"),
        "睡觉" to listOf("shuì", "jiào"),
        "午觉" to listOf("wǔ", "jiào"),
        "一觉醒来" to listOf("yī", "jiào", "xǐng", "lai"),
        "觉得" to listOf("jué", "de"),
        "快乐" to listOf("kuài", "lè"),
        "欢乐" to listOf("huān", "lè"),
        "音乐" to listOf("yīn", "yuè"),
        "乐器" to listOf("yuè", "qì"),
        "跑得快" to listOf("pǎo", "de", "kuài"),
        "跑得很快" to listOf("pǎo", "de", "hěn", "kuài"),
        "飞得高" to listOf("fēi", "de", "gāo"),
        "跳得高" to listOf("tiào", "de", "gāo"),
        "玩得很开心" to listOf("wán", "de", "hěn", "kāi", "xīn"),
        "不由得" to listOf("bù", "yóu", "de"),
        "舍不得" to listOf("shě", "bu", "de"),
        "恨不得" to listOf("hèn", "bu", "de"),
        "受不了" to listOf("shòu", "bù", "liǎo"),
        "不得了" to listOf("bù", "dé", "liǎo"),
        "饼干" to listOf("bǐng", "gān"),
        "干净" to listOf("gān", "jìng"),
        "干杯" to listOf("gān", "bēi"),
        "晒干" to listOf("shài", "gān"),
        "擦干" to listOf("cā", "gān"),
        "树干" to listOf("shù", "gàn"),
        "干活" to listOf("gàn", "huó"),
        "能干" to listOf("néng", "gàn"),
        "好奇" to listOf("hào", "qí"),
        "好客" to listOf("hào", "kè"),
        "爱好" to listOf("ài", "hào"),
        "好吃" to listOf("hǎo", "chī"),
        "好看" to listOf("hǎo", "kàn"),
        "好玩" to listOf("hǎo", "wán"),
        "好听" to listOf("hǎo", "tīng"),
        "好极了" to listOf("hǎo", "jí", "le"),
        "太棒了" to listOf("tài", "bàng", "le"),
        "只有" to listOf("zhǐ", "yǒu"),
        "只是" to listOf("zhǐ", "shì"),
        "只好" to listOf("zhǐ", "hǎo"),
        "只要" to listOf("zhǐ", "yào"),
        "一只" to listOf("yī", "zhī"),
        "两只" to listOf("liǎng", "zhī"),
        "三只" to listOf("sān", "zhī"),
        "几只" to listOf("jǐ", "zhī"),
        "种子" to listOf("zhǒng", "zi"),
        "种花" to listOf("zhòng", "huā"),
        "种树" to listOf("zhòng", "shù"),
        "头发" to listOf("tóu", "fa"),
        "发现" to listOf("fā", "xiàn"),
        "出发" to listOf("chū", "fā"),
        "没关系" to listOf("méi", "guān", "xi"),
        "答应" to listOf("dā", "ying"),
        "数一数" to listOf("shǔ", "yi", "shǔ"),
        "一模一样" to listOf("yī", "mú", "yī", "yàng"),
    )

    private val SENTENCE_FINAL_PARTICLES = mapOf(
        '吗' to "ma",
        '呢' to "ne",
        '吧' to "ba",
        '呀' to "ya",
        '哇' to "wa",
        '啦' to "la",
        '喽' to "lou",
    )

    fun annotateToCells(text: String): List<RubyCharCell> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<RubyCharCell>()
        var i = 0
        while (i < text.length) {
            if (StoryBookFormatter.isCjk(text[i])) {
                val start = i
                while (i < text.length && StoryBookFormatter.isCjk(text[i])) {
                    i++
                }
                val hanziRun = text.substring(start, i)
                val pinyins = annotateHanziRun(hanziRun)
                for (k in hanziRun.indices) {
                    out.add(
                        RubyCharCell(
                            charText = hanziRun[k].toString(),
                            pinyin = pinyins.getOrElse(k) { "" },
                            isHanzi = true,
                        ),
                    )
                }
            } else {
                val ch = text[i]
                if (ch.isLetterOrDigit() && ch.code < 128) {
                    val start = i
                    while (i < text.length && text[i].isLetterOrDigit() && text[i].code < 128) {
                        i++
                    }
                    out.add(
                        RubyCharCell(
                            charText = text.substring(start, i),
                            pinyin = "",
                            isHanzi = false,
                        ),
                    )
                } else {
                    if (ch != '\n' && ch != '\r') {
                        out.add(RubyCharCell(charText = ch.toString(), pinyin = "", isHanzi = false))
                    }
                    i++
                }
            }
        }
        return out
    }

    /**
     * Groups cells into non-breaking `RubyCluster`s so opening/trailing punctuation never wraps onto
     * forbidden line edges when laid out with `FlowRow`.
     */
    fun annotateParagraphToClusters(paragraph: String): List<RubyCluster> {
        val cells = annotateToCells(paragraph.trim())
        if (cells.isEmpty()) return emptyList()

        val clusters = mutableListOf<RubyCluster>()
        val pendingLeading = mutableListOf<RubyCharCell>()
        var idx = 0

        while (idx < cells.size) {
            val cell = cells[idx]
            val singleChar = cell.charText.singleOrNull()

            if (!cell.isHanzi && singleChar != null && singleChar in OPENING_PUNCT) {
                pendingLeading.add(cell)
                idx++
                continue
            }

            if (!cell.isHanzi && singleChar != null && singleChar in TRAILING_PUNCT && clusters.isNotEmpty() && pendingLeading.isEmpty()) {
                val prev = clusters.removeAt(clusters.lastIndex)
                clusters.add(RubyCluster(prev.cells + cell))
                idx++
                continue
            }

            val group = mutableListOf<RubyCharCell>()
            group.addAll(pendingLeading)
            pendingLeading.clear()
            group.add(cell)
            idx++

            // Attach any immediately following trailing punctuation (`，`, `。`, `。”`, `……`) to this character
            while (idx < cells.size) {
                val next = cells[idx]
                val nextCh = next.charText.singleOrNull()
                if (!next.isHanzi && nextCh != null && nextCh in TRAILING_PUNCT) {
                    group.add(next)
                    idx++
                } else {
                    break
                }
            }
            clusters.add(RubyCluster(group))
        }

        if (pendingLeading.isNotEmpty()) {
            if (clusters.isNotEmpty()) {
                val prev = clusters.removeAt(clusters.lastIndex)
                clusters.add(RubyCluster(prev.cells + pendingLeading))
            } else {
                clusters.add(RubyCluster(pendingLeading.toList()))
            }
        }
        return clusters
    }

    internal fun annotateHanziRun(hanziRun: String): List<String> {
        if (hanziRun.isEmpty()) return emptyList()
        val rawStr = try {
            PinyinHelper.toPinyin(hanziRun, PinyinStyleEnum.DEFAULT, " ")
        } catch (_: Exception) {
            ""
        }
        val tokens = rawStr.trim().split(Regex("\\s+")).toMutableList()
        if (tokens.size != hanziRun.length) {
            tokens.clear()
            for (ch in hanziRun) {
                val single = try {
                    PinyinHelper.toPinyin(ch.toString(), PinyinStyleEnum.DEFAULT, " ").trim()
                } catch (_: Exception) {
                    ""
                }
                tokens.add(single.split(Regex("\\s+")).firstOrNull().orEmpty())
            }
        }

        // 1. Apply common structural particle defaults (`的 -> de`, `了 -> le` unless overridden below)
        for (idx in hanziRun.indices) {
            when (hanziRun[idx]) {
                '的' -> {
                    val prev = if (idx > 0) hanziRun[idx - 1] else '\u0000'
                    if (prev != '目' && prev != '的') tokens[idx] = "de"
                }
                '了' -> {
                    val next = if (idx + 1 < hanziRun.length) hanziRun[idx + 1] else '\u0000'
                    if (next != '解' && next != '不') tokens[idx] = "le"
                }
                '着' -> {
                    // Default post-verbal aspect particle `V + 着` -> `zhe` unless overridden
                    if (idx > 0) tokens[idx] = "zhe"
                }
                '地' -> {
                    // Adverbial `Adj + 地 + V` (e.g., 开心地笑 / 慢慢地走) -> `de`
                    if (idx >= 2 && idx + 1 < hanziRun.length && hanziRun[idx - 1] != '土' && hanziRun[idx - 1] != '大' && hanziRun[idx - 1] != '草' && hanziRun[idx - 1] != '空') {
                        tokens[idx] = "de"
                    }
                }
            }
        }

        // 2. Apply neutral-tone 2-character lexicon (`妈妈 -> mā ma`, `月亮 -> yuè liang`, etc.)
        for ((word, pair) in NEUTRAL_SECOND_SYLLABLE_WORDS) {
            var pos = hanziRun.indexOf(word)
            while (pos >= 0) {
                tokens[pos] = pair.first
                tokens[pos + 1] = pair.second
                pos = hanziRun.indexOf(word, pos + 1)
            }
        }

        // 3. Apply explicit phrase polyphone overrides (longest phrases listed first)
        val matchedMask = BooleanArray(hanziRun.length)
        for ((phrase, pyList) in PHRASE_POLYPHONE_OVERRIDES) {
            var pos = hanziRun.indexOf(phrase)
            while (pos >= 0) {
                val overlap = (pos until (pos + phrase.length)).any { matchedMask[it] }
                if (!overlap) {
                    for (j in phrase.indices) {
                        tokens[pos + j] = pyList[j]
                        matchedMask[pos + j] = true
                    }
                }
                pos = hanziRun.indexOf(phrase, pos + 1)
            }
        }

        // 4. Sentence-end modal particles (`吗/呢/吧/呀/啦`)
        val lastIdx = hanziRun.lastIndex
        SENTENCE_FINAL_PARTICLES[hanziRun[lastIdx]]?.let { neutral ->
            tokens[lastIdx] = neutral
        }

        // 5. People's Education Press (部编版一年级语文) tone sandhi for `一` and `不`
        applyYiBuToneSandhi(hanziRun, tokens)

        return tokens
    }

    private val FOURTH_TONE_VOWELS = setOf('à', 'ò', 'è', 'ì', 'ù', 'ǜ')
    private val FIRST_SECOND_THIRD_VOWELS = setOf('ā', 'ō', 'ē', 'ī', 'ū', 'ǖ', 'á', 'ó', 'é', 'í', 'ú', 'ǘ', 'ǎ', 'ǒ', 'ě', 'ǐ', 'ǔ', 'ǚ')
    private val ORDINAL_PREV_CHARS = setOf('第', '初', '唯', '统', '万', '十', '百', '零')

    private fun detectSyllableTone(py: String): Int {
        if (py.any { it in FOURTH_TONE_VOWELS }) return 4
        if (py.any { it in FIRST_SECOND_THIRD_VOWELS }) return 123
        return 0
    }

    private fun applyYiBuToneSandhi(hanziRun: String, tokens: MutableList<String>) {
        for (i in 0 until hanziRun.length - 1) {
            val ch = hanziRun[i]
            val nextCh = hanziRun[i + 1]
            val nextTone = detectSyllableTone(tokens[i + 1])
            if (ch == '不' && tokens[i] == "bù") {
                // Reduplicative A-not-A (`好不好`, `去不去`) -> neutral `bu`; before 4th tone -> `bú`
                if (i > 0 && hanziRun[i - 1] == nextCh) {
                    tokens[i] = "bu"
                } else if (nextTone == 4) {
                    tokens[i] = "bú"
                }
            } else if (ch == '一' && tokens[i] == "yī") {
                val isOrdinalOrNumber = (i > 0 && hanziRun[i - 1] in ORDINAL_PREV_CHARS) ||
                    nextCh in "一二三四五六七八九十百千万亿"
                if (!isOrdinalOrNumber) {
                    if (i > 0 && hanziRun[i - 1] == nextCh) {
                        // `看一看`, `想一想` -> neutral `yi`
                        tokens[i] = "yi"
                    } else if (nextTone == 4) {
                        tokens[i] = "yí"
                    } else if (nextTone == 123) {
                        tokens[i] = "yì"
                    }
                }
            }
        }
    }
}
