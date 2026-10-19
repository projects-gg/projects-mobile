package gg.projects.mobile.launch;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.GrabListener;
import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.MinecraftGLSurface;
import net.kdt.pojavlaunch.R;

import org.lwjgl.glfw.CallbackBridge;

import gg.projects.mobile.mods.Motion;

/**
 * The game's loading screen (M14; the owner: "it waits on a dark screen before the red Mojang
 * screen; make a beautiful loading screen there"). It covers the game activity from its first
 * frame until Minecraft draws its own first frame:
 * <ul>
 * <li>the Projects emblem (a traced vector) settles in, the pixel brackets close around it, a
 * warm glow sits behind it and a sheen crosses it every few seconds. The emblem, brackets and sheen
 * are AnimatedVectorDrawables, which run on the render thread from Android 7.1, so they stay
 * smooth while the process starts Java;</li>
 * <li>one short line says where the start is ({@link LaunchStages}: getting ready, starting Java,
 * loading mods, opening Minecraft, almost ready), read from the game's log, cross-fading as it changes; after
 * 30 s in one stage a quiet second line says it can take a while.</li>
 * </ul>
 * When it lifts: the first frame on the game's surface that is not black ({@link FrameContent}),
 * sampled with TextureView's own frame callback or, for the SurfaceView mode, PixelCopy (Android
 * 7+); when the game grabs the pointer; and by {@link LiftRules}: frames that stay black, the
 * resources signal where frames cannot be seen, and long timeouts. A title screen shown early, while
 * the game still loads behind it (the Projects pack's Remove Reloading Screen), stays covered until the
 * game says it finished: it stutters and freezes meanwhile. It dissolves in 300 ms and
 * removes itself: nothing of it (log listener, polling, animations) runs afterwards. With
 * animations off it is a still picture that disappears at once.
 *
 * <p>A start that hangs never traps the player (REVIEW1 M14-1): with the slow line, or as soon as
 * the player taps the screen or presses back, "Kaydı göster" (the loading screen steps aside for
 * upstream's live log) and "Oyunu kapat" (upstream's force-close question) appear under the status.
 *
 * <p>Diagnostics: logcat tag {@code ProjectsCurtain} has the stages, the resources signal, the first
 * frame and why it lifted (and the first frame after the lift when it lifted without one). With
 * {@code adb shell setprop log.tag.ProjectsCurtain DEBUG} before the game starts it also traces the
 * frame rate and every sample.
 */
@SuppressLint("ViewConstructor")
public final class LaunchCurtain extends FrameLayout implements Logger.eventLogListener, GrabListener {
    private static final String TAG = "ProjectsCurtain";
    /** Frame rate and sample trace (off unless the log tag is set to DEBUG; read once per process). */
    private static final boolean TRACE = Log.isLoggable(TAG, Log.DEBUG);

    private static final long POLL_MS = 150;
    private static final long SLOW_HINT_MS = 30_000;
    private static final int MAX_POLL_ERRORS = 40;
    /** The game's surface is read at 16 x 16: enough to catch a white logo on black. */
    private static final int SAMPLE = 16;
    private static final long LIFT_MS = 300;
    /**
     * "Neredeyse hazır" comes this long after Minecraft starts loading its resources: a red Mojang
     * screen lifts the launch screen within a moment, and the line should not change in the last frames.
     */
    private static final long RESOURCES_LINE_MS = 3000;
    /**
     * The sheen crosses the emblem once (about a second) every 3.6 s. Between passes nothing animates,
     * so the render thread draws no frames: as one endless animator it drew every frame while the
     * band waited off the emblem (about 50 frames a second on the emulator while Java started).
     */
    private static final long SHEEN_EVERY_MS = 3600;
    private static final long SHEEN_FIRST_MS = 920;

    private static volatile LaunchCurtain sActive;
    /** The game drew in this process: an activity created again later shows no curtain. */
    private static volatile boolean sGameShown;
    private static long sShownAt;
    private static long sLiftedAt;
    /** It lifted before any frame came: the first one is logged (how early the fallback was). */
    private static boolean sAwaitingFrame;
    private static long sTraceSince;
    private static int sTraceFrames;

    private final MinecraftGLSurface mGame;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final long mStartedAt = SystemClock.uptimeMillis();
    private final boolean mMotion;
    private final View mColumn;
    private final ImageView mEmblem;
    private final ImageView mSheen;
    private final ImageView mBrackets;
    /** The line on screen; the other one waits to cross-fade in. They swap at every change. */
    private TextView mStatus;
    private TextView mStatusNext;
    private final TextView mSlow;
    /** "Kaydı göster" and "Oyunu kapat": a start that hangs never traps the player (REVIEW1 M14-1). */
    private final LinearLayout mActions;
    private final Paint mEmberPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGoldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Bitmap mSample = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888);
    private final int[] mPixels = new int[SAMPLE * SAMPLE];
    private final LiftRules.State mRules = new LiftRules.State();

    private volatile int mStage = LaunchStages.PREPARING;
    /** Written by the logger thread. */
    private volatile long mResourcesAt;
    private volatile long mLastLogAt;
    /** The game shows its title screen early and loads behind it (Remove Reloading Screen). */
    private volatile boolean mEarlyTitle;
    private volatile long mLoadDoneAt;
    /** A picture came while the game still loads behind its early title screen: no more sampling. */
    private long mPictureAt;
    private int mShownStage = -1;
    private int mLastCopyResult = Integer.MIN_VALUE;
    private long mStageShownAt;
    private long mMinecraftAt;
    private long mFirstFrameAt;
    private long mLastSampleAt;
    private boolean mSampling;
    private int mPollErrors;
    private boolean mLifted;
    private boolean mRemoved;

    private LaunchCurtain(Activity activity, MinecraftGLSurface game) {
        super(activity);
        mGame = game;
        mMotion = Motion.enabled(activity);
        setWillNotDraw(false);
        setClickable(true);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setContentDescription(activity.getString(R.string.projects_launch_title));
        setBackgroundColor(ContextCompat.getColor(activity, R.color.projects_obsidian_950));

        float d = getResources().getDisplayMetrics().density;
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        mColumn = column;

        FrameLayout mark = new FrameLayout(activity);
        mBrackets = image(activity, 172);
        mEmblem = image(activity, 112);
        mSheen = image(activity, 112);
        mark.addView(mBrackets, new FrameLayout.LayoutParams(dp(172), dp(172), Gravity.CENTER));
        mark.addView(mEmblem, new FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER));
        mark.addView(mSheen, new FrameLayout.LayoutParams(dp(112), dp(112), Gravity.CENTER));
        column.addView(mark, new LinearLayout.LayoutParams(dp(172), dp(172)));

        FrameLayout status = new FrameLayout(activity);
        mStatus = text(activity, 15, R.color.projects_ash_300, R.font.figtree_semibold);
        mStatusNext = text(activity, 15, R.color.projects_ash_300, R.font.figtree_semibold);
        mStatusNext.setAlpha(0f);
        status.addView(mStatus, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        status.addView(mStatusNext, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(10);
        column.addView(status, statusParams);
        mStatus.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);

        mSlow = text(activity, 13, R.color.projects_ash_400, R.font.figtree_regular);
        mSlow.setText(R.string.projects_launch_slow);
        mSlow.setAlpha(0f);
        LinearLayout.LayoutParams slowParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slowParams.topMargin = dp(6);
        column.addView(mSlow, slowParams);

        // The way out of a start that hangs: shown with the slow line, or as soon as the player taps
        // the screen or presses back. Space is kept for it so nothing moves when it appears.
        mActions = new LinearLayout(activity);
        mActions.setOrientation(LinearLayout.HORIZONTAL);
        mActions.setGravity(Gravity.CENTER);
        mActions.addView(action(activity, R.string.projects_launch_show_log, v -> showLog()));
        mActions.addView(action(activity, R.string.projects_launch_close, v -> net.kdt.pojavlaunch.Tools.dialogForceClose(getContext())));
        mActions.setAlpha(0f);
        mActions.setVisibility(INVISIBLE);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(12);
        column.addView(mActions, actionsParams);

        // A little above the middle: the optical centre of the screen.
        FrameLayout.LayoutParams columnParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        addView(column, columnParams);
        column.setTranslationY(-8 * d);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static ImageView image(Context context, int sizeDp) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private static TextView text(Context context, int sp, int color, int font) {
        TextView view = new TextView(context);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(ContextCompat.getColor(context, color));
        view.setTypeface(ResourcesCompat.getFont(context, font));
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        return view;
    }

    // ---- Entry points (MainActivity, MinecraftGLSurface) ----

    /** Covers {@code activity} until {@code game} shows Minecraft's first frame. */
    public static void attach(Activity activity, MinecraftGLSurface game) {
        if (sGameShown || sActive != null || game == null) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        LaunchCurtain curtain;
        try {
            curtain = new LaunchCurtain(activity, game);
        } catch (RuntimeException e) {
            // A drawable that cannot be read on this device must not keep the game from starting.
            Log.w(TAG, "launch screen unavailable", e);
            return;
        }
        content.addView(curtain, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sActive = curtain;
        curtain.begin();
    }

    /** The game was already running when its activity came back: no loading screen. */
    public static void dismissNow() {
        sGameShown = true;
        LaunchCurtain curtain = sActive;
        if (curtain != null) curtain.lift("already running", false);
    }

    /**
     * A frame reached the game's TextureView (its onSurfaceTextureUpdated, main thread). Once the
     * curtain is gone this is a field read or two per frame.
     */
    public static void onGameFrame() {
        LaunchCurtain curtain = sActive;
        if (curtain != null && !curtain.mLifted) {
            curtain.frameArrived();
        } else if (sAwaitingFrame) {
            sAwaitingFrame = false;
            Log.i(TAG, "first frame " + (SystemClock.uptimeMillis() - sLiftedAt) + " ms after the lift");
        }
        if (TRACE) traceFrame();
    }

    /** Trace: frames per second while the trace runs (the first ten minutes after the curtain showed). */
    private static void traceFrame() {
        long now = SystemClock.uptimeMillis();
        if (sShownAt == 0 || now - sShownAt > 600_000) return;
        sTraceFrames++;
        if (sTraceSince == 0) {
            sTraceSince = now;
            Log.d(TAG, "trace: first frame on the surface " + (now - sShownAt) + " ms after shown");
            sTraceFrames = 0;
        } else if (now - sTraceSince >= 1000) {
            Log.d(TAG, "trace: " + sTraceFrames + " frames in " + (now - sTraceSince) + " ms, at " + (now - sShownAt) + " ms");
            sTraceSince = now;
            sTraceFrames = 0;
        }
    }

    // ---- Lifecycle ----

    private void begin() {
        sShownAt = mStartedAt;
        Log.i(TAG, "shown");
        if (TRACE) Log.d(TAG, "trace: on, sample " + SAMPLE + "x" + SAMPLE);
        showStage(false);
        if (mMotion) {
            mBrackets.setImageResource(R.drawable.projects_launch_brackets);
            mEmblem.setImageResource(R.drawable.projects_launch_emblem_in);
            mSheen.setImageResource(R.drawable.projects_launch_sheen);
            start(mEmblem.getDrawable());
            mMain.postDelayed(() -> start(mBrackets.getDrawable()), 160);
            if (mSheen.getDrawable() instanceof AnimatedVectorDrawable) mMain.postDelayed(mSheenPass, SHEEN_FIRST_MS);
            mColumn.setAlpha(1f);
        } else {
            mBrackets.setImageResource(R.drawable.projects_launch_brackets_static);
            mEmblem.setImageResource(R.drawable.projects_launch_emblem);
        }
        Logger.addLogListener(this);
        CallbackBridge.addGrabListener(this);
        mMain.postDelayed(mTick, POLL_MS);
    }

    private void start(Drawable drawable) {
        if (mLifted || !(drawable instanceof Animatable)) return;
        try {
            ((Animatable) drawable).start();
        } catch (RuntimeException e) {
            Log.w(TAG, "animation unavailable", e);
        }
    }

    /** One pass of the sheen (drawable-v25), then the next one in {@link #SHEEN_EVERY_MS}. */
    private final Runnable mSheenPass = new Runnable() {
        @Override
        public void run() {
            if (mLifted || !(mSheen.getDrawable() instanceof AnimatedVectorDrawable)) return;
            AnimatedVectorDrawable sheen = (AnimatedVectorDrawable) mSheen.getDrawable();
            // Only drawable-v25 animates, so this runs from Android 7.1 (reset() is API 23).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) sheen.reset();
            start(sheen);
            mMain.postDelayed(this, SHEEN_EVERY_MS);
        }
    };

    private void stopAnimations() {
        for (ImageView view : new ImageView[]{mEmblem, mSheen, mBrackets}) {
            Drawable drawable = view.getDrawable();
            if (drawable instanceof Animatable && ((Animatable) drawable).isRunning()) ((Animatable) drawable).stop();
        }
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (mLifted) return;
            long now = SystemClock.uptimeMillis();
            pollSurface();
            mRules.startedAt = mStartedAt;
            mRules.stage = mStage;
            mRules.minecraftAt = mMinecraftAt;
            mRules.resourcesAt = mResourcesAt;
            mRules.lastLogAt = mLastLogAt;
            mRules.firstFrameAt = mFirstFrameAt;
            mRules.seesFrames = seesFrames();
            mRules.pictureAt = mPictureAt;
            mRules.loadDoneAt = mLoadDoneAt;
            String reason = LiftRules.reason(mRules, now);
            if (reason != null) {
                lift(reason, true);
                return;
            }
            if (mStageShownAt > 0 && now - mStageShownAt > SLOW_HINT_MS && mSlow.getAlpha() == 0f) {
                fade(mSlow, 1f, Motion.ENTER_MS);
                showActions();
            }
            mMain.postDelayed(this, POLL_MS);
        }
    };

    /**
     * Whether frames on the game's surface can be seen: TextureView reports each frame it shows (its
     * content changes no other way); the SurfaceView mode is read with PixelCopy from Android 7 while
     * that works. Before the game's view starts there is nothing to see yet.
     */
    private boolean seesFrames() {
        View render = mGame.getRenderView();
        if (render instanceof TextureView) return true;
        return render instanceof SurfaceView && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                && mPollErrors <= MAX_POLL_ERRORS;
    }

    /** SurfaceView mode (Android 7+): has a frame been queued, and does it show a picture? */
    private void pollSurface() {
        if (mSampling || mPictureAt > 0 || Build.VERSION.SDK_INT < Build.VERSION_CODES.N || mPollErrors > MAX_POLL_ERRORS) return;
        View render = mGame.getRenderView();
        if (!(render instanceof SurfaceView)) return;
        SurfaceView surface = (SurfaceView) render;
        if (!surface.getHolder().getSurface().isValid()) return;
        mSampling = true;
        try {
            PixelCopy.request(surface, mSample, result -> {
                mSampling = false;
                if (mLifted) return;
                if (TRACE && result != mLastCopyResult) {
                    Log.d(TAG, "trace: PixelCopy result " + result + " at " + (SystemClock.uptimeMillis() - mStartedAt) + " ms");
                }
                mLastCopyResult = result;
                if (result == PixelCopy.SUCCESS) {
                    mPollErrors = 0;
                    onSample();
                } else if (result != PixelCopy.ERROR_SOURCE_NO_DATA) {
                    mPollErrors++;
                }
            }, mMain);
        } catch (RuntimeException e) {
            mSampling = false;
            mPollErrors++;
        }
    }

    /** TextureView mode: a frame arrived; look at it (at most every 100 ms). */
    private void frameArrived() {
        if (mLifted) return;
        long now = SystemClock.uptimeMillis();
        if (mFirstFrameAt == 0) {
            mFirstFrameAt = now;
            Log.i(TAG, "first frame after " + (now - mStartedAt) + " ms");
        }
        if (mPictureAt > 0 || now - mLastSampleAt < 100) return;
        mLastSampleAt = now;
        // Not while TextureView is drawing (this runs inside its frame callback).
        mMain.post(() -> {
            if (mLifted) return;
            View render = mGame.getRenderView();
            if (!(render instanceof TextureView) || !((TextureView) render).isAvailable()) return;
            try {
                ((TextureView) render).getBitmap(mSample);
                onSample();
            } catch (RuntimeException ignored) {
                // The steady-frames rule and the fallbacks still lift the curtain.
            }
        });
    }

    private void onSample() {
        long now = SystemClock.uptimeMillis();
        if (mFirstFrameAt == 0) {
            mFirstFrameAt = now;
            Log.i(TAG, "first frame after " + (now - mStartedAt) + " ms");
        }
        mSample.getPixels(mPixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
        if (TRACE) Log.d(TAG, "trace: sample " + FrameContent.describe(mPixels) + " at " + (now - mStartedAt) + " ms");
        if (!FrameContent.hasPicture(mPixels)) return;
        if (mEarlyTitle && mLoadDoneAt == 0) {
            // The title screen came early; the game still loads behind it and would stutter and
            // freeze under the player's finger: LiftRules waits for it to finish.
            if (mPictureAt == 0) {
                mPictureAt = now;
                Log.i(TAG, "picture after " + (now - mStartedAt) + " ms, the game still loading behind it");
            }
            return;
        }
        lift("picture", true);
    }

    // ---- The game's log (logger thread) ----

    @Override
    public void onEventLogged(String text) {
        // The logger thread and the launcher's own appendToLog (the JVM thread) both call this.
        long now = SystemClock.uptimeMillis();
        mLastLogAt = now;
        synchronized (this) {
            int stage = LaunchStages.advance(mStage, text);
            if (stage > mStage) {
                mStage = stage;
                mMain.post(() -> showStage(true));
            }
            if (mResourcesAt == 0 && LaunchStages.loadingResources(text)) {
                mResourcesAt = now;
                Log.i(TAG, "resources after " + (now - mStartedAt) + " ms");
                mMain.postDelayed(this::showResources, RESOURCES_LINE_MS);
            }
            if (!mEarlyTitle && LaunchStages.earlyTitle(text)) {
                mEarlyTitle = true;
                Log.i(TAG, "early title screen after " + (now - mStartedAt) + " ms");
            }
            if (mLoadDoneAt == 0 && LaunchStages.loadDone(text)) {
                mLoadDoneAt = now;
                Log.i(TAG, "load done after " + (now - mStartedAt) + " ms");
            }
        }
    }

    /** Minecraft has been loading its resources for a moment and nothing lifted the screen: the last step. */
    private void showResources() {
        if (mLifted) return;
        synchronized (this) {
            if (mStage >= LaunchStages.RESOURCES) return;
            mStage = LaunchStages.RESOURCES;
        }
        showStage(true);
    }

    @Override
    public void onGrabState(boolean isGrabbing) {
        if (isGrabbing) mMain.post(() -> lift("pointer grabbed", true));
    }

    // ---- Status line ----

    private void showStage(boolean animate) {
        if (mLifted) return;
        int stage = mStage;
        if (stage == mShownStage) return;
        mShownStage = stage;
        mStageShownAt = SystemClock.uptimeMillis();
        if (stage >= LaunchStages.MINECRAFT && mMinecraftAt == 0) mMinecraftAt = mStageShownAt;
        Log.i(TAG, "stage " + stage + " after " + (mStageShownAt - mStartedAt) + " ms");
        CharSequence text = getContext().getString(stageText(stage));
        if (mSlow.getAlpha() > 0f) fade(mSlow, 0f, Motion.EXIT_MS);
        if (!animate || !mMotion || !isLaidOut()) {
            mStatus.animate().cancel();
            mStatusNext.animate().cancel();
            mStatus.setText(text);
            mStatus.setAlpha(1f);
            mStatus.setTranslationY(0f);
            mStatusNext.setAlpha(0f);
            return;
        }
        // The old line lifts away while the new one rises in (opacity and 4 dp of travel).
        float rise = 4 * getResources().getDisplayMetrics().density;
        TextView leaving = mStatus;
        mStatusNext.animate().cancel();
        mStatusNext.setText(text);
        mStatusNext.setAlpha(0f);
        mStatusNext.setTranslationY(rise);
        leaving.animate().cancel();
        leaving.animate().alpha(0f).translationY(-rise).setStartDelay(0).setDuration(Motion.EXIT_MS - 40)
                .setInterpolator(Motion.EASE_IN).start();
        mStatusNext.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(Motion.ENTER_MS)
                .setInterpolator(Motion.EASE_OUT).start();
        mStatusNext.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        leaving.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_NONE);
        // Swap roles, so a quick second change starts from the line now on screen.
        mStatus = mStatusNext;
        mStatusNext = leaving;
    }

    private static int stageText(int stage) {
        switch (stage) {
            case LaunchStages.JAVA: return R.string.projects_launch_java;
            case LaunchStages.MODS: return R.string.projects_launch_mods;
            case LaunchStages.MINECRAFT: return R.string.projects_launch_minecraft;
            case LaunchStages.RESOURCES: return R.string.projects_launch_almost;
            default: return R.string.projects_launch_preparing;
        }
    }

    private void fade(View view, float to, long duration) {
        view.animate().cancel();
        if (!mMotion) {
            view.setAlpha(to);
            return;
        }
        view.animate().alpha(to).setStartDelay(0).setDuration(duration).setInterpolator(Motion.EASE_OUT).start();
    }

    // ---- Lifting ----

    private void lift(String reason, boolean animate) {
        if (mLifted) return;
        mLifted = true;
        sGameShown = true;
        long now = SystemClock.uptimeMillis();
        sLiftedAt = now;
        sAwaitingFrame = mFirstFrameAt == 0;
        Log.i(TAG, "lifted (" + reason + ") after " + (now - mStartedAt) + " ms, stage " + mStage);
        detachSources();
        if (!animate || !mMotion || !isAttachedToWindow()) {
            remove();
            return;
        }
        // A cross-dissolve into Minecraft's first frame, the emblem drifting forward a little as it
        // goes: the drawer curve answers at once without a jump and settles softly (an ease-in held
        // the dark screen and then dropped it in the last frames).
        mColumn.animate().scaleX(1.04f).scaleY(1.04f).setDuration(LIFT_MS).setInterpolator(Motion.EASE_DRAWER).start();
        animate().alpha(0f).setDuration(LIFT_MS).setInterpolator(Motion.EASE_DRAWER).withEndAction(this::remove).start();
    }

    private void detachSources() {
        mMain.removeCallbacks(mTick);
        Logger.removeLogListener(this);
        CallbackBridge.removeGrabListener(this);
    }

    private void remove() {
        if (mRemoved) return;
        mRemoved = true;
        stopAnimations();
        mMain.removeCallbacksAndMessages(null);
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) parent.removeView(this);
        if (sActive == this) sActive = null;
        // mSample is not recycled: a PixelCopy may still be writing into it.
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // The activity went away while the curtain was up (or it was just removed).
        if (!mLifted) {
            mLifted = true;
            detachSources();
        }
        stopAnimations();
        mMain.removeCallbacksAndMessages(null);
        if (sActive == this) sActive = null;
    }

    // ---- Drawing: the glow behind the emblem (static; drawn when laid out) ----

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float cx = w / 2f;
        float cy = h / 2f - 8 * getResources().getDisplayMetrics().density - dp(24);
        float ember = Math.max(w, h) * 0.45f;
        mEmberPaint.setShader(new RadialGradient(cx, cy, ember,
                new int[]{0x663F0A0A, 0x243F0A0A, 0x003F0A0A}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        float gold = dp(150);
        mGoldPaint.setShader(new RadialGradient(cx, cy, gold,
                new int[]{0x2EE89A2C, 0x12E89A2C, 0x00E89A2C}, new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), mEmberPaint);
        canvas.drawRect(0, 0, getWidth(), getHeight(), mGoldPaint);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // The controls under it do nothing yet; a tap shows the way out instead.
        if (event.getActionMasked() == MotionEvent.ACTION_UP) showActions();
        return true;
    }

    /**
     * MainActivity's back key while the loading screen is up: shows the way out (back would only
     * send Escape to a game that is not on screen yet). Returns true when it took the key.
     */
    public static boolean onBack(android.view.KeyEvent event) {
        LaunchCurtain curtain = sActive;
        if (curtain == null || curtain.mLifted || event.getKeyCode() != android.view.KeyEvent.KEYCODE_BACK) return false;
        if (event.getAction() == android.view.KeyEvent.ACTION_UP) curtain.showActions();
        return true;
    }

    private TextView action(Context context, int text, View.OnClickListener listener) {
        TextView view = text(context, 14, R.color.projects_ash_300, R.font.figtree_semibold);
        view.setText(text);
        view.setMinHeight(dp(48));
        view.setPadding(dp(16), 0, dp(16), 0);
        view.setBackgroundResource(R.drawable.projects_row_ripple);
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnClickListener(listener);
        gg.projects.mobile.ui.Press.attach(view, gg.projects.mobile.ui.Press.Style.BUTTON);
        return view;
    }

    private void showActions() {
        if (mLifted || mActions.getVisibility() == VISIBLE) return;
        mActions.setVisibility(VISIBLE);
        fade(mActions, 1f, Motion.ENTER_MS);
    }

    /** "Kaydı göster": the loading screen steps aside for upstream's live log. */
    private void showLog() {
        View logger = ((Activity) getContext()).findViewById(R.id.mainLoggerView);
        lift("log shown", true);
        if (logger != null) logger.setVisibility(VISIBLE);
    }
}
