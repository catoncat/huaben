package com.luna.storynote.literacy

import com.github.houbb.pinyin.constant.enums.PinyinStyleEnum
import com.github.houbb.pinyin.util.PinyinHelper
import com.luna.storynote.model.ClausePhraseSpan

data class PinyinSpellingBreakdown(
    val fullSyllable: String,
    val initial: String,
    val medial: String,
    val finalPart: String,
    val isWholeSyllable: Boolean,
) {
    val formulaText: String
        get() = when {
            isWholeSyllable -> fullSyllable
            initial.isEmpty() -> fullSyllable
            medial.isNotEmpty() -> "$initial · $medial · $finalPart"
            else -> "$initial · $finalPart"
        }
}

data class WordSpan(
    val word: String,
    val startCharIndex: Int,
    val endCharIndex: Int,
    val pinyins: List<String>,
)

object LiteracyEngine {
    private val WHOLE_RECOGNITION_BASE = setOf(
        "zhi", "chi", "shi", "ri",
        "zi", "ci", "si",
        "yi", "wu", "yu",
        "ye", "yue", "yuan",
        "yin", "yun", "ying",
    )

    private val INITIALS_ORDERED = listOf(
        "zh", "ch", "sh",
        "b", "p", "m", "f",
        "d", "t", "n", "l",
        "g", "k", "h",
        "j", "q", "x",
        "r", "z", "c", "s",
        "y", "w",
    )

    private val TONE_STRIP_MAP = mapOf(
        'ā' to 'a', 'á' to 'a', 'ǎ' to 'a', 'à' to 'a',
        'ō' to 'o', 'ó' to 'o', 'ǒ' to 'o', 'ò' to 'o',
        'ē' to 'e', 'é' to 'e', 'ě' to 'e', 'è' to 'e',
        'ī' to 'i', 'í' to 'i', 'ǐ' to 'i', 'ì' to 'i',
        'ū' to 'u', 'ú' to 'u', 'ǔ' to 'u', 'ù' to 'u',
        'ǖ' to 'v', 'ǘ' to 'v', 'ǚ' to 'v', 'ǜ' to 'v', 'ü' to 'v',
    )

    private val COMMON_WORDS_BY_PINYIN: Map<String, List<String>> by lazy {
        buildHomophoneIndex()
    }

    fun stripToneToAscii(syllable: String): String =
        buildString(syllable.length) {
            for (ch in syllable.lowercase()) {
                append(TONE_STRIP_MAP[ch] ?: ch)
            }
        }

    fun analyzeSpelling(syllable: String): PinyinSpellingBreakdown {
        val clean = syllable.trim()
        if (clean.isEmpty()) {
            return PinyinSpellingBreakdown("", "", "", "", false)
        }
        val base = stripToneToAscii(clean)
        if (base in WHOLE_RECOGNITION_BASE) {
            return PinyinSpellingBreakdown(
                fullSyllable = clean,
                initial = "",
                medial = "",
                finalPart = clean,
                isWholeSyllable = true,
            )
        }

        val init = INITIALS_ORDERED.firstOrNull { base.startsWith(it) }.orEmpty()
        if (init.isEmpty()) {
            return PinyinSpellingBreakdown(clean, "", "", clean, false)
        }

        val restToned = clean.substring(init.length)
        val restBase = base.substring(init.length)

        if (init in setOf("j", "q", "x") && restBase.startsWith("u")) {
            if (restBase.startsWith("uan") || restBase.startsWith("iong")) {
                val medial = if (restBase.startsWith("uan")) "ü" else "i"
                val fin = restToned.substring(1)
                return PinyinSpellingBreakdown(clean, init, medial, fin, false)
            }
            val restoredUmlaut = restoreUmlautOnFirstU(restToned)
            return PinyinSpellingBreakdown(clean, init, "", restoredUmlaut, false)
        }

        val threePartMedial = when {
            restBase in setOf("ia", "iao", "ian", "iang", "iong") && restToned.length >= 2 -> "i"
            restBase in setOf("ua", "uo", "uai", "uang", "uan") && restToned.length >= 2 -> "u"
            else -> ""
        }
        if (threePartMedial.isNotEmpty() && TONE_STRIP_MAP[restToned[0]] == null) {
            return PinyinSpellingBreakdown(
                fullSyllable = clean,
                initial = init,
                medial = threePartMedial,
                finalPart = restToned.substring(1),
                isWholeSyllable = false,
            )
        }

        return PinyinSpellingBreakdown(
            fullSyllable = clean,
            initial = init,
            medial = "",
            finalPart = restToned,
            isWholeSyllable = false,
        )
    }

    private fun restoreUmlautOnFirstU(s: String): String {
        if (s.isEmpty()) return s
        val mapped = when (s[0]) {
            'u' -> 'ü'
            'ū' -> 'ǖ'
            'ú' -> 'ǘ'
            'ǔ' -> 'ǚ'
            'ù' -> 'ǜ'
            else -> s[0]
        }
        return mapped + s.substring(1)
    }

    /**
     * Phrase-first word segmentation powered by offline `JiebaWordSegmenter` (`SegMode.SEARCH`),
     * paired with Grade-1 `PinyinAnnotator`.
     */
    @JvmOverloads
    fun findWordAt(
        sentence: String,
        clickedCharIndex: Int,
        contextualPhrases: List<ClausePhraseSpan> = emptyList(),
    ): WordSpan? {
        if (clickedCharIndex !in sentence.indices) return null
        if (!StoryBookFormatter.isCjk(sentence[clickedCharIndex])) return null

        val cells = PinyinAnnotator.annotateToCells(sentence)
        val charPinyins = Array(sentence.length) { "" }
        for (cell in cells) {
            if (cell.isHanzi && cell.startIndexInSentence in sentence.indices) {
                charPinyins[cell.startIndexInSentence] = cell.pinyin
            }
        }

        val contextualMatch = contextualPhrases
            .filter { span ->
                span.endOffset > span.startOffset &&
                    span.startOffset in sentence.indices &&
                    span.endOffset <= sentence.length &&
                    clickedCharIndex in span.startOffset until span.endOffset &&
                    sentence.substring(span.startOffset, span.endOffset) == span.word
            }
            .maxByOrNull { it.endOffset - it.startOffset }
        if (contextualMatch != null) {
            val pys = (contextualMatch.startOffset until contextualMatch.endOffset).map { charPinyins[it] }
            return WordSpan(
                word = contextualMatch.word,
                startCharIndex = contextualMatch.startOffset,
                endCharIndex = contextualMatch.endOffset,
                pinyins = pys,
            )
        }

        val token = JiebaWordSegmenter.findWordAt(sentence, clickedCharIndex)
        if (token != null) {
            val pys = (token.startOffset until token.endOffset).map { charPinyins[it] }
            return WordSpan(
                word = token.word,
                startCharIndex = token.startOffset,
                endCharIndex = token.endOffset,
                pinyins = pys,
            )
        }

        val ch = sentence[clickedCharIndex].toString()
        return WordSpan(ch, clickedCharIndex, clickedCharIndex + 1, listOf(charPinyins[clickedCharIndex]))
    }

    fun suggestHomophones(word: String, pinyins: List<String>): List<String> {
        if (word.isEmpty()) return emptyList()
        val key = pinyins.joinToString(" ") { stripToneToAscii(it) }
        val results = LinkedHashSet<String>()
        COMMON_WORDS_BY_PINYIN[key]?.forEach { cand ->
            if (cand != word) results.add(cand)
        }
        if (word.length == 1) {
            SINGLE_CHAR_HOMOPHONES[key]?.forEach { ch ->
                val s = ch.toString()
                if (s != word) results.add(s)
            }
        }
        return results.take(6)
    }

    private val SINGLE_CHAR_HOMOPHONES: Map<String, String> by lazy {
        val commonChars =
            "的一是在不了有和人这中大为上个国我以要他她它们时来用们生到作地于出就分对成会可主发年动同工也能下过子说产种面而方后多定行学法所民得经十三之进着等部度家电力里如水化高自二理起小物现实加量都两体制机当使点从业本去把性好应开它合还因由其些然前外天政四日那社义事平形相全表间样与关各重新线内数正心反你明看原又么利比或但质气第向道命此变条只没结解问意建月公无系军很情者最立代想已通并提直题党程展五果料象员革位入常文总次品式活设及管特件长求老头基资边流路级少图山统接知较将组见计别她手角期根论运农指几九区强放决西被干做必战先回则任取据处队南给色光门即保治北造百规热领七海口东导器压志世金增争济阶油思术极交受联什认六共权收证改清己美再采转更单风切打白教速花带安场身车例真务具万每目至达走积示议声报斗完类八离华名确才科张信马节话米整空元况今集温传土许步群广石记需段研界拉林律叫且究观越织装影算低持音众书布复容儿须际商非验连断深难近矿千周委素技备半办青省列习响约支般史感劳便团往酸历市克何除消构府称太准精值号率族维划选标写存候毛亲快效斯院查江型眼王按格养易置派层片始却专状育厂京识适属圆包火住调满县局照参红细引听该铁价严孔控桶勇涌永泳懂洞冻栋龙笼聋拢隆农浓弄蓝篮兰拦栏坐座做作左在再载戴带代袋待刻课客颗棵壳船串床窗星兴心新辛欣狐壶湖糊虎飞妃肥废费画化滑猫帽冒木牧幕象像向想箱香乡园员源远院愿玩碗晚弯湾拾食室视试音阴银印英鹰影映硬童铜桐筒痛钟忠终舟洲珠株蛛猪竹烛煮住助注祝抓爪砖庄壮追准捉桌字紫仔姿滋送松颂诵"
        val map = HashMap<String, StringBuilder>()
        for (ch in commonChars) {
            val py = try {
                stripToneToAscii(PinyinHelper.toPinyin(ch.toString(), PinyinStyleEnum.NORMAL, ""))
            } catch (_: Exception) {
                ""
            }
            if (py.isNotEmpty()) {
                val sb = map.getOrPut(py) { StringBuilder() }
                if (sb.indexOf(ch) < 0) sb.append(ch)
            }
        }
        map.mapValues { it.value.toString() }
    }

    private fun buildHomophoneIndex(): Map<String, List<String>> {
        val words = listOf(
            "恐龙", "霸王龙", "翼龙", "三角龙", "喷火龙", "猫咪", "狐狸", "白兔", "松鼠", "灰狼",
            "狮子", "老虎", "猴子", "长颈鹿", "鲸鱼", "海豚", "独角兽", "美人鱼", "外星人", "机器人",
            "棉花糖", "冰淇淋", "巧克力", "饼干", "草莓", "蛋糕", "棒棒糖", "爆米花", "苹果", "西瓜",
            "宇宙飞船", "热气球", "降落伞", "潜水艇", "消防车", "救护车", "轮船", "飞机", "直升机", "过山车",
            "彩虹", "月亮", "太阳", "星星", "云朵", "闪电", "暴风雨", "龙卷风", "森林", "火山",
            "宝藏", "秘密", "魔法", "城堡", "迷宫", "山洞", "皇冠", "宝石", "宝剑", "盾牌",
            "知道", "之道", "直到", "已经", "眼睛", "眼镜", "再见", "看见", "听见", "发现",
            "现在", "正在", "实在", "再说", "再来", "在家", "再一次", "做饭", "坐下", "座位", "工作", "作业",
            "他们", "她们", "它们", "大家", "朋友", "伙伴", "一起", "一直", "一定", "一样",
            "高兴", "开心", "快乐", "勇敢", "聪明", "漂亮", "可爱", "厉害", "神奇", "奇怪",
            "小心", "着急", "害怕", "生气", "伤心", "难过", "温暖", "舒服", "认真", "仔细",
        )
        val map = HashMap<String, MutableList<String>>()
        for (w in words) {
            val key = PinyinAnnotator.annotateHanziRun(w).joinToString(" ") { stripToneToAscii(it) }
            map.getOrPut(key) { mutableListOf() }.add(w)
        }
        return map
    }
}
