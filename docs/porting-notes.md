# 移植笔记与踩坑记录

目标设备：Meta Quest 3（`eureka`，Android 14 / SDK 34），root 由 Singularity 提供（重启失效），
Magisk + LSPosed（API 102）。源设备镜像：PICO 4 Ultra `5.14.4`。

## 1. PICO 应用的隐含假设

### 1.1 序列号即机型

`DeviceUtil.c()` 从序列号推断机型：

```
charAt(1) != 'A'                        -> UNSUPPORTED_DEVICE
charAt(2) == '9'                        -> PICO4S_SERIES（"PICO 4 Ultra"）
charAt(2) == '8' && charAt(3) in S..U/Z -> PICO4S_SERIES
charAt(2) == '8' && charAt(3) == 'A'    -> PICO4_PRO_SERIES
charAt(2) == '8' 其它                    -> PICO4_SERIES
charAt(2) == '7' && charAt(3) in H..Y   -> NEO3_SERIES
```

所以伪造的序列号必须满足格式（本项目用 `PA8X2C3D4E5F6` → PICO 4）。
直接给一个可读字符串（如 `PICO4ULTRA...`）会落到 `UNSUPPORTED_DEVICE`。

### 1.2 系统 uid 才有的权限

PICO 应用声明了 `sharedUserId="android.uid.system"`，因此调用
`Build.getSerial()`、`PowerManager.newWakeLock()` 时不检查权限。装成普通应用后：

```
SecurityException: getSerial: The uid 10203 does not meet the requirements
  → ExceptionInInitializerError（发生在静态初始化里，进程启动即崩）
```

`WAKE_LOCK` 更麻烦：它是系统服务端拒绝的，客户端 `checkPermission` 钩不住，
只能让 `WifiManager$WifiLock.acquire/release` 空转。

**Android 14 的签名差异**：`WifiLock.acquire(int)` 这种精确签名不存在，
必须用 `hookAllMethods` 枚举所有重载，否则 `NoSuchMethodError` 且钩子丢失。

### 1.3 同一个 uid 才能互访的 provider

登录态在 `com.picovr.vrusercenter` 的 provider 里：

```
content://com.picovr.vrusercenter.userinfo/user_info
```

manifest 里 `exported=false`。PICO 上所有 PICO 应用同属 `android.uid.system`，
同 uid 不受 exported 限制；Horizon 上各自 uid，于是 PICO Connect 报：

```
ActivityThread: Failed to find provider info for com.picovr.vrusercenter.userinfo
```

需要两步才能打通，缺一不可：

1. provider 改成 `exported=true`（二进制 manifest 属性补丁）
2. targetSdk ≥ 30 的应用要在 `<queries>` 里声明才能看见对方（Android 11 包可见性）

```xml
<queries>
  <package android:name="com.picovr.vrusercenter"/>
  <package android:name="com.bytedance.pico.matrix"/>
  <provider android:authorities="com.picovr.vrusercenter.userinfo"/>
  <provider android:authorities="com.bytedance.pico.matrix.account"/>
</queries>
```

### 1.4 面板尺寸

Horizon 的 2D 面板默认 **1024×640 dp**（最小 360×225 dp），由用户拖动调整。
应用可以用 activity 下的元数据声明默认值：

```xml
<activity android:name=".MainActivity">
    <layout android:defaultWidth="1163dp" android:defaultHeight="727dp" />
</activity>
```

二进制编码是 `TYPE_DIMENSION`(0x05)，`data = (dp << 8) | 1`（radix=0，unit=dp）。

`ActivityOptions.setLaunchBounds()` **无效**——DesktopActivity 由 VrShell 的
`PanelAppHost` 启动，应用侧的 `startActivity` 钩子根本不会被触发。
另外 VrShell 会**记住**每个面板的尺寸，`<layout>` 只对首次创建生效。

### 1.5 指针输入白名单

`DesktopGLSurfaceView.onTouchEvent()` 开头：

```java
int id = event.getDeviceId();
if (id != 99999 && id != 100000 && ... && id != 100004) {
    return true;   // 吞掉，不处理
}
```

Quest 给的手柄指针设备 id 是 `0x100002`/`0x100004`（1048578/1048580），不在白名单里，
于是"光标能动、点击无效"。把该区间映射到 `100000` 即可。

点击事件本身走 `onGenericMotionEvent` 的 `ACTION_HOVER_MOVE` + `ACTION_BUTTON_PRESS`，
而 Horizon 只发 `ACTION_DOWN/UP`——所以还要合成鼠标按键事件。

## 2. Horizon 侧的关键机制

### 2.1 shell 如何判定"VR 应用"

`com.oculus.vrshell` 里（本机混淆为 `X.007.A06/A08`）依次判断：

```
com.oculus.intent.category.2D                      → 面板应用
meta-data com.samsung.android.vr.application.mode  → vr_only / dual
com.oculus.intent.category.VR                      → 沉浸
org.khronos.openxr.intent.category.IMMERSIVE_HMD   → 沉浸
```

PICO 应用一个都不声明（PICO 系统自己判断），所以在 Quest 上会开成 2D 面板，
永远创建不了 XR session。钩住这个函数、对 PICO 命名空间返回 true 即可，
**不需要改 APK**。

### 2.2 LSPosed 的两个陷阱

**陷阱一：系统包直接跳过。** `handleLoadPackage` 开头是
`if (isSystemPackage(pkg)) return;`，而 vrshell 是系统应用。分支必须放在这句**之前**。

**陷阱二：重启 lspd 会让已运行进程失去钩子。** LSPosed 只在进程启动时注入。
`adb install -r` 模块后 `killall lspd` 重启，那些已经在跑的 PICO 进程就处于
"无钩子"状态，下次启动直接崩在 `getSerial`。**改完必须重启目标应用**
（`tools/restart_pico_apps.ps1`）。

### 2.3 OpenXR 的三个坑

**坑一：`xrGetInstanceProcAddr` 会绕过所有覆盖。** 应用通过它取函数指针，
如果直接转发给真 loader，应用拿到的是真实现，垫片里的 `xrEndFrame` 修正、
电池钩子全部失效。必须在 `xrGetInstanceProcAddr` 里返回**自己的**函数表，
而且表里要包含所有自己实现的函数（不只原始符号表）。

**坑二：交互 profile 不匹配。** PICO 提交
`/interaction_profiles/bytedance/pico4_controller` 等，Meta 不认识 → `-22`。
重写成 `/interaction_profiles/oculus/touch_controller`。

**坑三：一条非法路径拖垮整批。** OpenXR 的语义是"只要有一条组件路径不可绑定，
整次建议被拒"。`/input/system/click`（系统保留）、`/input/battery/value`（Touch 没有）
都会让 35 条绑定全废。必须逐条单独试绑，只提交成功的那批（实测保留 28/35）。

### 2.4 状态确认

```
xrSyncActions #1 -> 8    ← XR_SESSION_NOT_FOCUSED，此时动作还不 active
xrSyncActions #2 -> 0
xrGetActionStatePose -> 0 active=1
```

诊断时必须看**第二轮同步之后**的 `isActive`，否则会误判成"绑定没生效"。

## 3. 双眼错位的量化

SteamVR 渲染正常但两眼画面水平错开约半个屏幕。用截图互相关测量：

```
两眼水平错位 ≈ 572 px / 2064 px = 27.7% 眼宽
```

正常 64mm IPD 在 2 米距离只对应约 30 px，所以这不是 IPD，而是投影中心偏置。
用每眼视锥水平偏移补偿，真机调参结果：

| `fovshift` | 结果 |
| --- | --- |
| 0.22 | 过头（交叉） |
| 0.11 | 接近但差一点 |
| **0.13** | **最佳** |
| 0.145 | 又过头 |

垂直方向是共模偏移（两眼一起偏下），`fovvshift 0.05` 修正。

## 4. 工具链上的坑

- **Windows 的 `rust-fs-erofs` 读不了 PICO 的 `system.img`**：
  镜像启用了 EROFS `0padding` 特性，工具报
  `malformed inode: LZ4 decompressed size mismatch`。尝试过在 Quest 上 loop 挂载，
  但 Quest 内核**不支持 erofs**（`/proc/filesystems` 里没有）。最终需要 Linux 版
  erofs-utils（WSL）或自写解析器。
- **Windows 打 zip 不能用 `ZipFile.CreateFromDirectory`**：会写反斜杠路径，Magisk
  解压后文件名变成 `files\libc++.so`。用 Python `zipfile`。
- **PowerShell 的 `>` 重定向写出 UTF-16**：`adb shell ... > file` 之后用 Python
  按 UTF-8 解析会失败，需要 `cmd /c` 或按 UTF-16 读。
- **`adb install -r` 会改 `/data/app` 路径**，LSPosed 的 `modules_config.db` 里
  存的旧路径会让模块静默失效，装完必须改写那一行并重启 lspd。
