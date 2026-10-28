package gg.projects.mobile.cloud.movement;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudStatus;
import gg.projects.mobile.cloud.ui.CloudScreen;
import gg.projects.mobile.cloud.ui.CloudSettingsFragment;
import gg.projects.mobile.cloud.ui.CloudUi;
import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.FontSpan;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.PixelSprites;
import gg.projects.mobile.ui.Press;

/**
 * "Hareket" of a cloud account (services/cloud-afk README "Movement"; design.md §6 "Cloud movement": the launcher's parts
 * and words, a game pad, not a form). Where it stands and where it looks; under it one reserved line that is never empty
 * (a refusal for 4 s, else what it is doing, else how the last walk ended, else that it stands), its words cross-fading
 * in place, with "Dur" fading in at its end while it moves; the pad in the keys' own shape, relative to where it looks
 * (İleri over Sola, Geri, Sağa; "Eğil", a key that stays pressed in while it sneaks, beside the wide "Zıpla"; a tap is one
 * block, held eight now and every 1.5 s and a stop when the finger lifts; every send vibrates); a walk to coordinates;
 * one row per account the service names (never a typed name). The stream runs only while the panel is visible.
 */
public final class CloudMovementFragment extends CloudScreen implements CloudService.MovementListener, CloudService.LiveListener {
    public static final String TAG = "ProjectsCloudMovementFragment";
    /** A held direction asks for 8 blocks again this often (the contract's rhythm). */
    private static final long HOLD_REPEAT_MS = 1500;
    private static final long REFUSAL_MS = 4000;
    /** A trade holds every control but "Dur" at once: one even dim, as far as a disabled control (the launcher's 45 %). */
    private static final float LOCKED_ALPHA = 0.45f;
    /** The walks to an own account that is not in game wait, dimmed. */
    private static final float WAITING_ALPHA = 0.5f;

    @Nullable
    private CloudService.LiveHandle mHandle;
    @Nullable
    private MoveFrames.State mState;
    @Nullable
    private MoveFrames.Pos mPos;
    @Nullable
    private CloudService.Live mLive;
    /** The name the last approach or follow went to (for "… yakında değil."). */
    @Nullable
    private String mAskedName;
    /** A direction is held: its release sends a stop. */
    private boolean mHolding;
    @Nullable
    private View mHeldKey;
    private boolean mBusy;
    private String mShape = "";
    /** "X 128", "Y 64", "Z -41": the game's own form, each as wide as three digits at least. */
    @Nullable
    private TextView[] mFigures;
    /** The dimension and "Yön: Kuzey" at the position line's end. */
    @Nullable
    private TextView mSide;
    @Nullable
    private FadeLine mLine;
    @Nullable
    private Button mStop;
    private boolean mStopShown;
    @Nullable
    private View mSneakKey;
    /** The pad, "Koordinata git" and the walks: what a trade holds, as one block. */
    @Nullable
    private InertBlock mControlBlock;
    private boolean mLocked;
    private final List<WalkRow> mWalks = new ArrayList<>();
    /** A refusal holds the line for a moment. */
    @Nullable
    private String mRefusal;
    /** The walk the machine just refused ("approach:kaan_07"): its button keeps a flare edge while the line says why. */
    @Nullable
    private String mRefusedWalk;
    /** "Koordinata git"'s typed target, kept between openings of its sheet ("" is where it stands). */
    private final String[] mTarget = {"", "", ""};
    /** The last walk to coordinates seen in the stream (one started elsewhere fills empty fields once). */
    @Nullable
    private String mSeenGoto;
    private final Runnable mRefusalEnd = () -> {
        mRefusal = null;
        mRefusedWalk = null;
        if (alive()) update();
    };

    /** "Eğil" reads as a switch, on while it sneaks. */
    private static final View.AccessibilityDelegate TOGGLE = new View.AccessibilityDelegate() {
        @Override
        public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName(ToggleButton.class.getName());
            info.setCheckable(true);
            info.setChecked(host.isActivated());
        }
    };

    @Override
    protected void created(View view) {
        mHeader.setTitle(getString(R.string.projects_feat_movement));
    }

    @Override
    protected void started() {
        CloudModel.Account a = account();
        if (a == null) return;
        mHeader.setCaption(a.name);
        if (mHandle == null) mHandle = mService.watch(a.id, false, this, null, this);
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        // Leaving with a direction held: it stops, as a lifted finger would.
        if (mHolding && mHandle != null) mHandle.moveOp(MoveOp.stop(), e -> {});
        mHolding = false;
        if (mHandle != null) mHandle.close();
        mHandle = null;
        mState = null;
        mShape = "";
        mRefusal = null;
        mRefusedWalk = null;
        View view = getView();
        if (view != null) view.removeCallbacks(mRefusalEnd);
    }

    @Override
    protected void render() {
        CloudModel.Account a = account();
        if (mContent == null) return;
        CloudModel.MovementFeatures move = a == null ? null : a.features.movement;
        // What holds it: the machine's word, else the account's own (not in game: no need to wait for the machine).
        String shape;
        if (a == null) shape = "loading";
        else if (mState != null && !mState.ok) shape = "off:" + mState.reason + (mBusy ? ":busy" : "");
        else if (mState == null && !CloudStatus.inGame(a)) shape = "off:not_running";
        else if (mState == null) shape = mLive == CloudService.Live.LOST || mLive == CloudService.Live.HOST_OFFLINE ? "away:" + mLive : "loading";
        else if (move == null) shape = "away:" + mLive;
        else shape = "ok|" + move.main + "|" + move.own;
        if (!shape.equals(mShape)) {
            mShape = shape;
            if ("loading".equals(shape)) {
                LinearLayout content = clear();
                forget();
                if (content != null) content.addView(CloudUi.loading(ctx()));
            } else if (shape.startsWith("away:")) {
                // Our machine away is said as such (never the Minecraft server's word); a lost socket, the account.
                quiet(getString(mLive == CloudService.Live.HOST_OFFLINE ? R.string.projects_feat_move_machine_away
                        : R.string.projects_feat_inv_unreachable), null);
            } else if (shape.startsWith("off:")) {
                buildOff(a, mState != null ? mState.reason : "not_running");
            } else {
                build(a);
            }
        }
        update();
    }

    /** The panel's views are gone (a quiet state or a redraw replaces them). */
    private void forget() {
        mFigures = null;
        mSide = null;
        mLine = null;
        mStop = null;
        mStopShown = false;
        mSneakKey = null;
        mControlBlock = null;
        mLocked = false;
        mHeldKey = null;
        mWalks.clear();
    }

    /** It cannot move now: the quiet empty state (the boot), and where the player can do something about it, the one thing. */
    private void buildOff(final CloudModel.Account a, @Nullable String reason) {
        Button action = null;
        if ("setting".equals(reason)) {
            action = CloudUi.button(ctx(), getString(R.string.projects_cloud_open_settings), true, v -> openFor(CloudSettingsFragment.class, a.id));
        } else if ("restart".equals(reason) && reconnectable(a)) {
            action = CloudUi.button(ctx(), getString(R.string.projects_feat_inv_reconnect_now), false, this::reconnect);
            action.setEnabled(!mBusy);
        }
        quiet(Movement.off(ctx(), reason), action);
    }

    private void quiet(String words, @Nullable View action) {
        LinearLayout content = clear();
        forget();
        if (content == null) return;
        Context c = ctx();
        LinearLayout empty = CloudUi.empty(c, PixelSprites.Sprite.MOVEMENT, words);
        View sentence = empty.getChildAt(1);
        if (sentence != null) sentence.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (action != null) empty.addView(centered(c, action));
        content.addView(empty);
    }

    private static boolean reconnectable(CloudModel.Account a) {
        return CloudStatus.inGame(a) || "connecting".equals(a.live.state) || "waiting".equals(a.live.state);
    }

    private static View centered(Context c, View child) {
        FrameLayout box = new FrameLayout(c);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = CloudUi.dp(c, 18);
        box.addView(child, lp);
        box.setLayoutParams(CloudUi.matchWrap());
        return box;
    }

    private void build(final CloudModel.Account a) {
        LinearLayout content = clear();
        forget();
        if (content == null) return;
        final Context c = ctx();
        final CloudModel.MovementFeatures move = a.features.movement;

        // Where it stands: one quiet line in the game's own form, no tile ("X 128  Y 64  Z -41"), the dimension and where
        // it looks at its end on the same baseline (they take a second line there only when the screen is too narrow).
        LinearLayout where = new LinearLayout(c);
        where.setOrientation(LinearLayout.HORIZONTAL);
        where.setBaselineAligned(true);
        where.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 20), CloudUi.dp(c, 8), CloudUi.dp(c, 2));
        mFigures = new TextView[3];
        for (int i = 0; i < 3; i++) {
            TextView figure = CloudUi.figure(c, "", 17, R.color.projects_bone_50);
            figure.setSingleLine(true);
            // At least three digits wide, so the line keeps its place as the numbers change.
            figure.setMinWidth(axisWidth(c, figure));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(CloudUi.dp(c, 14));
            where.addView(figure, lp);
            mFigures[i] = figure;
        }
        TextView side = CloudUi.text(c, "", 12.5f, R.color.projects_ash_300, false);
        side.setGravity(Gravity.END);
        side.setMaxLines(2);
        LinearLayout.LayoutParams sideLp = CloudUi.weight(1f);
        sideLp.setMarginStart(CloudUi.dp(c, 12));
        where.addView(side, sideLp);
        mSide = side;
        where.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        content.addView(where, CloudUi.matchWrap());

        // One reserved line, nothing below moves: a refusal, what it is doing, how the last walk ended or that it stands;
        // "Dur" (a hand, not the account's square "Durdur") fades in at its end while it moves.
        LinearLayout doing = new LinearLayout(c);
        doing.setOrientation(LinearLayout.HORIZONTAL);
        doing.setGravity(Gravity.CENTER_VERTICAL);
        doing.setMinimumHeight(CloudUi.dp(c, 60));
        doing.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 6), 0, CloudUi.dp(c, 6));
        FadeLine line = new FadeLine(c);
        LinearLayout.LayoutParams lineLp = CloudUi.weight(1f);
        lineLp.setMarginEnd(CloudUi.dp(c, 12));
        doing.addView(line.view, lineLp);
        mLine = line;
        Button stop = CloudUi.button(c, getString(R.string.projects_feat_move_stop), false, v -> {
            if (mStopShown) send(v, MoveOp.stop(), null);
        });
        stop.setCompoundDrawablesRelative(CloudUi.tinted(c, R.drawable.projects_ic_hand_palm, R.color.projects_bone_100, 18), null, null, null);
        stop.setCompoundDrawablePadding(CloudUi.dp(c, 8));
        stop.setAlpha(0f);
        stop.setVisibility(View.INVISIBLE);
        stop.setClickable(false);
        stop.setFocusable(false);
        stop.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        doing.addView(stop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mStop = stop;
        content.addView(doing, CloudUi.matchWrap());

        // Everything a trade holds, in one block that dims evenly: the pad, the walk to coordinates, the walks.
        InertBlock block = new InertBlock(c);
        block.setOrientation(LinearLayout.VERTICAL);
        mControlBlock = block;
        block.addView(pad(c), CloudUi.matchWrap());
        block.addView(CloudUi.gap(c, 22));
        LinearLayout go = CloudUi.group(c);
        CloudUi.Row coords = CloudUi.actionRow(c, R.drawable.projects_ic_crosshair, getString(R.string.projects_feat_move_goto), v -> coordinates());
        go.addView(coords.view);
        block.addView(go);
        // Under "Hesapların" (the launcher's heading), one row per account the service names: the main one, then the
        // player's others on this server.
        final String main = move == null ? null : move.main;
        List<String> own = move == null ? new ArrayList<>() : move.own;
        if (main != null || !own.isEmpty()) {
            block.addView(CloudUi.section(c, getString(R.string.projects_feat_move_walks_title)));
            LinearLayout walks = CloudUi.group(c);
            if (main != null) {
                walks.addView(walkRow(c, main, false, getString(R.string.projects_feat_move_go_main), getString(R.string.projects_feat_move_follow_main)));
            }
            for (String name : own) {
                if (walks.getChildCount() > 0) walks.addView(CloudUi.textDivider(c));
                walks.addView(walkRow(c, name, true, getString(R.string.projects_feat_move_go_named, name),
                        getString(R.string.projects_feat_move_follow_named, name)));
            }
            block.addView(walks);
        }
        content.addView(block, CloudUi.matchWrap());
        content.addView(CloudUi.gap(c, 16));
    }

    /**
     * One account the service names: its name (the main one with "Ana hesabın" under it, another not in game with "Oyunda
     * değil"), then its two walks in the contract's short words; TalkBack reads their long sentences.
     */
    private View walkRow(Context c, final String name, boolean own, String goLabel, String followLabel) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(CloudUi.dp(c, 64));
        row.setPaddingRelative(CloudUi.dp(c, 16), CloudUi.dp(c, 8), CloudUi.dp(c, 10), CloudUi.dp(c, 8));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = CloudUi.text(c, name, 15.5f, R.color.projects_bone_100, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        TextView note = CloudUi.text(c, own ? "" : getString(R.string.projects_feat_move_walk_main), 13, R.color.projects_ash_400, false);
        note.setMaxLines(1);
        note.setEllipsize(TextUtils.TruncateAt.END);
        note.setVisibility(own ? View.GONE : View.VISIBLE);
        texts.addView(note);
        LinearLayout.LayoutParams textsLp = CloudUi.weight(1f);
        textsLp.setMarginEnd(CloudUi.dp(c, 8));
        row.addView(texts, textsLp);
        // The walk under way stays pressed in, and a press on it stops it.
        Button to = walkButton(c, R.string.projects_feat_move_go_to_own, goLabel,
                v -> send(v, v.isActivated() ? MoveOp.stop() : MoveOp.approach(name), v.isActivated() ? null : name));
        Button follow = walkButton(c, R.string.projects_feat_move_follow_own, followLabel,
                v -> send(v, v.isActivated() ? MoveOp.stop() : MoveOp.follow(name), v.isActivated() ? null : name));
        row.addView(to);
        LinearLayout.LayoutParams followLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        followLp.setMarginStart(CloudUi.dp(c, 8));
        row.addView(follow, followLp);
        mWalks.add(new WalkRow(name, own, note, to, follow));
        return row;
    }

    /**
     * A small chamfered walk button: the contract's short words on it, the long sentence for TalkBack, pressed in (the
     * pad's gold edge) while the account walks that way.
     */
    private static Button walkButton(Context c, @StringRes int words, String spoken, View.OnClickListener click) {
        Button button = CloudUi.button(c, c.getString(words), false, click);
        button.setBackground(ChamferDrawable.key(c));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(CloudUi.dp(c, 12), 0, CloudUi.dp(c, 12), 0);
        button.setContentDescription(spoken);
        button.setAccessibilityDelegate(TOGGLE);
        return button;
    }

    /** The pad in the keys' own shape: İleri over Sola, Geri, Sağa; under them "Eğil" beside the wide "Zıpla". */
    private View pad(Context c) {
        LinearLayout pad = new LinearLayout(c);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER_HORIZONTAL);
        pad.setPadding(0, CloudUi.dp(c, 4), 0, 0);
        int key = CloudUi.dp(c, 78);
        int tall = CloudUi.dp(c, 72);
        int low = CloudUi.dp(c, 60);
        int gap = CloudUi.dp(c, 10);
        LinearLayout top = keyRow(c, 0);
        top.addView(direction(c, R.drawable.projects_ic_arrow_up_bold, R.string.projects_feat_move_forward, "forward"), keyLp(key, tall, 0));
        pad.addView(top);
        LinearLayout middle = keyRow(c, gap);
        middle.addView(direction(c, R.drawable.projects_ic_arrow_left_bold, R.string.projects_feat_move_left, "left"), keyLp(key, tall, 0));
        middle.addView(direction(c, R.drawable.projects_ic_arrow_down_bold, R.string.projects_feat_move_back, "back"), keyLp(key, tall, gap));
        middle.addView(direction(c, R.drawable.projects_ic_arrow_right_bold, R.string.projects_feat_move_right, "right"), keyLp(key, tall, gap));
        pad.addView(middle);
        LinearLayout bottom = keyRow(c, gap);
        View sneak = key(c, 0, R.string.projects_feat_move_sneak);
        sneak.setOnClickListener(v -> send(v, MoveOp.sneak(!(mState != null && mState.sneak)), null));
        sneak.setAccessibilityDelegate(TOGGLE);
        mSneakKey = sneak;
        bottom.addView(sneak, keyLp(key, low, 0));
        View jump = key(c, 0, R.string.projects_feat_move_jump);
        jump.setOnClickListener(v -> send(v, MoveOp.jump(), null));
        bottom.addView(jump, keyLp(2 * key + gap, low, gap));
        pad.addView(bottom);
        return pad;
    }

    private static LinearLayout keyRow(Context c, int topMargin) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        row.setLayoutParams(lp);
        return row;
    }

    private static LinearLayout.LayoutParams keyLp(int width, int height, int start) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, height);
        lp.setMarginStart(start);
        return lp;
    }

    /**
     * One key: its arrow over its word, chamfered and outlined; gold-edged while it is held or stays pressed in. Without
     * an arrow ({@code icon} 0: "Eğil", "Zıpla", words only as on the launcher's pad, where an arrow under "Geri" read as
     * a second "Geri") the word is a step larger and bright, gold while the key stays pressed in.
     */
    private View key(Context c, @DrawableRes int icon, @StringRes int words) {
        LinearLayout button = new LinearLayout(c);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        button.setBackground(ChamferDrawable.key(c));
        if (icon != 0) {
            ImageView glyph = new ImageView(c);
            glyph.setImageResource(icon);
            glyph.setImageTintList(ColorStateList.valueOf(CloudUi.color(c, R.color.projects_bone_100)));
            glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            button.addView(glyph, new LinearLayout.LayoutParams(CloudUi.dp(c, 24), CloudUi.dp(c, 24)));
        }
        TextView label = CloudUi.text(c, getString(words), icon != 0 ? 12.5f : 13.5f,
                icon != 0 ? R.color.projects_ash_300 : R.color.projects_bone_100, true);
        if (icon == 0) {
            label.setDuplicateParentStateEnabled(true);
            label.setTextColor(new ColorStateList(new int[][]{{android.R.attr.state_activated}, {}},
                    new int[]{CloudUi.color(c, R.color.projects_gold_200), CloudUi.color(c, R.color.projects_bone_100)}));
        }
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (icon != 0) labelLp.topMargin = CloudUi.dp(c, 4);
        button.addView(label, labelLp);
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(getString(words));
        Press.attach(button, Press.Style.BUTTON);
        return button;
    }

    /** A direction: a tap steps one block; held, it asks for eight now and every 1.5 s, and the lifted finger stops it. */
    @SuppressLint("ClickableViewAccessibility")
    private View direction(Context c, @DrawableRes int icon, @StringRes int words, final String dir) {
        final View button = key(c, icon, words);
        final Runnable again = new Runnable() {
            @Override
            public void run() {
                if (!mHolding || !button.isPressed()) return;
                send(button, MoveOp.step(dir, MoveOp.STEP_MAX), null);
                button.postDelayed(this, HOLD_REPEAT_MS);
            }
        };
        button.setOnClickListener(v -> send(v, MoveOp.step(dir, 1), null));
        button.setOnLongClickListener(v -> {
            mHolding = true;
            mHeldKey = v;
            v.setActivated(true);
            send(v, MoveOp.step(dir, MoveOp.STEP_MAX), null);
            v.postDelayed(again, HOLD_REPEAT_MS);
            return true;
        });
        button.setOnTouchListener((v, e) -> {
            int act = e.getActionMasked();
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                v.removeCallbacks(again);
                v.setActivated(false);
                if (mHolding && mHeldKey == v) {
                    mHolding = false;
                    mHeldKey = null;
                    send(v, MoveOp.stop(), null);
                }
            }
            return false;
        });
        return button;
    }

    /**
     * Sends one operation. A refusal holds the line for a moment (a refused walk's button keeps a flare edge meanwhile);
     * the next operation the machine takes ends it at once (the launcher's). {@code name}: an approach's or a follow's.
     */
    private void send(@Nullable View from, MoveOp op, @Nullable final String name) {
        CloudService.LiveHandle handle = mHandle;
        if (handle == null) return;
        if (from != null) Haptics.tick(from);
        if (name != null) mAskedName = name;
        String action = op.action();
        final String walk = name != null && ("approach".equals(action) || "follow".equals(action))
                ? action + ":" + name.toLowerCase(Locale.ROOT) : null;
        handle.moveOp(op, error -> {
            if (!alive()) return;
            if (error == null) {
                if (mRefusal != null || mRefusedWalk != null) endRefusal();
                return;
            }
            CloudModel.Account a = account();
            String main = a == null || a.features.movement == null ? null : a.features.movement.main;
            String words = Movement.refusal(ctx(), error, name, main);
            if (words != null) refuse(words, walk);
        });
    }

    private void refuse(String words, @Nullable String walk) {
        View view = getView();
        if (view == null) return;
        mRefusal = words;
        mRefusedWalk = walk;
        Haptics.reject(mLine != null ? mLine.view : view);
        view.removeCallbacks(mRefusalEnd);
        view.postDelayed(mRefusalEnd, REFUSAL_MS);
        update();
    }

    private void endRefusal() {
        View view = getView();
        if (view != null) view.removeCallbacks(mRefusalEnd);
        mRefusalEnd.run();
    }

    /** "Koordinata git": empty fields mean where it stands now (their hints); a typed target stays for the next time. */
    private void coordinates() {
        final Context c = ctx();
        final MoveFrames.Pos here = mPos;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 16));
        // The sheet's title as the app's other sheets: Tektur 17, a heading (design.md §3).
        TextView title = CloudUi.text(c, getString(R.string.projects_feat_move_goto), 17, R.color.projects_bone_100, true);
        Typeface tektur = font(c, R.font.tektur_semibold);
        if (tektur != null) title.setTypeface(tektur);
        title.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 2), CloudUi.dp(c, 8), CloudUi.dp(c, 6));
        if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        root.addView(title);
        // Beyond 256 blocks: said before anything goes out; the typed fields stay flare until they change.
        final TextView error = CloudUi.text(c, "", 13.5f, R.color.projects_flare_400, false);
        error.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 6), CloudUi.dp(c, 8), 0);
        error.setVisibility(View.GONE);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout fields = new LinearLayout(c);
        fields.setOrientation(LinearLayout.HORIZONTAL);
        final CloudUi.Field[] xyz = new CloudUi.Field[3];
        // "Git" waits until a target somewhere else is typed (set once the button exists).
        final Runnable[] ready = new Runnable[1];
        String[] axes = {"X", "Y", "Z"};
        int[] now = here == null ? null : new int[]{here.x, here.y, here.z};
        for (int i = 0; i < 3; i++) {
            final CloudUi.Field f = CloudUi.field(c, axes[i], mTarget[i], now == null ? null : String.valueOf(now[i]),
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
            f.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(9)});
            f.input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {}

                @Override
                public void afterTextChanged(Editable s) {
                    f.input.setActivated(false);
                    error.setVisibility(View.GONE);
                    if (ready[0] != null) ready[0].run();
                }
            });
            xyz[i] = f;
            fields.addView(f.view, CloudUi.weight(1f));
        }
        root.addView(fields, CloudUi.matchWrap());
        root.addView(error);
        final BottomPanel[] panel = new BottomPanel[1];
        final Button go = CloudUi.button(c, getString(R.string.projects_feat_move_go), true, v -> {
            MoveFrames.Pos from = mPos;
            int[] target = new int[3];
            String[] typed = new String[3];
            for (int i = 0; i < 3; i++) {
                typed[i] = xyz[i].value().trim();
                Integer value = typed[i].isEmpty()
                        ? (from == null ? null : Integer.valueOf(i == 0 ? from.x : i == 1 ? from.y : from.z))
                        : MoveOp.coordinate(typed[i], i == 1);
                if (value == null) {
                    // Half typed, out of the game's range, or empty before the first position: this field says so.
                    Haptics.reject(v);
                    xyz[i].input.setActivated(true);
                    xyz[i].input.requestFocus();
                    return;
                }
                target[i] = value;
            }
            if (from != null && !MoveOp.withinReach(from, target[0], target[1], target[2])) {
                Haptics.reject(v);
                error.setText(getString(R.string.projects_feat_move_far));
                error.setVisibility(View.VISIBLE);
                for (int i = 0; i < 3; i++) if (!typed[i].isEmpty()) xyz[i].input.setActivated(true);
                return;
            }
            System.arraycopy(typed, 0, mTarget, 0, 3);
            send(v, MoveOp.go(target[0], target[1], target[2]), null);
            if (panel[0] != null) panel[0].dismiss();
        });
        go.setTextColor(ContextCompat.getColorStateList(c, R.color.projects_on_gold_text));
        ready[0] = () -> go.setEnabled(!nowhere(xyz, mPos));
        ready[0].run();
        xyz[2].input.setImeOptions(EditorInfo.IME_ACTION_GO);
        xyz[2].input.setOnEditorActionListener((field, action, event) -> {
            if (action != EditorInfo.IME_ACTION_GO) return false;
            if (go.isEnabled()) go.performClick();
            return true;
        });
        LinearLayout.LayoutParams goLp = CloudUi.matchWrap();
        goLp.topMargin = CloudUi.dp(c, 14);
        goLp.leftMargin = CloudUi.dp(c, 8);
        goLp.rightMargin = CloudUi.dp(c, 8);
        root.addView(go, goLp);
        ScrollView scroll = new ScrollView(c);
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel[0] = BottomPanel.of(c, scroll, 0.9f);
        panel[0].show();
    }

    /**
     * Nothing typed, or the typed target is where it already stands: "Git" has nowhere to go (the launcher's). A field
     * that does not read as a coordinate counts as somewhere, so "Git" can say which field it is.
     */
    private static boolean nowhere(CloudUi.Field[] xyz, @Nullable MoveFrames.Pos here) {
        for (int i = 0; i < 3; i++) {
            String typed = xyz[i].value().trim();
            if (typed.isEmpty()) continue;
            if (here == null) return false;
            Integer value = MoveOp.coordinate(typed, i == 1);
            if (value == null || value != (i == 0 ? here.x : i == 1 ? here.y : here.z)) return false;
        }
        return true;
    }

    /** The words that change in place: the figures, the line, "Dur", sneaking, the walks, a trade's lock. */
    private void update() {
        MoveFrames.State state = mState;
        if (state == null || !state.ok || mFigures == null) return;
        Context c = ctx();
        MoveFrames.Pos p = mPos;
        // Before the first position (it follows the state at once) the line keeps its labels and its height.
        String[] axes = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            String value = p == null ? "" : String.format(Locale.ROOT, "%d", i == 0 ? p.x : i == 1 ? p.y : p.z);
            mFigures[i].setText(axisText(c, axes[i], value));
        }
        if (mSide != null) {
            String dim = p == null ? "" : Movement.dimension(c, p.dim);
            String facing = p == null ? "" : getString(R.string.projects_feat_move_facing, Movement.facing(c, p.facing));
            mSide.setText(sideText(dim, facing));
            View line = (View) mSide.getParent();
            line.setContentDescription(p == null ? getString(R.string.projects_feat_move_position)
                    : getString(R.string.projects_feat_move_position) + ": X " + p.x + ", Y " + p.y + ", Z " + p.z + ", "
                    + dim + ", " + facing);
        }
        // A walk to coordinates started elsewhere (the launcher, the site) puts its target in the empty fields once.
        String gotoKey = "goto".equals(state.doing) && state.to != null ? state.to[0] + "," + state.to[1] + "," + state.to[2] : null;
        if (gotoKey != null && !gotoKey.equals(mSeenGoto)) {
            mSeenGoto = gotoKey;
            if (mTarget[0].isEmpty() && mTarget[1].isEmpty() && mTarget[2].isEmpty()) {
                for (int i = 0; i < 3; i++) mTarget[i] = String.valueOf(state.to[i]);
            }
        }
        CloudModel.Account a = account();
        String main = a == null || a.features.movement == null ? null : a.features.movement.main;
        // The line: a refusal for a moment, else what it is doing (a trade's hold grey, with the lock), else how the last
        // walk ended, else that it stands.
        String doing = doing(c, state);
        String words;
        @ColorRes int tone;
        boolean held = false;
        if (mRefusal != null) {
            words = mRefusal;
            tone = R.color.projects_flare_400;
        } else if (doing != null) {
            words = doing;
            held = state.tradeLock;
            tone = held ? R.color.projects_ash_300 : R.color.projects_gold_300;
        } else {
            String ended = "idle".equals(state.doing) ? Movement.ended(c, state.ended, state.name != null ? state.name : mAskedName, main) : null;
            words = ended != null ? ended : getString(state.sneak ? R.string.projects_feat_move_sneaking : R.string.projects_feat_move_idle);
            tone = R.color.projects_ash_300;
        }
        if (mLine != null) mLine.set(words, tone, held);
        showStop(state.going());
        if (mSneakKey != null) mSneakKey.setActivated(state.sneak);
        lock(state.tradeLock);
        // The player's other accounts from the app's own list (no new request): one not in game cannot be walked to.
        for (WalkRow row : mWalks) row.set(row.own && !ownInGame(row.name), state, mRefusedWalk);
    }

    /** "X 128": the axis small and quiet, its number in Tektur (the game's own form of a position). */
    private static CharSequence axisText(Context c, String axis, String value) {
        SpannableStringBuilder s = new SpannableStringBuilder(axis + " ");
        s.setSpan(new AbsoluteSizeSpan(sp(c, 12)), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(CloudUi.color(c, R.color.projects_ash_400)), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Typeface label = font(c, R.font.figtree_semibold);
        if (label != null) s.setSpan(new FontSpan(label), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.append(value);
        return s;
    }

    /**
     * The dimension, then where it looks, as quiet as each other (the launcher's); each kept whole when the end of the
     * line has to wrap (an em space between them may break, the spaces inside them may not).
     */
    private static String sideText(String dim, String facing) {
        char keep = (char) 0x00A0;
        if (dim.isEmpty()) return facing.replace(' ', keep);
        if (facing.isEmpty()) return dim.replace(' ', keep);
        return dim.replace(' ', keep) + (char) 0x2003 + facing.replace(' ', keep);
    }

    /** How wide "X 000" is in the line's own type: an axis keeps at least that. */
    private static int axisWidth(Context c, TextView figure) {
        TextPaint number = new TextPaint(figure.getPaint());
        TextPaint label = new TextPaint(figure.getPaint());
        label.setTextSize(sp(c, 12));
        Typeface semibold = font(c, R.font.figtree_semibold);
        if (semibold != null) label.setTypeface(semibold);
        return (int) Math.ceil(label.measureText("X ") + number.measureText("000"));
    }

    private static int sp(Context c, float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, c.getResources().getDisplayMetrics()));
    }

    @Nullable
    private static Typeface font(Context c, int font) {
        try {
            return ResourcesCompat.getFont(c, font);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** What it is doing now, in one line; null while it stands. */
    @Nullable
    private static String doing(Context c, MoveFrames.State state) {
        if (state.tradeLock) return c.getString(R.string.projects_feat_move_trade);
        if ("goto".equals(state.doing) && state.to != null) {
            return c.getString(R.string.projects_feat_move_going, state.to[0], state.to[1], state.to[2]);
        }
        if ("approach".equals(state.doing) && state.name != null) return c.getString(R.string.projects_feat_move_approaching, state.name);
        if ("follow".equals(state.doing) && state.name != null) return c.getString(R.string.projects_feat_move_following, state.name);
        if ("step".equals(state.doing)) return c.getString(R.string.projects_feat_move_stepping);
        return null;
    }

    private boolean ownInGame(String name) {
        for (CloudModel.Account other : mService.accounts()) {
            if (other.name.equalsIgnoreCase(name)) return CloudStatus.inGame(other);
        }
        return true;
    }

    /** "Dur" fades in at the line's end while it moves, and out when it stands (its place stays reserved). */
    private void showStop(boolean show) {
        final Button stop = mStop;
        if (stop == null || show == mStopShown) return;
        mStopShown = show;
        stop.setClickable(show);
        stop.setFocusable(show);
        stop.setImportantForAccessibility(show ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        stop.animate().cancel();
        if (!Motion.enabled(stop.getContext())) {
            stop.setAlpha(show ? 1f : 0f);
            stop.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        if (show) {
            stop.setVisibility(View.VISIBLE);
            stop.animate().alpha(1f).setDuration(150).setInterpolator(Motion.EASE_OUT).start();
        } else {
            stop.animate().alpha(0f).setDuration(120).setInterpolator(Motion.EASE_OUT)
                    .withEndAction(() -> stop.setVisibility(View.INVISIBLE)).start();
        }
    }

    /**
     * An AFK+ deal is open: every control but "Dur" waits, the whole block one even dim (opacity only; no control takes
     * a disabled look of its own on top) and out of reach of touch, focus and TalkBack (the launcher's inert).
     */
    private void lock(boolean locked) {
        InertBlock block = mControlBlock;
        if (block == null) return;
        if (locked && mHolding) {
            // A held direction lets go: the machine refuses steps during a trade anyway.
            mHolding = false;
            if (mHeldKey != null) mHeldKey.setActivated(false);
            mHeldKey = null;
        }
        if (locked == mLocked) return;
        mLocked = locked;
        block.setInert(locked);
        float to = locked ? LOCKED_ALPHA : 1f;
        block.animate().cancel();
        if (Motion.enabled(block.getContext())) block.animate().alpha(to).setDuration(150).setInterpolator(Motion.EASE_OUT).start();
        else block.setAlpha(to);
    }

    private void reconnect(final View from) {
        final CloudModel.Account a = account();
        if (a == null || mBusy) return;
        mBusy = true;
        mShape = "";
        render();
        mService.restart(a.id, new CloudService.Result<CloudModel.Account>() {
            @Override
            public void ok(CloudModel.Account value) {
                mBusy = false;
                if (!alive()) return;
                Haptics.confirm(from);
                toast(getString(R.string.projects_cloud_reconnecting, value.name));
                mShape = "";
                render();
            }

            @Override
            public void failed(CloudProblem p) {
                mBusy = false;
                if (!alive()) return;
                problem(p, from);
                mShape = "";
                render();
            }
        });
    }

    // ---- the stream ----

    @Override
    public void moveState(MoveFrames.State state) {
        mState = state;
        if (!state.ok) {
            mHolding = false;
            mHeldKey = null;
        }
        if (alive()) render();
    }

    @Override
    public void position(MoveFrames.Pos pos) {
        mPos = pos;
        if (alive()) update();
    }

    @Override
    public void liveState(CloudService.Live state) {
        mLive = state;
        // A lost socket: what it showed may be stale; the next watch sends the state first.
        if (state != CloudService.Live.LIVE) mState = null;
        if (alive()) render();
    }

    @Override
    public void console(List<CloudModel.ConsoleLine> lines, int skipped, boolean snapshot) {}


    @Override
    public void approval(CloudModel.Approval approval) {}

    @Override
    public void hostOnline(boolean online) {
        if (!online) {
            mState = null;
            mLive = CloudService.Live.HOST_OFFLINE;
            if (alive()) render();
        }
    }

    /**
     * The block a trade holds (the web's {@code inert}): while inert no touch reaches the controls (a finger already
     * down gets its cancel, so a held key lets go), none takes the focus, and TalkBack skips them; the line above says
     * why. The controls keep their own look: the block's opacity is the one dim.
     */
    private static final class InertBlock extends LinearLayout {
        private boolean mInert;

        InertBlock(Context c) {
            super(c);
        }

        void setInert(boolean inert) {
            mInert = inert;
            setDescendantFocusability(inert ? FOCUS_BLOCK_DESCENDANTS : FOCUS_AFTER_DESCENDANTS);
            setImportantForAccessibility(inert ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            if (inert) {
                View focused = findFocus();
                if (focused != null) focused.clearFocus();
            }
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            return mInert || super.onInterceptTouchEvent(e);
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            return mInert || super.onTouchEvent(e);
        }
    }

    /** A walk row's parts that change in place (an own account leaving or joining the game, a trade's lock). */
    private static final class WalkRow {
        final String name;
        final boolean own;
        final TextView note;
        final Button to;
        final Button follow;

        WalkRow(String name, boolean own, TextView note, Button to, Button follow) {
            this.name = name;
            this.own = own;
            this.note = note;
            this.to = to;
            this.follow = follow;
        }

        /** The walk of this row the machine refused, while the line says why: "approach", "follow" or null. */
        @Nullable
        private String mRefused;

        void set(boolean waiting, MoveFrames.State state, @Nullable String refusedWalk) {
            Context c = note.getContext();
            if (own) {
                note.setText(waiting ? c.getString(R.string.projects_feat_move_not_in_game) : "");
                note.setVisibility(waiting ? View.VISIBLE : View.GONE);
            }
            // The walk under way to this account stays pressed in.
            boolean toward = state.ok && name.equalsIgnoreCase(state.name == null ? "" : state.name);
            pressed(to, toward && "approach".equals(state.doing));
            pressed(follow, toward && "follow".equals(state.doing));
            for (Button b : new Button[]{to, follow}) {
                b.setEnabled(!waiting);
                b.setAlpha(waiting ? WAITING_ALPHA : 1f);
            }
            String key = ":" + name.toLowerCase(Locale.ROOT);
            String refused = refusedWalk == null ? null
                    : refusedWalk.equals("approach" + key) ? "approach" : refusedWalk.equals("follow" + key) ? "follow" : null;
            if (!TextUtils.equals(refused, mRefused)) {
                mRefused = refused;
                edge(to, "approach".equals(refused));
                edge(follow, "follow".equals(refused));
            }
        }

        /** A refused walk's flare edge, or the key's own; the button keeps its padding (a background brings its own). */
        private static void edge(Button b, boolean refused) {
            int start = b.getPaddingStart(), top = b.getPaddingTop(), end = b.getPaddingEnd(), bottom = b.getPaddingBottom();
            b.setBackground(refused ? ChamferDrawable.keyRefused(b.getContext()) : ChamferDrawable.key(b.getContext()));
            b.setPaddingRelative(start, top, end, bottom);
        }

        private static void pressed(Button b, boolean on) {
            if (b.isActivated() == on) return;
            b.setActivated(on);
            b.setTextColor(CloudUi.color(b.getContext(), on ? R.color.projects_gold_200 : R.color.projects_bone_100));
        }
    }

    /**
     * One line whose words cross-fade in place (opacity only; at once with animations off); TalkBack reads new words. A
     * trade's hold carries the lock before its words (the launcher's LockSimple, quiet).
     */
    private static final class FadeLine {
        final FrameLayout view;
        private final TextView[] mTexts = new TextView[2];
        private int mFront;
        private String mWords = "";
        private int mTone;
        private boolean mLock;

        FadeLine(Context c) {
            view = new FrameLayout(c);
            for (int i = 0; i < 2; i++) {
                TextView t = CloudUi.text(c, "", 14.5f, R.color.projects_ash_300, false);
                t.setMaxLines(2);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setLineSpacing(0, 1.1f);
                t.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                t.setAlpha(i == 0 ? 1f : 0f);
                t.setVisibility(i == 0 ? View.VISIBLE : View.INVISIBLE);
                view.addView(t, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER_VERTICAL));
                mTexts[i] = t;
            }
        }

        void set(String words, @ColorRes int tone, boolean lock) {
            if (words.equals(mWords) && tone == mTone && lock == mLock) return;
            Context c = view.getContext();
            final TextView front = mTexts[mFront];
            if (words.equals(mWords)) {
                front.setTextColor(CloudUi.color(c, tone));
                if (lock != mLock) lock(front, lock);
                mTone = tone;
                mLock = lock;
                return;
            }
            mWords = words;
            mTone = tone;
            mLock = lock;
            TextView back = mTexts[1 - mFront];
            mFront = 1 - mFront;
            front.animate().cancel();
            back.animate().cancel();
            back.setVisibility(View.VISIBLE);
            back.setTextColor(CloudUi.color(c, tone));
            lock(back, lock);
            back.setText(words);
            front.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            back.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            if (!Motion.enabled(c)) {
                back.setAlpha(1f);
                front.setAlpha(0f);
                front.setVisibility(View.INVISIBLE);
                return;
            }
            back.animate().alpha(1f).setDuration(150).setInterpolator(Motion.EASE_OUT).start();
            front.animate().alpha(0f).setDuration(100).setInterpolator(Motion.EASE_OUT)
                    .withEndAction(() -> front.setVisibility(View.INVISIBLE)).start();
        }

        private static void lock(TextView t, boolean on) {
            Context c = t.getContext();
            t.setCompoundDrawablesRelative(on ? CloudUi.tinted(c, R.drawable.projects_ic_lock_bold, R.color.projects_ash_400, 14) : null,
                    null, null, null);
            t.setCompoundDrawablePadding(on ? CloudUi.dp(c, 6) : 0);
        }
    }
}
