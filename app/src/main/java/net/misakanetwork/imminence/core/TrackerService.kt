package net.misakanetwork.imminence.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import net.misakanetwork.imminence.R
import net.misakanetwork.imminence.data.Days
import net.misakanetwork.imminence.data.Prefs
import net.misakanetwork.imminence.data.UsageStore
import net.misakanetwork.imminence.overlay.OverlayController
import net.misakanetwork.imminence.perm.Permissions
import net.misakanetwork.imminence.ui.MainActivity
import net.misakanetwork.imminence.util.AppFilter

/**
 * 常驻前台服务。它只做四件事：
 * 1. 定时（亮屏时每 15s）拉一次 UsageEvents 喂给 [TimelineEngine]；
 * 2. 在分钟边界刷新悬浮窗；
 * 3. 在切应用 / 息屏 / 每 5 分钟把内存账本批量落盘；
 * 4. 维护通知与悬浮窗的可见性。
 *
 * 全程没有 WakeLock、没有 AlarmManager、没有网络。
 */
class TrackerService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var prefs: Prefs
    private lateinit var store: UsageStore
    private lateinit var sampler: ForegroundSampler
    private lateinit var engine: TimelineEngine
    private lateinit var overlay: OverlayController
    private lateinit var ticker: MinuteTicker

    private var excluded: Set<String> = emptySet()
    private var tracking = false
    private var receiverRegistered = false
    private var burstUntil = 0L
    private var lastFlushAt = 0L

    private val pollRunnable = Runnable {
        if (isScreenOn()) poll()
        schedulePoll()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // 直接告诉引擎"屏幕关了"，不再依赖事件流里的 SCREEN_NON_INTERACTIVE
                    // （部分 ROM 上它并不可靠，一旦缺失，整晚都会算到最后一个应用头上）。
                    engine.onInteractiveChanged(false)
                    ticker.cancel()
                    store.flush()
                    schedulePoll()
                }

                Intent.ACTION_SCREEN_ON -> {
                    // 先把游标推到"现在"，把息屏期间的后台 RESUMED 直接跳过，
                    // 再切换状态。否则那些事件会被当成用户真的在用。
                    engine.onInteractiveChanged(true)
                    rebaseFromSystem()
                    poll()
                    schedulePoll()
                }

                Intent.ACTION_USER_PRESENT -> {
                    poll()
                    schedulePoll()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = UsageStore(this)
        sampler = ForegroundSampler(this)
        excluded = AppFilter.excludedPackages(this)
        engine = TimelineEngine(
            sink = store,
            excluded = { excluded },
            ignored = { AppFilter.TRANSIENT_PACKAGES },
        )
        overlay = OverlayController(this, prefs) { onOverlayHidden() }
        ticker = MinuteTicker(handler, engine, overlay)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()

        when (intent?.action) {
            ACTION_STOP -> {
                prefs.enabled = false
                stopTracking()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_OVERLAY -> {
                prefs.overlayVisible = !prefs.overlayVisible
                applyOverlayVisibility()
                notifyState()
            }
        }

        startTracking()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTracking()
        store.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 启动与停止

    private fun startTracking() {
        if (tracking) return
        tracking = true

        applyOverlayVisibility()
        registerScreenReceiver()
        scheduleWatchdog()
        bootstrap()
        renderAndRearm()
        schedulePoll()
    }

    private fun stopTracking() {
        if (!tracking) {
            isRunning = false
            return
        }
        tracking = false

        handler.removeCallbacksAndMessages(null)
        ticker.cancel()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(screenReceiver) }
            receiverRegistered = false
        }
        store.flush()
        overlay.detach()
        cancelWatchdog()
        isRunning = false
    }

    // ---------------------------------------------------------------- 保活

    /**
     * 不精确的 15 分钟重复闹钟：服务被厂商省电策略杀掉后把服务拉回来。
     *
     * 只在用户已加入电池优化白名单时生效——否则 Android 12+ 会拒绝
     * 后台启动前台服务，闹钟就算触发也起不来，纯属浪费电。
     * Doze 期间系统会把这个闹钟推迟到维护窗口，所以深睡时不耗电。
     */
    private fun scheduleWatchdog() {
        if (!Permissions.isIgnoringBatteryOptimizations(this)) {
            Log.d(TAG, "watchdog skipped: not exempt from battery optimizations")
            return
        }
        val alarm = getSystemService(AlarmManager::class.java) ?: return
        alarm.setInexactRepeating(
            AlarmManager.RTC,
            System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
            WATCHDOG_INTERVAL_MS,
            watchdogIntent(),
        )
    }

    private fun cancelWatchdog() {
        val alarm = getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarm.cancel(watchdogIntent()) }
    }

    private fun watchdogIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        REQUEST_WATCHDOG,
        Intent(this, WatchdogReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /**
     * 启动时恢复现场。
     *
     * 关键取舍：历史成绩从 SQLite 读，**不从事件流重放**。
     * 事件流并不保证完整（部分 ROM 会漏报息屏事件），一旦拿它重新推导历史，
     * 就会把"应用在后台被拉起来"的瞬间当成用户在用，于是整晚的时长被算到某个应用头上。
     * 事件流在这里只回答一个问题：现在前台是谁。
     */
    private fun bootstrap() {
        val now = System.currentTimeMillis()
        val day = Days.keyOf(now)

        // 先读回我们自己记的账，再用系统的当天统计补齐。
        // 用户想看的是「今天总共用了多久」，而不是「从点击开始统计到现在」。
        store.loadDay(day)
        rebaseFromSystem(now)

        // 游标从"现在"开始，之后只向前累计，绝不回头补记。
        engine.start(now)
        engine.syncScreenState(isScreenOn())
        syncSystemState()

        val from = maxOf(now - FOREGROUND_LOOKBACK_MS, Days.startOf(now))
        sampler.since(from, now)
            .lastOrNull { it.type == RawEvent.RESUMED }
            ?.let { engine.restoreForeground(it.pkg) }
    }

    /**
     * 用系统的当天统计校正基数。
     *
     * `rebase` 取两者较大值：系统值包含服务启动前、以及服务被杀期间的使用，
     * 我们的累加则包含系统尚未刷新出来的那一小段。两者描述同一个量，取 max 不会重复。
     */
    private fun rebaseFromSystem(now: Long = System.currentTimeMillis()) {
        runCatching {
            store.rebase(Days.keyOf(now), sampler.totalsToday(Days.startOf(now), now))
        }
    }

    // ---------------------------------------------------------------- 轮询

    private fun poll() {
        val now = System.currentTimeMillis()
        val before = engine.currentPackage()

        // 锁屏状态直接问系统，不依赖事件流里可能缺失的 KEYGUARD_* 事件。
        syncSystemState()

        val events = sampler.since(engine.lastTs, now)

        if (events.isNotEmpty()) {
            engine.onEvents(events)
            // 刚发生过切换，说明用户正在连续操作，接下来几秒提高轮询频率。
            burstUntil = now + BURST_WINDOW_MS
        }

        // 留一点回退余量：系统的某些事件会稍晚才可见，游标贴太紧会把它永久漏掉。
        engine.settleUpTo(now - REWIND_MS)

        val after = engine.currentPackage()
        // 只在真的有变化时打日志：3s 轮询下一律打印会刷屏。
        if (before != after || events.isNotEmpty()) {
            Log.d(
                TAG,
                "poll events=${events.size} pkg=$after running=${engine.isRunning} " +
                    "minutes=${engine.millisToday(now) / 60_000L}",
            )
        }

        if (before != after) store.flush()

        renderAndRearm()

        // 按时间落盘，不按轮询次数——否则一旦调快轮询，写库频率会跟着爆炸。
        if (now - lastFlushAt >= FLUSH_INTERVAL_MS) {
            lastFlushAt = now
            store.flush()
        }
    }

    private fun renderAndRearm() {
        // 先确认窗口还在：部分 ROM 会在切后台后把 overlay 窗口摘掉，
        // 此时 root 引用还在，但所有 render 都是往空气上画。
        overlay.ensureAttached()
        ticker.renderNow()
        ticker.rearm()
    }

    /**
     * 永远排下一次，只是息屏时把间隔拉长。
     *
     * 之前这里会在息屏 / 锁屏时直接 return，一旦某个状态标志写错，
     * 整个轮询就永久停摆、悬浮窗冻在最后一个应用上——这是不能接受的脆弱点。
     */
    private fun schedulePoll() {
        handler.removeCallbacks(pollRunnable)
        val now = System.currentTimeMillis()
        val delay = when {
            !isScreenOn() -> SCREEN_OFF_INTERVAL_MS
            now < burstUntil -> BURST_INTERVAL_MS
            else -> POLL_INTERVAL_MS
        }
        handler.postDelayed(pollRunnable, delay)
    }

    /** 再便宜也要挡一层：引擎对息屏的认知来自事件流，可能比真实状态晚一步。 */
    private fun isScreenOn(): Boolean = runCatching {
        getSystemService(PowerManager::class.java)?.isInteractive == true
    }.getOrDefault(true)

    /** 锁屏状态由系统直接给出，比事件流可靠得多。 */
    private fun syncSystemState() {
        runCatching {
            getSystemService(KeyguardManager::class.java)
                ?.let { engine.syncKeyguard(it.isKeyguardLocked) }
        }
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    // ---------------------------------------------------------------- 通知与悬浮窗

    private fun ensureForeground() {
        createChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            this,
            REQUEST_TOGGLE,
            Intent(this, TrackerService::class.java).setAction(ACTION_TOGGLE_OVERLAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, TrackerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val toggleLabel =
            if (prefs.overlayVisible) R.string.notif_action_hide else R.string.notif_action_show

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(toggleLabel), toggle)
            .addAction(0, getString(R.string.notif_action_stop), stop)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                }
            }
            .build()
    }

    private fun notifyState() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun applyOverlayVisibility() {
        if (prefs.overlayVisible && Permissions.canDrawOverlays(this)) {
            overlay.attach()
        } else {
            overlay.detach()
        }
    }

    private fun onOverlayHidden() {
        prefs.overlayVisible = false
        notifyState()
    }

    companion object {
        private const val TAG = "Imminence"

        const val ACTION_TOGGLE_OVERLAY = "net.misakanetwork.imminence.action.TOGGLE_OVERLAY"
        const val ACTION_STOP = "net.misakanetwork.imminence.action.STOP"

        private const val CHANNEL_ID = "tracker"
        private const val NOTIFICATION_ID = 0x1A11
        private const val REQUEST_OPEN = 1
        private const val REQUEST_TOGGLE = 2
        private const val REQUEST_STOP = 3
        private const val REQUEST_WATCHDOG = 4

        /** 保活闹钟间隔。Doze 期间由系统推迟，不会持续唤醒 CPU。 */
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L

        /**
         * 亮屏轮询间隔。
         *
         * 轮询模式下「最坏延迟 = 这个值」，所以它直接决定手感。
         * 1 秒：16 小时亮屏约 5.8 万次查询，每次不到 0.5ms，全天 CPU 约 30 秒；
         * 而亮屏时 CPU 本来就是醒着的，实际耗电影响在千分之一量级。
         */
        private const val POLL_INTERVAL_MS = 1_000L

        /** 刚发生过切换时进一步加密，让连续切换几乎无感。 */
        private const val BURST_INTERVAL_MS = 300L

        /** 切换后维持高速档的时长。 */
        private const val BURST_WINDOW_MS = 8_000L

        /** 息屏后的轮询间隔：只为保住循环，不做任何查询。 */
        private const val SCREEN_OFF_INTERVAL_MS = 60_000L

        /** 结算游标相对当前时间的回退量，用来捞回"迟到可见"的事件。 */
        private const val REWIND_MS = 2_000L

        /** 启动时向前回溯多久，用来找出"现在前台是哪个应用"。 */
        private const val FOREGROUND_LOOKBACK_MS = 12 * 60 * 60 * 1000L

        /** 周期性落盘间隔。切应用、息屏时另外还会立即落盘。 */
        private const val FLUSH_INTERVAL_MS = 5 * 60 * 1000L

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, TrackerService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackerService::class.java))
        }

        fun toggleOverlay(context: Context) {
            val intent = Intent(context, TrackerService::class.java)
                .setAction(ACTION_TOGGLE_OVERLAY)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
