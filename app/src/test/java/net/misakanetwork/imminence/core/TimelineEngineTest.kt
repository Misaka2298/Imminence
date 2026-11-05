package net.misakanetwork.imminence.core

import net.misakanetwork.imminence.data.UsageSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineEngineTest {

    private class FakeSink : UsageSink {
        private val totals = HashMap<String, Long>()
        override fun totalToday(pkg: String, now: Long): Long = totals[pkg] ?: 0L
        override fun add(pkg: String, from: Long, to: Long) {
            totals[pkg] = (totals[pkg] ?: 0L) + (to - from)
        }
    }

    private val base = 1_700_000_000_000L

    private fun event(offset: Long, type: Int, pkg: String = "") = RawEvent(base + offset, type, pkg)

    private fun makeEngine(
        sink: FakeSink,
        excluded: Set<String> = emptySet(),
        ignored: Set<String> = emptySet(),
    ) = TimelineEngine(
        sink = sink,
        excluded = { excluded },
        ignored = { ignored },
    )

    @Test
    fun `switching app settles the previous interval`() {
        val sink = FakeSink()
        val engine = makeEngine(sink)
        // 游标放在第一个事件之前，否则 base 时刻的事件会被 ts <= lastTs 丢掉
        engine.start(base - 1)

        engine.onEvents(listOf(event(0, RawEvent.RESUMED, "a")))
        engine.onEvents(listOf(event(10_000, RawEvent.RESUMED, "b")))

        assertEquals(10_000L, sink.totalToday("a", base))
        assertEquals(0L, sink.totalToday("b", base))
        assertEquals(20_000L, engine.millisToday(base + 30_000))
        assertEquals("b", engine.currentPackage())
    }

    @Test
    fun `screen off stops the clock`() {
        val sink = FakeSink()
        val engine = makeEngine(sink)
        // 游标放在第一个事件之前，否则 base 时刻的事件会被 ts <= lastTs 丢掉
        engine.start(base - 1)

        engine.onEvents(
            listOf(
                event(0, RawEvent.RESUMED, "a"),
                event(10_000, RawEvent.SCREEN_OFF),
            ),
        )

        assertEquals(10_000L, engine.millisToday(base + 600_000))
        assertFalse(engine.isRunning)
    }

    @Test
    fun `excluded package pauses the clock and is not attributed`() {
        val sink = FakeSink()
        val engine = makeEngine(sink, excluded = setOf("self"))
        // 游标放在第一个事件之前，否则 base 时刻的事件会被 ts <= lastTs 丢掉
        engine.start(base - 1)

        engine.onEvents(listOf(event(0, RawEvent.RESUMED, "a")))
        engine.onEvents(listOf(event(5_000, RawEvent.RESUMED, "self")))
        engine.onEvents(listOf(event(15_000, RawEvent.RESUMED, "b")))

        // 只记到切走的那一刻；自己占用的这段时间不计入任何应用。
        assertEquals(5_000L, sink.totalToday("a", base))
        assertEquals(0L, sink.totalToday("self", base))
        assertEquals(5_000L, engine.millisToday(base + 20_000))
    }

    @Test
    fun `keyguard state comes from the system and gates counting`() {
        val sink = FakeSink()
        val engine = makeEngine(sink)
        // 游标放在第一个事件之前，否则 base 时刻的事件会被 ts <= lastTs 丢掉
        engine.start(base - 1)

        engine.onEvents(listOf(event(0, RawEvent.RESUMED, "a")))
        assertTrue(engine.isRunning)

        // 屏幕亮着但停在锁屏界面：这时候用户并没有在用任何应用
        engine.syncKeyguard(true)
        assertFalse(engine.isRunning)

        engine.syncKeyguard(false)
        assertTrue(engine.isRunning)
    }

    @Test
    fun `transient system ui does not clear the current app`() {
        val sink = FakeSink()
        val engine = makeEngine(
            sink,
            excluded = setOf("self", "launcher"),
            ignored = setOf("sysui"),
        )
        // 游标放在第一个事件之前，否则 base 时刻的事件会被 ts <= lastTs 丢掉
        engine.start(base - 1)

        engine.onEvents(listOf(event(0, RawEvent.RESUMED, "a")))
        // 下拉通知栏：sysui 抢到前台，但收起时不会补发 a 的 RESUMED
        engine.onEvents(listOf(event(10_000, RawEvent.RESUMED, "sysui")))

        assertEquals("a", engine.currentPackage())
        assertEquals(20_000L, engine.millisToday(base + 20_000))
    }

    @Test
    fun `resume while the screen is off is ignored`() {
        val sink = FakeSink()
        val engine = makeEngine(sink)
        // 游标放在第一个事件之前，否则 base 时刻的事件会被 ts <= lastTs 丢掉
        engine.start(base - 1)

        engine.onEvents(listOf(event(0, RawEvent.RESUMED, "wechat")))
        engine.syncScreenState(false) // 用户锁屏

        // QQ 在息屏期间把自己拉起来——这不是"用户在看它"
        engine.onEvents(listOf(event(1_000, RawEvent.RESUMED, "qq")))

        assertFalse(engine.isRunning)
        assertEquals("wechat", engine.currentPackage())
        assertEquals(0L, sink.totalToday("qq", base))
    }

    @Test
    fun `repeated events are not double counted`() {
        val sink = FakeSink()
        val engine = makeEngine(sink)
        // 游标放在第一个事件之前，否则 base 时刻的事件会被 ts <= lastTs 丢掉
        engine.start(base - 1)

        engine.onEvents(listOf(event(0, RawEvent.RESUMED, "a")))
        engine.onEvents(listOf(event(0, RawEvent.RESUMED, "a")))
        engine.onEvents(listOf(event(0, RawEvent.RESUMED, "a")))

        assertEquals(10_000L, engine.millisToday(base + 10_000))
    }
}
