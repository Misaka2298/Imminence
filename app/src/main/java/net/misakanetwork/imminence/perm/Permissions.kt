package net.misakanetwork.imminence.perm

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * 四项权限的检测与跳转。
 *
 * 「使用情况访问」是特殊权限，只能靠 AppOps 判断，而且部分 ROM 在已授权时
 * 仍返回 MODE_DEFAULT，所以补了一次真实查询兜底。
 */
object Permissions {

    private const val DAY_MS = 86_400_000L

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false

        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }

        if (mode == AppOpsManager.MODE_ALLOWED) return true
        if (mode != AppOpsManager.MODE_DEFAULT) return false

        return runCatching {
            val usageStats = context.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            usageStats.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - DAY_MS, now)
                ?.isNotEmpty() == true
        }.getOrDefault(false)
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun hasNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    // ---------------------------------------------------------------- 跳转

    fun openUsageAccess(context: Context) {
        val specific = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(specific) }.isSuccess) return

        val fallback = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(fallback) }
    }

    fun openOverlaySettings(context: Context) {
        val specific = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(specific) }.isSuccess) return

        val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(fallback) }
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(request) }.isSuccess) return

        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(fallback) }
    }

    /**
     * 厂商自启动页面没有统一入口，这里按常见 ComponentName 逐个尝试。
     * @return 是否成功打开了某个页面。
     */
    fun openAutostartSettings(context: Context): Boolean {
        for (component in AUTOSTART_COMPONENTS) {
            val intent = Intent()
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    private val AUTOSTART_COMPONENTS = listOf(
        ComponentName(
            "com.miui.securitycenter",
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
        ComponentName(
            "com.huawei.systemmanager",
            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        ComponentName(
            "com.hihonor.systemmanager",
            "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        ComponentName(
            "com.coloros.safecenter",
            "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        ),
        ComponentName(
            "com.oppo.safe",
            "com.oppo.safe.permission.startup.StartupAppListActivity",
        ),
        ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ),
        ComponentName(
            "com.letv.android.letvsafe",
            "com.letv.android.letvsafe.AutobootManageActivity",
        ),
    )
}
