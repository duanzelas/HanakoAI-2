package `fun`.kirari.hanako.feature.overlay.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import `fun`.kirari.hanako.core.debug.AppDebugLogStore
import `fun`.kirari.hanako.feature.overlay.service.OverlayService
import `fun`.kirari.hanako.feature.overlay.state.OverlayRuntimeState
import `fun`.kirari.hanako.feature.overlay.window.OverlayWindowHost

/**
 * 只做一件事：为悬浮球提供一个可承载 `TYPE_ACCESSIBILITY_OVERLAY` 的窗口令牌。
 *
 * 不读取界面内容（canRetrieveWindowContent=false），不监听/不操作任何应用。
 * 用户在系统「无障碍」里开启本服务后，[OverlayWindowHost] 会把悬浮窗切到
 * TYPE_ACCESSIBILITY_OVERLAY，从而不被 setHideOverlayWindows(true) 隐藏。
 */
class HanakoOverlayAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        OverlayWindowHost.attach(this)
        AppDebugLogStore.i(TAG, "overlay accessibility service connected")
        if (OverlayRuntimeState.running.value) {
            runCatching {
                Toast.makeText(this, "悬浮层增强已启用，重启悬浮球后生效", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 仅提供窗口令牌，不需要任何事件
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onUnbind(intent: Intent?): Boolean {
        OverlayWindowHost.detach()
        AppDebugLogStore.i(TAG, "overlay accessibility service unbound")
        // 无障碍层被关闭后，已经挂在上面的悬浮窗会随令牌一起消失，
        // 这里主动停掉前台服务，避免出现「服务在跑但球看不见」的僵死状态。
        if (OverlayRuntimeState.running.value) {
            runCatching { stopService(Intent(this, OverlayService::class.java)) }
        }
        return super.onUnbind(intent)
    }

    private companion object {
        const val TAG = "HanakoA11yOverlay"
    }
}
