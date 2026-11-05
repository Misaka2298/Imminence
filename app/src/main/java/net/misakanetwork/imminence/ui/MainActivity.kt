package net.misakanetwork.imminence.ui

import android.Manifest
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import net.misakanetwork.imminence.R
import net.misakanetwork.imminence.core.TrackerService
import net.misakanetwork.imminence.data.Prefs
import net.misakanetwork.imminence.perm.Permissions

/**
 * 权限引导 + 启停。刻意做得很薄：真正的功能全在服务里。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var rows: LinearLayout
    private lateinit var action: MaterialButton
    private lateinit var status: TextView
    private lateinit var prefs: Prefs

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        rows = findViewById(R.id.rows)
        action = findViewById(R.id.action)
        status = findViewById(R.id.status)
        action.setOnClickListener { onActionClick() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val usageAccess = Permissions.hasUsageAccess(this)
        val overlayAccess = Permissions.canDrawOverlays(this)
        val notifications = Permissions.hasNotifications(this)
        val battery = Permissions.isIgnoringBatteryOptimizations(this)

        rows.removeAllViews()

        addRow(R.string.perm_usage_title, R.string.perm_usage_desc, usageAccess) {
            Permissions.openUsageAccess(this)
        }
        addRow(R.string.perm_overlay_title, R.string.perm_overlay_desc, overlayAccess) {
            Permissions.openOverlaySettings(this)
        }
        addRow(R.string.perm_notify_title, R.string.perm_notify_desc, notifications) {
            requestNotifications()
        }
        addRow(R.string.perm_battery_title, R.string.perm_battery_desc, battery) {
            Permissions.requestIgnoreBatteryOptimizations(this)
        }
        addRow(R.string.perm_autostart_title, R.string.perm_autostart_desc, null) {
            if (!Permissions.openAutostartSettings(this)) {
                toast(R.string.toast_autostart_unavailable)
            }
        }

        val running = TrackerService.isRunning
        val showing = running && prefs.overlayVisible
        action.text = getString(
            if (showing) R.string.action_hide_overlay else R.string.action_show_overlay,
        )
        status.text = when {
            running -> getString(R.string.status_running)
            !usageAccess || !overlayAccess -> getString(R.string.status_missing)
            else -> getString(R.string.status_stopped)
        }
    }

    private fun onActionClick() {
        if (TrackerService.isRunning) {
            // 服务在跑时，这个按钮只负责显示 / 隐藏今日使用时间
            TrackerService.toggleOverlay(this)
            status.postDelayed({ render() }, 250L)
            return
        }

        if (!Permissions.hasUsageAccess(this)) {
            toast(R.string.toast_need_usage)
            Permissions.openUsageAccess(this)
            return
        }
        if (!Permissions.canDrawOverlays(this)) {
            toast(R.string.toast_need_overlay)
            Permissions.openOverlaySettings(this)
            return
        }

        prefs.enabled = true
        prefs.overlayVisible = true
        TrackerService.start(this)
        status.postDelayed({ render() }, 500L)
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Permissions.openNotificationSettings(this)
        }
    }

    private fun addRow(
        titleRes: Int,
        descRes: Int,
        state: Boolean?,
        onClick: () -> Unit,
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(14), dp(2), dp(14))
            isClickable = true
            isFocusable = true
            background = selectableBackground()
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            TextView(this).apply {
                text = getString(titleRes)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(themeColor(android.R.attr.textColorPrimary))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            },
        )

        header.addView(
            TextView(this).apply {
                text = when (state) {
                    true -> getString(R.string.state_on)
                    false -> getString(R.string.state_off)
                    null -> getString(R.string.state_settings)
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(
                    when (state) {
                        true -> COLOR_ENABLED
                        false -> themeColor(com.google.android.material.R.attr.colorPrimary)
                        null -> themeColor(android.R.attr.textColorSecondary)
                    },
                )
            },
        )

        row.addView(header)
        row.addView(
            TextView(this).apply {
                text = getString(descRes)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(themeColor(android.R.attr.textColorSecondary))
                setPadding(0, dp(4), 0, 0)
            },
        )
        row.setOnClickListener { onClick() }

        rows.addView(row)
    }

    private fun selectableBackground(): Drawable? {
        val out = TypedValue()
        return if (theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)) {
            ContextCompat.getDrawable(this, out.resourceId)
        } else {
            null
        }
    }

    private fun themeColor(attr: Int): Int {
        val out = TypedValue()
        return if (theme.resolveAttribute(attr, out, true)) {
            if (out.resourceId != 0) ContextCompat.getColor(this, out.resourceId) else out.data
        } else {
            0xFF000000.toInt()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    private companion object {
        val COLOR_ENABLED = 0xFF2E7D32.toInt()
    }
}
