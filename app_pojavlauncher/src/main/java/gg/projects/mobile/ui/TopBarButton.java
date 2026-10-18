package gg.projects.mobile.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.ViewPropertyAnimator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;

/**
 * The top bar's right button (M14): settings on the main menu, home everywhere else. A 48 dp
 * target with a 24 dp Phosphor glyph. When it changes, the leaving glyph turns away and shrinks
 * while the new one turns in (rotation, scale and opacity only, interruptible), instead of
 * upstream's instant swap. With animations off it changes in place.
 */
public final class TopBarButton extends FrameLayout {
    private final ImageView mSettings;
    private final ImageView mHome;
    private boolean mIsHome;

    public TopBarButton(Context context) {
        this(context, null);
    }

    public TopBarButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        int icon = getResources().getDimensionPixelSize(R.dimen.projects_icon);
        ColorStateList tint = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.projects_bone_100));
        mSettings = glyph(context, R.drawable.projects_ic_gear_six, tint, icon);
        mHome = glyph(context, R.drawable.projects_ic_house, tint, icon);
        mHome.setAlpha(0f);
        mHome.setVisibility(INVISIBLE);
        setClickable(true);
        setFocusable(true);
        setBackgroundResource(R.drawable.projects_icon_button_ripple);
        setContentDescription(context.getString(R.string.projects_top_settings));
        Press.attach(this, Press.Style.ICON);
    }

    private ImageView glyph(Context context, int drawable, ColorStateList tint, int size) {
        ImageView view = new ImageView(context);
        view.setImageResource(drawable);
        view.setImageTintList(tint);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(view, new LayoutParams(size, size, Gravity.CENTER));
        return view;
    }

    /** Settings (main menu) or home (any other screen). */
    public void setHome(boolean home) {
        if (home == mIsHome) return;
        mIsHome = home;
        setContentDescription(getContext().getString(home ? R.string.projects_top_home : R.string.projects_top_settings));
        ImageView in = home ? mHome : mSettings;
        ImageView out = home ? mSettings : mHome;
        boolean animate = Motion.enabled(getContext()) && isAttachedToWindow() && isLaidOut() && isShown();
        in.animate().cancel();
        out.animate().cancel();
        if (!animate) {
            show(in);
            hide(out);
            return;
        }
        // The glyphs turn the same way: the old one leaves a quarter turn ahead, the new one
        // arrives from a quarter turn behind.
        float turn = home ? 1f : -1f;
        out.animate().alpha(0f).rotation(90f * turn).scaleX(0.6f).scaleY(0.6f)
                .setStartDelay(0).setDuration(Motion.EXIT_MS - 50).setInterpolator(Motion.EASE_IN)
                .withEndAction(() -> out.setVisibility(INVISIBLE)).start();
        in.setVisibility(VISIBLE);
        if (in.getAlpha() == 0f) {
            in.setRotation(-90f * turn);
            in.setScaleX(0.6f);
            in.setScaleY(0.6f);
        }
        ViewPropertyAnimator arrive = in.animate().alpha(1f).rotation(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(40).setDuration(Motion.ENTER_MS - 40).setInterpolator(Motion.EASE_OUT);
        arrive.withEndAction(null).start();
    }

    private static void show(View view) {
        view.setVisibility(VISIBLE);
        view.setAlpha(1f);
        view.setRotation(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
    }

    private static void hide(View view) {
        view.setVisibility(INVISIBLE);
        view.setAlpha(0f);
    }
}
