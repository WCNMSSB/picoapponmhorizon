# picoapponmhorizon

**PICO Apps on Meta Horizon** — 在 Meta Quest 上运行 PICO 4 Ultra 应用，不刷机、不改系统分区。

这个项目把 PICO 4 Ultra（P4U, `5.14.4`）的应用搬到 Quest 3 上，全部通过 Magisk 与
LSPosed 在运行时适配。已经在真机上验证过 PICO Connect（串流）、PICO 个人中心、PICO 账号登录。

> 仅用于**自有设备上的互操作性研究**。仓库不含任何 PICO / Meta 的二进制，
> 需要你自己从自己的设备镜像里提取。

## 目前该项目准备重构，使用p4u的5.15版本，并且引入miuix

## 它解决什么

PICO 应用假设自己跑在 PICO 的 Android 定制系统上：

| PICO 的假设 | Horizon OS 的现实 | 本项目的处理 |
|---|---|---|
| 应用是系统 uid，能直接读序列号 | 普通 uid，`getSerial` 抛异常 | LSPosed 钩 `Build.getSerial`，伪造 PICO 序列号 |
| 序列号里编码了机型 | 无 | 伪造 `PA8X...` → 应用识别为 PICO 4 |
| 有 `com.picovr.*` 权限、`horizonos.permission.*` | 缺失 | 钩 `checkPermission` / `getPackageInfo` 补齐 |
| 应用由 PICO shell 拉起，全屏沉浸 | Horizon 只认 `com.oculus.intent.category.VR` | 钩 vrshell 的 VR 判定函数强制为 VR |
| `libpico_openxr_loader.so` 是 PICO 运行时 | Meta 的 loader | Magisk bind-mount 自研垫片（不改 APK） |
| 手柄/指针输入是 PICO 私有设备 | Quest 的输入设备 | 伪造设备 id + 合成鼠标按键事件 |
| 面板尺寸由 PICO 决定 | Horizon 默认 1024×640 dp | 二进制 manifest 写 `<layout>` 或运行时改 |

## 仓库结构

```
lsposed/                LSPosed 模块（Java）
  src/                  序列号、hidden API、权限门面、输入、强制 VR、HTTP 调试
  build_apk.ps1         构建脚本（javac → d8 → aapt2 → apksigner）
magisk/pico_xr_loader/  Magisk 模块：把 OpenXR 垫片 bind-mount 到已安装应用
native/                 OpenXR loader 垫片源码与 Khronos 头文件
  include/openxr/       openxr.h（Khronos, Apache-2.0）
tools/
  gen_xr_shim.py        生成垫片 C 源码（眼序、fov 补偿、电池、交互 profile 重写）
  repack_app.py         APK 重打包（sharedUserId / targetSdk / .so 替换）
  add_vr_category.py    给 Activity 插入 oculus VR category
  add_visibility.py     补 <queries> 包可见性
  add_layout_size.py    写 <layout defaultWidth/Height> 面板尺寸
  set_manifest_attr.py  改二进制 manifest 的布尔/字符串属性
  add_permission.py     插入 <uses-permission>
  apk_probe.py          探测 APK 的 targetSdk / sharedUserId / loader / VR 声明
  lsp_scope.py          命令行增删 LSPosed 作用域
  deploy_lsp.ps1        安装模块并刷新 LSPosed 缓存
  enable_pico_app.ps1   一键接入已安装 PICO 应用
  restart_pico_apps.ps1 重启所有 PICO 应用（lspd 重启后必须）
  pcap_hosts.py         纯 Python 解析 pcap 的 DNS/SNI
  erofs_pull_via_device.ps1  用设备内核挂 EROFS 取文件
docs/porting-notes.md   移植过程与踩坑记录
```

## 快速开始

前置：Quest 已 root（本项目在 Singularity 提供的临时 root + Magisk + LSPosed 上开发），
PC 上有 JDK、Android SDK（build-tools 34）和 Xposed API 82。

```powershell
# 1. 构建 LSPosed 模块
$env:ANDROID_SDK_ROOT = "C:\Android\Sdk"
$env:XPosed_API = "C:\path\to\api-82.jar"
powershell -ExecutionPolicy Bypass -File lsposed\build_apk.ps1

# 2. 安装并刷新 LSPosed 缓存（会自动重启 lspd）
powershell -ExecutionPolicy Bypass -File tools\deploy_lsp.ps1

# 3. 关键：lspd 重启后必须重启应用，否则运行中的进程没有钩子
powershell -ExecutionPolicy Bypass -File tools\restart_pico_apps.ps1 -AlsoLaunch
```

把任意已安装的 PICO 应用接入：

```powershell
powershell -ExecutionPolicy Bypass -File tools\enable_pico_app.ps1 -Package com.example.game
```

Magisk 的 loader 覆盖模块需要自己准备 `files/`（见该目录的 README）。

## 已验证 / 未验证

**已实测可用**

- PICO Connect 桌面串流、VR 模式全屏沉浸
- 双手柄追踪与按键（profile 重写 + 逐条绑定过滤）
- 桌面模式点击（设备 id 白名单绕过）
- 麦克风回传、PICO 原生个人中心、PICO 账号登录
- 面板尺寸精确控制（`<layout>` 元数据）
- Horizon shell 把 PICO 包判定为 VR 应用（LSPosed 钩，不改 APK）

**已知不可行**

- PICO 商店：重签名后被服务端拒绝
- 手柄电量：PICO 应用不轮询 OpenXR 电池动作，Quest 端无数据源
- PICO 手机 App 设备同步：PICO 云端按出厂序列号绑定，客户端无法伪造
- 依赖 PICO 私有服务（XRShell / `PanelWrapper`）的官方应用
- 带 `sharedUserId="android.uid.system"` 的 APK：安装阶段就无法通过

## 免责声明

本项目不包含、不分发任何 PICO 或 Meta 的专有代码或二进制。所有二进制访问
（APK、系统镜像、系统库）都需要使用者从自己合法持有的设备中提取。
项目仅做接口兼容层，用于在自己拥有的硬件上运行自己拥有的应用。

## 作者

**周-zhou (WCNMSSB)** 与 **DeepSeek**。

真机调试、逆向分析与工程实现均为两人协作完成。

## License

**GNU General Public License v3.0**（GPL-3.0），全文见 `LICENSE`。

简单说：你可以自由使用、修改、分发，但**分发修改版时必须同样以 GPL-3.0 开源**。
私有使用（自己装在自己设备上）不受任何限制。

例外：`native/include/openxr/openxr.h` 来自 Khronos Group，采用 `Apache-2.0 OR MIT`
双许可，与 GPL-3.0 兼容。
