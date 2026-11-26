# Spectrometer Mobile

安卓版 Theremino Spectrometer V5.0 —— 把原 VB.NET 桌面程序的全部功能移植到 Android。

## 功能

对应原 VB 源码（`Module_Spectrometer.vb` / `Module_Calibrations.vb` / `Form1.vb`）：

- **摄像头采集**：Camera2 API 替代 DirectShow，支持 YUV_420_888 取 Y 平面亮度，可选摄像头、曝光/增益/补光
- **信号处理链**（原样移植）：
  - 辐照度校正 → 平均滤波 → 减 ADC 底噪 → 缩放 65536
  - UP/DOWN 快慢滤波（`K = (speed/100)^1.5`）
  - bin↔nm 分段线性插值校准
  - 双向空间滤波（`KFilter = 0.1 + 0.9*(10-level)/10`）
  - 背景扣除 / 参考归一化
- **光谱显示**：nm/强度网格、彩色波长填充、黑曲线、峰值标记、校准点、十字光标（对应 `ShowSpectrumGraph`）
- **校准编辑**：多点 bin/nm 增删、自动归一化到图像宽度
- **文件**：保存/载入 Theremino 兼容的 `Nanometers<TAB>Intensity` 文本格式
- **色度学**：CCT（McCamy 近似）、Duv、峰值波长、melanopic EDI（CIE S 026 简化）
- **黑帧抑制**：摄像头交替出图/黑帧时只取有效帧

## 构建

不用本地装 Android SDK 或 Gradle。GitHub Actions CI 自动构建并产出 APK：

1. push 到 `main` 分支，或手动触发 workflow_dispatch
2. CI 用 JDK 17 + Android SDK 34 + Gradle 8.9 编译
3. 在 Actions 页面下载 artifact：
   - `spectrometer-debug-apk`
   - `spectrometer-release-apk`

## 源码对应关系

| VB 原文件 | 安卓实现 |
|---|---|
| `Module_Spectrometer.vb` | `spectrometer/SpectrometerEngine.kt` |
| `Module_Calibrations.vb` | `spectrometer/SpectrometerEngine.kt`（calib* 方法） |
| DirectShow 采集 | `spectrometer/Camera2Capture.kt` |
| `Module_Spectrometer.ShowSpectrumGraph` | `ui/SpectrumView.kt` |
| `GetSpectrumText` / `SetSpectrumTextFromFile` | `SpectrometerEngine.getSpectrumText/loadSpectrumText` |
| CCT/颜色（依赖 System.Drawing） | `spectrometer/Colorimetry.kt` |
| `Form1.vb` 控件 | `MainActivity.kt` + `res/layout/activity_main.xml` |

## 说明

- 摄像头路径不需要 CH340/串口；原程序的线阵传感器（COM）路径在 USB Camera 模式下用不上
- WebCam 模式 `SENSOR_NumSamples = 图像宽度`，校准数组会自动归一化
- `minSdk 26`，`targetSdk 34`
