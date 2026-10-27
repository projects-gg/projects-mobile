package gg.projects.mobile.social.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ScrollView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * MOB-POLISH: {@link FadingRecyclerView}'s top fade for a friends screen's scroll view (the settings, adding a friend,
 * creating a crew, the crew page without a crew, the sign-in screen, who reacted).
 */
public class FadingScrollView extends ScrollView {
    public FadingScrollView(@NonNull Context context) {
        super(context);
        FadingRecyclerView.fadeTop(this);
    }

    public FadingScrollView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        FadingRecyclerView.fadeTop(this);
    }

    public FadingScrollView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        FadingRecyclerView.fadeTop(this);
    }

    @Override
    protected float getTopFadingEdgeStrength() {
        return getChildCount() == 0 ? 0f : FadingRecyclerView.strength(getScrollY(), getVerticalFadingEdgeLength());
    }

    @Override
    protected float getBottomFadingEdgeStrength() {
        return 0f;
    }

    @Override
    protected boolean isPaddingOffsetRequired() {
        return !getClipToPadding();
    }

    @Override
    protected int getTopPaddingOffset() {
        return getClipToPadding() ? 0 : -getPaddingTop();
    }

    @Override
    protected int getBottomPaddingOffset() {
        return getClipToPadding() ? 0 : getPaddingBottom();
    }

    @Override
    protected int getLeftPaddingOffset() {
        return getClipToPadding() ? 0 : -getPaddingLeft();
    }

    @Override
    protected int getRightPaddingOffset() {
        return getClipToPadding() ? 0 : getPaddingRight();
    }
}
