# 小作家故事集 (`com.luna.storynote`)

面向准一年级（7 岁左右、正在学拼音认字但不会打字）小朋友的**注音故事书创作应用**（Compose Multiplatform：同一套代码同时运行于 **macOS 原生桌面窗口** 与 **Android 独立 APK**，无需安装 Android Studio 或模拟器）：

- **免按住讲故事 + 思考暂停续录**：点一下开讲，中途可点 **「⏸ 暂停想一想」/「▶ 继续讲」**，讲完点 **「✓ 讲完了，生成故事书」** 直接翻开排版好的注音书页；已有故事底部支持 **「🎙️ 接着往下讲这一篇」** 自动续接录音与段落。
- **自动切分短段落（「一小段一小段」）**：[StoryBookFormatter.kt](app/src/commonMain/kotlin/com/luna/storynote/StoryBookFormatter.kt) 按叙事转折词与句群长度自动拆成适合一年级阅读的短段落，规整中文标点并断句。
- **部编版一年级标准拼音注音与禁则排版**：
  - [PinyinAnnotator.kt](app/src/commonMain/kotlin/com/luna/storynote/PinyinAnnotator.kt) 内置多音字词组消歧（如 `长大了 zhǎng dà le`、`睡觉 shuì jiào`、`快乐 kuài lè / 音乐 yīn yuè`、`看着 kàn zhe`、`饼干 bǐng gān`）、常用双音节轻声词表（`妈妈 mā ma`、`月亮 yuè liang`、`喜欢 xǐ huan`）及 **`一 / 不` 课本变调规则**（`一只 yì zhī`、`一块 yí kuài`、`不是 bú shì`）。
  - [RubyTextComponents.kt](app/src/commonMain/kotlin/com/luna/storynote/ui/RubyTextComponents.kt) 采用宋体字面 + 等宽字格逐字居中注音、首行缩进两格，并按中文避头尾禁则将标点绑定词簇，杜绝句读符号掉到行首。
- **原声留存对照跟读**：每篇故事完整保留 16kHz `audio.wav` 原声与 `note.md` 文稿，孩子可边听自己讲的原声边对照拼音认字。

## 本地直接打开桌面版预览（零额外环境）

```bash
~/fun/story-note/run-local.sh
```

桌面窗口顶部内置 **「平板横屏铺满 / 平板竖屏 / 手机竖屏」** 一键切换，并可直接调用 Mac 麦克风试录与回放。

## 编译安卓独立 APK

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd ~/fun/story-note
./gradlew :app:desktopTest :app:assembleRelease
# 产物：dist/StoryNote-v0.2.0.apk
```
