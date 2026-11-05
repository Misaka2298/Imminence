package net.misakanetwork.imminence.data

/** 一段落在同一天内的时间区间。 */
data class Segment(val day: String, val start: Long, val end: Long) {
    val millis: Long get() = end - start
}

/**
 * 把任意区间按本地零点切分。
 *
 * 跨零点的一次结算必须拆成两段，否则「今天」的累计会混进昨天的时长，
 * 且零点之后的第一分钟会显示成负值或跳变。
 */
object DaySplit {

    fun segments(from: Long, to: Long): List<Segment> {
        if (to <= from) return emptyList()

        val out = ArrayList<Segment>(2)
        var cursor = from
        while (cursor < to) {
            val day = Days.keyOf(cursor)
            val boundary = Days.nextStart(day)
            // boundary <= cursor 只可能出现在时钟回拨等极端情况下，此时直接收尾，避免死循环。
            val end = if (boundary in (cursor + 1)..to) boundary else to
            out += Segment(day, cursor, end)
            cursor = end
        }
        return out
    }
}
