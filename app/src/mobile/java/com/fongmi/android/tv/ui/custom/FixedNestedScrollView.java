package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;

public class FixedNestedScrollView extends NestedScrollView {

    public FixedNestedScrollView(@NonNull Context context) {
        super(context);
    }

    public FixedNestedScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public FixedNestedScrollView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return false;
    }

    @Override
    public void scrollTo(int x, int y) {
        super.scrollTo(x, 0);
    }

    @Override
    public void fling(int velocityY) {
    }
}
