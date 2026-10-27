package gg.projects.mobile.social.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

/**
 * MOB-POLISH: a friends screen's list under a fixed top (the header, the DM's "Discord'dan mesajlaş", the crew rail):
 * what scrolls under it fades out over 20 dp instead of being cut by a hard line, the 7/24 Bulut screens' fading edge.
 * Only the top edge, and only once something is scrolled under it: the fade grows over the first 20 dp of scroll,
 * nothing moves, so it needs no reduced-motion form. It is drawn from the list's own edge, since the lists scroll into
 * their padding ({@code clipToPadding="false"}) and Android would start a fade below the padding (as AbsListView does).
 * {@link FadingScrollView} is the same for a scroll view.
 */
public class FadingRecyclerView extends RecyclerView {
    /** The fade's length, the Bulut screens' {@code fadingEdgeLength}. */
    static final int FADE_DP = 20;

    public FadingRecyclerView(@NonNull Context context) {
        super(context);
        fadeTop(this);
    }

    public FadingRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        fadeTop(this);
    }

    public FadingRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        fadeTop(this);
    }

    /** Turns the fading edge on for {@code view} (its strength decides that only the top shows). */
    static void fadeTop(View view) {
        view.setVerticalFadingEdgeEnabled(true);
        view.setFadingEdgeLength(Math.round(FADE_DP * view.getResources().getDisplayMetrics().density));
        // The fade is drawn in draw(), which a view that "will not draw" skips.
        view.setWillNotDraw(false);
    }

    /** The top fade's strength with {@code scrolled} px under the top: none at the top, full after its length. */
    static float strength(int scrolled, int length) {
        if (length <= 0 || scrolled <= 0) return 0f;
        return Math.min(1f, scrolled / (float) length);
    }

    @Override
    protected float getTopFadingEdgeStrength() {
        return strength(computeVerticalScrollOffset(), getVerticalFadingEdgeLength());
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
