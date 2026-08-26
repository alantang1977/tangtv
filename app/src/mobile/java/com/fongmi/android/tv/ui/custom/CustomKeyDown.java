package com.fongmi.android.tv.ui.custom;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.LiveSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.BrightnessPolicy;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;

public class CustomKeyDown extends GestureDetector.SimpleOnGestureListener implements ScaleGestureDetector.OnScaleGestureListener {

    private static final int DISTANCE = 100;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector detector;
    private final AudioManager manager;
    private final Listener listener;
    private final Activity activity;
    private final View videoView;
    private final int[] videoLocation;
    private boolean changeBright;
    private boolean changeVolume;
    private boolean changeSpeed;
    private boolean changeScale;
    private boolean changeTime;
    private boolean multiTouch;
    private boolean animating;
    private boolean touch;
    private boolean lock;
    private float bright;
    private float currentBright;
    private float brightLimit = BrightnessPolicy.NO_LIMIT;
    private float volume;
    private float scale;
    private long time;

    public static CustomKeyDown create(Activity activity, View videoView) {
        return new CustomKeyDown(activity, videoView);
    }

    private CustomKeyDown(Activity activity, View videoView) {
        this.manager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
        this.scaleDetector = new ScaleGestureDetector(activity, this);
        this.detector = new GestureDetector(activity, this);
        this.listener = (Listener) activity;
        this.videoView = videoView;
        this.activity = activity;
        this.videoLocation = new int[2];
        this.scale = 1.0f;
        applyBrightness();
    }

    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        boolean end = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (action == MotionEvent.ACTION_DOWN) multiTouch = false;
        if (action == MotionEvent.ACTION_POINTER_DOWN) multiTouch = true;
        if (action == MotionEvent.ACTION_UP) listener.onTouchEnd();
        // 手势被父容器拦截或来电时只收到 CANCEL，同样要落盘，否则窗口已改而记忆值未更新
        if (changeBright && end) PlayerSetting.putBrightness(currentBright);
        if (changeSpeed && action == MotionEvent.ACTION_UP) listener.onSpeedEnd();
        if (changeTime && action == MotionEvent.ACTION_UP) listener.onSeekEnd(time);
        return e.getPointerCount() == 2 ? scaleDetector.onTouchEvent(e) : detector.onTouchEvent(e);
    }

    private void applyBrightness() {
        // merge 在「用户未设定且无夜间上限」时返回 -1，即 BRIGHTNESS_OVERRIDE_NONE，
        // 写入窗口就是恢复跟随系统。所以这里必须无条件写，不能短路：
        // 否则夜间模式由「有上限」切回「无上限」时窗口会停留在旧的压暗值。
        applyWindowBrightness(BrightnessPolicy.merge(PlayerSetting.getBrightness(), brightLimit));
    }

    /**
     * 设置夜间模式亮度上限（负数表示无上限），随后立即重算窗口亮度。
     */
    public void setBrightLimit(float limit) {
        this.brightLimit = limit;
        applyBrightness();
    }

    private void applyWindowBrightness(float brightness) {
        WindowManager.LayoutParams attributes = activity.getWindow().getAttributes();
        // 值没变就不要回写，避免无谓的窗口属性更新；也保证「从未接管」的页面
        // (如直播页) 不会因为显式写入默认值而触发多余的 relayout。
        if (attributes.screenBrightness == brightness) return;
        attributes.screenBrightness = brightness;
        activity.getWindow().setAttributes(attributes);
    }

    public void resetScale() {
        if (scale == 1.0f) return;
        videoView.animate().scaleX(1.0f).scaleY(1.0f).translationX(0f).translationY(0f).setDuration(250).withEndAction(() -> {
            videoView.setPivotY(videoView.getHeight() / 2.0f);
            videoView.setPivotX(videoView.getWidth() / 2.0f);
            scale = 1.0f;
        }).start();
    }

    public void setLock(boolean lock) {
        this.lock = lock;
    }

    public float getScale() {
        return scale;
    }

    private boolean isMultiple(MotionEvent e) {
        return e.getPointerCount() > 1;
    }

    private boolean isEdge(MotionEvent e) {
        int width = getVideoWidth();
        int height = getVideoHeight();
        if (width <= 0 || height <= 0) return false;
        int edge = ResUtil.dp2px(24);
        float x = getVideoX(e);
        float y = getVideoY(e);
        return x < edge || x > width - edge || y < edge || y > height - edge;
    }

    private boolean isSide(MotionEvent e) {
        int width = getVideoWidth();
        if (width <= 0) return false;
        int four = width / 4;
        float x = getVideoX(e);
        return !(x > four) || !(x < four * 3);
    }

    private int getVideoWidth() {
        int width = videoView.getWidth();
        return width > 0 ? width : videoView.getMeasuredWidth();
    }

    private int getVideoHeight() {
        int height = videoView.getHeight();
        return height > 0 ? height : videoView.getMeasuredHeight();
    }

    private float getVideoX(MotionEvent e) {
        videoView.getLocationOnScreen(videoLocation);
        return e.getRawX() - videoLocation[0];
    }

    private float getVideoY(MotionEvent e) {
        videoView.getLocationOnScreen(videoLocation);
        return e.getRawY() - videoLocation[1];
    }

    private void reset() {
        time = 0;
        touch = true;
        changeTime = false;
        changeSpeed = false;
        changeBright = false;
        changeVolume = false;
        // 窗口里存的是「手势值与夜间上限合并后」的结果，直接回读会让夜间模式下的
        // 手势基准被上限带偏，所以优先用已持久化的手势值。
        float saved = PlayerSetting.getBrightness();
        bright = saved >= 0 ? saved : Util.getBrightness(activity);
        currentBright = bright;
        volume = manager.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    @Override
    public boolean onDown(@NonNull MotionEvent e) {
        if (isMultiple(e) || isEdge(e) || changeScale || lock) return true;
        reset();
        return true;
    }

    @Override
    public void onLongPress(@NonNull MotionEvent e) {
        if (multiTouch || isEdge(e) || changeScale || lock) return;
        listener.onSpeedUp();
        changeSpeed = true;
    }

    @Override
    public boolean onScroll(MotionEvent e1, @NonNull MotionEvent e2, float distanceX, float distanceY) {
        if (isMultiple(e1) || isEdge(e1) || changeScale || lock || changeSpeed) return true;
        float deltaX = e2.getX() - e1.getX();
        float deltaY = e1.getY() - e2.getY();
        if (touch) checkFunc(Math.abs(deltaX), Math.abs(deltaY), e2);
        if (changeTime) listener.onSeeking(time = (long) (deltaX * 50));
        if (changeBright) setBright(deltaY);
        if (changeVolume) setVolume(deltaY);
        return true;
    }

    @Override
    public boolean onDoubleTap(@NonNull MotionEvent e) {
        if (isMultiple(e) || isEdge(e) || changeScale) return true;
        listener.onDoubleTap();
        return true;
    }

    @Override
    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
        if (isMultiple(e) || changeScale) return true;
        listener.onSingleTap(getVideoX(e), getVideoWidth());
        return true;
    }

    @Override
    public boolean onFling(MotionEvent e1, @NonNull MotionEvent e2, float velocityX, float velocityY) {
        if (isMultiple(e1) || isEdge(e1) || isSide(e1) || changeScale || lock || animating) return true;
        checkFunc(e1, e2);
        return true;
    }

    private void checkFunc(float distanceX, float distanceY, MotionEvent e2) {
        if ((float) Math.sqrt(distanceX * distanceX + distanceY * distanceY) < ResUtil.dp2px(20)) return;
        if (distanceX >= distanceY) changeTime = true;
        else if (isSide(e2)) checkSide(e2);
        touch = false;
    }

    private void checkFunc(MotionEvent e1, MotionEvent e2) {
        float dx = e2.getX() - e1.getX();
        float dy = e2.getY() - e1.getY();
        double angle = Math.toDegrees(Math.atan2(Math.abs(dy), Math.abs(dx)));
        if (angle > 70 && e1.getY() - e2.getY() > DISTANCE) {
            videoView.animate().translationYBy(ResUtil.dp2px(LiveSetting.isInvert() ? 24 : -24)).setDuration(150).withStartAction(() -> animating = true).withEndAction(() -> videoView.animate().translationY(0).setDuration(100).withStartAction(listener::onFlingUp).withEndAction(() -> animating = false).start()).start();
        } else if (angle > 70 && e2.getY() - e1.getY() > DISTANCE) {
            videoView.animate().translationYBy(ResUtil.dp2px(LiveSetting.isInvert() ? -24 : 24)).setDuration(150).withStartAction(() -> animating = true).withEndAction(() -> videoView.animate().translationY(0).setDuration(100).withStartAction(listener::onFlingDown).withEndAction(() -> animating = false).start()).start();
        }
    }

    private void checkSide(MotionEvent e2) {
        int width = getVideoWidth();
        float x = getVideoX(e2);
        if (x > width / 2f) changeVolume = true;
        else changeBright = true;
    }

    private void setBright(float deltaY) {
        int height = videoView.getMeasuredHeight();
        // 手势值先与夜间上限合并，再作为「当前亮度」对外报告并持久化。
        // 否则夜间模式下滑到顶会把 1.0 落盘，切到无上限的页面（如直播）就突然全亮。
        float brightness = BrightnessPolicy.merge(BrightnessPolicy.scroll(bright, deltaY, height), brightLimit);
        currentBright = brightness;
        applyWindowBrightness(brightness);
        listener.onBright((int) (brightness * 100));
    }

    private void setVolume(float deltaY) {
        int height = videoView.getMeasuredHeight();
        int maxVolume = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        float deltaV = deltaY * 2.0f / height * maxVolume;
        float index = volume + deltaV;
        if (index > maxVolume) index = maxVolume;
        if (index < 0) index = 0;
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, (int) index, 0);
        listener.onVolume((int) (index / maxVolume * 100.0f));
    }

    @Override
    public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
        if (changeBright || changeVolume || changeSpeed || changeTime || lock) return changeScale = false;
        return changeScale = true;
    }

    @Override
    public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
        App.post(() -> changeScale = false, 500);
    }

    @Override
    public boolean onScale(@NonNull ScaleGestureDetector detector) {
        scale *= detector.getScaleFactor();
        scale = Math.max(1.0f, Math.min(scale, 5.0f));
        videoView.setPivotX(detector.getFocusX());
        videoView.setPivotY(detector.getFocusY());
        videoView.setScaleX(scale);
        videoView.setScaleY(scale);
        return true;
    }

    public interface Listener {

        void onSeeking(long time);

        void onSeekEnd(long time);

        void onSpeedUp();

        void onSpeedEnd();

        void onBright(int progress);

        void onVolume(int progress);

        void onFlingUp();

        void onFlingDown();

        void onSingleTap();

        default void onSingleTap(float x, float width) {
            onSingleTap();
        }

        void onDoubleTap();

        void onTouchEnd();
    }
}
