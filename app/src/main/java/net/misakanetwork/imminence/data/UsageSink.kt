package net.misakanetwork.imminence.data

/**
 * [net.misakanetwork.imminence.core.TimelineEngine] 的落点。
 *
 * 拆成接口是为了让时间轴逻辑保持纯 JVM 可测，不依赖 Android 与 SQLite。
 */
interface UsageSink {

    /** 该包名在 [now] 所属那一天的累计毫秒数。 */
    fun totalToday(pkg: String, now: Long): Long

    /** 把 [from, to) 记到 [pkg] 名下；跨零点由实现自己切分。 */
    fun add(pkg: String, from: Long, to: Long)
}
