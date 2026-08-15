# Exo AV3A 实机验收记录（2026-08-11）

## 构建输入

- nextlib：`https://github.com/anilbeesetti/nextlib.git` commit `6ff6cf9d0820382b3c233d018c52e4163b09d345`
- FFmpeg：`https://github.com/FongMi/FFmpeg.git` commit `04482c8d13ac27b2a9fe93f5d388929eef8af5f4`
- nextlib 版本：`1.10.0-0.12.1-fongmi-softload-av3a-r1`
- Android ABI：`arm64-v8a`、`armeabi-v7a`
- NDK：`28.2.13676358`
- CMake：`3.22.1`

两端 AAR 内的 `libavcodec.so` 均包含 `libarcdav3a AV3A` 和 `AV3A Audio Vivid`。`libarcdav3a` 静态链接进 `libavcodec.so`，ELF `DT_NEEDED` 不包含独立 `libarcdav3a.so`。

## 公开测试资源

- 项目：`https://github.com/nilaoda/av3a_decoder`
- 原始下载：`https://github.com/nilaoda/av3a_decoder/releases/download/v0.2/sample2_ts.zip`
- 国内代理下载：`https://ghfast.top/https://github.com/nilaoda/av3a_decoder/releases/download/v0.2/sample2_ts.zip`
- ZIP SHA-256：`7a533e9e74fb09b3a480c08500f3f886991947280bd1cb6cb25097ed2dfd096a`
- 样片：`playlist_761477780_1710946736.ts`

样片视频为 3840×2160、50 fps、HEVC/HLG；音频为 AV3A、48 kHz、10 声道（5.1.4）。

## 实机结果

设备：vivo V2453A，Android 15，arm64-v8a。

将同一 TS 片段重复组成约 65 秒本地 HLS 列表并使用 Exo 播放，结果如下：

- 播放参数识别：`av3a 10ch 48kHz 384Kbps`
- 音频解码器：`ffmpegLavc63.3.100-libarcdav3a`
- 播放状态：正常到达 `ENDED`
- 重缓冲：0 次
- 视频掉帧：13 帧（4K 50 fps 样片）
- AudioFlinger：为 App 创建 48 kHz、10 声道 PCM AudioTrack，约 59 秒内服务端帧计数持续增长
- 日志：无 `PlaybackException`、SIGSEGV、ANR 或 Java/native crash

首次实机运行曾在 `swr_alloc_set_opts2()` 内触发 SIGSEGV。根因是 JNI 下混路径中的 `SwrContext *resampleContext` 未初始化，随机栈值被当作已有上下文传入 `av_opt_find2()`。修复为显式初始化 `nullptr` 后，完整验收通过。

## 复测步骤

```bash
curl -L 'https://ghfast.top/https://github.com/nilaoda/av3a_decoder/releases/download/v0.2/sample2_ts.zip' -o /tmp/sample2_ts.zip
shasum -a 256 /tmp/sample2_ts.zip
unzip /tmp/sample2_ts.zip -d /tmp/av3a-sample
adb push /tmp/av3a-sample/playlist_761477780_1710946736.ts /sdcard/Download/av3a-sample2-5.1.4.ts
adb shell am start -a android.intent.action.VIEW -c android.intent.category.DEFAULT -d file:///sdcard/Download/av3a-sample2-5.1.4.ts -t video/mp2t -n com.fongmi.android.tv/.ui.activity.HomeActivity
```

进入全屏后打开“播放参数”，应看到音频格式 `av3a`，解码器名称以 `ffmpeg` 开头并以 `libarcdav3a` 结尾。若设备不接受源多声道 PCM，renderer 会请求 FFmpeg 下混为双声道；支持源通道布局的设备则保留多声道交给 Android 音频栈。
