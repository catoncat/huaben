# 小作家故事本 (`story-note`)

专为 7 岁不识字小朋友设计的免按住长语音写书/记故事应用（适配安卓平板浏览器 & PWA 主屏安装）：

- **点一下开讲，再点一下保存**：无需按住屏幕，开启录音期间自动申请平板 `Screen Wake Lock` 常亮防息屏断麦，含 850ms 防兴奋连点误触保护。
- **原声 + 文字双留存**：
  - 每一篇独立保存在 `data/notes/<YYYYMMDD-HHMMSS-id>/` 下：
    - `audio.wav`（16kHz 单声道 16-bit 连续无损原声，支持浏览器拖动进度回听与 Finder 直接播放）
    - `note.md`（带 YAML 头信息的 Markdown 故事稿，方便归档、打印成册或导入笔记软件）
    - `meta.json`（故事序号、贴纸图标、时长与时间信息）
- **豆包 Frontier 零密钥流式语音识别（突破单次 ~200s 限制）**：
  - 实测豆包输入法接口支持 45 秒以上长静音思考不断连，但单次 `StartSession` 约 200 秒封顶。
  - `RollingDoubaoTranscriber` 将连续录音与识别会话解耦：满 120 秒遇到换气停顿（或满 165 秒安全线）自动内存缓冲无缝切换新会话拼接文本，并支持对已保存的 `audio.wav` 一键重新分段识别。

## 启动与安卓平板访问

```bash
# 1. 启动本地服务（默认监听 127.0.0.1:8788）
python3 ~/fun/story-note/server/app.py --port 8788

# 2. 通过 Tailscale Serve 暴露可信 HTTPS 地址（安卓浏览器获取麦克风权限必须走 HTTPS）
preview --port 8788
```

在安卓平板 Chrome 打开输出的 `https://...ts.net` 地址，点击右上角菜单 **「添加到主屏幕」** 即可全屏独立使用。
