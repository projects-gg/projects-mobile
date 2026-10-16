package gg.projects.mobile.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.LruCache;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.BlurHash;
import gg.projects.mobile.content.MediaRef;
import gg.projects.mobile.content.NewsFeed;
import gg.projects.mobile.content.Snapshot;
import gg.projects.mobile.mods.Motion;

/**
 * A news cover in a box of fixed shape (16:9, capped height), so nothing below it moves when the
 * picture arrives. Until then it shows the cover's BlurHash (feed covers carry one) or a quiet
 * obsidian tile; the picture then fades in. Binding the same cover again changes nothing, and a
 * picture already in memory appears at once.
 */
public final class CoverView extends ImageView {
    private static final float RATIO = 9f / 16f;
    private static final float SCREEN_FRACTION = 0.26f;
    private static final LruCache<String, Bitmap> PLACEHOLDERS = new LruCache<>(48);

    private int mMaxHeight = Integer.MAX_VALUE;
    private String mKey;
    private boolean mHasImage;
    private ValueAnimator mFade;

    public CoverView(Context context) {
        this(context, null);
    }

    public CoverView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setScaleType(ScaleType.CENTER_CROP);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        final float radius = 8 * getResources().getDisplayMetrics().density;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    /** Caps the height (the width follows the parent); the box keeps 16:9 below the cap. */
    public void setMaxCoverHeight(int px) {
        if (mMaxHeight != px) {
            mMaxHeight = px;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        // Never more than about a quarter of the screen's current height: in landscape the cover
        // becomes a strip instead of filling the view (portrait keeps the 16:9 box under its cap).
        int screenCap = Math.round(getResources().getDisplayMetrics().heightPixels * SCREEN_FRACTION);
        int height = Math.min(Math.round(width * RATIO), Math.min(mMaxHeight, screenCap));
        setMeasuredDimension(width, height);
    }

    /** The cover of {@code item}, or gone when {@code show} is false or the item has none. */
    public void bind(Snapshot snapshot, @Nullable NewsFeed.NewsItem item, boolean show) {
        MediaRef image = !show || item == null || item.cover == null ? null : item.cover.displayImage();
        if (image == null || snapshot == null) {
            clear();
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        final String key = image.cacheFileName();
        if (key.equals(mKey)) return;
        mKey = key;
        mHasImage = false;
        if (mFade != null) mFade.cancel();
        setBackground(placeholder(image.blurhash));
        // Always decoded for the screen width, so every card, row and article shares one copy.
        int width = getResources().getDisplayMetrics().widthPixels;
        Bitmap ready = ProjectsContent.memoryCover(key, width);
        if (ready != null) {
            show(ready, false);
            return;
        }
        setImageDrawable(null);
        ProjectsContent.loadNewsCover(getContext(), snapshot, item, width, bitmap -> {
            if (!key.equals(mKey) || bitmap == null) return;
            show(bitmap, true);
        });
    }

    private void clear() {
        mKey = null;
        mHasImage = false;
        if (mFade != null) mFade.cancel();
        setImageDrawable(null);
    }

    private void show(Bitmap bitmap, boolean animate) {
        mHasImage = true;
        setImageBitmap(bitmap);
        if (!animate || !Motion.enabled(getContext()) || !isShown()) {
            setImageAlpha(255);
            return;
        }
        setImageAlpha(0);
        mFade = ValueAnimator.ofInt(0, 255);
        mFade.setDuration(Motion.ENTER_MS + 80);
        mFade.setInterpolator(Motion.EASE_OUT);
        mFade.addUpdateListener(a -> setImageAlpha((Integer) a.getAnimatedValue()));
        mFade.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mFade != null) mFade.end();
    }

    /** True when the picture itself is showing (not only its placeholder). */
    public boolean hasImage() {
        return mHasImage;
    }

    private Drawable placeholder(@Nullable String blurhash) {
        if (blurhash != null) {
            Bitmap cached = PLACEHOLDERS.get(blurhash);
            if (cached == null) {
                int[] pixels = BlurHash.decode(blurhash, 32, 18, 1);
                if (pixels != null) {
                    cached = Bitmap.createBitmap(pixels, 32, 18, Bitmap.Config.ARGB_8888);
                    PLACEHOLDERS.put(blurhash, cached);
                }
            }
            if (cached != null) {
                BitmapDrawable drawable = new BitmapDrawable(getResources(), cached);
                drawable.setFilterBitmap(true);
                return drawable;
            }
        }
        return new ColorDrawable(ContextCompat.getColor(getContext(), R.color.projects_obsidian_800));
    }
}
