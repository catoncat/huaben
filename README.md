<div align="center">
  <img src="website/assets/app-icon.png" alt="话本本图标" width="88" />
  <h1>话本本</h1>
  <p><strong>开口讲出故事，不用打字成绘本。</strong></p>
  <p>按住说一句，纸页上长出拼音、留住童声，还能配水彩插画。</p>
  <p>
    <a href="https://cnb.cool/lzb-prod/story-note-releases/-/git/raw/main/apk/StoryNote-latest.apk"><strong>下载 Android APK（国内直连）</strong></a>
    &nbsp;·&nbsp;
    <a href="https://github.com/catoncat/story-note/releases">更新记录</a>
  </p>
</div>

---

给正在学拼音认字、还不会打字的小朋友做的一本温柔故事书。  
打开就能用，没有广告，不强制登录，孩子的作品和原声都安安静静留在本地。

### 为什么做它

| 留住童声 | 改错不怕 | 本地零打扰 |
| :--- | :--- | :--- |
| 点哪句播哪句，写完一键拼成整本童声录音和可打印绘本。 | 语音改词、换同音字、家长全文校对，每一步进时光机随时撤回。 | 无广告、不强制登录；AI 接口完全由家长在设置里掌控。 |

---

### 用起来是什么样子

- **开口成书**  
  像翻一本暖纸精装绘本：平板横屏左图右文双页摊开，手机竖屏整页留给故事。孩子按住麦克风自然讲故事，纸页上自动长出带标准拼音的短句，每一句都留着孩子自己的原声。

- **点字认字，顺手换错字**  
  点正文里的任意词语，弹出田字格识字卡：看楷体逐笔动画、拆解声母韵母、听标准读音，也能一键换同音字或重念换词。喜欢的词收进「字词盒」，翻开复习时还能回听孩子当时的原声例句。

- **点句首圆点，随时重说与调整**  
  点每句开头的声波圆点，可以单句回听、重说、在句后插新句或删掉这句；写卡壳时，还可以请右上角的小星星轻声给一句续写启发。

- **家长全文校对**  
  长按阅读页右上角的「校对」（轻点静默，防孩子误触），进入像备忘录一样干净的全文纸页，直接用键盘改书名、修错别字、增删句子或调整分段。点「完成」后，系统会静默把文字重新对齐回童声原声，无需操心任何音轨操作。

- **时光机与整本带走**  
  每一次改动都会自动留档，随时回听旧录音、一键回到刚才。长按书架上的故事书，就能导出无缝拼接的完整童声录音、带拼音可打印绘本网页和整本故事包。

- **按需开启的 AI 伴读（可完全离线使用）**  
  核心的语音写书、拼音排版、田字格笔顺、字词盒和导出功能均可离线使用。长按书架角落设置图标可进入家长设置，按需填入自己的 OpenAI 兼容接口，开启水彩插画、语音改句与续写启发。

---

### 下载安装

支持 **Android 8.0 及以上平板与手机**（装好后支持在家长设置里一键检查并覆盖更新）。

- **国内直连最新版**：[StoryNote-latest.apk](https://cnb.cool/lzb-prod/story-note-releases/-/git/raw/main/apk/StoryNote-latest.apk)
- **历史版本与校验值**：[GitHub Releases](https://github.com/catoncat/story-note/releases)

<p>
  <img src="website/assets/qr-apk.png" alt="手机扫码直接下载话本本 APK" width="140" />
</p>

---

<details>
<summary>开发者本地运行与构建</summary>

需要 JDK 17 与 Android SDK：

```bash
# macOS 本地窗口预览
./run-local.sh

# 运行测试与打包 Android Release APK
./gradlew :app:desktopTest :app:assembleRelease

# 一键发版（同步版本、打包、推送 CNB 自动更新源并更新清单）
./scripts/release.sh 1.2.1 "更新说明"
```

</details>
