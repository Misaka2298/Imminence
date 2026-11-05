package net.misakanetwork.imminence.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.core.content.ContextCompat
import net.misakanetwork.imminence.R

/**
 * 不该被计时为「使用」的包：自己、桌面。
 */
object AppFilter {

    /**
     * 会短暂抢到前台、但收起时不会触发底层应用 RESUMED 的系统浮层。
     *
     * 这类包必须「忽略」而不是「排除」：一旦把它当成"用户离开了当前应用"，
     * 下拉一次通知栏，悬浮窗就会消失到你下次切应用为止。
     */
    val TRANSIENT_PACKAGES: Set<String> = setOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
    )

    fun excludedPackages(context: Context): Set<String> {
        val result = mutableSetOf(context.packageName)

        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching {
            context.packageManager
                .resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
        }.getOrNull()?.let(result::add)

        return result
    }
}

/**
 * 应用图标的查询缓存。
 *
 * getApplicationIcon 是一次 binder 调用，切一次应用查一次可以接受，
 * 但绝不能放进刷新循环里——那样每分钟都会额外唤醒 PackageManager。
 */
class AppIcons(private val context: Context) {

    private val cache = object : LruCache<String, Drawable>(MAX_ENTRIES) {}

    fun get(pkg: String): Drawable? =
        cache.get(pkg) ?: runCatching { context.packageManager.getApplicationIcon(pkg) }
            .getOrNull()
            ?.also { cache.put(pkg, it) }

    fun fallback(): Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_dot)

    private companion object {
        const val MAX_ENTRIES = 24
    }
}
