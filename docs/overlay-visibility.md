# 悬浮窗在敏感页面被隐藏：成因与解决方案

## 现象

在微信收款码、银行 App、支付密码页、企业管控页等界面上，Hanako 的悬浮球会消失。

## 根因（AOSP 源码级）

这类页面通常做了两件事中的一件或两件：

1. `Window.setHideOverlayWindows(true)`（Android 12 / API 31 起）
2. `WindowManager.LayoutParams.FLAG_SECURE`

真正把悬浮球「藏起来」的是第 1 个。WMS 内部实现（`WindowState#setForceHideNonSystemOverlayWindowIfNeeded`）：

```java
void setForceHideNonSystemOverlayWindowIfNeeded(boolean forceHide) {
    final int baseType = getBaseType();
    if (mSession.mCanAddInternalSystemWindow
            || (!isSystemAlertWindowType(baseType) && baseType != TYPE_TOAST)) {
        return;                      // ← 不是「系统警报窗口」就直接返回，不受影响
    }
    ...
    if (forceHide) hide(true, true);
}
```

而 `isSystemAlertWindowType()` 只包含这几个类型：

```
TYPE_PHONE / TYPE_PRIORITY_PHONE / TYPE_SYSTEM_ALERT
TYPE_SYSTEM_ERROR / TYPE_SYSTEM_OVERLAY / TYPE_APPLICATION_OVERLAY
（外加单独判断的 TYPE_TOAST）
```

**`TYPE_ACCESSIBILITY_OVERLAY` 不在名单里**，所以从系统层面看，无障碍悬浮层不会被 `setHideOverlayWindows(true)` 隐藏。

顺带澄清两个常见误解：

- `FLAG_SECURE` 本身不会让悬浮窗在**屏幕显示**上消失，它只影响截图/录屏/投屏的合成结果（截图变黑）。
- 这跟「悬浮窗权限」无关，`Settings.canDrawOverlays()` 一直是 true，是系统主动隐藏而非权限被收回。

## 方案对比

| 方案 | 能否解决 | 代价 | 结论 |
|---|---|---|---|
| 保持 `TYPE_APPLICATION_OVERLAY` | ❌ | — | 系统强制隐藏，无解 |
| 改 `TYPE_ACCESSIBILITY_OVERLAY` | ✅ | 用户手动开启无障碍服务 | **已采用** |
| 系统签名 / `PRIVATE_FLAG_SYSTEM_APPLICATION_OVERLAY` | ✅ | 需要系统权限，普通 App 拿不到 | 不可行 |
| Shizuku 反射改 WMS 状态 | ⚠️ | 每次目标 App 设置都会被重新隐藏，等于对抗式 hook | 不可行 |
| 被隐藏时用通知/快捷方式兜底 | ✅（降级） | UX 变差，每次多一步 | 未实现，可作后续兜底 |

## 已实现的改动

1. **新增无障碍服务** `HanakoOverlayAccessibilityService`
   - 只提供 `TYPE_ACCESSIBILITY_OVERLAY` 所需的窗口令牌
   - `canRetrieveWindowContent=false`，不读取、不监听、不操作任何界面
2. **窗口类型选择器** `OverlayWindowHost`（`OverlayWindowTypes.kt`）
   - 无障碍服务可用 → `TYPE_ACCESSIBILITY_OVERLAY`
   - 不可用 → 回退原来的 `TYPE_APPLICATION_OVERLAY`
   - 三个悬浮窗（气泡、扇形菜单、结果面板）共用同一套判断，改一处全生效
3. **OverlayService** 改用 `OverlayWindowHost.windowManager()`，并在无障碍模式下不再强制要求 `SYSTEM_ALERT_WINDOW`
4. **设置 → 更多 → 无障碍悬浮层** 新增入口卡片，直接跳系统无障碍设置并显示当前状态
5. 无障碍服务被关闭时，主动停掉前台服务，避免出现「服务在跑但球看不见」的僵死状态

## 使用方式

1. 设置 → 更多 → 无障碍悬浮层 → 点卡片跳到系统无障碍设置
2. 找到「Hanako 悬浮层增强」并开启（Android 13+ 侧载应用需要先在应用详情里允许「受限设置」）
3. **重启悬浮球**（关掉再启动），此后悬浮球全程走无障碍悬浮层
4. 在微信收款码页面验证：悬浮球应该保持可见

日志关键字：`adb logcat -s HanakoA11yOverlay`

## 已知限制

- **必须由用户手动开启无障碍**，这是系统对无障碍权限的硬性要求，无法绕过。
- 无障碍悬浮层的层级比普通悬浮窗更高（在键盘之上），个别页面的遮挡关系会变化。
- Google Play 政策对「非无障碍用途使用无障碍 API」有限制，本功能只适合侧载分发；描述文案已明确说明不读取界面内容。
- 如果无障碍服务在悬浮球运行期间被关闭，悬浮球会被系统一并移除，需要重新启动。

## 配套测试工具

`SecureOverlayLab`（`apk/SecureOverlayLab.apk`）用来复现和对照验证：

- 开关 `FLAG_SECURE`：观察截图是否变黑（悬浮窗应仍然可见）
- 开关 `setHideOverlayWindows(true)`：观察 A 球（普通悬浮窗）是否消失、B 球（无障碍悬浮层）是否留下
- 实时显示两个悬浮窗的 `isShown()` 与 `windowVisibility`
- 内置一个模拟收款码页面

预期结果：打开 `setHideOverlayWindows` 后，**A 消失、B 保留**。这正是 Hanako 改用 B 的依据。
