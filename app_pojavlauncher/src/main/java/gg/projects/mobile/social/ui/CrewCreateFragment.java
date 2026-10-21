package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.util.List;

import gg.projects.mobile.social.CrewLook;
import gg.projects.mobile.social.CrewRules;
import gg.projects.mobile.social.SocialGate;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * "Ekip kur" (M19): a phone-only player can found a crew too. Name and tag (the service's rules,
 * checked as they type), an emblem, and where the service offers looks a colour, a frame and a motto,
 * with the crew's mark previewed live. Changing it later stays on the desktop launcher.
 */
public class CrewCreateFragment extends Fragment {
    public static final String TAG = "ProjectsCrewCreateFragment";

    private CrewMarkView mMark;
    private TextView mPreviewName;
    private TextView mPreviewTag;
    private TextView mPreviewMotto;
    private EditText mName;
    private EditText mTag;
    private EditText mMotto;
    private TextView mNameError;
    private TextView mTagError;
    private TextView mMottoError;
    private TextView mMottoCount;
    private GridLayout mEmblems;
    private GridLayout mColors;
    private LinearLayout mFrames;
    private String mEmblem = "sword";
    private String mColor = CrewLook.DEFAULT_COLOR;
    private String mFrame = CrewLook.DEFAULT_FRAME;
    private boolean mLook;
    private boolean mBusy;
    private boolean mTried;

    public CrewCreateFragment() {
        super(R.layout.fragment_projects_crew_create);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Context context = view.getContext();
        SocialGate gate = SocialService.get(context).gate();
        mLook = gate.look;
        mMark = view.findViewById(R.id.projects_create_mark);
        mPreviewName = view.findViewById(R.id.projects_create_preview_name);
        mPreviewTag = view.findViewById(R.id.projects_create_preview_tag);
        mPreviewMotto = view.findViewById(R.id.projects_create_preview_motto);
        mName = view.findViewById(R.id.projects_create_name);
        mTag = view.findViewById(R.id.projects_create_tag);
        mMotto = view.findViewById(R.id.projects_create_motto);
        mNameError = view.findViewById(R.id.projects_create_name_error);
        mTagError = view.findViewById(R.id.projects_create_tag_error);
        mMottoError = view.findViewById(R.id.projects_create_motto_error);
        mMottoCount = view.findViewById(R.id.projects_create_motto_count);
        mEmblems = view.findViewById(R.id.projects_create_emblems);
        mColors = view.findViewById(R.id.projects_create_colors);
        mFrames = view.findViewById(R.id.projects_create_frames);
        view.findViewById(R.id.projects_create_motto_group).setVisibility(mLook ? View.VISIBLE : View.GONE);
        view.findViewById(R.id.projects_create_look).setVisibility(mLook ? View.VISIBLE : View.GONE);
        View submit = view.findViewById(R.id.projects_create_submit);
        Press.attach(submit, Press.Style.BUTTON);
        submit.setOnClickListener(v -> create(v));
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                preview();
                if (mTried) validate();
            }
        };
        mName.addTextChangedListener(watcher);
        mTag.addTextChangedListener(watcher);
        mMotto.addTextChangedListener(watcher);
        buildEmblems();
        if (mLook) {
            buildColors();
            buildFrames();
        }
        preview();
    }

    private void preview() {
        mMark.setLook(mEmblem, mColor, mFrame);
        String name = CrewRules.clean(mName.getText().toString());
        mPreviewName.setText(name.isEmpty() ? getString(R.string.projects_crew_name) : name);
        mPreviewName.setAlpha(name.isEmpty() ? 0.5f : 1f);
        String tag = CrewRules.tagOf(mTag.getText().toString());
        // The tag shows once written (the desktop's preview).
        mPreviewTag.setText(tag);
        mPreviewTag.setVisibility(tag.isEmpty() ? View.GONE : View.VISIBLE);
        CrewViews.tintTag(mPreviewTag, mColor);
        String motto = CrewRules.clean(mMotto.getText().toString());
        mPreviewMotto.setText(motto);
        mPreviewMotto.setVisibility(mLook && !motto.isEmpty() ? View.VISIBLE : View.GONE);
        mMottoCount.setText(getString(R.string.projects_crew_count, CrewRules.length(motto), CrewRules.MOTTO_MAX));
    }

    private boolean validate() {
        boolean ok = true;
        CrewRules.Problem name = CrewRules.name(mName.getText().toString());
        ok &= show(mNameError, mName, name == CrewRules.Problem.NAME_SHORT ? R.string.projects_crew_name_short
                : name == CrewRules.Problem.NAME_LONG ? R.string.projects_crew_name_long
                : name == CrewRules.Problem.NAME_CHARS ? R.string.projects_crew_name_chars : 0);
        CrewRules.Problem tag = CrewRules.tag(mTag.getText().toString());
        ok &= show(mTagError, mTag, tag == CrewRules.Problem.TAG ? R.string.projects_crew_tag_invalid : 0);
        if (mLook) {
            CrewRules.Problem motto = CrewRules.motto(mMotto.getText().toString());
            ok &= show(mMottoError, mMotto, motto == CrewRules.Problem.MOTTO_LONG ? R.string.projects_crew_motto_long
                    : motto == CrewRules.Problem.MOTTO_CHARS ? R.string.projects_crew_motto_chars
                    : motto == CrewRules.Problem.MOTTO_LETTERS ? R.string.projects_crew_motto_letters : 0);
        }
        return ok;
    }

    private static boolean show(TextView error, EditText field, int text) {
        error.setVisibility(text == 0 ? View.GONE : View.VISIBLE);
        if (text != 0) error.setText(text);
        field.setActivated(text != 0);
        return text == 0;
    }

    private void create(View button) {
        if (mBusy) return;
        mTried = true;
        if (!validate()) {
            Haptics.reject(button);
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("name", CrewRules.clean(mName.getText().toString()));
        body.addProperty("tag", CrewRules.tagOf(mTag.getText().toString()));
        body.addProperty("emblem", mEmblem);
        if (mLook) {
            // Only a service with looks takes these (an older one refuses unknown fields).
            body.addProperty("color", mColor);
            body.addProperty("frame", mFrame);
            String motto = CrewRules.clean(mMotto.getText().toString());
            if (!motto.isEmpty()) body.addProperty("motto", motto);
        }
        mBusy = true;
        button.setEnabled(false);
        final FragmentActivity activity = requireActivity();
        final String name = CrewRules.clean(mName.getText().toString());
        SocialService.get(activity).createTeam(body, new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                mBusy = false;
                Haptics.confirm(button);
                SocialUi.toast(activity, activity.getString(R.string.projects_crew_created, name));
                // M20: the crew page shows the new crew (with several, it is not the main one).
                gg.projects.mobile.social.SocialModel.Team made = gg.projects.mobile.social.SocialModel.Team.read(
                        gg.projects.mobile.social.Json.obj(value, "team"));
                CrewFragment.selectNext(made == null ? null : made.id);
                SocialUi.leave(CrewCreateFragment.this);
            }

            @Override
            public void failed(SocialProblem problem) {
                mBusy = false;
                button.setEnabled(true);
                switch (problem.kind) {
                    case CREW_NAME:
                    case CREW_NAME_REJECTED:
                        show(mNameError, mName, problem.kind == SocialProblem.Kind.CREW_NAME
                                ? R.string.projects_social_error_crew_name : R.string.projects_social_error_crew_name_rejected);
                        Haptics.reject(button);
                        break;
                    case CREW_TAG:
                        show(mTagError, mTag, R.string.projects_social_error_crew_tag);
                        Haptics.reject(button);
                        break;
                    case CREW_MOTTO:
                    case CREW_MOTTO_REJECTED:
                        show(mMottoError, mMotto, problem.kind == SocialProblem.Kind.CREW_MOTTO
                                ? R.string.projects_social_error_crew_motto : R.string.projects_social_error_crew_motto_rejected);
                        Haptics.reject(button);
                        break;
                    default:
                        SocialUi.toastProblem(activity, problem, button);
                        break;
                }
            }
        });
    }

    // ---- pickers ----

    private int cellSize() {
        float dp = getResources().getDisplayMetrics().density;
        int width = getResources().getDisplayMetrics().widthPixels - Math.round(32 * dp);
        return Math.max(Math.round(44 * dp), Math.min(Math.round(56 * dp), width / 6));
    }

    private void buildEmblems() {
        mEmblems.removeAllViews();
        List<String> emblems = CrewLook.emblemsFor(mLook);
        if (!emblems.contains(mEmblem)) mEmblem = emblems.get(0);
        int size = cellSize();
        for (final String emblem : emblems) {
            FrameLayout cell = new FrameLayout(requireContext());
            cell.setClickable(true);
            cell.setFocusable(true);
            int label = getResources().getIdentifier("projects_crew_emblem_" + emblem, "string", requireContext().getPackageName());
            cell.setContentDescription(label == 0 ? emblem : getString(label));
            cell.setBackgroundResource(R.drawable.projects_row_ripple);
            int pad = Math.round(size * 0.12f);
            cell.setPadding(pad, pad, pad, pad);
            cell.setTag(emblem);
            cell.setOnClickListener(v -> {
                mEmblem = emblem;
                Haptics.perform(v, gg.projects.mobile.ui.HapticMap.Kind.TOGGLE_ON);
                refreshEmblems();
                preview();
            });
            Press.attach(cell, Press.Style.ICON);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            mEmblems.addView(cell, lp);
        }
        refreshEmblems();
    }

    /** The chosen emblem on its frame in the crew's colour; the others gold on obsidian (the desktop's picker). */
    private void refreshEmblems() {
        Context context = requireContext();
        for (int i = 0; i < mEmblems.getChildCount(); i++) {
            FrameLayout cell = (FrameLayout) mEmblems.getChildAt(i);
            String emblem = (String) cell.getTag();
            cell.removeAllViews();
            boolean selected = emblem.equals(mEmblem);
            cell.setSelected(selected);
            if (selected) {
                CrewMarkView mark = new CrewMarkView(context);
                mark.setLook(emblem, mColor, mFrame);
                cell.addView(mark, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            } else {
                ImageView glyph = new ImageView(context);
                Drawable d = CrewMarkView.emblemDrawable(context, emblem);
                if (d != null) d.setTint(ContextCompat.getColor(context, R.color.projects_gold_300));
                glyph.setImageDrawable(d);
                glyph.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int pad = Math.round(cell.getLayoutParams() == null ? 8 : cell.getLayoutParams().width * 0.16f);
                glyph.setPadding(pad, pad, pad, pad);
                GradientDrawable tile = new GradientDrawable();
                tile.setCornerRadius(8 * getResources().getDisplayMetrics().density);
                tile.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_800));
                glyph.setBackground(tile);
                cell.addView(glyph, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }
    }

    private void buildColors() {
        mColors.removeAllViews();
        int size = cellSize();
        for (final String color : CrewLook.COLORS) {
            Swatch swatch = new Swatch(requireContext(), color);
            swatch.setContentDescription(getString(colorName(color)));
            swatch.setOnClickListener(v -> {
                mColor = color;
                Haptics.perform(v, gg.projects.mobile.ui.HapticMap.Kind.TOGGLE_ON);
                for (int i = 0; i < mColors.getChildCount(); i++) mColors.getChildAt(i).setSelected(mColors.getChildAt(i) == v);
                refreshEmblems();
                refreshFrames();
                preview();
            });
            swatch.setSelected(color.equals(mColor));
            Press.attach(swatch, Press.Style.ICON);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = size;
            lp.height = size;
            mColors.addView(swatch, lp);
        }
    }

    private void buildFrames() {
        mFrames.removeAllViews();
        float dp = getResources().getDisplayMetrics().density;
        for (final String frame : CrewLook.FRAMES) {
            LinearLayout cell = new LinearLayout(requireContext());
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(0, Math.round(6 * dp), 0, Math.round(6 * dp));
            cell.setBackgroundResource(R.drawable.projects_row_ripple);
            cell.setClickable(true);
            cell.setFocusable(true);
            cell.setTag(frame);
            CrewMarkView mark = new CrewMarkView(requireContext());
            cell.addView(mark, new LinearLayout.LayoutParams(Math.round(44 * dp), Math.round(44 * dp)));
            TextView label = new TextView(requireContext());
            label.setText(frameName(frame));
            label.setTextSize(12);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, Math.round(4 * dp), 0, 0);
            cell.addView(label);
            cell.setOnClickListener(v -> {
                mFrame = frame;
                Haptics.perform(v, gg.projects.mobile.ui.HapticMap.Kind.TOGGLE_ON);
                refreshFrames();
                refreshEmblems();
                preview();
            });
            Press.attach(cell, Press.Style.CARD);
            mFrames.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        refreshFrames();
    }

    private void refreshFrames() {
        Context context = requireContext();
        for (int i = 0; i < mFrames.getChildCount(); i++) {
            LinearLayout cell = (LinearLayout) mFrames.getChildAt(i);
            String frame = (String) cell.getTag();
            boolean selected = frame.equals(mFrame);
            ((CrewMarkView) cell.getChildAt(0)).setLook(mEmblem, selected ? mColor : "obsidian", frame);
            TextView label = (TextView) cell.getChildAt(1);
            label.setTextColor(ContextCompat.getColor(context, selected ? R.color.projects_gold_300 : R.color.projects_ash_400));
            cell.setSelected(selected);
        }
    }

    static int colorName(String color) {
        switch (color) {
            case "copper": return R.string.projects_crew_color_copper;
            case "crimson": return R.string.projects_crew_color_crimson;
            case "rose": return R.string.projects_crew_color_rose;
            case "purple": return R.string.projects_crew_color_purple;
            case "navy": return R.string.projects_crew_color_navy;
            case "blue": return R.string.projects_crew_color_blue;
            case "teal": return R.string.projects_crew_color_teal;
            case "green": return R.string.projects_crew_color_green;
            case "silver": return R.string.projects_crew_color_silver;
            case "ivory": return R.string.projects_crew_color_ivory;
            case "obsidian": return R.string.projects_crew_color_obsidian;
            default: return R.string.projects_crew_color_gold;
        }
    }

    static int frameName(String frame) {
        switch (frame) {
            case "shield": return R.string.projects_crew_frame_shield;
            case "banner": return R.string.projects_crew_frame_banner;
            case "hex": return R.string.projects_crew_frame_hex;
            default: return R.string.projects_crew_frame_cut;
        }
    }

    /** A colour choice: the frame's fill with its edge, a gold ring around the chosen one. */
    static final class Swatch extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final CrewLook.Colors mColors;

        Swatch(Context context, String color) {
            super(context);
            mColors = CrewLook.colors(color);
            setClickable(true);
            setFocusable(true);
            setBackgroundResource(R.drawable.projects_icon_button_ripple);
        }

        @Override
        public void setSelected(boolean selected) {
            super.setSelected(selected);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float dp = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = Math.min(getWidth(), getHeight()) / 2f - 8 * dp;
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mColors.fill);
            canvas.drawCircle(cx, cy, r, mPaint);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(2 * dp);
            mPaint.setColor(mColors.edge);
            canvas.drawCircle(cx, cy, r - dp, mPaint);
            if (isSelected()) {
                mPaint.setColor(ContextCompat.getColor(getContext(), R.color.projects_gold_300));
                canvas.drawCircle(cx, cy, r + 4 * dp, mPaint);
            }
        }
    }
}
