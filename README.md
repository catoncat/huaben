# 小作家故事本 (`com.luna.storynote`)

独立运行的原生 Android 应用（Kotlin + Jetpack Compose，适配手机竖屏与安卓平板横屏双栏），专为 7 岁不识字小朋友免按住讲故事写书设计：

- **点一下开始讲、再点一下保存**：解放双手，录制期间自动持有屏幕常亮与 `PARTIAL_WAKE_LOCK` 防锁屏断麦，含 `850ms` 防连点误触保护与 16 柱实时声波反馈。
- **豆包输入法接口零密钥长语音识别（突破 ~200s 单次上限）**：
  - 本地独立完成虚拟设备注册与 `Frontier` WebSocket 协议交互，无需电脑后台服务。
  - `RollingDoubaoTranscriber` 在满 120s 遇到 0.45s 换气停顿（或满 165s 安全线）自动收尾切换新会话无缝拼接，同时支持对着已存 `audio.wav` 本地一键重新识别。
- **原声 + Markdown 双留存与系统分享**：
  - 每篇故事独立保存在应用外部文件目录 `notes/<时间戳-id>/`（含 16kHz 单声道无损 `audio.wav`、`note.md`、`meta.json`），支持贴纸标记、进度条回听、家长改字及系统一键分享。

## 本地编译 APK

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd ~/fun/story-note
./gradlew assembleRelease testDebugUnitTest
```
