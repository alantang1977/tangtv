package com.tangtv.ad;

/**
 * VoiceAdDetector: 语音去广告占位器。实际接入需要从 ExoPlayer PCM 管线获取音频样本。
 * 当前实现仅提供生命周期管理入口。
 */
public final class VoiceAdDetector {
    private volatile boolean enabled = false;

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * 将 PCM 音频样本传入检测器（单声道、16kHz、16-bit PCM），实现可订阅的识别管线。
     * TODO: 实现 Sherpa-ONNX 集成或其它离线识别，并按照设计文档返回 AdSignal。
     */
    public void onPcmSample(byte[] pcm16le) {
        if (!enabled) return;
        // TODO: 推送到识别队列并生成 AdSignal
    }
}
