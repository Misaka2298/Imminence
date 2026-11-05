package net.misakanetwork.imminence.core

/**
 * 从 [android.app.usage.UsageEvents] 归一化出来的事件，只保留真正影响计时的几种。
 *
 * 注意这里刻意用自定义常量而不是直接用系统常量：引擎是纯 JVM 代码，
 * 不引入 Android 依赖才能在单元测试里跑。
 */
data class RawEvent(val ts: Long, val type: Int, val pkg: String) {

    companion object {
        const val RESUMED = 1
        const val SCREEN_ON = 2
        const val SCREEN_OFF = 3
        const val KEYGUARD_SHOWN = 4
        const val KEYGUARD_HIDDEN = 5
        const val SHUTDOWN = 6
    }
}
