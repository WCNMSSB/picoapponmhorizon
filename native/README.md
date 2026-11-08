# OpenXR loader shim

生成器：`tools/gen_xr_shim.py`。它读取两个输入，产出一个 C 源码：

```
python tools/gen_xr_shim.py <symbols.txt> <openxr.h> <out.c>
```

- `symbols.txt`：目标 loader 导出的符号（一行一个）
- `openxr.h`：Khronos 头文件（本仓库 `native/include/openxr/openxr.h`）

从自己的设备提取符号表：

```bash
adb pull /data/app/<hash>/<pkg>-<hash>/lib/arm64/libpico_openxr_loader.so loader.so
llvm-nm -D --defined-only loader.so | awk '{print $3}' | grep '^xr' > symbols.txt
```

编译（NDK r27 clang，目标 Android 29）：

```bash
$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe \
  --target=aarch64-linux-android29 -shared -fPIC -O2 -fvisibility=default \
  -I native/include -Wl,-soname,libpico_openxr_loader.so \
  -o libpico_openxr_loader.so out.c -llog -ldl
```

产物改名成 `libpico_openxr_loader.so` 放进 Magisk 模块的 `files/`。

## 垫片做了什么

| 能力 | 说明 |
| --- | --- |
| 转发 | 除下列函数外全部透传给 `libopenxr_loader_real.so` |
| `xrGetInstanceProcAddr` | **返回垫片自己的实现**，否则应用通过它拿到的全是真 loader 指针，所有覆盖失效 |
| `xrEndFrame` | 修正双眼 fov / 位姿（`persist.pico.xr.*` 实时可调） |
| `xrLocateViews` | 缓存运行时当帧眼位姿 |
| `xrSuggestInteractionProfileBindings` | 把 PICO 交互 profile 重写为 Oculus Touch，并逐条过滤不可绑定路径 |
| `xrGetActionStateFloat` | 对 battery 动作返回可配值（PICO 有、Meta 没有） |

运行时开关（全部 `persist.*`，每帧读取）：

| 属性 | 作用 |
| --- | --- |
| `persist.pico.xr.eyeswap` | `none/views/images/fov/offset/offset+images/locate-live/fill` |
| `persist.pico.xr.ipd` | `offset` 模式的合成 IPD（米） |
| `persist.pico.xr.fovshift` | 每眼视锥水平偏移（弧度） |
| `persist.pico.xr.fovvshift` | 垂直偏移（弧度） |
| `persist.pico.xr.vmode` | `0` 共模 / `1` 差分 |
| `persist.pico.battery.left/right/hmd` | 电量显示值（0..1） |
| `persist.pico.injector` | 是否加载原生 router（默认 0） |

## 为什么需要 fov 偏移

PICO 应用按 PICO 的投影中心渲染，PICO 运行时通过
`XR_BD_composition_layer_settings` 得知这个偏置，Meta 没有该扩展，于是两幅画面
整体横移。实测错位约 **27.7% 眼宽**（572/2064 px），用 `fovshift 0.13 rad` 补偿后
残差降到 1%（20 px）。
