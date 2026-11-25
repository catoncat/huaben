# 小作家故事本 (`com.luna.storynote`)

面向准一年级（7 岁左右、正在学拼音认字、不会打字）小朋友设计的**语音创作与注音互动故事书应用**（Compose Multiplatform：同一套代码同时运行于 **macOS 本地原生窗口** 与 **Android 独立 APK**，零额外环境安装）：

- **精装绘本书架与拟真对开书页**：[BookshelfAndWordBoxScreen.kt](app/src/commonMain/kotlin/com/luna/storynote/ui/BookshelfAndWordBoxScreen.kt) 与 [InteractiveBookSpread.kt](app/src/commonMain/kotlin/com/luna/storynote/ui/InteractiveBookSpread.kt) 采用亚麻装帧书脊、烫金线稿徽章、贴签注音封面与平板横屏双页对开（竖屏/手机自动切换单页纸叠），全界面零 Emoji、零说教提示文案。
- **声文合一逐句创作（无需打字）**：[UtteranceStreamRecorder.kt](app/src/commonMain/kotlin/com/luna/storynote/UtteranceStreamRecorder.kt) 基于豆包流式语音与停顿断句，点击书页底部机械质感麦克风即可边讲边在呼吸墨迹光标处长出带拼音的文字，每句话自动绑定独立裁剪的 `16kHz WAV` 原声片段，页满自动翻开新页并盖下页码章。
- **双层原位点按交互与修改**：
  - **点任意汉字/词语** → 弹出 [TianZiGeWordSheet.kt](app/src/commonMain/kotlin/com/luna/storynote/ui/TianZiGeWordSheet.kt) **田字格认字拼读卡**：内置离线 `3,755` 个国标一级汉字楷体逐笔动画（[HanziStrokeRepository.kt](app/src/commonMain/kotlin/com/luna/storynote/HanziStrokeRepository.kt)）、部编版一年级**声母/介母/韵母/整体认读音节**拆解（[LiteracyEngine.kt](app/src/commonMain/kotlin/com/luna/storynote/LiteracyEngine.kt)）、标准普通话点读、同音字一键纠错与重念换词、收入字词盒。
  - **点句首声波圆点** → 播放孩子自己录下的该句原声，并浮出纯矢量三键胶囊（重讲本句 / 在此句后插讲 / 橡皮擦除及撤回）；点页面任意留白即可移动续写光标。
- **部编版一年级标准注音与避头尾排版**：[PinyinAnnotator.kt](app/src/commonMain/kotlin/com/luna/storynote/PinyinAnnotator.kt) 支持常见多音字词组消歧（`地图 dì tú / 开心地 kāi xīn de`、`长大了 zhǎng dà le`、`睡觉 shuì jiào`）、轻声词表及 `一 / 不` 变调规则。
- **3D 翻转字词盒（`我的字词盒`）**：[WordBoxFlashcardScreen.kt](app/src/commonMain/kotlin/com/luna/storynote/ui/WordBoxFlashcardScreen.kt) 正面纯汉字无拼音自测，点击 180° 翻转揭示拼音并回放孩子故事里的原声例句。

## 本地直接打开桌面版预览（零额外环境）

```bash
~/fun/story-note/run-local.sh
```

桌面窗口顶部支持 **「平板对开 1140 / 平板单页 760 / 手机 400」** 实时切换预览，可直接用 Mac 麦克风试录与点读。

## 编译测试与安卓独立 APK

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd ~/fun/story-note
./gradlew :app:desktopTest :app:assembleRelease
# 产物：dist/StoryNote-v0.3.0.apk
```
