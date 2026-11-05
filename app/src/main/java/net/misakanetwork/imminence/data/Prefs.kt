package net.misakanetwork.imminence.data

import android.content.Context

/**
 * 只有几个开关和坐标，用 SharedPreferences 就够了，不值得引入 DataStore。
 */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("imminence", Context.MODE_PRIVATE)

    /** 用户是否点过「开始统计」；用于开机自启时决定要不要拉起来。 */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 用户是否希望显示悬浮窗（通知栏可以开关）。 */
    var overlayVisible: Boolean
        get() = sp.getBoolean(KEY_OVERLAY_VISIBLE, true)
        set(value) = sp.edit().putBoolean(KEY_OVERLAY_VISIBLE, value).apply()

    var overlayX: Int
        get() = sp.getInt(KEY_OVERLAY_X, UNSET)
        set(value) = sp.edit().putInt(KEY_OVERLAY_X, value).apply()

    var overlayY: Int
        get() = sp.getInt(KEY_OVERLAY_Y, UNSET)
        set(value) = sp.edit().putInt(KEY_OVERLAY_Y, value).apply()

    companion object {
        const val UNSET = Int.MIN_VALUE

        private const val KEY_ENABLED = "enabled"
        private const val KEY_OVERLAY_VISIBLE = "overlay_visible"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
    }
}
