package net.misakanetwork.imminence.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 以「本地日期字符串」（yyyy-MM-dd）为单位的日界工具。
 *
 * 所有换算都走系统默认时区，夏令时与手动改时间由 java.time 负责，
 * 代码里不要自己用 86_400_000 去推零点。
 */
object Days {

    private fun zone(): ZoneId = ZoneId.systemDefault()

    /** 某个时间戳落在哪一天。 */
    fun keyOf(timestamp: Long): String =
        Instant.ofEpochMilli(timestamp).atZone(zone()).toLocalDate().toString()

    /** 某一天（时间戳所属那天）的零点。 */
    fun startOf(timestamp: Long): Long {
        val z = zone()
        return Instant.ofEpochMilli(timestamp)
            .atZone(z)
            .toLocalDate()
            .atStartOfDay(z)
            .toInstant()
            .toEpochMilli()
    }

    /** 某一天的结束，也就是次日零点。 */
    fun nextStart(dayKey: String): Long {
        val z = zone()
        return LocalDate.parse(dayKey)
            .plusDays(1)
            .atStartOfDay(z)
            .toInstant()
            .toEpochMilli()
    }
}
