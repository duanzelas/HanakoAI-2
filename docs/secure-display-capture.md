# Shizuku secure display 捕获改造说明

本改造让 Hanako 在使用 **Shizuku 截屏后端** 时，可以截到设置了 `FLAG_SECURE` 的页面
（银行、支付、DRM、企业管控应用等），失败时自动回退到原来的 `screencap`。

## 为什么原来的 Shizuku 后端截不到

原实现是 `service.exec(["sh","-c","screencap -p"])`，即以 shell 身份跑系统自带的
`screencap`。`screencap` 不带 secure policy，SurfaceFlinger 在合成时会把 secure 层过滤掉，
结果是一张黑图。

而 `MediaProjection` / `scrcpy` / 录屏 App 走的是虚拟屏路径：secure 层要显示在目标 Display 上，
必须让该 Display 带 `VIRTUAL_DISPLAY_FLAG_SECURE`，创建这种 Display 需要
`CAPTURE_SECURE_VIDEO_OUTPUT`（signature|privileged），第三方应用拿不到。
所以这两条路都无解，唯一可行的是**以 shell uid 直接调用 WMS 的 `captureDisplay` 并显式请求 secure 层**。

## 改动点

| 文件 | 改动 |
|---|---|
| `app/src/main/aidl/.../IShizukuShellService.aidl` | 新增 `byte[] captureSecureDisplay(int maxLongEdge)` |
| `platform/capture/shizuku/SecureDisplayCapture.kt` | 新增，反射实现 WMS 整屏捕获 + secure policy + JPEG 编码 |
| `platform/capture/shizuku/ShizukuShellService.kt` | 实现新 AIDL 方法，异常一律降级为空数组 |
| `platform/capture/shizuku/ShizukuShellAccess.kt` | `ShizukuShellScreencap` 优先 secure 捕获、全黑检测、回退 `screencap`；UserService 版本号 2→3 |
| `platform/capture/shizuku/ShellScreencap.kt` | `capturePng()` 改名 `captureImage()`，PNG/JPEG 都可解码 |

## 版本适配（关键）

系统在 Android 16.1 重构了这套 API：

| Android 版本 | 类 | secure 开关 |
|---|---|---|
| 14 ~ 16.0 | `android.window.ScreenCapture` | `CaptureArgs.mCaptureSecureLayers`（boolean） |
| 16.1+ | `android.window.ScreenCaptureInternal` | `mSecureContentPolicy` / `mProtectedContentPolicy`（int，1 = 允许） |
| ≤13 | — | 不支持，直接回退 `screencap` |

`SecureDisplayCapture` 不写死版本号，而是**按类名探测**：依次尝试
`ScreenCaptureInternal`、`ScreenCapture`，谁加载成功就用谁；secure 开关则依次尝试
builder 的 `setCaptureSecureLayers(true)`、`mSecureContentPolicy=1`、
`mProtectedContentPolicy=1`、字段 `mCaptureSecureLayers=true`，任一命中即认为开启成功。
这样系统再次改名时最多退化为「回退 screencap」，不会编译期就废掉。

`captureDisplay` 的签名也在变（2 参与 3 参两种），因此不写死方法签名，而是扫描
`IWindowManager` 的方法表，找参数里包含当前 `CaptureArgs` 类型与 `ScreenCaptureListener` 的那个。

## 失败与回退

1. `captureSecureDisplay` 返回空数组 / 抛异常 → 回退 `screencap`；
2. 返回的帧**全黑**（采样所有像素都是 `#000000`）→ 判定 secure policy 未生效 → 回退 `screencap`；
3. 服务进程死亡（`RemoteException`）→ 作废连接、重新绑定后再回退。

全黑检测是必要的：权限被拒或策略被忽略时，WMS 仍会返回一张黑图，如果当成成功，
模型会拿到一张纯黑图并输出莫名其妙的答案，排查成本极高。

## 体积控制

单帧编码为 JPEG（quality 92），长边先压到 1600px；若仍超过 800KB（Binder 事务余量），
自动降质量、降分辨率，最多 4 轮。日志会打印每一轮的字节数。

## 验证方法

```bash
# 观察 secure 捕获是否生效
adb logcat -s HanakoSecureCap HanakoShizuku

# 期望看到
# I HanakoSecureCap: using capture api android.window.ScreenCaptureInternal
# I HanakoSecureCap: secure capture ok bytes=412345 durationMs=180
# I HanakoShizuku: secure display capture ok bytes=412345
```

打开一个带 `FLAG_SECURE` 的页面（银行 App 的余额页、Chrome 无痕等）后触发一次截屏：

- 日志出现 `secure capture ok` 且结果图正常 → 生效；
- 出现 `secure display capture returned an all-black frame` → 该 ROM 上 secure policy 被忽略，
  已自动回退，功能退化为改造前；
- 出现 `no compatible ScreenCapture API` / `captureDisplay not found` → 该类名或方法签名又变了，
  按上面表格补一条候选即可。

## 需要真机确认的两点

1. **隐藏 API 是否可达**：UserService 跑在 `app_process` 启动的进程里，通常不受应用侧
   hidden-api 限制；若某些 ROM 上 `WindowManagerGlobal.getWindowManagerService()` 被拦，
   日志会明确报 `unavailable`，此时只能回退。
2. **shell 权限是否被厂商收紧**：部分 ROM 对 `CAPTURE_SECURE_VIDEO_OUTPUT` 的判定更严，
   表现为 `SecurityException`，同样会自动回退。

## 合规

该能力会绕过其他应用的截屏保护。Google Play 政策明确禁止此类功能，本改动仅适用于侧载分发；
面向银行、DRM、考试等场景使用时可能违反当地法律或目标应用服务条款，请自行评估。
