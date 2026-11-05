package net.misakanetwork.imminence.core

import net.misakanetwork.imminence.data.Days
import net.misakanetwork.imminence.data.UsageSink

/**
 * 时间轴状态机：把「前台应用切换 / 亮屏 / 锁屏」事件回放成每个应用的使用时长。
 *
 * 设计要点：
 * 1. 只有事件能改变状态，定时器只负责刷新显示，因此进程被冻结再唤醒不会丢时长；
 * 2. 采用「区间结算」——处理到事件 E 时，把 [上一个事件, E) 这段记给当时的前台应用；
 * 3. 正在进行的这一小段不写库，由 [millisToday] 在读取时补上，避免高频写盘。
 *
 * 本类是纯 JVM 实现，不依赖 Android。
 */
class TimelineEngine(
    private val sink: UsageSink,
    private val excluded: () -> Set<String>,
    private val ignored: () -> Set<String> = { emptySet() },
) {

    /** 已经结算到的时间点，下一次查询从这里开始。 */
    var lastTs: Long = 0L
        private set

    private var current: String? = null
    private var screenOn = true
    private var keyguard = false
    private var excludedCache: Set<String>? = null
    private var ignoredCache: Set<String>? = null

    /** 屏幕亮着且没有锁屏。 */
    val isInteractive: Boolean get() = screenOn && !keyguard

    /** 正在某个第三方应用里，可以计时。 */
    val isRunning: Boolean get() = isInteractive && current != null

    fun start(at: Long) {
        lastTs = at
        current = null
        screenOn = true
        keyguard = false
    }

    fun currentPackage(): String? = current

    /** 事件必须按时间升序传入；早于已结算位置的会被丢弃。 */
    fun onEvents(events: List<RawEvent>) {
        for (event in events) {
            if (event.ts <= lastTs) continue
            settle(event.ts)
            lastTs = event.ts
            apply(event)
        }
    }

    /**
     * 把「已经发生但还没有事件来结算」的那一段入账，并把游标推到 [ts]。
     *
     * 每次轮询后调用一次，好处是游标始终贴着当前时间，
     * 下一次查询窗口很小；否则长时间没有事件时，每次都会重扫很大一段历史。
     */
    fun settleUpTo(ts: Long) {
        if (ts <= lastTs) return
        settle(ts)
        lastTs = ts
    }

    /**
     * 只同步锁屏状态，不做结算。
     *
     * 由 `KeyguardManager.isKeyguardLocked` 提供，每个轮询周期刷新一次。
     * 这是权威答案：屏幕亮着但停在锁屏界面（充电常亮、某些应用持 wakelock）时，
     * 事件流里可能什么都没有，只有它知道用户并没有真的在用那个应用。
     */
    fun syncKeyguard(locked: Boolean) {
        keyguard = locked
    }

    /**
     * 只同步屏幕状态，不做结算。
     *
     * 启动恢复现场时用它把状态对齐到系统的真实值：
     * 必须先定状态再开始计时，否则会把息屏期间的时间补记进去。
     */
    fun syncScreenState(interactive: Boolean) {
        screenOn = interactive
    }

    /**
     * 由系统广播直接告知屏幕状态，而不是靠事件流去猜。
     *
     * 事件流里的 SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE 在部分 ROM 上并不可靠，
     * 而这两个广播在任何设备上都一定会来。息屏时立刻结算并停表，
     * 是防止"整晚的时长算到最后一个应用头上"的关键。
     */
    fun onInteractiveChanged(interactive: Boolean) {
        val now = System.currentTimeMillis()
        if (now > lastTs) {
            settle(now)   // 先按当前状态把账结到这一刻
            lastTs = now
        }
        screenOn = interactive
    }

    /**
     * 该应用截至目前（[now]）的今日累计。
     * 包含尚未结算的那一小段，因此悬浮窗能「实时」增长。
     */
    fun millisToday(now: Long): Long {
        val pkg = current ?: return 0L
        var total = sink.totalToday(pkg, now)
        if (isRunning && now > lastTs) {
            // 只补今天这一部分，跨零点后不能把昨天的尾巴算进来。
            val from = maxOf(lastTs, Days.startOf(now))
            if (now > from) total += now - from
        }
        return total
    }

    private fun apply(event: RawEvent) {
        when (event.type) {
            RawEvent.RESUMED -> restoreForeground(event.pkg)
            RawEvent.SCREEN_OFF -> screenOn = false
            RawEvent.SCREEN_ON -> screenOn = true
            RawEvent.KEYGUARD_SHOWN -> keyguard = true
            RawEvent.KEYGUARD_HIDDEN -> keyguard = false
            RawEvent.SHUTDOWN -> {
                screenOn = false
                current = null
            }
            else -> Unit
        }
    }

    /**
     * 认定某个应用此刻在前台。
     *
     * 服务重启时用它把"当前应用"从事件流里捞出来——只取最后一个 RESUMED，
     * 不结算任何时长，因此不会把历史时间补记到任何人头上。
     */
    fun restoreForeground(pkg: String) {
        // 屏幕关着时也会有 RESUMED——应用可能在后台把自己拉起来（QQ、微信尤其爱这么干）。
        // 这种事件绝对不能算作"用户在看它"，否则一次后台拉活就能吞掉一整晚。
        if (!screenOn) return

        val transient = ignoredCache ?: ignored().also { ignoredCache = it }
        if (pkg.isNotEmpty() && pkg in transient) {
            // 通知栏、最近任务、音量面板这类系统浮层会短暂抢到前台，
            // 但它们收起时底层应用不会补发 RESUMED。若在这里把 current 清空，
            // 悬浮窗就会一直消失到用户下次真正切换应用为止。
            return
        }

        val skip = excludedCache ?: excluded().also { excludedCache = it }
        current = if (pkg.isEmpty() || pkg in skip) null else pkg
    }

    /** 把 [lastTs, ts) 记给当前应用。必须在更新 lastTs 之前调用。 */
    private fun settle(ts: Long) {
        if (!isRunning) return
        val pkg = current ?: return
        if (ts <= lastTs) return
        sink.add(pkg, lastTs, ts)
    }
}
