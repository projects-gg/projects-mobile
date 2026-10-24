package gg.projects.mobile.social.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
 * than the screen, a bone hairline, the action in gold); it rises 10 dp as it fades in, stays 5 s (a
 * finger on it holds it) and leaves faster than it came. One at a time; a new one takes its place.
 */
final class ActionToast {
    private ActionToast() {}

    private static final long STAY_MS = 5000;

    static void show(Activity activity, CharSequence text, @Nullable String action, @Nullable final Runnable onAction) {
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
        final LinearLayout bar = new LinearLayout(context) {
            final Runnable mLeave = () -> leave(this, root, true);

            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                int a = event.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) removeCallbacks(mLeave);
                else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) postDelayed(mLeave, STAY_MS / 2);
                return super.dispatchTouchEvent(event);
            }

            @Override
            protected void onAttachedToWindow() {
                super.onAttachedToWindow();
                postDelayed(mLeave, STAY_MS);
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
            button.setTextColor(ContextCompat.getColor(context, R.color.projects_gold_300));
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
        lp.setMargins(margin, 0, margin, bottom);
        root.addView(bar, lp);
        root.setTag(R.id.projects_action_toast, bar);
        if (Motion.enabled(context)) {
            bar.setAlpha(0f);
            bar.setTranslationY(10 * dp);
            bar.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(Motion.EASE_OUT).start();
        }
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
