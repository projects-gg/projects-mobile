package gg.projects.mobile.social.emoji;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeInterpolator;
import android.os.Build;
import android.util.Property;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * Plays a sticker's motion ({@link StickerMotion}) once on its View: an ObjectAnimator of
 * keyframes on translation (fractions of the view's size), rotation, scale and alpha around the
 * pattern's pivot, only transform and opacity, never repeating. As on the desktop, a pattern
 * under way finishes: a second press meanwhile does not restart it. When it ends or is cancelled,
 * what it moved is back at rest (and the pivot as it was). Main thread only.
 *
 * <p>Animate the sticker's own view (the image), not a container that has its own transforms.
 */
public final class StickerAnimator {
    private StickerAnimator() {}

    private static final WeakHashMap<View, Animator> RUNNING = new WeakHashMap<>();
    private static final WeakHashMap<View, Deferred> PENDING = new WeakHashMap<>();

    private static final TimeInterpolator LINEAR = new LinearInterpolator();
    /** steps(1, end): the frame's value until the next frame, then a jump. */
    private static final TimeInterpolator STEP_END = new TimeInterpolator() {
        @Override
        public float getInterpolation(float input) {
            return input < 1f ? 0f : 1f;
        }
    };
    private static TimeInterpolator out;
    private static TimeInterpolator inOut;
    private static TimeInterpolator down;

    /**
     * Plays the sticker's pattern ({@code motionName}, the manifest's {@code motion}) once on
     * {@code sticker}, unless one is already under way there (it finishes). {@code reduced} gives
     * the arrival's fade instead (reduced motion, animations off): call it so only for an arrival;
     * under reduced motion a press does nothing, as on the desktop.
     *
     * <p>A view that has no size yet (just bound, not laid out) starts right after its layout.
     * Returns the running animator, or null when nothing started now (one under way, an unknown
     * name, or a start after the layout).
     */
    @Nullable
    public static Animator play(View sticker, String motionName, boolean reduced) {
        if (isRunning(sticker) || PENDING.containsKey(sticker)) return null;
        StickerMotion motion = reduced ? StickerMotion.reduced() : StickerMotion.of(motionName);
        if (motion == null) return null;
        if (needsSize(motion) && (sticker.getWidth() == 0 || sticker.getHeight() == 0)) {
            Deferred deferred = new Deferred(sticker, motion);
            PENDING.put(sticker, deferred);
            sticker.post(deferred);
            return null;
        }
        return start(sticker, motion);
    }

    /** Whether a motion is under way on the view. */
    public static boolean isRunning(View sticker) {
        Animator current = RUNNING.get(sticker);
        return current != null && current.isStarted();
    }

    /**
     * Stops a motion under way (or about to start) on the view and puts it at rest: call it when
     * a recycled view is bound to another message.
     */
    public static void cancel(View sticker) {
        Deferred deferred = PENDING.remove(sticker);
        if (deferred != null) sticker.removeCallbacks(deferred);
        Animator current = RUNNING.get(sticker);
        if (current != null) current.cancel();
    }

    /**
     * The motion as an animator for the view at its present size, not started (the pivot is set
     * by {@link #play}, not here).
     */
    public static Animator build(View sticker, StickerMotion motion) {
        List<StickerMotion.Frame> frames = motion.frames;
        List<PropertyValuesHolder> holders = new ArrayList<>();
        for (StickerMotion.Channel channel : StickerMotion.Channel.values()) {
            if (!motion.animates(channel)) continue;
            float size = channel == StickerMotion.Channel.TRANSLATION_X ? sticker.getWidth()
                    : channel == StickerMotion.Channel.TRANSLATION_Y ? sticker.getHeight() : 1f;
            Keyframe[] keys = new Keyframe[frames.size()];
            for (int i = 0; i < keys.length; i++) {
                StickerMotion.Frame frame = frames.get(i);
                keys[i] = Keyframe.ofFloat(frame.offset, frame.value(channel) * size);
                // Android eases the interval that ends at a keyframe with that keyframe's
                // interpolator; the pattern's easing belongs to the frame that starts it.
                if (i > 0) keys[i].setInterpolator(interpolator(frames.get(i - 1).easing));
            }
            holders.add(PropertyValuesHolder.ofKeyframe(property(channel), keys));
        }
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(sticker,
                holders.toArray(new PropertyValuesHolder[0]));
        animator.setDuration(motion.duration);
        // The keyframes carry the easing; the animator's default would add its own on top.
        animator.setInterpolator(LINEAR);
        animator.setRepeatCount(0);
        return animator;
    }

    private static boolean needsSize(StickerMotion motion) {
        return motion.animates(StickerMotion.Channel.TRANSLATION_X)
                || motion.animates(StickerMotion.Channel.TRANSLATION_Y) || turns(motion);
    }

    /** Whether the pattern turns or scales (the pivot matters). */
    private static boolean turns(StickerMotion motion) {
        return motion.animates(StickerMotion.Channel.ROTATION)
                || motion.animates(StickerMotion.Channel.SCALE_X)
                || motion.animates(StickerMotion.Channel.SCALE_Y);
    }

    private static Animator start(final View sticker, final StickerMotion motion) {
        final boolean pivot = turns(motion);
        final float pivotX = sticker.getPivotX();
        final float pivotY = sticker.getPivotY();
        final boolean pivotWasSet = Build.VERSION.SDK_INT >= 28 && sticker.isPivotSet();
        if (pivot) {
            sticker.setPivotX(motion.pivotX * sticker.getWidth());
            sticker.setPivotY(motion.pivotY * sticker.getHeight());
        }
        final Animator animator = build(sticker, motion);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Also after a cancel: whatever it moved goes back to rest.
                if (RUNNING.get(sticker) == animation) RUNNING.remove(sticker);
                for (StickerMotion.Channel channel : StickerMotion.Channel.values()) {
                    if (motion.animates(channel)) property(channel).set(sticker, channel.rest);
                }
                if (pivot) {
                    if (Build.VERSION.SDK_INT >= 28 && !pivotWasSet) {
                        sticker.resetPivot();
                    } else {
                        sticker.setPivotX(pivotX);
                        sticker.setPivotY(pivotY);
                    }
                }
            }
        });
        RUNNING.put(sticker, animator);
        animator.start();
        return animator;
    }

    /** A start waiting for the view's first layout; holds the view weakly (it may never come). */
    private static final class Deferred implements Runnable {
        private final WeakReference<View> view;
        private final StickerMotion motion;

        Deferred(View view, StickerMotion motion) {
            this.view = new WeakReference<>(view);
            this.motion = motion;
        }

        @Override
        public void run() {
            View sticker = view.get();
            if (sticker == null || PENDING.get(sticker) != this) return;
            PENDING.remove(sticker);
            if (sticker.getWidth() == 0 || sticker.getHeight() == 0 || isRunning(sticker)) return;
            start(sticker, motion);
        }
    }

    private static Property<View, Float> property(StickerMotion.Channel channel) {
        switch (channel) {
            case TRANSLATION_X: return View.TRANSLATION_X;
            case TRANSLATION_Y: return View.TRANSLATION_Y;
            case ROTATION: return View.ROTATION;
            case SCALE_X: return View.SCALE_X;
            case SCALE_Y: return View.SCALE_Y;
            default: return View.ALPHA;
        }
    }

    @Nullable
    private static TimeInterpolator interpolator(StickerMotion.Easing easing) {
        if (easing.stepEnd) return STEP_END;
        if (easing.isLinear()) return null;
        if (easing == StickerMotion.Easing.OUT) {
            if (out == null) out = curve(easing);
            return out;
        }
        if (easing == StickerMotion.Easing.IN_OUT) {
            if (inOut == null) inOut = curve(easing);
            return inOut;
        }
        if (easing == StickerMotion.Easing.DOWN) {
            if (down == null) down = curve(easing);
            return down;
        }
        return curve(easing);
    }

    private static TimeInterpolator curve(StickerMotion.Easing easing) {
        return new PathInterpolator(easing.x1, easing.y1, easing.x2, easing.y2);
    }
}
