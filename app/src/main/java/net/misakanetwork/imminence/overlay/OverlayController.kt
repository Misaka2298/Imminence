package net.misakanetwork.imminence.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import net.misakanetwork.imminence.R
import net.misakanetwork.imminence.data.Prefs
import net.misakanetwork.imminence.util.AppIcons
import kotlin.math.abs

/**
 * 顶层小窗。
 *
 * 省电约定：
 * - 窗口只在用户手动隐藏时才真正 remove，平时用 visibility 切换，避免反复 add/remove 的 IPC；
 * - [render] 里字符串没变就不碰 TextView，不触发重绘；
 * - 只有手指按住拖动时才 updateViewLayout。
 */
class OverlayController(
    private val context: Context,
    private val prefs: Prefs,
    private val onHiddenByUser: () -> Unit,
) {

    private val windowManager: WindowManager =
        context.getSystemService(WindowManager::class.java)

    private val icons = AppIcons(context)
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var iconView: ImageView? = null
    private var textView: TextView? = null

    private var shownPkg: String? = null
    private var shownText: String? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var dragging = false

    private val longPress = Runnable {
        detach()
        onHiddenByUser()
    }

    fun attach() {
        if (root != null) return
        if (!Settings.canDrawOverlays(context)) return

        val view = build()
        val lp = buildParams()
        if (runCatching { windowManager.addView(view, lp) }.isFailure) return

        root = view
        params = lp
        view.post { snapIntoBounds(view, lp) }
    }

    /**
     * 每个轮询周期调一次：窗口被系统摘掉时重建。
     *
     * 部分 ROM 会在切后台、锁屏、切换分屏后把 overlay 窗口摘掉，
     * 但 View 引用还留在内存里，之后所有 render 都是往一个不存在的窗口上画。
     * 用 isAttachedToWindow 判定，比"root != null"可靠。
     *
     * 只处理「用户没主动隐藏」的情况：长按隐藏过的不会自己冒出来。
     * 注意：这个方法是高频调用的（切换时 0.5s 一次），所以把最便宜的判断放在最前面，
     * canDrawOverlays 是一次 binder 调用，绝不能每次轮询都做。
     */
    fun ensureAttached() {
        if (!prefs.overlayVisible) return

        val view = root
        if (view != null && view.isAttachedToWindow) return

        if (!Settings.canDrawOverlays(context)) return

        if (view != null) {
            runCatching { windowManager.removeViewImmediate(view) }
            clearRefs()
        }
        attach()
    }

    fun detach() {
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
        clearRefs()
    }

    private fun clearRefs() {
        root = null
        params = null
        iconView = null
        textView = null
        shownPkg = null
        shownText = null
        handler.removeCallbacks(longPress)
    }

    /** [pkg] 为 null 表示当前没有可统计的前台应用（锁屏、自己、桌面）。 */
    fun render(pkg: String?, millis: Long) {
        val view = root ?: return

        if (pkg == null) {
            if (view.visibility != View.GONE) view.visibility = View.GONE
            return
        }
        if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE

        if (pkg != shownPkg) {
            shownPkg = pkg
            iconView?.setImageDrawable(icons.get(pkg) ?: icons.fallback())
            shownText = null // 换了应用，强制重排一次文字
        }

        val label = format(millis)
        if (label != shownText) {
            shownText = label
            textView?.text = label
            // "59 分" 变成 "1 小时" 时胶囊会变宽，贴右边缘时可能顶出屏幕，重新夹一次。
            if (!dragging) {
                view.post {
                    if (!dragging) params?.let { snapIntoBounds(view, it) }
                }
            }
        }
    }

    /**
     * 分钟数是主单位；跨过 60 分钟后折算成「小时 + 分钟」，
     * 整点就不带零头，避免出现"2 小时 0 分"这种别扭写法。
     */
    private fun format(millis: Long): String {
        val totalMinutes = millis / MINUTE_MS
        return when {
            totalMinutes <= 0 -> context.getString(R.string.overlay_less_than_minute)
            totalMinutes < MINUTES_PER_HOUR ->
                context.getString(R.string.overlay_minutes, totalMinutes)
            totalMinutes % MINUTES_PER_HOUR == 0L ->
                context.getString(R.string.overlay_hours, totalMinutes / MINUTES_PER_HOUR)
            else -> context.getString(
                R.string.overlay_hours_minutes,
                totalMinutes / MINUTES_PER_HOUR,
                totalMinutes % MINUTES_PER_HOUR,
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(Color.argb(0xCC, 0x10, 0x10, 0x14))
            }
            elevation = dp(6).toFloat()
        }

        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(7) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(icons.fallback())
        }

        val label = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        iconView = icon
        textView = label
        row.addView(icon)
        row.addView(label)
        row.setOnTouchListener(dragListener())
        return row
    }

    private fun buildParams(): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START

        val screen = screenSize()
        lp.x = if (prefs.overlayX == Prefs.UNSET) screen.x / 4 else prefs.overlayX
        lp.y = if (prefs.overlayY == Prefs.UNSET) screen.y / 3 else prefs.overlayY
        return lp
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dragListener() = View.OnTouchListener { view, event ->
        val lp = params
        if (lp == null) {
            false
        } else {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = lp.x
                    downY = lp.y
                    dragging = false
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        lp.x = downX + dx.toInt()
                        lp.y = downY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, lp) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (dragging) {
                        snapIntoBounds(view, lp)
                        prefs.overlayX = lp.x
                        prefs.overlayY = lp.y
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun snapIntoBounds(view: View, lp: WindowManager.LayoutParams) {
        val screen = screenSize()
        val width = view.width.coerceAtLeast(0)
        val height = view.height.coerceAtLeast(0)
        val top = statusBarHeight()

        lp.x = lp.x.coerceIn(0, (screen.x - width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(top, (screen.y - height).coerceAtLeast(top))
        runCatching { windowManager.updateViewLayout(view, lp) }
    }

    private fun screenSize(): Point {
        val point = Point()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            point.set(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
        }
        return point
    }

    private fun statusBarHeight(): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val MINUTES_PER_HOUR = 60L
        const val LONG_PRESS_MS = 600L
    }
}
