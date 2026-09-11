package com.qing.hachimi.player

/**
 * 极简 LRC 解析器：支持多时间戳行、[offset:±ms] 全局偏移、逐字/译文按时间合并。
 */
data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {

    private val stampRegex = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetRegex = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)

    fun parse(lrc: String): List<LrcLine> {
        if (lrc.isBlank()) return emptyList()
        val offset = offsetRegex.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val out = mutableListOf<LrcLine>()
        lrc.lineSequence().forEach { raw ->
            val stamps = stampRegex.findAll(raw).toList()
            if (stamps.isEmpty()) return@forEach
            var text = raw
            stamps.forEach { text = text.replace(it.value, "") }
            text = text.trim()
            if (text.isBlank()) return@forEach
            for (s in stamps) {
                val min = s.groupValues[1].toLong()
                val sec = s.groupValues[2].toLong()
                val fracStr = s.groupValues[3]
                val frac = when (fracStr.length) {
                    0 -> 0L
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                val t = min * 60000 + sec * 1000 + frac - offset
                out += LrcLine(if (t < 0) 0L else t, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }

    /** 把翻译歌词按时间点合并进主歌词（原文 / 译文）。 */
    fun mergeTranslation(main: List<LrcLine>, trans: List<LrcLine>): List<LrcLine> {
        if (main.isEmpty() || trans.isEmpty()) return main
        val byTime = HashMap<Long, String>(trans.size)
        trans.forEach { byTime[it.timeMs] = it.text }
        return main.map { l ->
            byTime[l.timeMs]?.let { t -> if (t.isNotBlank()) l.copy(text = l.text + " / " + t) else l } ?: l
        }
    }

    /** 二分查找当前播放位置对应的行下标（最后一行 timeMs <= position）。无匹配返回 -1。 */
    fun lineIndexFor(lines: List<LrcLine>, positionMs: Long, hint: Int = -1): Int {
        if (lines.isEmpty()) return -1
        if (positionMs < lines.first().timeMs) return -1
        if (hint in lines.indices && positionMs >= lines[hint].timeMs &&
            (hint == lines.size - 1 || positionMs < lines[hint + 1].timeMs)
        ) return hint
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
