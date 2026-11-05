package net.misakanetwork.imminence.core

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
/**
 * [UsageStatsManager] 的薄封装：只取出会影响计时的几类事件。
 *
 * 这里刻意不做任何归属判断——判断全在 [TimelineEngine] 里，
 * 采样层保持无状态，便于替换（将来接无障碍加速器时不用改这里）。
 */
class ForegroundSampler(context: Context) {

    private val usageStats: UsageStatsManager? =
        context.getSystemService(UsageStatsManager::class.java)

    private val scratch = UsageEvents.Event()

    fun since(from: Long, to: Long = System.currentTimeMillis()): List<RawEvent> {
        if (to <= from) return emptyList()
        val stats = usageStats ?: return emptyList()

        val events = runCatching { stats.queryEvents(from, to) }.getOrNull()
            ?: return emptyList()

        val result = ArrayList<RawEvent>(16)
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(scratch)) break

            val type = when (scratch.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> RawEvent.RESUMED
                UsageEvents.Event.SCREEN_INTERACTIVE -> RawEvent.SCREEN_ON
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> RawEvent.SCREEN_OFF
                UsageEvents.Event.KEYGUARD_SHOWN -> RawEvent.KEYGUARD_SHOWN
                UsageEvents.Event.KEYGUARD_HIDDEN -> RawEvent.KEYGUARD_HIDDEN
                UsageEvents.Event.DEVICE_SHUTDOWN -> RawEvent.SHUTDOWN
                else -> 0
            }
            if (type != 0) {
                result += RawEvent(scratch.timeStamp, type, scratch.packageName.orEmpty())
            }
        }
        return result
    }

    /**
     * 系统统计的「今天每个应用在前台待了多久」。
     *
     * 这才是用户想看的数字：它包含本应用启动之前的使用，也包含应用被杀掉那段时间的使用。
     * 我们自己的累计只覆盖「服务运行期间」，所以两者要取较大值合并。
     */
    fun totalsToday(dayStart: Long, now: Long): Map<String, Long> {
        val stats = usageStats ?: return emptyMap()
        val result = HashMap<String, Long>()
        runCatching {
            stats.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dayStart, now)
                ?.forEach { item ->
                    val millis = item.totalTimeInForeground
                    if (millis > 0L) result[item.packageName] = millis
                }
        }
        return result
    }
}
