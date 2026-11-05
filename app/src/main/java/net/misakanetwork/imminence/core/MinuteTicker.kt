package net.misakanetwork.imminence.core

import android.os.Handler
import net.misakanetwork.imminence.overlay.OverlayController

/**
 * 整个应用唯一的周期任务：在「分钟数将要跳变」的那一刻刷新一次悬浮窗。
 *
 * 因为显示精度是分钟，一天最多 1440 次唤醒；息屏时直接不排期。
 * 注意它只负责刷新显示，不负责计时——计时完全由事件时间戳决定。
 */
class MinuteTicker(
    private val handler: Handler,
    private val engine: TimelineEngine,
    private val overlay: OverlayController,
) {

    private val tick = Runnable {
        render()
        rearm()
    }

    fun renderNow() = render()

    /** 把下一次刷新对齐到当前累计时长跨过整分钟的时刻。 */
    fun rearm() {
        handler.removeCallbacks(tick)
        if (!engine.isRunning) {
            // 没在计时就把窗口收起来，别把过期的数字留在屏幕上。
            overlay.render(null, 0L)
            return
        }

        val used = engine.millisToday(System.currentTimeMillis())
        val delay = MINUTE_MS - used % MINUTE_MS + SLOP_MS
        handler.postDelayed(tick, delay.coerceAtLeast(SLOP_MS))
    }

    fun cancel() = handler.removeCallbacks(tick)

    private fun render() {
        if (!engine.isRunning) {
            overlay.render(null, 0L)
            return
        }
        overlay.render(engine.currentPackage(), engine.millisToday(System.currentTimeMillis()))
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val SLOP_MS = 80L
    }
}
