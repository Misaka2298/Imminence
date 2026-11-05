package net.misakanetwork.imminence.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.misakanetwork.imminence.data.Prefs

/**
 * 开机 / 覆盖安装后把服务拉起来。
 *
 * Android 12+ 禁止从后台启动前台服务，但 BOOT_COMPLETED 属于系统豁免场景；
 * 部分 OEM 仍可能拦截，所以外层兜了异常——失败时用户下次打开应用会再拉一次。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        if (!Prefs(context).enabled) return

        runCatching { TrackerService.start(context) }
    }
}
