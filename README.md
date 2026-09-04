# 李志音乐 (LizhiMusic)

车机用音乐播放器，内置李志 18 首热门歌曲。

- 打开即自动播放
- 播放/暂停、上一首、下一首、拖动进度条
- 列表循环 / 单曲循环、随机播放
- 前台通知播控 + 车机媒体按键兼容
- 退出按钮完全停止服务

## 构建

推送到 `main` 后 GitHub Actions 自动执行 `./gradlew assembleDebug`，
产物为 `app-debug.apk`（Artifacts: `lizhi-music-apk`）。

## 安装

```bash
adb install app-debug.apk
```

兼容 Android 5.0+（车机 7.1 实测目标）。首次启动会解包曲库（约 165MB），稍候即自动播放。
