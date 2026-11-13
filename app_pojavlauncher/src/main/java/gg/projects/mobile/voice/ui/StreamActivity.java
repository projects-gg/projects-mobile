package gg.projects.mobile.voice.ui;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Rational;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import net.kdt.pojavlaunch.R;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialRoutes;
import gg.projects.mobile.social.ui.AvatarView;
import gg.projects.mobile.social.ui.ReportDialog;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.ui.TickSeekBar;
import gg.projects.mobile.voice.RoomChat;
import gg.projects.mobile.voice.StreamSound;
import gg.projects.mobile.voice.StreamView;
import gg.projects.mobile.voice.VoiceAnswer;
import gg.projects.mobile.voice.VoiceHub;
import gg.projects.mobile.voice.VoiceLink;
import gg.projects.mobile.voice.VoicePrefs;
import gg.projects.mobile.voice.VoiceState;

/**
 * MOB-WATCH: the stream screen (the desktop's stream stage and its tile, the lead's {@code sqol.md} §1.1, as a phone
 * player). The picture fills the screen and turns with the phone; a tap shows the controls, which fade after 3 s (never
 * under TalkBack, never while a panel is open): "Küçük Pencerede İzle", the streamer's name and what they show, "Düşük
 * Kalite" on a weak connection, and at the bottom "Yayın Sesi" (0–200 % for this streamer, remembered on the phone, live
 * while dragging; "Yayını Sessize Al"), "Kalite" (Otomatik / Yüksek / Veri Tasarrufu), "Ses Odası" (the room's sheet
 * over the stream), "Yayın Seçenekleri" ("Yayını Rapor Et") and "İzlemeyi Bırak". Two fingers zoom up to 400 % and
 * drag the picture; a double tap zooms back (or in, where it is not zoomed).
 *
 * <p>The connection and the decoding live in the voice process ({@code :voice}): this screen hands it the surface of its
 * {@link TextureView} and says whether it is on screen. Leaving the app goes into picture-in-picture where Android has
 * it; the screen off, or the app away without it, lets the stream go entirely (asked again on return). Back goes into
 * picture-in-picture too, so the room's sheet and the call bar can be used while the stream plays.
 */
public final class StreamActivity extends AppCompatActivity implements VoiceLink.Listener {
    private static final String EXTRA_PERSON = "person";
    private static final String EXTRA_N = "n";
    private static final String ACTION_PIP_MUTE = "gg.projects.mobile.voice.STREAM_PIP_MUTE";
    /** The controls fade this long after the last touch. */
    private static final long CONTROLS_MS = 3_000;
    /** "Yayın bitti." stays this long before the screen goes. */
    private static final long ENDED_MS = 2_000;
    /** Other last words (a refusal, a lost connection) stay a little longer. */
    private static final long NOTICE_MS = 3_200;
    /** A touch reaches the voice process at most this often ("Hâlâ izliyor musun?" counts from the last one). */
    private static final long STILL_GAP_MS = 60_000;
    /** The cellular sentence stays this long (TalkBack keeps it until it is closed). */
    private static final long HINT_MS = 8_000;
    /** "Yayın Sesi" steps (percent). */
    private static final int VOLUME_STEP = 5;
    /** A link that has not said a stay by then has none (the stream screen goes). */
    private static final long NO_STAY_MS = 4_000;
    /** After this screen switches the layer, the picture's own hiccup is no sign of a weak connection. */
    private static final long LAYER_SETTLE_MS = 4_000;

    /** "İzle": opens the stream screen on {@code person}'s share {@code n} (or switches the open one to it). */
    public static void open(Activity from, String person, int n) {
        Intent intent = new Intent(from, StreamActivity.class)
                .putExtra(EXTRA_PERSON, person)
                .putExtra(EXTRA_N, n)
                // The picture comes up at once: the system's slide between tasks showed a light band beside it (and a
                // custom animation is not honoured for another task's start on Android 14 and newer).
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        from.startActivity(intent);
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private float mDp;
    private VoiceLink mLink;
    private VoicePrefs mPrefs;
    private String mPerson = "";
    private int mN;
    @Nullable private VoiceState mState;
    @Nullable private VoiceState.Watch mWatch;

    // ---- views ----
    private FrameLayout mRoot;
    private ImageView mPoster;
    private TextureView mVideo;
    private View mVeil;
    private LinearLayout mCenter;
    private AvatarView mCenterFace;
    private TextView mCenterText;
    private TextView mCenterButton;
    private LinearLayout mTop;
    private LinearLayout mBottom;
    private ImageButton mPipButton;
    private TextView mName;
    private TextView mMeta;
    private TextView mWeakChip;
    private TextView mZoomChip;
    private ImageButton mSoundButton;
    private ImageButton mQualityButton;
    private FrameLayout mRoomBox;
    private TextView mRoomBadge;
    private TextView mStopButton;
    private FrameLayout mPanelLayer;
    @Nullable private View mPanel;
    private LinearLayout mHint;
    private TextView mHintText;
    private TextView mHintAction;

    // ---- the picture ----
    @Nullable private Surface mSurface;
    @Nullable private SurfaceTexture mTexture;
    private int mPictureW;
    private int mPictureH;
    private StreamView.Zoom mZoom = new StreamView.Zoom(1f, 0f, 0f);
    @Nullable private ValueAnimator mZoomAnim;
    private ScaleGestureDetector mScale;
    private GestureDetector mGestures;
    private boolean mFrameSeen;
    private long mPosterTh = -1;

    // ---- state ----
    private boolean mControls = true;
    private boolean mInPip;
    private boolean mEnding;
    /** A stay was seen: a state of none after it is the stay's end. */
    private boolean mHadState;
    /** The room this screen's stream is in (the first stay seen). */
    @Nullable private String mRoom;
    /** This screen's watch was in a state once (its going without a word ends the screen). */
    private boolean mHadWatch;
    /** The sound state picture-in-picture's button was last given (null: none yet). */
    @Nullable private Boolean mPipMuted;
    /** Android 12+: picture-in-picture pushed to the screen's edge (nothing of the picture shows). */
    private boolean mPipStashed;
    private boolean mStarted;
    private int mNoticeSeen = -1;
    private long mStillSentAt;
    private boolean mMetered;
    @Nullable private Object mNetCallback;
    private StreamView.WeakHold mWeakHold = new StreamView.WeakHold();
    /** When this screen last asked for another layer (its switch may stall the picture a moment). */
    private long mLayerAt;
    @Nullable private String mLayerSent;
    // TalkBack: what was said last about the stream.
    @Nullable private String mHeard;
    private boolean mHintShown;

    /** The controls' 3 s count runs (states never restart it; touches do). */
    private boolean mHidePending;
    private final Runnable mHideControls = () -> {
        mHidePending = false;
        if (mayHide()) setControls(false, true);
    };
    /** The weak hold's time is up (no new state may come): it lets the full layer back, the chip goes. */
    private final Runnable mLayerCheck = () -> {
        VoiceState.Watch w = mWatch;
        if (w != null) mWeakHold.step(weakNow(w), SystemClock.elapsedRealtime());
        syncLayer();
        render();
    };
    private final Runnable mFinish = this::finishWatching;
    private final Runnable mHideHint = () -> showHint(false);
    private final Runnable mNoStay = () -> {
        if (mState == null && !mEnding) {
            mEnding = true;
            stopWatching();
            finish();
        }
    };

    private final BroadcastReceiver mPipActions = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_PIP_MUTE.equals(intent.getAction())) toggleMute();
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(net.kdt.pojavlaunch.utils.LocaleUtils.setLocale(newBase));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        gg.projects.mobile.language.LanguageManager.applyLocal(this);
        mDp = getResources().getDisplayMetrics().density;
        mLink = VoiceLink.get(this);
        mPrefs = new VoicePrefs(this);
        if (!readIntent(getIntent())) {
            finish();
            return;
        }
        // Edge to edge, the system bars away (a swipe brings them for a moment), the screen kept on while it plays.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        // Android 14 and newer: closing goes at once too (the system's slide between tasks showed a light band).
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0);
        buildViews();
        setContentView(mRoot);
        hideSystemBars();
        setTitle(getString(R.string.projects_stream_label, VoiceUi.nameOf(this, mPerson)));
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (mPanel != null) {
                    closePanel();
                } else if (!enterPip()) {
                    finishWatching();
                }
            }
        });
        IntentFilter filter = new IntentFilter(ACTION_PIP_MUTE);
        // Picture-in-picture's own button (its intent names this app; Android 13+ keeps the receiver to the app).
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(mPipActions, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(mPipActions, filter);
        if (Build.VERSION.SDK_INT >= 26 && pipAllowed()) updatePipParams();
    }

    private boolean readIntent(@Nullable Intent intent) {
        String person = intent == null ? null : intent.getStringExtra(EXTRA_PERSON);
        int n = intent == null ? 0 : intent.getIntExtra(EXTRA_N, 0);
        if (person == null || person.isEmpty() || n <= 0) return false;
        mPerson = person;
        mN = n;
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String before = mPerson;
        int beforeN = mN;
        if (!readIntent(intent) || (before.equals(mPerson) && beforeN == mN)) {
            mPerson = before;
            mN = beforeN;
            return;
        }
        // Another stream from the room's sheet: this one lets go, the screen starts over on the new one.
        mLink.stopWatching(before, beforeN);
        mMain.removeCallbacks(mFinish);
        mEnding = false;
        mWatch = null;
        // The new stream has not been in a state yet; its sound state is its own.
        mHadWatch = false;
        mPipMuted = null;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mHeard = null;
        mFrameSeen = false;
        mPosterTh = -1;
        mPoster.setImageDrawable(null);
        mLayerSent = null;
        mMain.removeCallbacks(mLayerCheck);
        mWeakHold = new StreamView.WeakHold();
        // 16:9 until the new picture says its size.
        mPictureW = 0;
        mPictureH = 0;
        mZoom = StreamView.none(0, 0, 0, 0);
        layoutPicture();
        if (Build.VERSION.SDK_INT >= 26 && pipAllowed()) updatePipParams();
        setTitle(getString(R.string.projects_stream_label, VoiceUi.nameOf(this, mPerson)));
        if (mStarted) mLink.watch(mPerson, mN, wantedLayer());
        render();
        setControls(true, false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mStarted = true;
        readMetered();
        listenNetwork(true);
        // The screen's choices before the watch itself (a held watch comes back as it is asked for); a link not bound
        // yet says them when it is.
        mLink.watchShown(true);
        sendDuck();
        // A screen showing a stream's end asks for nothing again (its word stays until it closes).
        if (!mEnding) {
            mLink.watch(mPerson, mN, wantedLayer());
            if (mSurface != null) mLink.watchSurface(mSurface);
        }
        mLink.attach(this);
        touched();
        scheduleHide();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mStarted = false;
        listenNetwork(false);
        if (isFinishing() && !mEnding) {
            stopWatching();
        } else {
            // Out of sight (the screen off, the app away without picture-in-picture): the stream lets go in a moment.
            mLink.watchShown(false);
        }
        mLink.detach(this);
    }

    @Override
    protected void onDestroy() {
        mMain.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(mPipActions);
        } catch (IllegalArgumentException ignored) {
            // Never registered (finished in onCreate).
        }
        // A recreation (text size, language) comes straight back on the same stream: only a real end lets go.
        if (mPerson.length() > 0 && !mEnding && !isChangingConfigurations()) stopWatching();
        super.onDestroy();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Android 12 and newer enter by themselves (auto-enter); older ones are asked here.
        if (Build.VERSION.SDK_INT < 31 && !mEnding) enterPip();
    }

    @Override
    public void onPictureInPictureModeChanged(boolean inPip, Configuration config) {
        super.onPictureInPictureModeChanged(inPip, config);
        mInPip = inPip;
        if (!inPip) mPipStashed = false;
        if (!inPip && !getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            // Picture-in-picture's X (the window went, the screen stopped first): watching ends.
            finishWatching();
            return;
        }
        closePanel();
        render();
        setControls(!inPip, false);
        // The window may have taken its new size before this call: the picture's place and the layer by the mode now
        // (back from the small window, "Otomatik" asks for the full picture again).
        layoutPicture();
        syncLayer();
    }

    @Override
    public void onPictureInPictureUiStateChanged(android.app.PictureInPictureUiState state) {
        super.onPictureInPictureUiStateChanged(state);
        // Pushed to the screen's edge: nothing of the picture shows, the small layer is enough until it comes back.
        boolean stashed = state.isStashed();
        if (stashed == mPipStashed) return;
        mPipStashed = stashed;
        syncLayer();
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        hideSystemBars();
    }

    // ---- the voice process ----

    @Override
    public void onVoiceState(@Nullable VoiceState state) {
        mState = state;
        if (state == null) {
            if (mHadState) {
                // The stay ended (left, moved away, the connection gave up): the call bar says why; the screen goes,
                // and the link forgets the watch (the next stay never starts it by itself).
                mMain.removeCallbacks(mNoStay);
                if (!mEnding) {
                    mEnding = true;
                    stopWatching();
                    finish();
                }
            } else {
                // A link that is still binding says nothing yet: no stay at all after a moment, nothing to watch.
                mMain.removeCallbacks(mNoStay);
                mMain.postDelayed(mNoStay, NO_STAY_MS);
            }
            return;
        }
        mHadState = true;
        mMain.removeCallbacks(mNoStay);
        VoiceState.Watch watch = state.watch != null && state.watch.u.equals(mPerson) && state.watch.n == mN ? state.watch : null;
        mWatch = watch;
        if (state.watchNoticeSeq != mNoticeSeen) {
            boolean first = mNoticeSeen < 0;
            mNoticeSeen = state.watchNoticeSeq;
            // A word said before this screen came up is old news.
            if (!first && state.watchNotice != null) notice(state.watchNotice);
        }
        // The stream belongs to its room: a stay elsewhere (a join, a move) or a watch gone without a word ends the
        // screen too (it never waits on "Bağlanıyor…" for a stream that is not coming).
        if (mRoom == null) mRoom = state.room;
        if (!mEnding && ((mRoom != null && !mRoom.equals(state.room)) || (mHadWatch && watch == null))) {
            finishWatching();
            return;
        }
        if (watch != null) mHadWatch = true;
        // Picture-in-picture's sound button says the state the voice process has now (a tap changes it a moment later).
        if (watch != null && (mPipMuted == null || mPipMuted != watch.muted)) {
            mPipMuted = watch.muted;
            if (Build.VERSION.SDK_INT >= 26 && pipAllowed()) updatePipParams();
        }
        if (watch != null) {
            // Every reading: the layer by the hold now, and its release counted from the first good one.
            mWeakHold.step(weakNow(watch), SystemClock.elapsedRealtime());
            syncLayer();
            if (watch.width > 0 && watch.height > 0 && (watch.width != mPictureW || watch.height != mPictureH)) {
                mPictureW = watch.width;
                mPictureH = watch.height;
                applyBufferSize();
                layoutPicture();
                if (Build.VERSION.SDK_INT >= 26 && pipAllowed()) updatePipParams();
            }
        }
        render();
        hear();
    }

    @Override
    public void onVoiceNotice(VoiceLink.Notice notice) {
        // The call bar's words: not this screen's.
    }

    @Override
    public void onVoiceRefused(VoiceAnswer answer) {
        // Nothing to refuse here.
    }

    @Override
    public void onVoiceChat(@Nullable RoomChat.View chat) {
        render();
    }

    // ---- drawing ----

    private void buildViews() {
        Context c = this;
        mRoot = new FrameLayout(c) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touched();
                return super.dispatchTouchEvent(event);
            }
        };
        mRoot.setBackgroundColor(color(R.color.projects_obsidian_950));
        mRoot.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The stream's last small picture under the picture while it connects (no empty black screen).
        mPoster = new ImageView(c);
        mPoster.setScaleType(ImageView.ScaleType.FIT_CENTER);
        mPoster.setAlpha(0.35f);
        mPoster.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mRoot.addView(mPoster, match());

        mVideo = new TextureView(c);
        mVideo.setOpaque(false);
        mVideo.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                mTexture = texture;
                applyBufferSize();
                mSurface = new Surface(texture);
                if (mStarted) mLink.watchSurface(mSurface);
                layoutPicture();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                // TextureView set the buffer to its own size: the picture's size again (drawn 1:1, scaled once here).
                applyBufferSize();
                layoutPicture();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                mLink.watchSurface(null);
                if (mSurface != null) mSurface.release();
                mSurface = null;
                mTexture = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                if (!mFrameSeen) {
                    mFrameSeen = true;
                    render();
                }
            }
        });
        mVideo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mRoot.addView(mVideo, match());
        mVideo.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                layoutPicture();
                syncLayer();
            }
        });

        // Over the picture for a paused share (opaque) or a minimized window (dimmed).
        mVeil = new View(c);
        mVeil.setBackgroundColor(color(R.color.projects_obsidian_950));
        mVeil.setVisibility(View.GONE);
        mRoot.addView(mVeil, match());

        mCenter = new LinearLayout(c);
        mCenter.setOrientation(LinearLayout.VERTICAL);
        mCenter.setGravity(Gravity.CENTER_HORIZONTAL);
        mCenter.setPadding(dp(24), dp(16), dp(24), dp(16));
        mCenterFace = new AvatarView(c);
        mCenterFace.setVisibility(View.GONE);
        mCenter.addView(mCenterFace, new LinearLayout.LayoutParams(dp(72), dp(72)));
        mCenterText = text(15, R.color.projects_bone_100, R.font.figtree_semibold);
        mCenterText.setGravity(Gravity.CENTER);
        mCenterText.setSingleLine(false);
        LinearLayout.LayoutParams centerTextLp = wrap();
        centerTextLp.topMargin = dp(12);
        mCenter.addView(mCenterText, centerTextLp);
        mCenterButton = button(c, R.string.projects_stream_still_yes, true);
        mCenterButton.setVisibility(View.GONE);
        LinearLayout.LayoutParams centerButtonLp = wrap();
        centerButtonLp.topMargin = dp(16);
        mCenter.addView(mCenterButton, centerButtonLp);
        ViewCompat.setAccessibilityLiveRegion(mCenterText, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        mRoot.addView(mCenter, centerLp);

        // The gestures: a tap shows or hides the controls; two fingers zoom; a drag moves a zoomed picture.
        mScale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                zoomBy(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        mGestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                setControls(!mControls, true);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float[] fit = fit();
                animateZoom(StreamView.doubleTap(mZoom, e.getX(), e.getY(), fit[0], fit[1], mVideo.getWidth(), mVideo.getHeight()));
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent first, MotionEvent e, float dx, float dy) {
                if (!mZoom.zoomed() || mScale.isInProgress()) return false;
                float[] fit = fit();
                setZoom(StreamView.panBy(mZoom, -dx, -dy, fit[0], fit[1], mVideo.getWidth(), mVideo.getHeight()));
                return true;
            }
        });
        View touch = new View(c);
        touch.setOnTouchListener((v, event) -> {
            if (mInPip) return false;
            boolean scaled = mScale.onTouchEvent(event);
            boolean gestured = mGestures.onTouchEvent(event);
            return scaled || gestured;
        });
        touch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mRoot.addView(touch, 3, match());

        buildTop(c);
        buildBottom(c);

        // Panels open from the bottom bar's buttons (the volume, the quality, the options).
        mPanelLayer = new FrameLayout(c);
        mPanelLayer.setClickable(false);
        mRoot.addView(mPanelLayer, match());

        buildHint(c);

        ViewCompat.setOnApplyWindowInsetsListener(mRoot, (v, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            mTop.setPadding(safe.left + dp(8), safe.top + dp(8), safe.right + dp(8), dp(28));
            mBottom.setPadding(safe.left + dp(8), dp(28), safe.right + dp(8), safe.bottom + dp(8));
            mPanelLayer.setPadding(safe.left + dp(8), safe.top, safe.right + dp(8), safe.bottom);
            return insets;
        });
    }

    private void buildTop(Context c) {
        mTop = new LinearLayout(c);
        mTop.setOrientation(LinearLayout.HORIZONTAL);
        mTop.setGravity(Gravity.CENTER_VERTICAL);
        mTop.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{withAlpha(R.color.projects_obsidian_950, 0.9f), withAlpha(R.color.projects_obsidian_950, 0.78f),
                        withAlpha(R.color.projects_obsidian_950, 0f)}));
        mPipButton = iconButton(c, R.drawable.projects_ic_picture_in_picture, R.string.projects_stream_pip);
        mPipButton.setOnClickListener(v -> enterPip());
        mPipButton.setVisibility(pipAllowed() ? View.VISIBLE : View.GONE);
        mTop.addView(mPipButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout words = new LinearLayout(c);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPaddingRelative(dp(8), 0, dp(8), 0);
        mName = text(16, R.color.projects_bone_100, R.font.figtree_semibold);
        mName.setSingleLine(true);
        mName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ViewCompat.setAccessibilityHeading(mName, true);
        mMeta = text(13, R.color.projects_ash_300, R.font.figtree_regular);
        mMeta.setSingleLine(true);
        mMeta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        words.addView(mName);
        words.addView(mMeta);
        mTop.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mWeakChip = chip(c, getString(R.string.projects_stream_weak));
        mWeakChip.setOnClickListener(v -> SocialUi.toast(this, getString(R.string.projects_stream_weak_hint)));
        mWeakChip.setContentDescription(getString(R.string.projects_stream_weak) + ". " + getString(R.string.projects_stream_weak_hint));
        mWeakChip.setVisibility(View.GONE);
        mTop.addView(mWeakChip, chipLp());
        mZoomChip = chip(c, "");
        // Figtree's own "%" (Tektur's reads as a slanted mark), its figures of one width while the zoom moves.
        mZoomChip.setTypeface(font(R.font.figtree_semibold));
        mZoomChip.setFontFeatureSettings("tnum");
        mZoomChip.setOnClickListener(v -> animateZoom(StreamView.none(fit()[0], fit()[1], mVideo.getWidth(), mVideo.getHeight())));
        mZoomChip.setVisibility(View.GONE);
        mTop.addView(mZoomChip, chipLp());
        mRoot.addView(mTop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
    }

    private void buildBottom(Context c) {
        mBottom = new LinearLayout(c);
        mBottom.setOrientation(LinearLayout.HORIZONTAL);
        mBottom.setGravity(Gravity.CENTER_VERTICAL);
        mBottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{withAlpha(R.color.projects_obsidian_950, 0.9f), withAlpha(R.color.projects_obsidian_950, 0.78f),
                        withAlpha(R.color.projects_obsidian_950, 0f)}));
        mSoundButton = iconButton(c, R.drawable.projects_ic_voice_speaker, R.string.projects_stream_volume);
        mSoundButton.setOnClickListener(v -> togglePanel(Panel.SOUND, v));
        mBottom.addView(mSoundButton, new LinearLayout.LayoutParams(dp(48), dp(48)));
        mQualityButton = iconButton(c, R.drawable.projects_ic_sliders, R.string.projects_stream_quality);
        mQualityButton.setOnClickListener(v -> togglePanel(Panel.QUALITY, v));
        mBottom.addView(mQualityButton, gapLp());
        mRoomBox = new FrameLayout(c);
        ImageButton room = iconButton(c, R.drawable.projects_ic_users, R.string.projects_stream_room);
        room.setOnClickListener(v -> openRoom());
        mRoomBox.addView(room, new FrameLayout.LayoutParams(dp(48), dp(48)));
        mRoomBadge = text(11, R.color.projects_bone_100, R.font.tektur_semibold);
        mRoomBadge.setGravity(Gravity.CENTER);
        mRoomBadge.setMinWidth(dp(18));
        mRoomBadge.setPadding(dp(5), 0, dp(5), 0);
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(9));
        pill.setColor(color(R.color.projects_obsidian_700));
        mRoomBadge.setBackground(pill);
        mRoomBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mRoomBadge.setVisibility(View.GONE);
        mRoomBox.addView(mRoomBadge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18), Gravity.TOP | Gravity.END));
        mBottom.addView(mRoomBox, gapLp());
        ImageButton more = iconButton(c, R.drawable.projects_ic_dots, R.string.projects_stream_more);
        more.setOnClickListener(v -> togglePanel(Panel.MORE, v));
        mBottom.addView(more, gapLp());
        // The rest of the bar holds "İzlemeyi Bırak" at its end: with large text it wraps instead of leaving the screen.
        FrameLayout end = new FrameLayout(c);
        mStopButton = button(c, R.string.projects_stream_stop, false);
        mStopButton.setMaxLines(2);
        mStopButton.setOnClickListener(v -> {
            Haptics.confirm(v);
            finishWatching();
        });
        end.addView(mStopButton, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams endLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        endLp.setMarginStart(dp(8));
        mBottom.addView(end, endLp);
        mRoot.addView(mBottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
    }

    /** The one sentence about mobile data (once per install), under the title. */
    private void buildHint(Context c) {
        mHint = new LinearLayout(c);
        mHint.setOrientation(LinearLayout.VERTICAL);
        mHint.setPadding(dp(16), dp(12), dp(16), dp(8));
        mHint.setBackground(panelPlate());
        mHintText = text(14, R.color.projects_bone_100, R.font.figtree_regular);
        mHintText.setSingleLine(false);
        mHint.addView(mHintText);
        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        mHintAction = textButton(c, getString(R.string.projects_stream_cell_switch));
        mHintAction.setOnClickListener(v -> {
            mPrefs.setStreamQuality(StreamView.SAVER);
            syncLayer();
            showHint(false);
        });
        actions.addView(mHintAction);
        TextView ok = textButton(c, getString(R.string.projects_stream_ok));
        ok.setOnClickListener(v -> showHint(false));
        actions.addView(ok);
        mHint.addView(actions);
        mHint.setVisibility(View.GONE);
        ViewCompat.setAccessibilityLiveRegion(mHintText, ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(dp(420), getResources().getDisplayMetrics().widthPixels - dp(32)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(84);
        mRoot.addView(mHint, lp);
    }

    /** Everything the screen shows from the stay's state. */
    private void render() {
        VoiceState s = mState;
        VoiceState.Person p = s == null ? null : s.person(mPerson);
        VoiceState.Watch w = mWatch;
        String name = p != null ? VoiceUi.nameOf(this, p) : VoiceUi.nameOf(this, mPerson);
        // The state comes with every speaking ring: views change only when their words do.
        same(mName, name);
        String kind = getString("game".equals(w != null ? w.src : p != null ? p.shareSrc : null) ? R.string.projects_voice_share_game
                : "window".equals(w != null ? w.src : p != null ? p.shareSrc : null) ? R.string.projects_voice_share_window
                : R.string.projects_voice_share_screen);
        int watchers = w != null ? w.watchers : p != null ? p.shareWatchers : 0;
        same(mMeta, watchers > 0 ? getResources().getQuantityString(R.plurals.projects_voice_share_watchers, watchers, kind, watchers)
                : getString(R.string.projects_voice_share_nobody, kind));

        // The room's sheet: unread messages of the room chat.
        int unread = s == null ? 0 : s.chatUnread;
        mRoomBadge.setVisibility(unread > 0 && !mInPip ? View.VISIBLE : View.GONE);
        same(mRoomBadge, String.valueOf(Math.min(99, unread)));
        View roomButton = mRoomBox.getChildAt(0);
        roomButton.setContentDescription(unread > 0 ? getResources().getQuantityString(R.plurals.projects_stream_room_unread, unread, unread)
                : getString(R.string.projects_stream_room));

        boolean muted = w != null && w.muted;
        mSoundButton.setImageResource(muted ? R.drawable.projects_ic_speaker_slash : R.drawable.projects_ic_voice_speaker);
        mSoundButton.setContentDescription(getString(R.string.projects_stream_volume) + ", "
                + (muted ? getString(R.string.projects_stream_say_muted) : percent(w != null ? w.volume : StreamSound.VOLUME_DEFAULT)));

        // "Düşük Kalite" while the weak hold keeps the small picture ("Otomatik" only: the other choices ignore it).
        boolean weak = w != null && w.live() && mWeakHold.held()
                && StreamView.AUTO.equals(StreamView.quality(mPrefs.streamQuality()));
        mWeakChip.setVisibility(weak && !mInPip ? View.VISIBLE : View.GONE);
        mZoomChip.setVisibility(mZoom.zoomed() && !mInPip ? View.VISIBLE : View.GONE);
        if (mZoom.zoomed()) {
            String value = percent(mZoom.percent());
            mZoomChip.setText(value);
            mZoomChip.setContentDescription(getString(R.string.projects_stream_zoom_reset, value));
        }

        // The middle: what holds the picture back, or the question.
        String st = w != null ? w.st : p != null ? p.shareSt : null;
        boolean hold = "hold".equals(st);
        boolean min = "min".equals(st);
        boolean playing = w != null && w.live() && w.playing && mFrameSeen;
        if (mEnding) return;
        if (w != null && w.asking) {
            center(getString(R.string.projects_stream_still_ask), null, R.string.projects_stream_still_yes, v -> {
                mLink.watchStill();
                mStillSentAt = SystemClock.elapsedRealtime();
                Haptics.confirm(v);
            });
            // The picture goes on behind the question, dimmed: the words stay readable over any picture.
            veil(0.72f);
        } else if (hold) {
            center(getString(R.string.projects_voice_share_paused), mPerson, 0, null);
            veil(1f);
        } else if (min) {
            center(getString(R.string.projects_voice_share_minimized), null, 0, null);
            veil(0.72f);
        } else if (!playing) {
            center(getString(R.string.projects_stream_connecting), null, 0, null);
            veil(0f);
        } else {
            center(null, null, 0, null);
            veil(0f);
        }
        mPoster.setVisibility(playing || hold ? View.GONE : View.VISIBLE);
        if (!playing) poster(p);
        if (playing) maybeHint();
        keepHide();
    }

    /** The middle's words ({@code null}: none), the streamer's face ({@code face}), and a button ({@code button}: 0 none). */
    private void center(@Nullable String words, @Nullable String face, int button, @Nullable View.OnClickListener action) {
        boolean small = mInPip;
        mCenter.setVisibility(words == null ? View.GONE : View.VISIBLE);
        if (words == null) return;
        mCenterText.setTextSize(small ? 12 : 15);
        if (!words.contentEquals(mCenterText.getText())) mCenterText.setText(words);
        if (face != null && !small) {
            mCenterFace.setProfile(VoiceFace.profileOf(this, face, mState == null ? null : mState.person(face)));
            mCenterFace.setVisibility(View.VISIBLE);
        } else {
            mCenterFace.setVisibility(View.GONE);
        }
        if (button != 0 && !small) {
            mCenterButton.setText(button);
            mCenterButton.setOnClickListener(action);
            mCenterButton.setVisibility(View.VISIBLE);
        } else {
            mCenterButton.setVisibility(View.GONE);
        }
    }

    /** The veil over the picture: none, dimmed, or the whole of it. */
    private void veil(float alpha) {
        mVeil.setVisibility(alpha > 0 ? View.VISIBLE : View.GONE);
        mVeil.setAlpha(alpha);
    }

    /** The share's last small picture under a stream that is not playing yet (asked only when its time moved). */
    private void poster(@Nullable VoiceState.Person p) {
        VoiceState s = mState;
        if (p == null || s == null || p.shareTh <= 0 || p.shareTh == mPosterTh || p.shareN != mN) return;
        final long th = p.shareTh;
        mPosterTh = th;
        VoiceHub hub = VoiceHub.instance();
        if (hub == null) return;
        hub.thumb(s.room, mPerson, mN, th, picture -> {
            if (picture != null && mPosterTh == th && !isFinishing()) mPoster.setImageBitmap(picture);
        });
    }

    // ---- TalkBack ----

    private boolean talkBack() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        return am != null && am.isEnabled() && am.isTouchExplorationEnabled();
    }

    /** The stream's state in one polite sentence, once per change (started, paused, minimized, back). */
    private void hear() {
        VoiceState.Watch w = mWatch;
        if (w == null || mEnding) return;
        String name = VoiceUi.nameOf(this, mPerson);
        String words;
        if ("hold".equals(w.st)) words = getString(R.string.projects_stream_say_hold, name);
        else if ("min".equals(w.st)) words = getString(R.string.projects_stream_say_min, name);
        else if (w.live() && w.playing) words = mHeard == null ? getString(R.string.projects_stream_say_open, name)
                : getString(R.string.projects_stream_say_resume, name);
        else return;
        if (words.equals(mHeard)) return;
        boolean first = mHeard == null;
        mHeard = words;
        // "Yayın sürüyor" only after a pause or a minimized window, never as the first word.
        if (first && !(w.live() && w.playing)) return;
        if (talkBack()) mRoot.announceForAccessibility(words);
    }

    // ---- the watch's last word ----

    private void notice(String code) {
        // The stream is over: the screen may sleep again (with TalkBack this word stays until it is closed).
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int words;
        long stay = NOTICE_MS;
        switch (code) {
            case "ended":
                words = R.string.projects_stream_ended;
                stay = ENDED_MS;
                break;
            case "stopped":
                words = R.string.projects_stream_stopped;
                break;
            case "watch_full":
                words = R.string.projects_stream_full;
                break;
            case "share_off":
            case "budget":
                words = R.string.projects_stream_off;
                break;
            case "gone":
            case "not_found":
                words = R.string.projects_stream_gone;
                break;
            case "failed":
            default:
                words = R.string.projects_stream_failed;
                break;
        }
        mEnding = true;
        closePanel();
        showHint(false);
        center(getString(words), null, mInPip ? 0 : R.string.projects_stream_ok, v -> finishWatching());
        veil(0.72f);
        setControls(false, true);
        if ("ended".equals(code) && talkBack()) {
            mRoot.announceForAccessibility(getString(R.string.projects_stream_say_end, VoiceUi.nameOf(this, mPerson)));
        }
        mMain.removeCallbacks(mFinish);
        // TalkBack users close it themselves (the words stay until then).
        if (!talkBack() || mInPip) mMain.postDelayed(mFinish, stay);
    }

    // ---- controls ----

    private void setControls(boolean show, boolean animate) {
        if (mInPip) show = false;
        mControls = show;
        cancelHide();
        float to = show ? 1f : 0f;
        for (View bar : new View[]{mTop, mBottom}) {
            bar.animate().cancel();
            if (show) bar.setVisibility(View.VISIBLE);
            if (!animate || !Motion.enabled(this)) {
                bar.setAlpha(to);
                bar.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
                continue;
            }
            // A fade only (no slide): interruptible, from wherever it is.
            bar.animate().alpha(to).setDuration(show ? 180 : 130).setInterpolator(Motion.EASE_OUT)
                    .withEndAction(() -> {
                        if (!mControls) bar.setVisibility(View.INVISIBLE);
                    }).start();
        }
        if (show) scheduleHide();
    }

    /** The controls may fade: never under TalkBack, with a panel or the hint open, or while nothing plays. */
    private boolean mayHide() {
        VoiceState.Watch w = mWatch;
        boolean playing = w != null && w.live() && w.playing && mFrameSeen && w.st == null && !w.asking;
        return mControls && mPanel == null && !talkBack() && playing && !mEnding && mHint.getVisibility() != View.VISIBLE;
    }

    /** A touch or the player's own change: the controls fade 3 s from now. */
    private void scheduleHide() {
        cancelHide();
        keepHide();
    }

    /** A new state: a running count goes on; it starts when the controls may fade and stops when they may not. */
    private void keepHide() {
        if (!mayHide()) {
            cancelHide();
        } else if (!mHidePending) {
            mHidePending = true;
            mMain.postDelayed(mHideControls, CONTROLS_MS);
        }
    }

    private void cancelHide() {
        mMain.removeCallbacks(mHideControls);
        mHidePending = false;
    }

    /** A touch anywhere on the screen: the controls stay a while longer, "Hâlâ izliyor musun?" counts from here. */
    private void touched() {
        stillHere();
        if (mControls) scheduleHide();
    }

    /** The player is here (a touch of this screen or of the room's sheet over it): "Hâlâ izliyor musun?" counts from now. */
    void stillHere() {
        long now = SystemClock.elapsedRealtime();
        if (mStillSentAt == 0 || now - mStillSentAt >= STILL_GAP_MS) {
            mStillSentAt = now;
            mLink.watchStill();
        }
    }

    private void hideSystemBars() {
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        bars.hide(WindowInsetsCompat.Type.systemBars());
    }

    // ---- panels ----

    private enum Panel { SOUND, QUALITY, MORE }

    @Nullable private Panel mPanelKind;

    private void togglePanel(Panel kind, View from) {
        if (mPanel != null && mPanelKind == kind) {
            closePanel();
            return;
        }
        closePanel();
        View content;
        switch (kind) {
            case SOUND:
                content = soundPanel();
                break;
            case QUALITY:
                content = qualityPanel();
                break;
            case MORE:
            default:
                content = morePanel();
                break;
        }
        content.setBackground(panelPlate());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(dp(320), mRoot.getWidth() - dp(16)),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        // Above the bottom bar, its start over the button that opened it.
        int[] at = new int[2];
        from.getLocationInWindow(at);
        int[] root = new int[2];
        mRoot.getLocationInWindow(root);
        lp.leftMargin = Math.max(0, Math.min(at[0] - root[0] - dp(8), mRoot.getWidth() - lp.width - dp(16)));
        lp.bottomMargin = mBottom.getHeight() - dp(20);
        mPanelLayer.setClickable(true);
        mPanelLayer.setOnClickListener(v -> closePanel());
        mPanelLayer.addView(content, lp);
        mPanel = content;
        mPanelKind = kind;
        content.setClickable(true);
        // From the button that opened it: 160 ms, grown from 96 % (design.md's popover); a fade only without motion.
        content.setPivotX(dp(24));
        content.setPivotY(content.getMeasuredHeight() > 0 ? content.getMeasuredHeight() : dp(160));
        if (Motion.enabled(this)) {
            content.setAlpha(0f);
            content.setScaleX(0.96f);
            content.setScaleY(0.96f);
            content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(Motion.EASE_OUT).start();
        }
        cancelHide();
        content.post(() -> content.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED));
    }

    private void closePanel() {
        View panel = mPanel;
        mPanel = null;
        mPanelKind = null;
        // In this order: setting a listener (even none) makes a view clickable again, and a clickable layer would take
        // every touch meant for the picture.
        mPanelLayer.setOnClickListener(null);
        mPanelLayer.setClickable(false);
        if (panel == null) return;
        if (Motion.enabled(this)) {
            panel.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(110).setInterpolator(Motion.EASE_OUT)
                    .withEndAction(() -> mPanelLayer.removeView(panel)).start();
        } else {
            mPanelLayer.removeView(panel);
        }
        scheduleHide();
    }

    /** "Yayın Sesi": 0–200 % in steps of 5, live while dragging, a mark at 100 %; "Yayını Sessize Al". */
    private View soundPanel() {
        Context c = this;
        LinearLayout box = panelBox(c);
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(14, R.color.projects_bone_100, R.font.figtree_semibold);
        title.setText(R.string.projects_stream_volume);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = text(14, R.color.projects_ash_300, R.font.figtree_semibold);
        value.setFontFeatureSettings("tnum");
        value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.addView(value);
        head.setPadding(dp(4), 0, dp(4), 0);
        box.addView(head);

        VoiceState.Watch w = mWatch;
        final int start = w != null ? w.volume : StreamSound.VOLUME_DEFAULT;
        final boolean[] muted = {w != null && w.muted};
        value.setText(percent(start));
        FrameLayout track = new FrameLayout(c);
        // The 100 % mark under the bar (the desktop's notch).
        View notch = new View(c);
        notch.setBackgroundColor(color(R.color.projects_ash_400));
        notch.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        track.addView(notch, new FrameLayout.LayoutParams(dp(2), dp(12), Gravity.CENTER));
        final TickSeekBar bar = new TickSeekBar(c);
        bar.setMax(StreamSound.VOLUME_MAX / VOLUME_STEP);
        bar.setProgress(start / VOLUME_STEP);
        int bone = color(R.color.projects_bone_100);
        bar.setProgressTintList(ColorStateList.valueOf(bone));
        bar.setThumbTintList(ColorStateList.valueOf(bone));
        bar.setContentDescription(getString(R.string.projects_stream_volume));
        ViewCompat.setStateDescription(bar, percent(start));
        track.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48), Gravity.CENTER_VERTICAL));
        box.addView(track, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int volume = progress * VOLUME_STEP;
                value.setText(percent(volume));
                ViewCompat.setStateDescription(seekBar, percent(volume));
                // Live while dragging; a change of the volume brings the sound back (as Discord's slider does).
                if (fromUser) {
                    muted[0] = false;
                    mLink.setStreamSound(mPerson, volume, false);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        final TextView mute = row(c, muted[0] ? R.drawable.projects_ic_voice_speaker : R.drawable.projects_ic_speaker_slash,
                getString(muted[0] ? R.string.projects_stream_unmute : R.string.projects_stream_mute));
        mute.setOnClickListener(v -> {
            Haptics.toggle(v, !muted[0]);
            toggleMute();
            closePanel();
        });
        box.addView(mute);
        return box;
    }

    /** "Kalite": Otomatik / Yüksek / Veri Tasarrufu (for every stream, as the settings' "Yayın Kalitesi"). */
    private View qualityPanel() {
        Context c = this;
        LinearLayout box = panelBox(c);
        TextView title = text(14, R.color.projects_bone_100, R.font.figtree_semibold);
        title.setText(R.string.projects_stream_quality);
        title.setPadding(dp(4), 0, dp(4), dp(4));
        ViewCompat.setAccessibilityHeading(title, true);
        box.addView(title);
        String chosen = mPrefs.streamQuality();
        String[] keys = {StreamView.AUTO, StreamView.HIGH, StreamView.SAVER};
        int[] labels = {R.string.projects_stream_quality_auto, R.string.projects_stream_quality_high, R.string.projects_stream_quality_saver};
        for (int i = 0; i < keys.length; i++) {
            final String key = keys[i];
            boolean on = key.equals(chosen);
            TextView row = row(c, on ? R.drawable.projects_ic_check : 0, getString(labels[i]));
            ViewCompat.setStateDescription(row, null);
            row.setSelected(on);
            ViewCompat.setAccessibilityDelegate(row, new androidx.core.view.AccessibilityDelegateCompat() {
                @Override
                public void onInitializeAccessibilityNodeInfo(View host, androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setCheckable(true);
                    info.setChecked(on);
                }
            });
            row.setOnClickListener(v -> {
                Haptics.toggle(v, true);
                mPrefs.setStreamQuality(key);
                syncLayer();
                closePanel();
            });
            box.addView(row);
        }
        return box;
    }

    /** "Yayın Seçenekleri": "Yayını Rapor Et". */
    private View morePanel() {
        Context c = this;
        LinearLayout box = panelBox(c);
        TextView report = row(c, R.drawable.projects_ic_flag, getString(R.string.projects_stream_report));
        report.setOnClickListener(v -> {
            closePanel();
            report();
        });
        box.addView(report);
        return box;
    }

    private LinearLayout panelBox(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(12), dp(12), dp(8));
        return box;
    }

    // ---- actions ----

    private void toggleMute() {
        VoiceState.Watch w = mWatch;
        int volume = w != null ? w.volume : StreamSound.VOLUME_DEFAULT;
        boolean muted = w == null || !w.muted;
        mLink.setStreamSound(mPerson, volume, muted);
        if (talkBack()) {
            mRoot.announceForAccessibility(getString(muted ? R.string.projects_stream_say_muted : R.string.projects_stream_say_unmuted));
        }
        // Picture-in-picture's button follows the voice process's state when it comes (onVoiceState): built from the
        // state still shown now, it would say the old one.
    }

    /** "Ses Odası": the room's sheet over the stream (the stream goes on behind it). */
    private void openRoom() {
        if (mLink.state() == null) return;
        VoiceSheet.show(this);
    }

    /** The room sheet's "Mesajlar": the stream goes on in its small window while the launcher opens the conversation. */
    void openConversation(String conv) {
        enterPip();
        SocialRoutes.set(SocialRoutes.CHAT, conv);
        SocialRoutes.bringUp(this);
    }

    /** "Yayını Rapor Et": the still the player sees now goes along (60 KB at most), when there is one. */
    private void report() {
        VoiceState s = mState;
        if (s == null) return;
        final String room = s.room;
        final int n = mN;
        final String person = mPerson;
        final SocialModel.Profile profile = VoiceFace.profileOf(this, person, s.person(person));
        Bitmap still = null;
        if (mFrameSeen && mVideo.isAvailable() && mPictureW > 0 && mPictureH > 0) {
            float scale = Math.min(1f, 1280f / mPictureW);
            try {
                still = picture(Math.max(1, Math.round(mPictureW * scale)), Math.max(1, Math.round(mPictureH * scale)));
            } catch (RuntimeException e) {
                still = null;
            }
        }
        final Bitmap frame = still;
        new Thread(() -> {
            String jpeg = frame == null ? null : jpegOf(frame);
            mMain.post(() -> {
                if (isFinishing()) return;
                ReportDialog.share(this, profile, room, n, jpeg, jpeg == null ? null : frame);
            });
        }, "projects-stream-report").start();
    }

    /** The picture as it is on screen at {@code w} × {@code h} (TextureView draws its buffer through no transform here). */
    @Nullable
    private Bitmap picture(int w, int h) {
        Matrix before = mVideo.getTransform(null);
        // The buffer itself, not the zoomed view: what the streamer shows.
        mVideo.setTransform(new Matrix());
        try {
            return mVideo.getBitmap(w, h);
        } finally {
            mVideo.setTransform(before);
        }
    }

    /** The still as a JPEG's base64 of at most 60 KB: the quality steps down, then the width (the desktop's frame.ts). */
    @Nullable
    static String jpegOf(Bitmap picture) {
        int[] widths = {1280, 960, 640, 427};
        int[] qualities = {82, 70, 58, 46, 36, 28};
        for (int width : widths) {
            Bitmap sized = picture.getWidth() <= width ? picture
                    : Bitmap.createScaledBitmap(picture, width, Math.max(1, Math.round(picture.getHeight() * (width / (float) picture.getWidth()))), true);
            for (int quality : qualities) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                if (!sized.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null;
                if (out.size() <= 60 * 1024) return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            }
        }
        return null;
    }

    /** "İzlemeyi Bırak", and every end: the voice process lets go, the screen fades away. */
    private void finishWatching() {
        mMain.removeCallbacks(mFinish);
        stopWatching();
        mEnding = true;
        finish();
        // As it came: at once (no band from the system's slide between tasks).
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(0, 0);
    }

    private void stopWatching() {
        if (mPerson.isEmpty()) return;
        mLink.watchSurface(null);
        mLink.stopWatching(mPerson, mN);
    }

    private void sendDuck() {
        mLink.setDuck(mPrefs.duck(), mPrefs.duckLevel(), mPrefs.duckSelf());
    }

    // ---- the layer ----

    private String wantedLayer() {
        // A small window pushed to the screen's edge shows nothing of the picture: the small layer, whatever the choice.
        if (mInPip && mPipStashed) return StreamView.SMALL;
        int width = Math.round(fit()[0] * mZoom.s);
        return StreamView.layerFor(mPrefs.streamQuality(), mInPip ? Math.min(width, StreamView.SMALL_VIEW_PX - 1) : width,
                mMetered, mWeakHold.held(), mZoom.zoomed());
    }

    /** A weak reading counts unless this screen's own layer switch (moments ago) explains the stall. */
    private boolean weakNow(VoiceState.Watch w) {
        return w.weak && SystemClock.elapsedRealtime() - mLayerAt >= LAYER_SETTLE_MS;
    }

    /** Asks the voice process for the layer the screen wants now (only when it changes). */
    private void syncLayer() {
        mMain.removeCallbacks(mLayerCheck);
        String layer = wantedLayer();
        if (!layer.equals(mLayerSent)) {
            mLayerSent = layer;
            mLayerAt = SystemClock.elapsedRealtime();
            mLink.watchLayer(mPerson, mN, layer);
        }
        // Looked at again when the hold may let go, and when a switch's settling time is over (a weak reading then counts).
        long now = SystemClock.elapsedRealtime();
        long next = mWeakHold.backAt();
        long settled = mLayerAt + LAYER_SETTLE_MS;
        if (settled > now && (next < 0 || settled < next)) next = settled;
        if (next >= 0) mMain.postDelayed(mLayerCheck, Math.max(0, next - now) + 50);
    }

    private void readMetered() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        mMetered = cm != null && cm.isActiveNetworkMetered();
    }

    @SuppressLint("MissingPermission")
    private void listenNetwork(boolean on) {
        if (Build.VERSION.SDK_INT < 24) return;
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        if (on && mNetCallback == null) {
            ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onCapabilitiesChanged(Network network, android.net.NetworkCapabilities caps) {
                    boolean metered = !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
                    mMain.post(() -> {
                        if (metered == mMetered) return;
                        mMetered = metered;
                        syncLayer();
                    });
                }
            };
            try {
                cm.registerDefaultNetworkCallback(callback);
                mNetCallback = callback;
            } catch (RuntimeException ignored) {
                // Too many callbacks in this app: the layer follows the network as it was.
            }
        } else if (!on && mNetCallback != null) {
            try {
                cm.unregisterNetworkCallback((ConnectivityManager.NetworkCallback) mNetCallback);
            } catch (RuntimeException ignored) {
                // Gone already.
            }
            mNetCallback = null;
        }
    }

    /** The sentence about mobile data, once: on "Otomatik" what happens, on "Yüksek" a way to save. */
    private void maybeHint() {
        if (mHintShown || !mMetered || mInPip || mEnding || mPrefs.cellHintShown()) return;
        String quality = mPrefs.streamQuality();
        if (StreamView.SAVER.equals(quality)) return;
        mHintShown = true;
        mPrefs.setCellHintShown();
        boolean high = StreamView.HIGH.equals(quality);
        mHintText.setText(high ? R.string.projects_stream_cell_high : R.string.projects_stream_cell_auto);
        mHintAction.setVisibility(high ? View.VISIBLE : View.GONE);
        showHint(true);
    }

    private void showHint(boolean show) {
        mMain.removeCallbacks(mHideHint);
        if (show) {
            mHint.setAlpha(0f);
            mHint.setVisibility(View.VISIBLE);
            mHint.animate().alpha(1f).setDuration(Motion.enabled(this) ? 180 : 0).setInterpolator(Motion.EASE_OUT).start();
            if (!talkBack()) mMain.postDelayed(mHideHint, HINT_MS);
            setControls(true, true);
        } else if (mHint.getVisibility() == View.VISIBLE) {
            mHint.animate().alpha(0f).setDuration(Motion.enabled(this) ? 130 : 0).setInterpolator(Motion.EASE_OUT)
                    .withEndAction(() -> {
                        mHint.setVisibility(View.GONE);
                        scheduleHide();
                    }).start();
        }
    }

    // ---- the picture's place ----

    /** The picture's fitted size in the view (16:9 until its size is known). */
    private float[] fit() {
        return StreamView.fit(mPictureW, mPictureH, mVideo.getWidth(), mVideo.getHeight());
    }

    private void applyBufferSize() {
        SurfaceTexture texture = mTexture;
        if (texture != null && mPictureW > 0 && mPictureH > 0) texture.setDefaultBufferSize(mPictureW, mPictureH);
    }

    /** The picture where it belongs after a size change (rotation, picture-in-picture): fitted, zoom kept in bounds. */
    private void layoutPicture() {
        float[] fit = fit();
        int width = mVideo.getWidth();
        int height = mVideo.getHeight();
        if (width <= 0 || height <= 0) return;
        mZoom = mZoom.zoomed() && !mInPip ? StreamView.clamp(mZoom, fit[0], fit[1], width, height)
                : StreamView.none(fit[0], fit[1], width, height);
        applyZoom();
        // The poster takes the picture's place too.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mPoster.getLayoutParams();
        lp.width = Math.round(fit[0]);
        lp.height = Math.round(fit[1]);
        lp.gravity = Gravity.CENTER;
        mPoster.setLayoutParams(lp);
    }

    private void zoomBy(float factor, float px, float py) {
        if (mZoomAnim != null) mZoomAnim.cancel();
        float[] fit = fit();
        setZoom(StreamView.zoomAt(mZoom, factor, px, py, fit[0], fit[1], mVideo.getWidth(), mVideo.getHeight()));
    }

    private void setZoom(StreamView.Zoom zoom) {
        boolean wasZoomed = mZoom.zoomed();
        mZoom = zoom;
        applyZoom();
        if (wasZoomed != zoom.zoomed()) {
            render();
            syncLayer();
        } else if (zoom.zoomed()) {
            String value = percent(zoom.percent());
            mZoomChip.setText(value);
            mZoomChip.setContentDescription(getString(R.string.projects_stream_zoom_reset, value));
        }
    }

    /** A double tap's zoom glides (220 ms); with animations off it jumps. */
    private void animateZoom(final StreamView.Zoom to) {
        if (mZoomAnim != null) mZoomAnim.cancel();
        if (!Motion.enabled(this)) {
            setZoom(to);
            return;
        }
        final StreamView.Zoom from = mZoom;
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(220);
        anim.setInterpolator(Motion.EASE_OUT);
        anim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            setZoom(new StreamView.Zoom(from.s + (to.s - from.s) * t, from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t));
        });
        mZoomAnim = anim;
        anim.start();
    }

    /** The TextureView's content: its buffer stretched to the view by default, put back to the picture's place. */
    private void applyZoom() {
        float width = mVideo.getWidth();
        float height = mVideo.getHeight();
        if (width <= 0 || height <= 0) return;
        float[] fit = fit();
        Matrix m = new Matrix();
        m.setScale(mZoom.s * fit[0] / width, mZoom.s * fit[1] / height);
        m.postTranslate(mZoom.x, mZoom.y);
        mVideo.setTransform(m);
    }

    // ---- picture-in-picture ----

    private boolean pipAllowed() {
        return Build.VERSION.SDK_INT >= 26 && getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    /** Into picture-in-picture (false: Android has none here, or the player turned it off for the app). */
    private boolean enterPip() {
        if (!pipAllowed() || mEnding || mInPip) return mInPip;
        try {
            closePanel();
            return enterPictureInPictureMode(pipParams());
        } catch (RuntimeException e) {
            return false;
        }
    }

    @SuppressLint("NewApi")
    private void updatePipParams() {
        try {
            setPictureInPictureParams(pipParams());
        } catch (RuntimeException ignored) {
            // Turned off for the app: nothing to update.
        }
    }

    @SuppressLint("NewApi")
    private PictureInPictureParams pipParams() {
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder();
        int w = mPictureW > 0 ? mPictureW : 16;
        int h = mPictureH > 0 ? mPictureH : 9;
        // Android takes ratios from 1:2.39 to 2.39:1 (both ends included, compared as floats): the picture's own when it
        // fits, otherwise just inside the nearer end (a rounded end can fall outside and throw).
        Rational ratio = new Rational(w, h);
        if (ratio.floatValue() > 2.389f) ratio = new Rational(2389, 1000);
        else if (ratio.floatValue() < 1000f / 2389f) ratio = new Rational(1000, 2389);
        b.setAspectRatio(ratio);
        float[] fit = fit();
        if (fit[0] > 0) {
            int[] at = new int[2];
            mVideo.getLocationInWindow(at);
            int left = at[0] + Math.round((mVideo.getWidth() - fit[0]) / 2f);
            int top = at[1] + Math.round((mVideo.getHeight() - fit[1]) / 2f);
            b.setSourceRectHint(new Rect(left, top, left + Math.round(fit[0]), top + Math.round(fit[1])));
        }
        VoiceState.Watch w0 = mWatch;
        boolean muted = w0 != null && w0.muted;
        Intent mute = new Intent(ACTION_PIP_MUTE).setPackage(getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(this, 1, mute, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = getString(muted ? R.string.projects_stream_pip_unmute : R.string.projects_stream_pip_mute);
        List<RemoteAction> actions = new ArrayList<>();
        // The icon says the sound's state, as the stream screen's own button does; its words say the action.
        actions.add(new RemoteAction(Icon.createWithResource(this, muted ? R.drawable.projects_ic_speaker_slash
                : R.drawable.projects_ic_voice_speaker), title, title, pi));
        b.setActions(actions);
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(!mEnding);
        return b.build();
    }

    // ---- small builders ----

    private int dp(int value) {
        return Math.round(value * mDp);
    }

    private int color(int id) {
        return ContextCompat.getColor(this, id);
    }

    private int withAlpha(int id, float alpha) {
        int c = color(id);
        return (Math.round(alpha * 255) << 24) | (c & 0x00FFFFFF);
    }

    @Nullable
    private android.graphics.Typeface font(int id) {
        try {
            return ResourcesCompat.getFont(this, id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String percent(int value) {
        return getString(R.string.projects_voice_percent, value);
    }

    /** Sets {@code words} only when they changed (no layout pass for the same words). */
    private static void same(TextView view, CharSequence words) {
        if (!android.text.TextUtils.equals(view.getText(), words)) view.setText(words);
    }

    private TextView text(int sp, int colorId, int fontId) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color(colorId));
        android.graphics.Typeface face = font(fontId);
        if (face != null) t.setTypeface(face);
        return t;
    }

    private ImageButton iconButton(Context c, int icon, int description) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(color(R.color.projects_bone_100)));
        b.setContentDescription(getString(description));
        ShapeDrawable mask = new ShapeDrawable(new OvalShape());
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(color(R.color.projects_ripple)), null, mask));
        b.setScaleType(ImageView.ScaleType.CENTER);
        Press.attach(b, Press.Style.ICON);
        return b;
    }

    /** A plain button: {@code strong}, bone on a dark plate (the question's "Evet"); otherwise the quiet plate. */
    private TextView button(Context c, int label, boolean strong) {
        TextView b = text(15, strong ? R.color.projects_obsidian_950 : R.color.projects_bone_100, R.font.figtree_semibold);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(48));
        b.setMinWidth(dp(96));
        b.setPadding(dp(18), 0, dp(18), 0);
        b.setBackground(new ChamferDrawable(color(strong ? R.color.projects_bone_100 : R.color.projects_obsidian_800), dp(8), null));
        b.setClickable(true);
        b.setFocusable(true);
        Press.attach(b, Press.Style.BUTTON);
        return b;
    }

    private TextView textButton(Context c, String label) {
        TextView b = text(14, R.color.projects_bone_100, R.font.figtree_semibold);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(48));
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setBackgroundResource(R.drawable.projects_row_ripple);
        b.setClickable(true);
        b.setFocusable(true);
        Press.attach(b, Press.Style.BUTTON);
        return b;
    }

    private TextView chip(Context c, String label) {
        TextView t = text(12, R.color.projects_bone_100, R.font.figtree_semibold);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(32));
        t.setPadding(dp(10), 0, dp(10), 0);
        t.setBackground(new ChamferDrawable(withAlpha(R.color.projects_obsidian_800, 0.92f), dp(6), null));
        t.setClickable(true);
        t.setFocusable(true);
        Press.attach(t, Press.Style.BUTTON);
        return t;
    }

    private LinearLayout.LayoutParams chipLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        lp.setMarginStart(dp(8));
        return lp;
    }

    /** A panel row (48 dp): an icon (0: none, its room kept) and words. */
    private TextView row(Context c, int icon, String label) {
        TextView t = text(15, R.color.projects_bone_100, R.font.figtree_regular);
        t.setText(label);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setMinHeight(dp(48));
        t.setPadding(dp(4), 0, dp(8), 0);
        t.setCompoundDrawablePadding(dp(12));
        if (icon != 0) {
            android.graphics.drawable.Drawable d = ContextCompat.getDrawable(c, icon);
            if (d != null) {
                d = d.mutate();
                d.setBounds(0, 0, dp(20), dp(20));
                d.setTint(color(R.color.projects_bone_100));
            }
            t.setCompoundDrawablesRelative(d, null, null, null);
        } else {
            android.graphics.drawable.ColorDrawable space = new android.graphics.drawable.ColorDrawable(0);
            space.setBounds(0, 0, dp(20), dp(20));
            t.setCompoundDrawablesRelative(space, null, null, null);
        }
        t.setBackgroundResource(R.drawable.projects_row_ripple);
        t.setClickable(true);
        t.setFocusable(true);
        Press.attach(t, Press.Style.ROW);
        return t;
    }

    /** The panels' plate: obsidian with its strong border, 10 dp corners (design.md's container). */
    private GradientDrawable panelPlate() {
        GradientDrawable plate = new GradientDrawable();
        plate.setCornerRadius(dp(10));
        plate.setColor(withAlpha(R.color.projects_obsidian_900, 0.96f));
        plate.setStroke(Math.max(1, dp(1)), color(R.color.projects_obsidian_700));
        return plate;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams gapLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.setMarginStart(dp(4));
        return lp;
    }
}
