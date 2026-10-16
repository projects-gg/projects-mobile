package gg.projects.mobile.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.transition.ChangeBounds;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.transition.TransitionSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewGroupOverlay;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;

/**
 * Motion of the Projects home card (design.md section 5), all Animator-based so the system's
 * animation scale applies and nothing moves with animations removed:
 * <ul>
 * <li>sections that come or go fade in place while everything below glides to its new place
 * ({@link #beginLayoutChange});</li>
 * <li>a block that stays but shows something else (a newer story, the next event) cross-fades in
 * place: its old look is lifted out of the flow and fades while the new one rises 6 dp
 * ({@link #crossfade}).</li>
 * </ul>
 * Both can be interrupted: a new change starts from what is on screen.
 */
final class CardMotion {
    private CardMotion() {}

    static boolean canAnimate(View view) {
        return Motion.enabled(view.getContext()) && view.isAttachedToWindow() && view.isLaidOut() && view.isShown();
    }

    /** Animates the next layout change under {@code sceneRoot}. */
    static void beginLayoutChange(ViewGroup sceneRoot) {
        if (!canAnimate(sceneRoot)) return;
        TransitionSet set = new TransitionSet();
        set.setOrdering(TransitionSet.ORDERING_TOGETHER);
        Fade out = new Fade(Fade.OUT);
        out.setDuration(Motion.EXIT_MS);
        out.setInterpolator(Motion.EASE_IN);
        ChangeBounds bounds = new ChangeBounds();
        bounds.setDuration(Motion.ENTER_MS + 60);
        bounds.setInterpolator(Motion.EASE_OUT);
        Fade in = new Fade(Fade.IN);
        in.setDuration(Motion.ENTER_MS);
        in.setStartDelay(70);
        in.setInterpolator(Motion.EASE_OUT);
        set.addTransition(out).addTransition(bounds).addTransition(in);
        TransitionManager.beginDelayedTransition(sceneRoot, set);
    }

    /**
     * Call before {@code block} is re-bound with other content: its current look fades out where it
     * is while the new content rises in. No-op when it is not on screen.
     */
    static void crossfade(final View block) {
        Object running = block.getTag(R.id.projects_motion_ghost);
        if (running instanceof Animator) ((Animator) running).end();
        if (!canAnimate(block) || block.getVisibility() != View.VISIBLE || block.getWidth() == 0 || block.getHeight() == 0) return;
        final ViewGroup parent = (ViewGroup) block.getParent();
        if (parent == null) return;
        final Bitmap image;
        try {
            image = Bitmap.createBitmap(block.getWidth(), block.getHeight(), Bitmap.Config.ARGB_8888);
            block.draw(new Canvas(image));
        } catch (RuntimeException | OutOfMemoryError e) {
            return;
        }
        final BitmapDrawable ghost = new BitmapDrawable(block.getResources(), image);
        final int left = block.getLeft() + Math.round(block.getTranslationX());
        final int top = block.getTop() + Math.round(block.getTranslationY());
        ghost.setBounds(left, top, left + block.getWidth(), top + block.getHeight());
        final float startAlpha = block.getAlpha();
        ghost.setAlpha(Math.round(255 * startAlpha));
        final ViewGroupOverlay overlay = parent.getOverlay();
        overlay.add(ghost);
        final float lift = 3 * block.getResources().getDisplayMetrics().density;
        ValueAnimator fade = ValueAnimator.ofFloat(0f, 1f);
        fade.setDuration(Motion.EXIT_MS);
        fade.setInterpolator(Motion.EASE_IN);
        fade.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            int dy = Math.round(-lift * f);
            ghost.setBounds(left, top + dy, left + block.getWidth(), top + dy + block.getHeight());
            ghost.setAlpha(Math.round(255 * startAlpha * (1 - f)));
        });
        fade.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                overlay.remove(ghost);
                block.setTag(R.id.projects_motion_ghost, null);
            }
        });
        block.setTag(R.id.projects_motion_ghost, fade);
        fade.start();

        float rise = 6 * block.getResources().getDisplayMetrics().density;
        block.animate().cancel();
        block.setAlpha(0f);
        block.setTranslationY(rise);
        block.animate().alpha(1f).translationY(0f).setStartDelay(50).setDuration(Motion.ENTER_MS)
                .setInterpolator(Motion.EASE_OUT).start();
    }

    /** Fades a view that just became visible in place (used where no layout change runs). */
    static void fadeIn(View view) {
        if (!canAnimate(view)) return;
        view.animate().cancel();
        view.setAlpha(0f);
        view.animate().alpha(1f).setStartDelay(0).setDuration(Motion.ENTER_MS).setInterpolator(Motion.EASE_OUT).start();
    }
}
