package `fun`.kirari.hanako.feature.overlay.window

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.view.WindowManager

/**
 * 悬浮窗类型选择器。
 *
 * 背景：Android 12 起，前台应用可以调用 `Window.setHideOverlayWindows(true)`
 * （微信收款码、银行、支付密码页等敏感页面普遍这么做）。WMS 里的
 * `WindowState#setForceHideNonSystemOverlayWindowIfNeeded` 会强制隐藏
 * `isSystemAlertWindowType()` 覆盖的类型：
 * TYPE_PHONE / TYPE_PRIORITY_PHONE / TYPE_SYSTEM_ALERT / TYPE_SYSTEM_ERROR /
 * TYPE_SYSTEM_OVERLAY / TYPE_APPLICATION_OVERLAY，以及 TYPE_TOAST。
 *
 * TYPE_ACCESSIBILITY_OVERLAY 不在该名单里，因此不会被这套机制隐藏；
 * 它需要无障碍服务提供窗口令牌，所以采用「有无障碍服务就用它」的策略。
 */
internal object OverlayWindowHost {

    @Volatile
    private var accessibilityWindowManager: WindowManager? = null

    val usingAccessibilityOverlay: Boolean
        get() = accessibilityWindowManager != null

    fun attach(service: AccessibilityService) {
        accessibilityWindowManager =
            service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }

    fun detach() {
        accessibilityWindowManager = null
    }

    /** 有可用的无障碍悬浮层就用它，否则回落到应用自己的 WindowManager。 */
    fun windowManager(fallback: WindowManager): WindowManager =
        accessibilityWindowManager ?: fallback

    fun windowType(): Int {
        return if (usingAccessibilityOverlay) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            legacyOverlayWindowType()
        }
    }
}

private fun legacyOverlayWindowType(): Int {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }
}

internal fun overlayWindowType(): Int = OverlayWindowHost.windowType()
