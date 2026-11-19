package `fun`.kirari.hanako.feature.overlay.service

import `fun`.kirari.hanako.feature.overlay.window.BubbleGestureSink
import `fun`.kirari.hanako.feature.overlay.window.BubbleMenuController
import `fun`.kirari.hanako.feature.overlay.window.OverlayBubbleCoordinator
import `fun`.kirari.hanako.feature.overlay.window.OverlayWindowHost
import `fun`.kirari.hanako.feature.overlay.window.PanelWindowController
import `fun`.kirari.hanako.feature.overlay.presentation.OverlayViewModel
import `fun`.kirari.hanako.feature.overlay.OverlayDependencies
import `fun`.kirari.hanako.feature.overlay.OverlayDependenciesProvider
import `fun`.kirari.hanako.platform.capture.CaptureLaunchMode
import `fun`.kirari.hanako.platform.capture.CaptureLaunchContract
import `fun`.kirari.hanako.feature.overlay.state.BubbleSlot
import `fun`.kirari.hanako.feature.overlay.state.OverlayRuntimeState

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ServiceLifecycleDispatcher
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import `fun`.kirari.hanako.platform.capture.projection.ProjectionPermissionActivity
import `fun`.kirari.hanako.core.debug.AppDebugLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class OverlayService : Service(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
    private val logTag = "HanakoOverlaySvc"
    private val dispatcher = ServiceLifecycleDispatcher(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val serviceViewModelStore = ViewModelStore()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override val lifecycle: Lifecycle
        get() = dispatcher.lifecycle

    override val savedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = serviceViewModelStore

    private lateinit var overlayViewModel: OverlayViewModel
    private lateinit var dependencies: OverlayDependencies
    private var bubbleCoordinator: OverlayBubbleCoordinator? = null
    private var panelWindowController: PanelWindowController? = null
    private var bubbleMenuController: BubbleMenuController? = null
    private var stateObserver: OverlayStateObserver? = null
    private var automationEffectObserver: OverlayAutomationEffectObserver? = null

    override fun onCreate() {
        dispatcher.onServicePreSuperOnCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        if (!Settings.canDrawOverlays(this) && !OverlayWindowHost.usingAccessibilityOverlay) {
            stopSelf()
            return
        }

        // 无障碍悬浮层可用时优先用它：微信收款码、银行等页面会 setHideOverlayWindows(true)，
        // 普通 TYPE_APPLICATION_OVERLAY 会被系统强制隐藏，无障碍悬浮层不受影响。
        val windowManager = OverlayWindowHost.windowManager(
            getSystemService(WINDOW_SERVICE) as WindowManager
        )
        runCatching {
            dependencies = (application as OverlayDependenciesProvider).overlayDependencies
            overlayViewModel = ViewModelProvider(
                this,
                OverlayViewModel.factory(applicationContext, dependencies)
            )[OverlayViewModel::class.java]
            bubbleCoordinator = OverlayBubbleCoordinator(
                context = this,
                windowManager = windowManager,
                primarySink = gestureSink(BubbleSlot.PRIMARY),
                secondarySink = gestureSink(BubbleSlot.SECONDARY),
                settingsProvider = { overlayViewModel.uiState.value.settings },
                isStaticModeEnabled = {
                    overlayViewModel.uiState.value.settings.automation.staticModeEnabled
                }
            )
            bubbleMenuController = BubbleMenuController(
                context = this,
                windowManager = windowManager,
                lifecycleOwner = this,
                viewModelStoreOwner = this,
                savedStateRegistryOwner = this,
                overlayViewModel = overlayViewModel,
                onItemClick = { item ->
                    overlayViewModel.handleMenuSelect(item)
                },
                onDismiss = {
                    overlayViewModel.onMenuDismissed()
                    bubbleMenuController?.dismiss()
                }
            )
            panelWindowController = PanelWindowController(
                context = this,
                windowManager = windowManager,
                scope = serviceScope,
                lifecycleOwner = this,
                viewModelStoreOwner = this,
                savedStateRegistryOwner = this,
                viewModel = overlayViewModel
            )
            val bubbles = checkNotNull(bubbleCoordinator)
            val panelController = checkNotNull(panelWindowController)
            stateObserver = OverlayStateObserver(
                scope = serviceScope,
                viewModel = overlayViewModel,
                bubbles = bubbles,
                panelWindowController = panelController
            )
            automationEffectObserver = OverlayAutomationEffectObserver(
                context = this,
                scope = serviceScope,
                viewModel = overlayViewModel,
                solveOperations = dependencies.solveOperations,
                onNotifyAutomationCompleted = { action ->
                    notifyAutomationCompleted(action, dependencies.mainActivityIntent(this))
                },
                onVibrateLetters = { letters, settings -> vibrateLetters(letters, settings) }
            )
            OverlayRuntimeState.setRunning(true)
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification(dependencies.mainActivityIntent(this)))
            bubbles.attach()
            stateObserver?.start()
            automationEffectObserver?.start()
        }.onFailure {
            AppDebugLogStore.e(logTag, "Overlay initialization failed", it)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        dispatcher.onServicePreSuperOnStart()
        when (intent?.action) {
            ACTION_STOP -> {
                panelWindowController?.dismiss()
                stopSelf()
            }
            else -> {
                intent?.getStringExtra(CaptureLaunchContract.EXTRA_LAUNCH_MODE)
                    ?.let { runCatching { CaptureLaunchMode.valueOf(it) }.getOrNull() }
                    ?.let(overlayViewModel::setLaunchMode)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        bubbleMenuController?.dismiss()
        bubbleMenuController = null
        panelWindowController?.dismiss()
        panelWindowController = null
        bubbleCoordinator?.destroy()
        bubbleCoordinator = null
        stateObserver?.stop()
        stateObserver = null
        automationEffectObserver?.stop()
        automationEffectObserver = null
        OverlayRuntimeState.setRunning(false)
        serviceViewModelStore.clear()
        serviceScope.cancel()
        super.onDestroy()
        dispatcher.onServicePreSuperOnDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** 把一颗球的手势接到 ViewModel 上，并把点击位置带给菜单。 */
    private fun gestureSink(slot: BubbleSlot): BubbleGestureSink {
        return BubbleGestureSink(
            onSingleTap = { overlayViewModel.handleSingleTap(slot) },
            onDoubleTap = {
                val center = bubbleCoordinator?.centerOf(slot)
                overlayViewModel.handleDoubleTap(center?.first ?: 0, center?.second ?: 0, slot)
                showMenuIfExpanded()
            },
            onLongPress = {
                val center = bubbleCoordinator?.centerOf(slot)
                overlayViewModel.handleLongPress(center?.first ?: 0, center?.second ?: 0, slot)
                showMenuIfExpanded()
            },
            onLongPressHaptic = { vibrateShort() }
        )
    }

    private fun showMenuIfExpanded() {
        val state = overlayViewModel.bubbleStateMachine.currentState
        if (state is `fun`.kirari.hanako.feature.overlay.state.BubbleState.MenuExpanded) {
            bubbleMenuController?.show()
        } else {
            bubbleMenuController?.dismiss()
        }
    }

    companion object {
        const val ACTION_STOP = "fun.kirari.hanako.overlay.STOP"
        internal const val CHANNEL_ID = "overlay_service"
        internal const val AUTOMATION_CHANNEL_ID = "overlay_automation"
        private const val NOTIFICATION_ID = 1001
        internal const val AUTOMATION_COMPLETE_NOTIFICATION_ID = 1002
    }
}
