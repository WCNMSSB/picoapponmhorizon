# Magisk module: PICO XR loader overlay

把 `native/` 构建出的 OpenXR 垫片 **bind-mount** 到已安装 PICO 应用的
`lib/arm64/libpico_openxr_loader.so`（或 `libopenxr_loader.so`）上。
APK 保持字节不变，卸载模块/重装应用即可完全还原。

## 为什么需要它

PICO 应用链接 PICO 自己的 OpenXR loader，它把 PICO 运行时的约定当理所当然：

- 双眼顺序、每眼位姿
- 投影中心的水平/垂直偏移（PICO 通过 `XR_BD_composition_layer_settings` 告知）
- `/user/hand/*/input/battery/value` 这类 PICO 专有交互路径

Meta 的 loader 不满足这些假设，直接换上去会出现眼位错位、画面偏移、手柄不工作。
垫片实现这些兼容层，并转发给真正的 Meta loader。

## 目录

```
module.prop
service.sh          开机遍历所有 PICO 包并做 bind-mount
files/              需要自己放进来（不入库）
  libpico_openxr_loader.so     ← 由 native/ 构建，改名而来
  libopenxr_loader_real.so     ← 从你自己的设备提取（Meta 的 loader）
  libc++.so                    ← 从你自己的设备提取
```

`files/` 里两项来自设备（属于 Meta），因此**不随仓库分发**，请自行提取：

```bash
adb pull /system_ext/lib64/libopenxr_loader.so  files/libopenxr_loader_real.so
adb pull /system/lib64/libc++.so                files/libc++.so
```

（具体路径随系统版本可能不同，用 `adb shell find / -name libopenxr_loader.so` 定位。）

## 打包安装

> 注意：**不要用 Windows 的 `Compress-Archive` / .NET `ZipFile.CreateFromDirectory`**，
> 它们会写入反斜杠路径，Magisk 解压后文件名会变成 `files\libc++.so` 这种非法名字。
> 用 Python 的 `zipfile`：

```python
import os, zipfile
src, dst = "magisk/pico_xr_loader", "pico_xr_loader.zip"
with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(src):
        for f in files:
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, src).replace("\\", "/"))
```

```bash
adb push pico_xr_loader.zip /data/local/tmp/
adb shell su -c "magisk --install-module /data/local/tmp/pico_xr_loader.zip"
```

模块会先落在 `modules_update/`，**重启后生效**。想立刻应用：

```bash
adb shell su -c "sh /data/adb/modules_update/pico_xr_loader/service.sh"
adb shell su -c "cat /data/adb/modules_update/pico_xr_loader/apply.log"
```

## 验证

```bash
adb shell su -c "grep libpico_openxr_loader /proc/mounts"
```

应能看到 `files/libpico_openxr_loader.so` 挂到了应用 lib 目录下。
