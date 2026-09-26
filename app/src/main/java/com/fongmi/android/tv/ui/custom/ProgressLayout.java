package com.fongmi.android.tv.ui.custom;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import com.fongmi.android.tv.databinding.ViewEmptyBinding;
import com.fongmi.android.tv.databinding.ViewProgressBinding;
import com.fongmi.android.tv.databinding.ViewSkeletonBinding;

import java.util.ArrayList;
import java.util.List;

public class ProgressLayout extends RelativeLayout {

    private static final String TAG_PROGRESS = "ProgressLayout.TAG_PROGRESS";

    public enum State {
        CONTENT, PROGRESS, EMPTY, SKELETON
    }

    private List<View> mContentViews;
    private View mProgressView;
    private View mEmptyView;
    private View mSkeletonView;
    private ObjectAnimator mSkeletonPulse;
    private State mState;

    public ProgressLayout(Context context) {
        super(context);
    }

    public ProgressLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ProgressLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        mState = State.CONTENT;
        mContentViews = new ArrayList<>();
    }

    private void ensureEmptyView() {
        if (mEmptyView != null) return;
        mEmptyView = ViewEmptyBinding.inflate(LayoutInflater.from(getContext())).getRoot();
        mEmptyView.setTag(TAG_PROGRESS);
        mEmptyView.setVisibility(GONE);
        addView(mEmptyView, centerParams());
    }

    private void ensureProgressView() {
        if (mProgressView != null) return;
        mProgressView = ViewProgressBinding.inflate(LayoutInflater.from(getContext())).getRoot();
        mProgressView.setTag(TAG_PROGRESS);
        mProgressView.setVisibility(GONE);
        addView(mProgressView, centerParams());
    }

    private void ensureSkeletonView() {
        if (mSkeletonView != null) return;
        mSkeletonView = ViewSkeletonBinding.inflate(LayoutInflater.from(getContext())).getRoot();
        mSkeletonView.setTag(TAG_PROGRESS);
        mSkeletonView.setVisibility(GONE);
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addView(mSkeletonView, params);
    }

    private void startPulse() {
        if (mSkeletonPulse == null) {
            mSkeletonPulse = ObjectAnimator.ofFloat(mSkeletonView, View.ALPHA, 0.35f, 0.85f);
            mSkeletonPulse.setDuration(900);
            mSkeletonPulse.setRepeatCount(ObjectAnimator.INFINITE);
            mSkeletonPulse.setRepeatMode(ObjectAnimator.REVERSE);
        }
        if (!mSkeletonPulse.isRunning()) mSkeletonPulse.start();
    }

    private void stopPulse() {
        if (mSkeletonPulse != null && mSkeletonPulse.isRunning()) mSkeletonPulse.cancel();
        if (mSkeletonView != null) mSkeletonView.setAlpha(1f);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPulse();
        super.onDetachedFromWindow();
    }

    private LayoutParams centerParams() {
        LayoutParams params = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.addRule(CENTER_IN_PARENT);
        return params;
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        super.addView(child, index, params);
        if (child.getTag() == null || !child.getTag().equals(TAG_PROGRESS)) {
            mContentViews.add(child);
        }
    }

    public void showProgress() {
        switchState(State.PROGRESS);
    }

    public void showSkeleton() {
        switchState(State.SKELETON);
    }

    public void showEmpty() {
        switchState(State.EMPTY);
    }

    public void showContent() {
        switchState(State.CONTENT);
    }

    public void showContent(boolean flag, int size) {
        if (flag && size == 0) showEmpty();
        else showContent();
    }

    public boolean isProgress() {
        return mState == State.PROGRESS;
    }

    public boolean isSkeleton() {
        return mState == State.SKELETON;
    }

    public boolean isContent() {
        return mState == State.CONTENT;
    }

    public boolean isEmpty() {
        return mState == State.EMPTY;
    }

    public void switchState(State state) {
        if (mState == state) return;
        mState = state;
        if (state != State.SKELETON) stopPulse();
        switch (state) {
            case CONTENT:
                if (mEmptyView != null) mEmptyView.setVisibility(GONE);
                if (mProgressView != null) mProgressView.setVisibility(GONE);
                if (mSkeletonView != null) mSkeletonView.setVisibility(GONE);
                setContentVisibility(true);
                break;
            case PROGRESS:
                ensureProgressView();
                if (mEmptyView != null) mEmptyView.setVisibility(GONE);
                if (mSkeletonView != null) mSkeletonView.setVisibility(GONE);
                mProgressView.setVisibility(VISIBLE);
                setContentVisibility(false);
                break;
            case EMPTY:
                ensureEmptyView();
                mEmptyView.setVisibility(VISIBLE);
                if (mProgressView != null) mProgressView.setVisibility(GONE);
                if (mSkeletonView != null) mSkeletonView.setVisibility(GONE);
                setContentVisibility(false);
                break;
            case SKELETON:
                ensureSkeletonView();
                if (mEmptyView != null) mEmptyView.setVisibility(GONE);
                if (mProgressView != null) mProgressView.setVisibility(GONE);
                mSkeletonView.setVisibility(VISIBLE);
                startPulse();
                setContentVisibility(false);
                break;
        }
    }

    private void setContentVisibility(boolean visible) {
        for (View view : mContentViews) {
            if (visible) showView(view);
            else hideView(view);
        }
    }

    private void showView(View view) {
        view.setAlpha(0f);
        view.setVisibility(VISIBLE);
        view.animate().alpha(1f).setDuration(100);
    }

    private void hideView(View view) {
        view.setVisibility(INVISIBLE);
    }
}
