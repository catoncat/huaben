# 小作家故事本 (`com.luna.storynote`, v1.0.0)

面向准一年级（7 岁左右、正在学拼音认字、不会打字）小朋友设计的**语音创作与注音互动故事书应用**（Compose Multiplatform：同一套代码同时运行于 **macOS 本地原生窗口** 与 **Android 独立 APK**，零额外环境安装，全离线可用）：

- **精装绘本书架与全屏沉浸式书页**：[BookshelfScreen.kt](app/src/commonMain/kotlin/com/luna/storynote/ui/BookshelfScreen.kt) 与 [InteractiveBookSpread.kt](app/src/commonMain/kotlin/com/luna/storynote/ui/InteractiveBookSpread.kt) 采用亚麻装帧书脊、烫金线稿徽章与全屏暖纸（`#FAF7F0`）沉浸阅读体验，支持平板横屏双页对开（左水彩插画 + 右注音正文）及竖屏/手机响应式单页，支持**触控滑动、左右边缘点击/按钮翻页及桌面键盘 `←` / `→` 翻页**；全界面 Lucide 矢量图标 + 简洁中文短标签，零 Emoji、零说教提示文案。
- **声文合一逐句创作与时光机**：[UtteranceStreamRecorder.kt](app/src/commonMain/kotlin/com/luna/storynote/audio/UtteranceStreamRecorder.kt) 基于豆包流式语音与停顿断句，点击书页底部麦克风即可边讲边在呼吸墨迹光标处长出带拼音的文字，自动按语义切分 `8–16` 字短句并绑定连续 `16kHz WAV` 原声时间轴区间；[TimeMachineStore.kt](app/src/commonMain/kotlin/com/luna/storynote/storage/TimeMachineStore.kt) 自动保存每次修改快照，支持单句历史原声回放与一键还原。
- **双层原位点按交互（词语优先分词）**：
  - **点任意汉字/词语** → 离线 `jieba-analysis` 词语优先切分（[JiebaWordSegmenter.kt](app/src/commonMain/kotlin/com/luna/storynote/literacy/JiebaWordSegmenter.kt)），整词高亮并弹出 [TianZiGeWordSheet.kt](app/src/commonMain/kotlin/com/luna/storynote/ui/TianZiGeWordSheet.kt) **田字格认字拼读卡**：内置离线 `3,755` 个国标一级汉字楷体逐笔动画（[HanziStrokeRepository.kt](app/src/commonMain/kotlin/com/luna/storynote/literacy/HanziStrokeRepository.kt)）、部编版一年级声母/介母/韵母拆解、标准普通话点读、同音字纠错、`重念换词` 与收入字词盒。
  - **点句首声波圆点** → 播放该句原声，并浮出清晰标注的操作胶囊（`播放` / `重说` / `插句` / `改字`（配置 AI 后）/ `历史` / `删`）；点页面留白即可移动续写光标。
- **部编版一年级标准注音与避头尾排版**：[PinyinAnnotator.kt](app/src/commonMain/kotlin/com/luna/storynote/literacy/PinyinAnnotator.kt) 支持常见多音字词组消歧、轻声词表及 `一 / 不` 变调规则。
- **3D 翻转字词盒与家长设置**：[WordBoxFlashcardScreen.kt](app/src/commonMain/kotlin/com/luna/storynote/ui/WordBoxFlashcardScreen.kt) 正面纯汉字自测、180° 翻转揭示拼音并回放原声/普通话例句；长按书架角落设置齿轮打开家长设置（OpenAI 兼容文本/生图端点，Android APK 零内置密钥）。

## 本地直接打开桌面版预览（零额外环境）

```bash
~/fun/story-note/run-local.sh
```

桌面窗口支持自由调整尺寸实时预览响应式排版（横屏对开 / 竖屏单页 / 手机），可直接用 Mac 麦克风试录与点读，并支持鼠标边缘点击与键盘 `←` / `→` 翻页。

## 编译测试与安卓独立 APK

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
cd ~/fun/story-note
./gradlew :app:desktopTest :app:assembleRelease
# 产物：dist/StoryNote-v1.0.0.apk
```
