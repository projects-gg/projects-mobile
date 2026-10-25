package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.cloud.CloudStatus;
import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * The pieces 7/24 Bulut's screens are built from, in the app's design language (MOB1, docs/design.md): ash section
 * titles, one rounded group of 56 dp rows with inset hairlines (the main menu's tools), the gold chamfered action
 * (Tektur) beside the outlined one, the app's switches, labelled fields with a line for what is wrong, segmented
 * choices on the chamfer, thin quota bars and the server bars. Every tappable piece presses and ticks.
 */
public final class CloudUi {
    private CloudUi() {}

    public static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static Typeface font(Context c, int font) {
        try {
            return ResourcesCompat.getFont(c, font);
        } catch (RuntimeException e) {
            return Typeface.DEFAULT;
        }
    }

    public static int color(Context c, @ColorRes int res) {
        return ContextCompat.getColor(c, res);
    }

    public static TextView text(Context c, CharSequence value, float sp, @ColorRes int color, boolean semibold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color(c, color));
        t.setTypeface(font(c, semibold ? R.font.figtree_semibold : R.font.figtree_regular));
        return t;
    }

    /**
     * A time beside a line (the console, a chat): meta text in Figtree (design.md §3; Tektur is for live numbers), at
     * least as wide as "00:00" so a column of times keeps its text aligned.
     */
    public static TextView time(Context c, float sp, @ColorRes int color) {
        TextView t = text(c, "", sp, color, false);
        if (Build.VERSION.SDK_INT >= 21) t.setFontFeatureSettings("tnum");
        t.setIncludeFontPadding(false);
        t.setMinWidth((int) Math.ceil(t.getPaint().measureText("00:00")));
        return t;
    }

    /** A Tektur figure (an amount, a countdown, a big state): tabular digits. */
    public static TextView figure(Context c, CharSequence value, float sp, @ColorRes int color) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color(c, color));
        t.setTypeface(font(c, R.font.tektur_semibold));
        if (Build.VERSION.SDK_INT >= 21) t.setFontFeatureSettings("tnum");
        t.setIncludeFontPadding(false);
        return t;
    }

    /** An ash section title (a heading for TalkBack). */
    public static TextView section(Context c, CharSequence title) {
        TextView header = text(c, title, 13, R.color.projects_ash_300, true);
        if (Build.VERSION.SDK_INT >= 28) header.setAccessibilityHeading(true);
        header.setPadding(dp(c, 8), dp(c, 22), dp(c, 8), dp(c, 6));
        return header;
    }

    /** A quiet line under something (one sentence, only where the outcome is not obvious). */
    public static TextView note(Context c, CharSequence value) {
        TextView t = text(c, value, 13.5f, R.color.projects_ash_400, false);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 6));
        return t;
    }

    public static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    public static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
    }

    /** One rounded group of rows (the main menu's tool group), clipped so the rows' ripples keep its corners. */
    public static LinearLayout group(Context c) {
        LinearLayout group = new LinearLayout(c);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackgroundResource(R.drawable.projects_menu_group);
        group.setClipToOutline(true);
        group.setLayoutParams(matchWrap());
        return group;
    }

    /** A hairline between rows of a group, from the text's start (56 dp in). */
    public static View rowDivider(Context c) {
        View line = new View(c);
        line.setBackgroundColor(color(c, R.color.projects_obsidian_700));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        lp.setMarginStart(dp(c, 56));
        line.setLayoutParams(lp);
        return line;
    }

    /** Adds rows to a group with hairlines between them. */
    public static void fill(LinearLayout group, List<View> rows) {
        Context c = group.getContext();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) group.addView(rowDivider(c));
            group.addView(rows.get(i));
        }
    }

    /** A hairline between rows without an icon (switches, figures, values): from where their text starts (16 dp in). */
    public static View textDivider(Context c) {
        View line = rowDivider(c);
        ((LinearLayout.LayoutParams) line.getLayoutParams()).setMarginStart(dp(c, 16));
        return line;
    }

    /** Rows without an icon (figures): the hairlines start where their text does. */
    public static void fillFigures(LinearLayout group, List<View> rows) {
        Context c = group.getContext();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) group.addView(textDivider(c));
            group.addView(rows.get(i));
        }
    }

    /** A row of figures: a label and its value at the end in gold, read by TalkBack as one. */
    public static final class Figure {
        public final LinearLayout view;
        private final CharSequence mLabel;
        private final TextView mValue;

        Figure(LinearLayout view, CharSequence label, TextView value) {
            this.view = view;
            mLabel = label;
            mValue = value;
        }

        public void set(CharSequence value) {
            mValue.setText(value);
            view.setContentDescription(mLabel + ", " + value);
        }
    }

    public static Figure figureRow(Context c, CharSequence label, CharSequence value) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 52));
        row.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        row.addView(text(c, label, 15, R.color.projects_bone_100, false), weight(1f));
        // A plain figure is data, bone like its label; gold stays for what asks something of the player (a count to
        // answer, a time running out) so it keeps its meaning.
        TextView v = text(c, value, 16, R.color.projects_bone_100, true);
        v.setPaddingRelative(dp(c, 12), 0, 0, 0);
        row.addView(v);
        row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        Figure figure = new Figure(row, label, v);
        figure.set(value);
        return figure;
    }

    /** A row of a group: a 24 dp icon, a title, an optional line under it, an optional gold count, a caret. */
    public static final class Row {
        public final LinearLayout view;
        public final TextView title;
        public final TextView line;
        public final TextView count;

        Row(LinearLayout view, TextView title, TextView line, TextView count) {
            this.view = view;
            this.title = title;
            this.line = line;
            this.count = count;
        }

        public void setLine(@Nullable CharSequence value, @ColorRes int color) {
            line.setText(value);
            line.setTextColor(CloudUi.color(view.getContext(), color));
            line.setVisibility(value == null || value.length() == 0 ? View.GONE : View.VISIBLE);
        }

        public void setCount(int n) {
            count.setText(n > 0 ? String.valueOf(n) : "");
            count.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
        }
    }

    /** A row that does its thing at once: pressable like the others, without the caret that promises a screen. */
    public static Row actionRow(Context c, @DrawableRes int icon, CharSequence title, View.OnClickListener click) {
        Row row = row(c, icon, title, null);
        row.view.setBackgroundResource(R.drawable.projects_row_ripple);
        row.view.setClickable(true);
        row.view.setFocusable(true);
        row.view.setOnClickListener(click);
        Press.attach(row.view, Press.Style.ROW);
        return row;
    }

    public static Row row(Context c, @DrawableRes int icon, CharSequence title, @Nullable View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 56));
        row.setPaddingRelative(dp(c, 16), dp(c, 8), dp(c, 12), dp(c, 8));
        ImageView glyph = new ImageView(c);
        glyph.setImageResource(icon);
        glyph.setImageTintList(ColorStateList.valueOf(color(c, R.color.projects_ash_300)));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(glyph, new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(c, title, 15, R.color.projects_bone_100, true);
        TextView line = text(c, "", 13, R.color.projects_ash_400, false);
        line.setVisibility(View.GONE);
        texts.addView(name);
        texts.addView(line);
        LinearLayout.LayoutParams textsLp = weight(1f);
        textsLp.setMarginStart(dp(c, 16));
        row.addView(texts, textsLp);
        TextView count = text(c, "", 14, R.color.projects_gold_300, true);
        count.setVisibility(View.GONE);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.setMarginStart(dp(c, 8));
        row.addView(count, countLp);
        if (click != null) {
            ImageView caret = new ImageView(c);
            caret.setImageResource(R.drawable.projects_ic_caret_right);
            caret.setImageTintList(ColorStateList.valueOf(color(c, R.color.projects_ash_400)));
            caret.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams caretLp = new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20));
            caretLp.setMarginStart(dp(c, 6));
            row.addView(caret, caretLp);
            row.setBackgroundResource(R.drawable.projects_row_ripple);
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(click);
            Press.attach(row, Press.Style.ROW);
        }
        row.setLayoutParams(matchWrap());
        return new Row(row, name, line, count);
    }

    /**
     * A setting whose value is picked in place (a time): the title as a switch's label, the value on the right (bone:
     * data, as {@link #figureRow}), no icon (it lines up with the switches of its group) and no caret (a caret promises
     * a screen; a picker opens here). Separate it from its neighbours with {@link #textDivider}.
     */
    public static Row valueRow(Context c, CharSequence title, CharSequence value, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 56));
        row.setPaddingRelative(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(c, title, 16, R.color.projects_bone_100, false);
        TextView line = text(c, "", 13, R.color.projects_ash_400, false);
        line.setVisibility(View.GONE);
        texts.addView(name);
        texts.addView(line);
        row.addView(texts, weight(1f));
        TextView count = text(c, value, 15, R.color.projects_bone_100, true);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.setMarginStart(dp(c, 12));
        row.addView(count, countLp);
        row.setBackgroundResource(R.drawable.projects_row_ripple);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(click);
        Press.attach(row, Press.Style.ROW);
        row.setLayoutParams(matchWrap());
        return new Row(row, name, line, count);
    }

    /** The gold action (Tektur, text on gold) or the outlined one (Figtree, bone), 48 dp tall. */
    public static Button button(Context c, CharSequence label, boolean primary, View.OnClickListener click) {
        Button button = new Button(c, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, primary ? 16 : 15);
        button.setMinHeight(dp(c, 48));
        button.setMinimumHeight(dp(c, 48));
        button.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        button.setTextColor(primary ? color(c, R.color.projects_on_gold) : color(c, R.color.projects_bone_100));
        button.setBackground(primary ? ChamferDrawable.primary(c) : ChamferDrawable.outlined(c));
        button.setTypeface(font(c, primary ? R.font.tektur_semibold : R.font.figtree_semibold));
        if (primary && Build.VERSION.SDK_INT >= 21) button.setLetterSpacing(0.02f);
        Press.attach(button, Press.Style.BUTTON);
        button.setOnClickListener(click);
        return button;
    }

    /** A quiet text action (gold-300), 48 dp tall. */
    /**
     * The last row of a list's group that adds to the list (a command, a reply, a row to buy or sell): a gold plus and
     * gold words, no caret (a sheet opens here). Inside the group, so the action sits where the list ends.
     */
    public static Row addRow(Context c, CharSequence title, View.OnClickListener click) {
        Row row = actionRow(c, R.drawable.projects_ic_plus, title, click);
        row.title.setTextColor(color(c, R.color.projects_gold_300));
        View glyph = row.view.getChildAt(0);
        if (glyph instanceof ImageView) ((ImageView) glyph).setImageTintList(ColorStateList.valueOf(color(c, R.color.projects_gold_300)));
        return row;
    }

    /** {@link #addRow} as the list allows: a full list keeps the row, dimmed and not pressable. */
    public static void enable(Row row, boolean enabled) {
        row.view.setEnabled(enabled);
        row.view.setClickable(enabled);
        row.view.setAlpha(enabled ? 1f : 0.5f);
    }

    /**
     * "Discord ile giriş yap": Discord's Blurple and official mark, the one place outside the palette (design.md §6),
     * the same as the friends screen's sign-in button (projects_discord_button, Figtree, the glyph before the words).
     */
    public static Button discordButton(Context c, CharSequence label, View.OnClickListener click) {
        Button button = new Button(c, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setTextColor(0xFFFFFFFF);
        button.setTypeface(font(c, R.font.figtree_semibold));
        button.setBackgroundResource(R.drawable.projects_discord_button);
        button.setMinHeight(dp(c, 48));
        button.setMinimumHeight(dp(c, 48));
        button.setPadding(dp(c, 20), 0, dp(c, 20), 0);
        Drawable glyph = ContextCompat.getDrawable(c, R.drawable.projects_ic_discord_glyph);
        if (glyph != null) {
            glyph.setBounds(0, 0, dp(c, 22), Math.round(16.7f * c.getResources().getDisplayMetrics().density));
            button.setCompoundDrawablesRelative(glyph, null, null, null);
        }
        button.setCompoundDrawablePadding(dp(c, 10));
        Press.attach(button, Press.Style.BUTTON);
        button.setOnClickListener(click);
        return button;
    }

    /** A text action that leaves the app for the site: the words and a small "opens outside" mark after them. */
    public static Button siteLink(Context c, CharSequence label, View.OnClickListener click) {
        Button button = textButton(c, label, click);
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.projects_ic_arrow_square_out_small, 0);
        button.setCompoundDrawablePadding(dp(c, 6));
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(button, ColorStateList.valueOf(color(c, R.color.projects_gold_300)));
        return button;
    }

    public static Button textButton(Context c, CharSequence label, View.OnClickListener click) {
        Button button = new Button(c, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setMinHeight(dp(c, 48));
        button.setMinimumHeight(dp(c, 48));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(c, 12), 0, dp(c, 12), 0);
        button.setTextColor(color(c, R.color.projects_gold_300));
        button.setTypeface(font(c, R.font.figtree_semibold));
        button.setBackgroundResource(R.drawable.projects_row_ripple);
        Press.attach(button, Press.Style.BUTTON);
        button.setOnClickListener(click);
        return button;
    }

    public interface Toggle {
        void changed(boolean on);
    }

    /** A switch row (the app's switches: obsidian track, gold when on), 56 dp. */
    public static SwitchCompat toggle(Context c, CharSequence label, boolean checked, final Toggle toggle) {
        SwitchCompat row = new SwitchCompat(c);
        row.setText(label);
        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        row.setTextColor(color(c, R.color.projects_bone_100));
        row.setTypeface(font(c, R.font.figtree_regular));
        row.setMinHeight(dp(c, 56));
        row.setMinimumHeight(dp(c, 56));
        row.setPadding(dp(c, 16), 0, dp(c, 14), 0);
        row.setChecked(checked);
        row.setOnCheckedChangeListener((button, on) -> {
            Haptics.toggle(button, on);
            toggle.changed(on);
        });
        row.setLayoutParams(matchWrap());
        return row;
    }

    /** A labelled text field with a line for what is wrong with it (flare, read out as it changes). */
    public static final class Field {
        public final LinearLayout view;
        public final TextView label;
        public final EditText input;
        public final TextView error;
        public final TextView help;

        Field(LinearLayout view, TextView label, EditText input, TextView error, TextView help) {
            this.view = view;
            this.label = label;
            this.input = input;
            this.error = error;
            this.help = help;
        }

        public String value() {
            return input.getText().toString();
        }

        public void setError(@Nullable CharSequence text) {
            error.setText(text);
            error.setVisibility(text == null ? View.GONE : View.VISIBLE);
        }

        public void setHelp(@Nullable CharSequence text) {
            help.setText(text);
            help.setVisibility(text == null ? View.GONE : View.VISIBLE);
        }

        /** A unit after the box ("sn", "dk", "%"), centred on it. */
        public Field unit(CharSequence unit) {
            Context c = view.getContext();
            int at = view.indexOfChild(input);
            ViewGroup.LayoutParams inputLp = input.getLayoutParams();
            view.removeView(input);
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(input, weight(1f));
            TextView suffix = text(c, unit, 14, R.color.projects_ash_300, false);
            LinearLayout.LayoutParams suffixLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            suffixLp.setMarginStart(dp(c, 10));
            suffixLp.setMarginEnd(dp(c, 4));
            row.addView(suffix, suffixLp);
            view.addView(row, at, inputLp);
            return this;
        }
    }

    public static Field field(Context c, CharSequence label, @Nullable CharSequence value, @Nullable CharSequence hint, int inputType) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 8), dp(c, 10), dp(c, 8), dp(c, 2));
        TextView name = text(c, label, 14, R.color.projects_bone_100, true);
        box.addView(name);
        EditText input = new EditText(c);
        input.setBackgroundResource(R.drawable.projects_input);
        input.setTypeface(font(c, R.font.figtree_regular));
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setTextColor(color(c, R.color.projects_bone_100));
        input.setHintTextColor(color(c, R.color.projects_ash_400));
        input.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        input.setSingleLine((inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        input.setInputType(inputType);
        // After the line settings: setSingleLine() puts TextView's own minimum back to one line.
        input.setMinimumHeight(dp(c, 52));
        input.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        if (Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        if (hint != null) input.setHint(hint);
        if (value != null) input.setText(value);
        LinearLayout.LayoutParams inputLp = matchWrap();
        inputLp.topMargin = dp(c, 8);
        box.addView(input, inputLp);
        TextView help = text(c, "", 13, R.color.projects_ash_400, false);
        help.setVisibility(View.GONE);
        LinearLayout.LayoutParams helpLp = matchWrap();
        helpLp.topMargin = dp(c, 6);
        box.addView(help, helpLp);
        TextView error = text(c, "", 13, R.color.projects_flare_400, false);
        error.setVisibility(View.GONE);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        box.addView(error, helpLp);
        box.setLayoutParams(matchWrap());
        return new Field(box, name, input, error, help);
    }

    /** One choice of several on the chamfer (the selected one faint gold); equal widths, 48 dp targets. */
    public interface Choice {
        void picked(int index);
    }

    public static LinearLayout segmented(Context c, CharSequence[] options, int selected, final Choice choice) {
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
        final List<Button> buttons = new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            Button b = new Button(c, null, android.R.attr.borderlessButtonStyle);
            b.setText(options[i]);
            b.setAllCaps(false);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            b.setTypeface(font(c, R.font.figtree_semibold));
            b.setMinHeight(dp(c, 48));
            b.setMinimumHeight(dp(c, 48));
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(c, 6), 0, dp(c, 6), 0);
            b.setBackground(ChamferDrawable.toggle(c));
            b.setMaxLines(1);
            b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            Press.attach(b, Press.Style.BUTTON);
            buttons.add(b);
            LinearLayout.LayoutParams lp = weight(1f);
            if (i > 0) lp.setMarginStart(dp(c, 6));
            row.addView(b, lp);
            b.setOnClickListener(v -> {
                select(buttons, index);
                Haptics.tick(v);
                choice.picked(index);
            });
        }
        select(buttons, selected);
        row.setLayoutParams(matchWrap());
        return row;
    }

    /** One choice of several as rows (for longer words): the chosen row carries a gold check. */
    public static LinearLayout choices(Context c, CharSequence[] options, int selected, final Choice choice) {
        return choices(c, options, selected, false, choice);
    }

    /** {@code grouped}: the rows of a group on a page (16 dp in, hairlines between them); otherwise a sheet's list. */
    public static LinearLayout choices(Context c, CharSequence[] options, int selected, boolean grouped, final Choice choice) {
        final LinearLayout list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        final List<ImageView> checks = new ArrayList<>();
        final List<View> rows = new ArrayList<>();
        for (int i = 0; i < options.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(c, grouped ? 52 : 48));
            row.setPadding(dp(c, grouped ? 16 : 8), 0, dp(c, grouped ? 16 : 8), 0);
            row.setBackgroundResource(R.drawable.projects_row_ripple);
            row.setClickable(true);
            row.setFocusable(true);
            TextView label = text(c, options[i], 15, R.color.projects_bone_100, false);
            row.addView(label, weight(1f));
            ImageView check = new ImageView(c);
            check.setImageResource(R.drawable.projects_ic_check);
            check.setImageTintList(ColorStateList.valueOf(color(c, R.color.projects_gold_300)));
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(check, new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20)));
            checks.add(check);
            rows.add(row);
            Press.attach(row, Press.Style.ROW);
            row.setOnClickListener(v -> {
                mark(rows, checks, index);
                Haptics.tick(v);
                choice.picked(index);
            });
            if (grouped && i > 0) list.addView(textDivider(c));
            list.addView(row, matchWrap());
        }
        mark(rows, checks, selected);
        list.setLayoutParams(matchWrap());
        return list;
    }

    private static void mark(List<View> rows, List<ImageView> checks, int selected) {
        for (int i = 0; i < checks.size(); i++) {
            boolean on = i == selected;
            checks.get(i).setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            rows.get(i).setSelected(on);
            View label = ((ViewGroup) rows.get(i)).getChildAt(0);
            label.setContentDescription(null);
            if (Build.VERSION.SDK_INT >= 30) rows.get(i).setStateDescription(on ? rows.get(i).getContext().getString(R.string.projects_cloud_selected) : null);
        }
    }

    private static void select(List<Button> buttons, int selected) {
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            boolean on = i == selected;
            b.setActivated(on);
            b.setTextColor(color(b.getContext(), on ? R.color.projects_gold_300 : R.color.projects_ash_300));
            b.setContentDescription(b.getText());
            if (Build.VERSION.SDK_INT >= 30) b.setStateDescription(on ? b.getContext().getString(R.string.projects_cloud_selected) : null);
            b.setSelected(false);
        }
    }

    /** A thin quota bar: obsidian track, gold fill; its fill moves on a transform (scaleX from the start). */
    public static final class Bar extends View {
        private final Paint mTrack = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private float mShown;

        public Bar(Context c) {
            super(c);
            mTrack.setColor(color(c, R.color.projects_obsidian_700));
            mFill.setColor(color(c, R.color.projects_gold_500));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        /** 0-100; full turns the fill flare-free gold-600 (no red: a used allowance is not an error). */
        public void set(int pct, boolean animate) {
            final float target = Math.max(0, Math.min(100, pct)) / 100f;
            mFill.setColor(color(getContext(), target >= 1f ? R.color.projects_gold_600 : R.color.projects_gold_500));
            if (!animate || !Motion.enabled(getContext()) || getWidth() == 0) {
                mShown = target;
                invalidate();
                return;
            }
            final float from = mShown;
            android.animation.ValueAnimator a = android.animation.ValueAnimator.ofFloat(0f, 1f);
            a.setDuration(420);
            a.setInterpolator(Motion.EASE_OUT);
            a.addUpdateListener(v -> {
                mShown = from + (target - from) * (float) v.getAnimatedValue();
                invalidate();
            });
            a.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float h = getHeight();
            float r = h / 2f;
            mRect.set(0, 0, getWidth(), h);
            canvas.drawRoundRect(mRect, r, r, mTrack);
            if (mShown > 0) {
                mRect.set(0, 0, Math.max(h, getWidth() * mShown), h);
                canvas.drawRoundRect(mRect, r, r, mFill);
            }
        }
    }

    /** A state line: the server bars and the words; the words are what TalkBack reads. */
    public static final class StateLine {
        public final LinearLayout view;
        public final TextView text;
        public final SignalGlyph glyph;

        StateLine(LinearLayout view, TextView text, SignalGlyph glyph) {
            this.view = view;
            this.text = text;
            this.glyph = glyph;
        }

        public void set(CharSequence words, CloudStatus.Line line) {
            text.setText(words);
            int tone;
            switch (line.tone) {
                case OK: tone = R.color.projects_jade_400; break;
                case ERROR: tone = R.color.projects_flare_400; break;
                case WARN: tone = R.color.projects_gold_300; break;
                default: tone = R.color.projects_ash_300; break;
            }
            text.setTextColor(CloudUi.color(view.getContext(), tone));
            glyph.setSignal(line.signal);
        }
    }

    public static StateLine stateLine(Context c, float sp) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int unit = Math.max(1, Math.round(c.getResources().getDisplayMetrics().density * 1.25f));
        SignalGlyph glyph = new SignalGlyph(unit);
        ImageView mark = new ImageView(c);
        mark.setImageDrawable(glyph);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark, new LinearLayout.LayoutParams(glyph.getIntrinsicWidth(), glyph.getIntrinsicHeight()));
        TextView text = text(c, "", sp, R.color.projects_ash_300, false);
        LinearLayout.LayoutParams lp = weight(1f);
        lp.setMarginStart(dp(c, 10));
        row.addView(text, lp);
        return new StateLine(row, text, glyph);
    }

    /** A notice panel: a small leading icon carries the meaning's colour, the words stay calm. */
    public static LinearLayout notice(Context c, @DrawableRes int icon, @ColorRes int iconColor, CharSequence words) {
        LinearLayout panel = new LinearLayout(c);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.TOP);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(c, 10));
        bg.setColor(color(c, R.color.projects_obsidian_800));
        bg.setStroke(Math.max(1, dp(c, 1)), color(c, R.color.projects_obsidian_700));
        panel.setBackground(bg);
        panel.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        ImageView glyph = new ImageView(c);
        glyph.setImageResource(icon);
        glyph.setImageTintList(ColorStateList.valueOf(color(c, iconColor)));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams glyphLp = new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20));
        glyphLp.topMargin = dp(c, 1);
        panel.addView(glyph, glyphLp);
        TextView text = text(c, words, 14, R.color.projects_bone_100, false);
        text.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams lp = weight(1f);
        lp.setMarginStart(dp(c, 12));
        panel.addView(text, lp);
        LinearLayout.LayoutParams panelLp = matchWrap();
        panelLp.topMargin = dp(c, 8);
        panel.setLayoutParams(panelLp);
        return panel;
    }

    /**
     * An empty state: the thing it stands for as one of our pixel sprites on the friends' icon tile (ICON1; never a
     * stock glyph), and one short sentence, neutral.
     */
    public static LinearLayout empty(Context c, gg.projects.mobile.ui.PixelSprites.Sprite sprite, CharSequence words) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(c, 16), dp(c, 36), dp(c, 16), dp(c, 24));
        ImageView tile = new ImageView(c);
        Drawable bg = ContextCompat.getDrawable(c, R.drawable.projects_social_icon_tile);
        tile.setImageDrawable(bg == null ? new gg.projects.mobile.ui.PixelSpriteDrawable(sprite, false)
                : gg.projects.mobile.ui.ContentTiles.withSprite(c.getResources(), bg, sprite, false, 12));
        tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        box.addView(tile, new LinearLayout.LayoutParams(dp(c, 56), dp(c, 56)));
        TextView text = text(c, words, 15, R.color.projects_ash_300, false);
        text.setGravity(Gravity.CENTER);
        text.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(c, 14);
        box.addView(text, lp);
        box.setLayoutParams(matchWrap());
        return box;
    }

    /** A quiet spinner while the first answer is on its way (the words say what is loading for TalkBack). */
    public static View loading(Context c) {
        FrameLayout box = new FrameLayout(c);
        android.widget.ProgressBar spinner = new android.widget.ProgressBar(c);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(color(c, R.color.projects_gold_500)));
        spinner.setContentDescription(c.getString(R.string.projects_cloud_home_loading));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(c, 28), dp(c, 28));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(c, 56);
        box.addView(spinner, lp);
        box.setLayoutParams(matchWrap());
        return box;
    }

    /** A block whose children sit with the page's side margins (inside the scroll's own 12 dp). */
    public static FrameLayout.LayoutParams frameMatchWrap() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** Space between blocks. */
    public static View gap(Context c, float heightDp) {
        View space = new View(c);
        space.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, heightDp)));
        return space;
    }

    /** A drawable tinted for an inline icon (16-20 dp). */
    @Nullable
    public static Drawable tinted(Context c, @DrawableRes int icon, @ColorRes int tint, float sizeDp) {
        Drawable d = ContextCompat.getDrawable(c, icon);
        if (d == null) return null;
        d = d.mutate();
        d.setTint(color(c, tint));
        int size = dp(c, sizeDp);
        d.setBounds(0, 0, size, size);
        return d;
    }
}
