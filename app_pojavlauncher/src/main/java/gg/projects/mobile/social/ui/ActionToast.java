package gg.projects.mobile.social.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * MOB1: a short line with one action at the bottom of whatever screen is up (Android's own toasts
 * cannot carry a button): "GK etiketini taktın." with "Geri al", "Gece Kartalları ekibine katıldın"
 * with "Etiketi tak". The look of the home screen's undo bar (an opaque obsidian surface a step lighter
 * than the screen, a bone hairline, the action in bone semibold); it rises 10 dp as it fades in, stays 5 s (8 s with an action; a
 * finger on it holds it) and leaves faster than it came. One at a time; a new one takes its place.
 */
public final class ActionToast {
    private ActionToast() {}

    private static final long STAY_MS = 5000;
    /** M054 (M053's P3): a line with an action ("Geri Al") stays longer: 5 s was short to read it and reach the button. */
    private static final long STAY_ACTION_MS = 8000;

    public static void show(Activity activity, CharSequence text, @Nullable String action, @Nullable final Runnable onAction) {
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof FrameLayout)) {
            // Android's own toast (SocialUi.toast would come back here).
            android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        final FrameLayout root = (FrameLayout) content;
        Object old = root.getTag(R.id.projects_action_toast);
        if (old instanceof View) leave((View) old, root, false);
        Context context = activity;
        float dp = context.getResources().getDisplayMetrics().density;
        final long stay = action != null && onAction != null ? STAY_ACTION_MS : STAY_MS;
        final LinearLayout bar = new LinearLayout(context) {
            final Runnable mLeave = () -> leave(this, root, true);

            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                int a = event.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) removeCallbacks(mLeave);
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) postDelayed(mLeave, stay / 2);
                return super.dispatchTouchEvent(event);
            }

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                postDelayed(mLeave, stay);
            }

            @Override
            protected void onDetachedFromWindow() {
                removeCallbacks(mLeave);
                super.onDetachedFromWindow();
            }
        };
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(Math.round(52 * dp));
        bar.setPaddingRelative(Math.round(16 * dp), Math.round(2 * dp), Math.round(4 * dp), Math.round(2 * dp));
        GradientDrawable surface = new GradientDrawable();
        surface.setCornerRadius(10 * dp);
        surface.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_800));
        surface.setStroke(Math.max(1, Math.round(dp)), 0x29F3EBDD);
        bar.setBackground(surface);
        bar.setElevation(8 * dp);
        bar.setClickable(true);
        bar.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        TextView line = new TextView(context);
        line.setText(text);
        line.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        line.setMaxLines(2);
        try {
            line.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_regular));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        bar.addView(line, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (action != null && onAction != null) {
            TextView button = new TextView(context);
            button.setText(action);
            button.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            button.setGravity(Gravity.CENTER);
            button.setMinWidth(Math.round(64 * dp));
            button.setMinHeight(Math.round(48 * dp));
            button.setPadding(Math.round(14 * dp), 0, Math.round(14 * dp), 0);
            button.setBackgroundResource(R.drawable.projects_row_ripple);
            try {
                button.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
            } catch (RuntimeException ignored) {
                // The theme's font.
            }
            Press.attach(button, Press.Style.BUTTON);
            button.setOnClickListener(v -> {
                v.setOnClickListener(null);
                Haptics.confirm(v);
                leave(bar, root, true);
                onAction.run();
            });
            bar.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        int margin = Math.round(12 * dp);
        int bottom = margin;
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
        if (insets != null) {
            bottom += Math.max(insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                    insets.getInsets(WindowInsetsCompat.Type.ime()).bottom);
        }
        // APK-REL (0.4.9's emulator run): on the home screen the line sat over OYNA ("Tuşlar kaydedildi." after the controls
        // editor): it held OYNA's touch for 5 s, and a late tap on "Geri al" landed on OYNA once it left. It now stands
        // above the play bar whenever that bar is on screen, also when the home screen is laid out after the line came
        // (the launcher relaunched upright after the sideways editor).
        final int floor = bottom;
        lp.setMargins(margin, 0, margin, Math.max(floor, aboveBar(root, margin)));
        root.addView(bar, lp);
        final FrameLayout.LayoutParams place = lp;
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (bar.getParent() != root) {
                    if (root.getViewTreeObserver().isAlive()) root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    return;
                }
                int want = Math.max(floor, aboveBar(root, margin));
                if (place.bottomMargin != want) {
                    place.bottomMargin = want;
                    bar.setLayoutParams(place);
                }
            }
        });
        root.setTag(R.id.projects_action_toast, bar);
        if (Motion.enabled(context)) {
            bar.setAlpha(0f);
            bar.setTranslationY(10 * dp);
            bar.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(Motion.EASE_OUT).start();
        }
    }

    /**
     * APK-REL: the room the line keeps above the home screen's play bar (its top's distance from the root's bottom plus
     * {@code margin}); 0 when that bar is not on screen or not laid out yet.
     */
    private static int aboveBar(FrameLayout root, int margin) {
        // The marker is a Space, which keeps itself INVISIBLE: whether the bar is on screen is its parent's.
        View playBar = root.findViewById(R.id.projects_play_bar_top);
        if (playBar == null || !(playBar.getParent() instanceof View) || !((View) playBar.getParent()).isShown()
                || playBar.getHeight() == 0 || root.getHeight() == 0) {
            return 0;
        }
        int[] at = new int[2];
        int[] rootAt = new int[2];
        playBar.getLocationInWindow(at);
        root.getLocationInWindow(rootAt);
        int below = root.getHeight() - (at[1] - rootAt[1]);
        return below > 0 && below < root.getHeight() ? below + margin : 0;
    }

    private static void leave(final View bar, final FrameLayout root, boolean animate) {
        if (root.getTag(R.id.projects_action_toast) == bar) root.setTag(R.id.projects_action_toast, null);
        bar.animate().cancel();
        if (!animate || !Motion.enabled(bar.getContext())) {
            root.removeView(bar);
            return;
        }
        bar.animate().alpha(0f).translationY(6 * bar.getResources().getDisplayMetrics().density).setDuration(160)
                .setInterpolator(Motion.EASE_OUT).withEndAction(() -> root.removeView(bar)).start();
    }
}
