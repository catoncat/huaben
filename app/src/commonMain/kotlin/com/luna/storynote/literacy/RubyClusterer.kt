package com.luna.storynote.literacy

/**
 * Single character cell inside a ruby-annotated unit.
 * Punctuation marks have `pinyin = ""` so their vertical baseline aligns cleanly with Hanzi.
 */
data class RubyCharCell(
    val charText: String,
    val pinyin: String,
    val isHanzi: Boolean,
    val startIndexInSentence: Int = 0,
)

/**
 * Non-breaking visual cluster respecting Chinese GB/T 15834 line-breaking rules (避头尾禁则):
 * opening quotes/brackets stay attached to the following character, and trailing commas/periods/quotes
 * stay attached to the preceding character so punctuation never dangles at the start of a line.
 */
data class RubyCluster(
    val cells: List<RubyCharCell>,
)

object RubyClusterer {
    val OPENING_PUNCT = setOf('“', '‘', '《', '（', '(', '[', '【', '「', '『')
    val TRAILING_PUNCT = setOf(
        '，', '。', '！', '？', '、', '；', '：', '”', '’', '》', '）', ')', ']', '】', '」', '』',
        '…', '—', ',', '.', '!', '?', ';', ':',
    )

    fun annotateAndCluster(text: String): List<RubyCluster> =
        clusterCells(PinyinAnnotator.annotateToCells(text))

    fun clusterCells(cells: List<RubyCharCell>): List<RubyCluster> {
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

            if (!cell.isHanzi && singleChar != null && singleChar in TRAILING_PUNCT &&
                clusters.isNotEmpty() && pendingLeading.isEmpty()
            ) {
                val prev = clusters.removeAt(clusters.lastIndex)
                clusters.add(RubyCluster(prev.cells + cell))
                idx++
                continue
            }

            val group = ArrayList<RubyCharCell>()
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
}
