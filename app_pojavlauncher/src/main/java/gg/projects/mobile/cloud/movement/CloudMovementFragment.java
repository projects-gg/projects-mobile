package gg.projects.mobile.cloud.movement;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.ui.CloudScreen;
import gg.projects.mobile.cloud.ui.CloudSettingsFragment;
import gg.projects.mobile.cloud.ui.CloudUi;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * "Hareket" of a cloud account (services/cloud-afk README "Movement"): where it stands and where it looks, four big
 * direction buttons relative to that (a tap is one block; held, eight blocks now and again every 1.5 s, and a stop when
 * the finger lifts; every send vibrates), jump and sneak, a walk to coordinates, and going to or following the
 * player's own accounts the service names (never a typed name). The stream runs only while the panel is visible.
 */
public final class CloudMovementFragment extends CloudScreen implements CloudService.MovementListener, CloudService.LiveListener {
    public static final String TAG = "ProjectsCloudMovementFragment";
    /** A held direction asks for 8 blocks again this often (the contract's rhythm). */
    private static final long HOLD_REPEAT_MS = 1500;
    private static final long REFUSAL_MS = 4000;

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
    private boolean mBusy;
    private String mShape = "";
    @Nullable
    private TextView[] mFigures;
    @Nullable
    private TextView mDimension;
    @Nullable
    private TextView mFacing;
    @Nullable
    private TextView mActivity;
    @Nullable
    private Button mStop;
    @Nullable
    private SwitchCompat mSneak;
    @Nullable
    private TextView mRefusal;
    private final List<View> mControls = new ArrayList<>();
    private boolean mSneakQuiet;
    private final Runnable mRefusalEnd = () -> {
        if (mRefusal != null) mRefusal.setVisibility(View.INVISIBLE);
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
        View view = getView();
        if (view != null) view.removeCallbacks(mRefusalEnd);
    }

    @Override
    protected void render() {
        CloudModel.Account a = account();
        LinearLayout content = mContent;
        if (content == null) return;
        CloudModel.MovementFeatures move = a == null ? null : a.features.movement;
        String shape;
        if (a == null || (mState == null && mLive != CloudService.Live.LOST && mLive != CloudService.Live.HOST_OFFLINE)) shape = "loading";
        else if (mState == null || move == null) shape = "away";
        else if (!mState.ok) shape = "off:" + mState.reason + (mBusy ? ":busy" : "");
        else shape = "ok|" + move.main + "|" + move.own;
        if (!shape.equals(mShape)) {
            mShape = shape;
            if ("loading".equals(shape)) {
                content.removeAllViews();
                content.addView(CloudUi.loading(ctx()));
                mFigures = null;
            } else if ("away".equals(shape)) {
                message(getString(R.string.projects_feat_inv_unreachable), null, null);
            } else if (shape.startsWith("off:")) {
                buildOff(a, mState.reason);
            } else {
                build(a);
            }
        }
        update();
    }

    /** It cannot move now: one sentence, and where the player can do something about it, the one thing to do. */
    private void buildOff(final CloudModel.Account a, @Nullable String reason) {
        if ("setting".equals(reason)) {
            message(Movement.off(ctx(), reason), getString(R.string.projects_cloud_open_settings),
                    v -> openFor(CloudSettingsFragment.class, a.id));
        } else if ("restart".equals(reason)) {
            message(Movement.off(ctx(), reason), getString(R.string.projects_feat_inv_reconnect_now), this::reconnect);
        } else {
            message(Movement.off(ctx(), reason), null, null);
        }
    }

    private void message(String words, @Nullable String action, @Nullable View.OnClickListener click) {
        LinearLayout content = clear();
        if (content == null) return;
        Context c = ctx();
        mFigures = null;
        mControls.clear();
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 28), CloudUi.dp(c, 8), CloudUi.dp(c, 8));
        TextView line = CloudUi.text(c, words, 16, R.color.projects_bone_100, false);
        line.setLineSpacing(0, 1.15f);
        line.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        box.addView(line);
        if (action != null && click != null) {
            Button button = CloudUi.button(c, action, true, click);
            button.setEnabled(!mBusy);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = CloudUi.dp(c, 20);
            button.setMinWidth(CloudUi.dp(c, 140));
            box.addView(button, lp);
        }
        content.addView(box, CloudUi.matchWrap());
    }

    private void build(final CloudModel.Account a) {
        LinearLayout content = clear();
        if (content == null) return;
        final Context c = ctx();
        final CloudModel.MovementFeatures move = a.features.movement;
        mControls.clear();

        // Where it stands: three figures, the dimension and where it looks (the direction buttons follow that).
        content.addView(CloudUi.section(c, getString(R.string.projects_feat_move_position)));
        LinearLayout where = CloudUi.group(c);
        LinearLayout figures = new LinearLayout(c);
        figures.setOrientation(LinearLayout.HORIZONTAL);
        figures.setGravity(Gravity.CENTER_VERTICAL);
        figures.setPadding(CloudUi.dp(c, 16), CloudUi.dp(c, 14), CloudUi.dp(c, 16), CloudUi.dp(c, 14));
        mFigures = new TextView[3];
        String[] axes = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.addView(CloudUi.text(c, axes[i], 12.5f, R.color.projects_ash_400, true));
            TextView figure = CloudUi.figure(c, "", 22, R.color.projects_bone_100);
            LinearLayout.LayoutParams fLp = CloudUi.matchWrap();
            fLp.topMargin = CloudUi.dp(c, 2);
            cell.addView(figure, fLp);
            mFigures[i] = figure;
            figures.addView(cell, CloudUi.weight(1f));
        }
        LinearLayout side = new LinearLayout(c);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.END);
        mDimension = CloudUi.text(c, "", 14, R.color.projects_bone_100, true);
        mDimension.setGravity(Gravity.END);
        side.addView(mDimension);
        mFacing = CloudUi.text(c, "", 13, R.color.projects_ash_300, false);
        mFacing.setGravity(Gravity.END);
        LinearLayout.LayoutParams facingLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        facingLp.topMargin = CloudUi.dp(c, 2);
        facingLp.gravity = Gravity.END;
        side.addView(mFacing, facingLp);
        figures.addView(side, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        figures.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        where.addView(figures);
        content.addView(where);

        // What it is doing, with "Dur" while it walks or follows (a reserved line: nothing below moves).
        LinearLayout doing = new LinearLayout(c);
        doing.setOrientation(LinearLayout.HORIZONTAL);
        doing.setGravity(Gravity.CENTER_VERTICAL);
        doing.setMinimumHeight(CloudUi.dp(c, 56));
        doing.setPadding(CloudUi.dp(c, 8), 0, 0, 0);
        TextView activity = CloudUi.text(c, "", 14.5f, R.color.projects_gold_300, false);
        activity.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        doing.addView(activity, CloudUi.weight(1f));
        mActivity = activity;
        Button stop = CloudUi.button(c, getString(R.string.projects_feat_move_stop), false, v -> send(v, MoveOp.stop(), null));
        stop.setCompoundDrawablesRelative(CloudUi.tinted(c, R.drawable.projects_ic_stop, R.color.projects_bone_100, 18), null, null, null);
        stop.setCompoundDrawablePadding(CloudUi.dp(c, 8));
        doing.addView(stop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mStop = stop;
        content.addView(doing, CloudUi.matchWrap());
        TextView refusal = CloudUi.text(c, "", 14, R.color.projects_bone_100, false);
        refusal.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), CloudUi.dp(c, 6));
        refusal.setMinLines(1);
        refusal.setVisibility(View.INVISIBLE);
        refusal.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(refusal, CloudUi.matchWrap());
        mRefusal = refusal;

        // The direction pad: big chamfered buttons, relative to where it looks; jump in the middle.
        content.addView(pad(c), CloudUi.matchWrap());

        content.addView(CloudUi.gap(c, 18));
        LinearLayout sneakGroup = CloudUi.group(c);
        SwitchCompat sneak = CloudUi.toggle(c, getString(R.string.projects_feat_move_sneak), mState != null && mState.sneak, on -> {
            if (mSneakQuiet) return;
            send(mSneak, MoveOp.sneak(on), null);
        });
        sneakGroup.addView(sneak);
        mSneak = sneak;
        mControls.add(sneak);
        content.addView(sneakGroup);

        // Going places: coordinates, then the accounts the service names (the main account, the player's others).
        content.addView(CloudUi.gap(c, 14));
        LinearLayout go = CloudUi.group(c);
        List<View> rows = new ArrayList<>();
        CloudUi.Row coords = CloudUi.actionRow(c, R.drawable.projects_ic_crosshair, getString(R.string.projects_feat_move_goto), v -> coordinates());
        rows.add(coords.view);
        mControls.add(coords.view);
        final String main = move == null ? null : move.main;
        if (main != null) {
            CloudUi.Row toMain = CloudUi.actionRow(c, R.drawable.projects_ic_user_focus, getString(R.string.projects_feat_move_go_main),
                    v -> send(v, MoveOp.approach(main), main));
            CloudUi.Row follow = CloudUi.actionRow(c, R.drawable.projects_ic_person_walk, getString(R.string.projects_feat_move_follow_main),
                    v -> send(v, MoveOp.follow(main), main));
            rows.add(toMain.view);
            rows.add(follow.view);
            mControls.add(toMain.view);
            mControls.add(follow.view);
        }
        if (move != null) for (final String name : move.own) rows.add(ownRow(c, name));
        CloudUi.fill(go, rows);
        content.addView(go);
        content.addView(CloudUi.note(c, getString(R.string.projects_feat_move_setting_line)));
    }

    /** Another of the player's accounts: its name, "Yanına git" and "Takip et". */
    private View ownRow(Context c, final String name) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(CloudUi.dp(c, 56));
        row.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 8), 0);
        TextView label = CloudUi.text(c, name, 15.5f, R.color.projects_bone_100, true);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, CloudUi.weight(1f));
        Button to = CloudUi.textButton(c, getString(R.string.projects_feat_move_go_to_own), v -> send(v, MoveOp.approach(name), name));
        to.setContentDescription(getString(R.string.projects_feat_move_go_to_own) + ", " + name);
        Button follow = CloudUi.textButton(c, getString(R.string.projects_feat_move_follow_own), v -> send(v, MoveOp.follow(name), name));
        follow.setContentDescription(getString(R.string.projects_feat_move_follow_own) + ", " + name);
        row.addView(to);
        row.addView(follow);
        mControls.add(to);
        mControls.add(follow);
        return row;
    }

    private View pad(Context c) {
        LinearLayout pad = new LinearLayout(c);
        pad.setOrientation(LinearLayout.VERTICAL);
        pad.setGravity(Gravity.CENTER_HORIZONTAL);
        pad.setPadding(0, CloudUi.dp(c, 6), 0, 0);
        int size = CloudUi.dp(c, 78);
        int gap = CloudUi.dp(c, 10);
        pad.addView(rowOf(c, size, gap, null, padButton(c, R.drawable.projects_ic_arrow_up_bold, R.string.projects_feat_move_forward, "forward"), null));
        pad.addView(rowOf(c, size, gap, padButton(c, R.drawable.projects_ic_arrow_left_bold, R.string.projects_feat_move_left, "left"),
                padButton(c, R.drawable.projects_ic_arrow_line_up, R.string.projects_feat_move_jump, "jump"),
                padButton(c, R.drawable.projects_ic_arrow_right_bold, R.string.projects_feat_move_right, "right")));
        pad.addView(rowOf(c, size, gap, null, padButton(c, R.drawable.projects_ic_arrow_down_bold, R.string.projects_feat_move_back, "back"), null));
        return pad;
    }

    private static LinearLayout rowOf(Context c, int size, int gap, @Nullable View a, View b, @Nullable View d) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        View[] cells = {a, b, d};
        for (int i = 0; i < 3; i++) {
            View cell = cells[i] != null ? cells[i] : new View(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.setMarginStart(gap);
            row.addView(cell, lp);
        }
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = gap;
        row.setLayoutParams(rowLp);
        return row;
    }

    /**
     * One pad button: its arrow over its word, chamfered and outlined. A tap steps one block; held, it asks for eight
     * blocks now and every 1.5 s, and the lifted finger sends a stop.
     */
    @SuppressLint("ClickableViewAccessibility")
    private View padButton(Context c, @DrawableRes int icon, int words, final String action) {
        final LinearLayout button = new LinearLayout(c);
        button.setOrientation(LinearLayout.VERTICAL);
        button.setGravity(Gravity.CENTER);
        button.setBackground(ChamferDrawable.outlined(c));
        ImageView glyph = new ImageView(c);
        glyph.setImageResource(icon);
        glyph.setImageTintList(ColorStateList.valueOf(CloudUi.color(c, R.color.projects_bone_100)));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        button.addView(glyph, new LinearLayout.LayoutParams(CloudUi.dp(c, 26), CloudUi.dp(c, 26)));
        TextView label = CloudUi.text(c, getString(words), 12.5f, R.color.projects_ash_300, true);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.topMargin = CloudUi.dp(c, 4);
        button.addView(label, labelLp);
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(getString(words));
        Press.attach(button, Press.Style.BUTTON);
        mControls.add(button);
        if ("jump".equals(action)) {
            button.setOnClickListener(v -> send(v, MoveOp.jump(), null));
            return button;
        }
        final Runnable again = new Runnable() {
            @Override
            public void run() {
                if (!mHolding || !button.isPressed()) return;
                send(button, MoveOp.step(action, MoveOp.STEP_MAX), null);
                button.postDelayed(this, HOLD_REPEAT_MS);
            }
        };
        button.setOnClickListener(v -> send(v, MoveOp.step(action, 1), null));
        button.setOnLongClickListener(v -> {
            mHolding = true;
            send(v, MoveOp.step(action, MoveOp.STEP_MAX), null);
            v.postDelayed(again, HOLD_REPEAT_MS);
            return true;
        });
        button.setOnTouchListener((v, e) -> {
            int act = e.getActionMasked();
            if ((act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) && mHolding) {
                v.removeCallbacks(again);
                mHolding = false;
                send(v, MoveOp.stop(), null);
            }
            return false;
        });
        return button;
    }

    /** Sends one operation; a refusal shows under the activity line for a moment. {@code name}: an approach's or follow's. */
    private void send(@Nullable View from, MoveOp op, @Nullable final String name) {
        CloudService.LiveHandle handle = mHandle;
        if (handle == null) return;
        if (from != null) Haptics.tick(from);
        if (name != null) mAskedName = name;
        handle.moveOp(op, error -> {
            if (error == null || !alive()) return;
            CloudModel.Account a = account();
            String main = a == null || a.features.movement == null ? null : a.features.movement.main;
            String words = Movement.refusal(ctx(), error, name, main);
            if (words != null) refuse(words);
        });
    }

    private void refuse(String words) {
        if (mRefusal == null) return;
        mRefusal.setText(words);
        mRefusal.setVisibility(View.VISIBLE);
        Haptics.reject(mRefusal);
        View view = getView();
        if (view != null) {
            view.removeCallbacks(mRefusalEnd);
            view.postDelayed(mRefusalEnd, REFUSAL_MS);
        }
    }

    /** "Koordinata git": three whole numbers, at most 256 blocks away (the machine checks again). */
    private void coordinates() {
        final Context c = ctx();
        final MoveFrames.Pos here = mPos;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(CloudUi.dp(c, 16), 0, CloudUi.dp(c, 16), CloudUi.dp(c, 16));
        TextView title = CloudUi.text(c, getString(R.string.projects_feat_move_goto), 17, R.color.projects_bone_100, true);
        title.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 2), CloudUi.dp(c, 8), CloudUi.dp(c, 6));
        root.addView(title);
        LinearLayout fields = new LinearLayout(c);
        fields.setOrientation(LinearLayout.HORIZONTAL);
        final CloudUi.Field[] xyz = new CloudUi.Field[3];
        String[] axes = {"X", "Y", "Z"};
        int[] start = here == null ? new int[]{0, 64, 0} : new int[]{here.x, here.y, here.z};
        for (int i = 0; i < 3; i++) {
            CloudUi.Field f = CloudUi.field(c, axes[i], String.valueOf(start[i]), null,
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
            xyz[i] = f;
            fields.addView(f.view, CloudUi.weight(1f));
        }
        root.addView(fields, CloudUi.matchWrap());
        final TextView error = CloudUi.text(c, "", 13, R.color.projects_flare_400, false);
        error.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 4), CloudUi.dp(c, 8), 0);
        error.setVisibility(View.GONE);
        root.addView(error);
        root.addView(CloudUi.note(c, getString(R.string.projects_feat_move_far)));
        final BottomPanel[] panel = new BottomPanel[1];
        Button go = CloudUi.button(c, getString(R.string.projects_feat_move_go), true, v -> {
            int[] target = new int[3];
            for (int i = 0; i < 3; i++) {
                try {
                    target[i] = Integer.parseInt(xyz[i].value().trim());
                } catch (NumberFormatException e) {
                    // An empty or half-typed box: the field itself shows what is missing.
                    Haptics.reject(v);
                    xyz[i].input.requestFocus();
                    return;
                }
            }
            MoveOp op = MoveOp.go(target[0], target[1], target[2]);
            MoveFrames.Pos from = mPos;
            if (MoveOp.problem(op.op) != null || (from != null && !MoveOp.withinReach(from, target[0], target[1], target[2]))) {
                Haptics.reject(v);
                error.setText(getString(R.string.projects_feat_move_far));
                error.setVisibility(View.VISIBLE);
                return;
            }
            send(v, op, null);
            if (panel[0] != null) panel[0].dismiss();
        });
        LinearLayout.LayoutParams goLp = CloudUi.matchWrap();
        goLp.topMargin = CloudUi.dp(c, 12);
        goLp.leftMargin = CloudUi.dp(c, 8);
        goLp.rightMargin = CloudUi.dp(c, 8);
        root.addView(go, goLp);
        ScrollView scroll = new ScrollView(c);
        scroll.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel[0] = BottomPanel.of(c, scroll, 0.9f);
        panel[0].show();
    }

    /** The words that change in place: the figures, what it is doing, the stop button, sneaking, a trade's lock. */
    private void update() {
        MoveFrames.State state = mState;
        if (state == null || !state.ok || mFigures == null) return;
        Context c = ctx();
        MoveFrames.Pos p = mPos;
        // Before the first position (it follows the state at once) the figures stay empty.
        mFigures[0].setText(p == null ? "" : String.format(Locale.ROOT, "%d", p.x));
        mFigures[1].setText(p == null ? "" : String.format(Locale.ROOT, "%d", p.y));
        mFigures[2].setText(p == null ? "" : String.format(Locale.ROOT, "%d", p.z));
        if (mDimension != null && mFacing != null) {
            mDimension.setText(p == null ? "" : Movement.dimension(c, p.dim));
            mFacing.setText(p == null ? "" : getString(R.string.projects_feat_move_facing, Movement.facing(c, p.facing)));
            View group = (View) mDimension.getParent().getParent();
            group.setContentDescription(p == null ? getString(R.string.projects_feat_move_position)
                    : getString(R.string.projects_feat_move_position) + ": X " + p.x + ", Y " + p.y + ", Z " + p.z + ", "
                    + Movement.dimension(c, p.dim) + ", " + mFacing.getText());
        }
        CloudModel.Account a = account();
        String main = a == null || a.features.movement == null ? null : a.features.movement.main;
        String doing = null;
        boolean quiet = false;
        if (state.tradeLock) {
            doing = getString(R.string.projects_feat_move_trade);
        } else if ("goto".equals(state.doing) && state.to != null) {
            doing = getString(R.string.projects_feat_move_going, state.to[0], state.to[1], state.to[2]);
        } else if ("approach".equals(state.doing) && state.name != null) {
            doing = getString(R.string.projects_feat_move_approaching, state.name);
        } else if ("follow".equals(state.doing) && state.name != null) {
            doing = getString(R.string.projects_feat_move_following, state.name);
        } else if ("idle".equals(state.doing)) {
            doing = Movement.ended(c, state.ended, state.name != null ? state.name : mAskedName, main);
            quiet = true;
        }
        if (mActivity != null) {
            mActivity.setText(doing == null ? "" : doing);
            mActivity.setTextColor(CloudUi.color(c, quiet ? R.color.projects_ash_300 : R.color.projects_gold_300));
        }
        if (mStop != null) mStop.setVisibility(state.going() ? View.VISIBLE : View.INVISIBLE);
        if (mSneak != null && mSneak.isChecked() != state.sneak) {
            mSneakQuiet = true;
            mSneak.setChecked(state.sneak);
            mSneakQuiet = false;
        }
        // An AFK+ deal is open: everything but "Dur" waits.
        for (View control : mControls) {
            control.setEnabled(!state.tradeLock);
            control.setAlpha(state.tradeLock ? 0.45f : 1f);
        }
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
        if (!state.ok) mHolding = false;
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
    public void feed(List<CloudModel.FeedItem> items, boolean snapshot) {}

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
}
