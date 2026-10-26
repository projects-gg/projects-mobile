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
import gg.projects.mobile.cloud.ui.CloudScreen;
import gg.projects.mobile.cloud.ui.CloudSettingsFragment;
import gg.projects.mobile.cloud.ui.CloudUi;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * "Hareket" of a cloud account (CAFK-FEAT §3.7): where it stands, four big direction buttons (a step a press, a step
 * every 300 ms while held; every step vibrates), jump and sneak, walking to coordinates, going to or following the
 * player's own main account, and stop. Turning it on says once what can happen ("düşebilir ya da ölebilir"). Runs on
 * {@link Movement#backend}: the stand-in machine until the service's movement contract lands.
 */
public final class CloudMovementFragment extends CloudScreen implements Movement.Listener {
    public static final String TAG = "ProjectsCloudMovementFragment";
    private static final long REPEAT_MS = 300;

    @Nullable
    private Movement.Backend mBackend;
    private String mShape = "";
    @Nullable
    private TextView[] mFigures;
    @Nullable
    private TextView mDimension;
    @Nullable
    private TextView mActivity;
    @Nullable
    private Button mStop;
    @Nullable
    private SwitchCompat mSneak;
    @Nullable
    private TextView mRefusal;
    private final Runnable mRefusalEnd = () -> {
        if (mRefusal != null) mRefusal.setVisibility(View.GONE);
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
        mBackend = Movement.backend(a.id);
        mBackend.attach(this);
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mBackend != null) mBackend.detach();
        mBackend = null;
        mShape = "";
        View view = getView();
        if (view != null) view.removeCallbacks(mRefusalEnd);
    }

    @Override
    protected void render() {
        CloudModel.Account a = account();
        LinearLayout content = mContent;
        if (content == null) return;
        if (a == null || mBackend == null) {
            if (!"loading".equals(mShape)) {
                mShape = "loading";
                content.removeAllViews();
                content.addView(CloudUi.loading(ctx()));
            }
            return;
        }
        String shape = (mBackend.enabled() ? "on" : "off") + "|" + a.config.owner.isEmpty();
        if (!shape.equals(mShape)) {
            mShape = shape;
            build(a);
        }
        update();
    }

    private void build(final CloudModel.Account a) {
        LinearLayout content = clear();
        if (content == null || mBackend == null) return;
        final Context c = ctx();
        mFigures = null;
        if (!mBackend.enabled()) {
            LinearLayout box = new LinearLayout(c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(CloudUi.dp(c, 8), CloudUi.dp(c, 28), CloudUi.dp(c, 8), CloudUi.dp(c, 8));
            TextView off = CloudUi.text(c, getString(R.string.projects_feat_move_off), 17, R.color.projects_bone_100, true);
            box.addView(off);
            TextView line = CloudUi.text(c, getString(R.string.projects_feat_move_setting_line), 14, R.color.projects_ash_300, false);
            line.setLineSpacing(0, 1.15f);
            LinearLayout.LayoutParams lineLp = CloudUi.matchWrap();
            lineLp.topMargin = CloudUi.dp(c, 8);
            box.addView(line, lineLp);
            Button on = CloudUi.button(c, getString(R.string.projects_feat_inv_turn_on), true, v -> {
                Haptics.confirm(v);
                mBackend.setEnabled(true);
            });
            LinearLayout.LayoutParams onLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            onLp.topMargin = CloudUi.dp(c, 20);
            on.setMinWidth(CloudUi.dp(c, 140));
            box.addView(on, onLp);
            content.addView(box, CloudUi.matchWrap());
            return;
        }

        // Where it stands: three figures and the dimension.
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
        TextView dimension = CloudUi.text(c, "", 14, R.color.projects_ash_300, true);
        dimension.setGravity(Gravity.END);
        figures.addView(dimension, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mDimension = dimension;
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
        Button stop = CloudUi.button(c, getString(R.string.projects_feat_move_stop), false, v -> {
            Haptics.confirm(v);
            mBackend.halt();
        });
        stop.setCompoundDrawablesRelative(CloudUi.tinted(c, R.drawable.projects_ic_stop, R.color.projects_bone_100, 18), null, null, null);
        stop.setCompoundDrawablePadding(CloudUi.dp(c, 8));
        doing.addView(stop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mStop = stop;
        content.addView(doing, CloudUi.matchWrap());
        TextView refusal = CloudUi.text(c, "", 14, R.color.projects_bone_100, false);
        refusal.setPadding(CloudUi.dp(c, 8), 0, CloudUi.dp(c, 8), CloudUi.dp(c, 6));
        refusal.setVisibility(View.GONE);
        refusal.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(refusal, CloudUi.matchWrap());
        mRefusal = refusal;

        // The direction pad: big chamfered buttons, a step a press, steps while held; jump in the middle.
        content.addView(pad(c), CloudUi.matchWrap());

        content.addView(CloudUi.gap(c, 18));
        LinearLayout sneakGroup = CloudUi.group(c);
        SwitchCompat sneak = CloudUi.toggle(c, getString(R.string.projects_feat_move_sneak), mBackend.sneaking(), on -> mBackend.sneak(on));
        sneakGroup.addView(sneak);
        mSneak = sneak;
        content.addView(sneakGroup);

        // Going places.
        content.addView(CloudUi.gap(c, 14));
        LinearLayout go = CloudUi.group(c);
        final boolean owner = !a.config.owner.isEmpty();
        List<View> rows = new ArrayList<>();
        rows.add(CloudUi.actionRow(c, R.drawable.projects_ic_crosshair, getString(R.string.projects_feat_move_goto), v -> coordinates()).view);
        CloudUi.Row toMain = CloudUi.actionRow(c, R.drawable.projects_ic_user_focus, getString(R.string.projects_feat_move_go_main), v -> {
            Haptics.confirm(v);
            mBackend.goToMain(a.config.owner);
        });
        CloudUi.Row follow = CloudUi.actionRow(c, R.drawable.projects_ic_person_walk, getString(R.string.projects_feat_move_follow_main), v -> {
            Haptics.confirm(v);
            mBackend.followMain(a.config.owner);
        });
        if (!owner) {
            CloudUi.enable(toMain, false);
            CloudUi.enable(follow, false);
        }
        rows.add(toMain.view);
        rows.add(follow.view);
        CloudUi.fill(go, rows);
        content.addView(go);
        if (!owner) {
            content.addView(CloudUi.note(c, getString(R.string.projects_feat_move_needs_owner)));
            Button settings = CloudUi.textButton(c, getString(R.string.projects_cloud_open_settings),
                    v -> openFor(CloudSettingsFragment.class, a.id));
            content.addView(settings, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        content.addView(CloudUi.note(c, getString(R.string.projects_feat_move_setting_line)));
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

    /** One pad button: its arrow over its word, chamfered and outlined; a press steps, a hold keeps stepping. */
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
        final Runnable repeat = new Runnable() {
            @Override
            public void run() {
                if (!button.isPressed() || mBackend == null) return;
                act(button, action);
                button.postDelayed(this, REPEAT_MS);
            }
        };
        button.setOnClickListener(v -> act(v, action));
        if (!"jump".equals(action)) {
            button.setOnLongClickListener(v -> {
                // Held: a step now and every 300 ms until the finger lifts.
                act(v, action);
                v.postDelayed(repeat, REPEAT_MS);
                return true;
            });
            button.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    v.removeCallbacks(repeat);
                }
                return false;
            });
        }
        return button;
    }

    private void act(View from, String action) {
        if (mBackend == null) return;
        Haptics.tick(from);
        if ("jump".equals(action)) mBackend.jump();
        else mBackend.step(action);
    }

    /** "Koordinata git": three whole numbers, at most 256 blocks away. */
    private void coordinates() {
        final Context c = ctx();
        final Movement.Backend backend = mBackend;
        if (backend == null) return;
        Movement.Position here = backend.position();
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
            Movement.Position from = backend.position();
            if (from != null && !Movement.withinReach(from, target[0], target[1], target[2])) {
                Haptics.reject(v);
                error.setText(getString(R.string.projects_feat_move_far));
                error.setVisibility(View.VISIBLE);
                return;
            }
            Haptics.confirm(v);
            backend.goTo(target[0], target[1], target[2]);
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

    /** The words that change in place: the figures, what it is doing, the stop button, sneaking. */
    private void update() {
        Movement.Backend backend = mBackend;
        if (backend == null || mFigures == null) return;
        Movement.Position p = backend.position();
        if (p != null) {
            mFigures[0].setText(String.format(Locale.ROOT, "%d", p.x));
            mFigures[1].setText(String.format(Locale.ROOT, "%d", p.y));
            mFigures[2].setText(String.format(Locale.ROOT, "%d", p.z));
            if (mDimension != null) {
                int dim = "nether".equals(p.dimension) ? R.string.projects_feat_move_nether
                        : "end".equals(p.dimension) ? R.string.projects_feat_move_end : R.string.projects_feat_move_overworld;
                mDimension.setText(getString(dim));
                View group = (View) mDimension.getParent();
                group.setContentDescription(getString(R.string.projects_feat_move_position) + ": X " + p.x + ", Y " + p.y + ", Z " + p.z + ", "
                        + getString(dim));
            }
        }
        String doing = null;
        if (backend.activity() == Movement.Activity.WALKING && backend.target() != null) {
            int[] t = backend.target();
            doing = getString(R.string.projects_feat_move_going, t[0], t[1], t[2]);
        } else if (backend.activity() == Movement.Activity.FOLLOWING && backend.following() != null) {
            doing = getString(R.string.projects_feat_move_following, backend.following());
        }
        if (mActivity != null) mActivity.setText(doing == null ? "" : doing);
        if (mStop != null) mStop.setVisibility(doing == null ? View.INVISIBLE : View.VISIBLE);
        if (mSneak != null && mSneak.isChecked() != backend.sneaking()) mSneak.setChecked(backend.sneaking());
    }

    // ---- the backend ----

    @Override
    public void changed() {
        if (alive()) render();
    }

    @Override
    public void refused(String code) {
        if (!alive() || mRefusal == null) return;
        int words = "far".equals(code) ? R.string.projects_feat_move_far
                : "not_near".equals(code) ? R.string.projects_feat_move_not_near : R.string.projects_feat_move_no_slot;
        mRefusal.setText(getString(words));
        mRefusal.setVisibility(View.VISIBLE);
        Haptics.reject(mRefusal);
        View view = getView();
        if (view != null) {
            view.removeCallbacks(mRefusalEnd);
            view.postDelayed(mRefusalEnd, 4000);
        }
    }
}
