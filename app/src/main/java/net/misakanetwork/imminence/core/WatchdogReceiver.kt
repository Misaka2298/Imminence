package net.misakanetwork.imminence.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.misakanetwork.imminence.data.Prefs

/**
 * 保活看门狗。
 *
 * 前台服务再规范也可能被厂商的省电策略杀掉。这里用一个不精确的重复闹钟
 * （15 分钟一次，Doze 期间由系统推迟）来检查服务是否还活着，死了就拉起来。
 *
 * 前提是用户把本应用加入电池优化白名单——否则 Android 12+ 会拒绝
 * 后台启动前台服务，这个闹钟就形同虚设（所以调度时也做了同样的判断）。
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.enabled) return
        if (TrackerService.isRunning) return

        runCatching { TrackerService.start(context) }
    }
}
