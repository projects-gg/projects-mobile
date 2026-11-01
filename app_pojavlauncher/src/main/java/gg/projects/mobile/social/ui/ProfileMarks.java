package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.os.ConfigurationCompat;

import net.kdt.pojavlaunch.R;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import gg.projects.mobile.social.MarkCatalog;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialService;

/**
 * BADGES (docs: badges.md "BADGES-UI için", design.md "Profile badges"; the launcher's person-mark.tsx): where a
 * person's profile badges show on the phone, by the launcher's rules.
 * <ul>
 * <li>{@link #inline}: right of a name that labels its person (rows, titles, a message's sender, members, requests,
 * invites): their first badge 4 dp after the text, whole while the name shortens; TalkBack reads every badge's name
 * after the name ("Ayşe, Projects yöneticisi, Premium Club üyesi").</li>
 * <li>{@link #list}: under the name block of a person's card: every badge with its name, one per line.</li>
 * <li>{@link #sentence}: a line that starts from them (the home's friends row): the first badge right after their
 * name inside the sentence, never on a line of its own.</li>
 * </ul>
 * Never beside a name a sentence only mentions, never in the system's notifications (text only). No motion of its own.
 * Sizes as the launcher's: 12 beside chat names, 14 beside row names, 16 by titles and card names.
 */
public final class ProfileMarks {
    private ProfileMarks() {}

    /** Between a name and its badge (design.md: 4 px). */
    private static final int GAP_DP = 4;
    /** An offline row's badge, as its face (design.md: 70 %). */
    private static final int DIMMED_ALPHA = 179;
    /** The card's list: the drawing, the gap after it and between lines (the launcher's ProfileMarkList). */
    private static final int LIST_MARK_DP = 14;
    private static final int LIST_GAP_DP = 6;
    private static final int LIST_LINE_GAP_DP = 4;

    /** The badges of {@code profile} the catalogue draws, the most important first (none without a profile). */
    static List<MarkCatalog.Def> shown(Context context, @Nullable Profile profile) {
        if (profile == null || profile.marks.isEmpty()) return Collections.emptyList();
        return SocialService.get(context).marks().shown(profile.marks);
    }

    /** Whether the names are said in Turkish: the language the screen's own texts came in. */
    static boolean turkish(Context context) {
        Locale locale = ConfigurationCompat.getLocales(context.getResources().getConfiguration()).get(0);
        return locale != null && "tr".equals(locale.getLanguage());
    }

    /**
     * What a change of {@code profile}'s badges means for a row (its redraw key): "" without badges, else the catalogue's
     * revision and the keys (a new catalogue may rename or redraw them).
     */
    public static String key(SocialService service, @Nullable Profile profile) {
        if (profile == null || profile.marks.isEmpty()) return "";
        return service.marks().rev + ":" + profile.marksKey();
    }

    /** "Ayşe, Projects yöneticisi, Premium Club üyesi": the name, then every badge's name (the name alone without). */
    public static String spoken(Context context, Profile profile) {
        return MarkCatalog.spoken(profile.name(), shown(context, profile), turkish(context));
    }

    /** What TalkBack says for a name {@link #inline} labelled: its description, else its text. */
    public static CharSequence spoken(TextView name) {
        CharSequence said = name.getContentDescription();
        return said != null ? said : name.getText();
    }

    /** {@link #inline} undimmed, without a crew tag. */
    public static void inline(TextView name, @Nullable Profile profile, int dp) {
        inline(name, profile, dp, false, null);
    }

    /**
     * The first badge of {@code profile} right of {@code name} (its end compound drawable, {@code dp} tall: 12, 14 or
     * 16), at 70 % when {@code dimmed} (an offline row); none without a profile or a badge. The name keeps a drawable of
     * its start. Also its TalkBack name, every badge said after it, and {@code tag}, the crew tag shown before the name
     * ("Ayşe, Projects yöneticisi, etiket GK"; it replaces {@link CrewTagChip#labelName}'s).
     */
    public static void inline(TextView name, @Nullable Profile profile, int dp, boolean dimmed,
                              @Nullable SocialModel.Badge tag) {
        Context context = name.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        List<MarkCatalog.Def> shown = shown(context, profile);
        Drawable[] drawables = name.getCompoundDrawablesRelative();
        Drawable end = drawables[2];
        MarkDrawable mark = null;
        if (!shown.isEmpty()) {
            MarkCatalog.Def first = shown.get(0);
            mark = end instanceof MarkDrawable && ((MarkDrawable) end).draws(first, Math.round(dp * density))
                    ? (MarkDrawable) end : MarkDrawable.of(first, dp, density);
        }
        if (mark != null) {
            // An icon after it is the caller's to give again (endIcon).
            boolean resized = mark.setTrailing(null, 0);
            mark.setAlpha(dimmed ? DIMMED_ALPHA : 255);
            mark.anchor(name);
            int gap = Math.round(GAP_DP * density);
            if (end != mark || resized) name.setCompoundDrawablesRelative(drawables[0], drawables[1], mark, drawables[3]);
            if (name.getCompoundDrawablePadding() != gap) name.setCompoundDrawablePadding(gap);
        } else if (end instanceof MarkDrawable) {
            name.setCompoundDrawablesRelative(drawables[0], drawables[1], null, drawables[3]);
        }
        String said = MarkCatalog.spoken(name.getText().toString(), shown, turkish(context));
        name.setContentDescription(tag != null ? context.getString(R.string.projects_badge_row_label, said, tag.tag)
                : shown.isEmpty() ? null : said);
    }

    /**
     * An icon after the name's badge (the chat list's muted bell), drawn at the end of the name's box as without a badge;
     * {@code gapPx} before it. Null takes it off. Call after {@link #inline}, which takes it off too.
     */
    public static void endIcon(TextView name, @Nullable Drawable icon, int gapPx) {
        Drawable[] drawables = name.getCompoundDrawablesRelative();
        if (drawables[2] instanceof MarkDrawable) {
            MarkDrawable mark = (MarkDrawable) drawables[2];
            mark.setTrailing(icon, gapPx);
            // Set again: its width changed, and the name's room with it.
            name.setCompoundDrawablesRelative(drawables[0], drawables[1], mark, drawables[3]);
            return;
        }
        name.setCompoundDrawablesRelative(drawables[0], drawables[1], icon, drawables[3]);
        if (icon != null) name.setCompoundDrawablePadding(gapPx);
    }

    /**
     * Every badge of {@code profile} with its name, under the name block of a person's card, one per line as the
     * launcher's card lists them: a 14 dp drawing, then the name in 12 sp ash. Gone without badges.
     */
    public static void list(LinearLayout list, @Nullable Profile profile) {
        list.removeAllViews();
        Context context = list.getContext();
        List<MarkCatalog.Def> shown = shown(context, profile);
        list.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
        if (shown.isEmpty()) return;
        float density = context.getResources().getDisplayMetrics().density;
        boolean turkish = turkish(context);
        for (int i = 0; i < shown.size(); i++) {
            MarkCatalog.Def def = shown.get(i);
            TextView line = new TextView(context);
            line.setText(def.name(turkish));
            line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            line.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_300));
            line.setMaxLines(1);
            line.setEllipsize(TextUtils.TruncateAt.END);
            line.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            line.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            MarkDrawable mark = MarkDrawable.of(def, LIST_MARK_DP, density);
            line.setCompoundDrawablesRelative(mark, null, null, null);
            line.setCompoundDrawablePadding(Math.round(LIST_GAP_DP * density));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = Math.round(LIST_LINE_GAP_DP * density);
            list.addView(line, lp);
        }
    }

    /**
     * {@code text}, a line that starts from {@code who} (the home's friends row: "Ayşe seni oyuna çağırıyor"), with their
     * first badge right after their name: after the whole word, so a suffix stays with the name ("Ayşe'nin"), and a
     * no-break space under it so the two never part. The plain line without a badge or when the name is not in it.
     * Returns what TalkBack says for it (also the view's description): "Ayşe, Projects yöneticisi, seni oyuna çağırıyor".
     */
    public static String sentence(TextView view, String text, @Nullable Profile who, int dp) {
        Context context = view.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        List<MarkCatalog.Def> shown = shown(context, who);
        int at = who == null || shown.isEmpty() ? -1 : nameEnd(text, who.name());
        MarkDrawable mark = at < 0 ? null : MarkDrawable.of(shown.get(0), dp, density);
        if (mark == null) {
            view.setText(text);
            view.setContentDescription(null);
            return text;
        }
        boolean rtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        SpannableStringBuilder line = new SpannableStringBuilder(text, 0, at);
        line.append('\u00a0');
        line.setSpan(new MarkSpan(mark, Math.round(GAP_DP * density), rtl), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        line.append(text, at, text.length());
        view.setText(line);
        String tail = text.substring(at);
        String said = MarkCatalog.spoken(text.substring(0, at), shown, turkish(context))
                + (tail.startsWith(" ") ? "," : "") + tail;
        view.setContentDescription(said);
        return said;
    }

    /**
     * Where a badge goes in {@code text}: after the first appearance of {@code name} as a word of its own (not inside a
     * longer name: "Can" in "Canberk" or "Can_Blok"), and after a suffix joined to it by an apostrophe ("Ayşe'nin",
     * "Ayşe's"); -1 when it is not there.
     */
    static int nameEnd(String text, @Nullable String name) {
        if (name == null || name.isEmpty()) return -1;
        int from = 0;
        while (true) {
            int at = text.indexOf(name, from);
            if (at < 0) return -1;
            int end = at + name.length();
            boolean starts = at == 0 || !inName(text.codePointBefore(at));
            boolean ends = end == text.length() || !inName(text.codePointAt(end));
            if (starts && ends) {
                if (end < text.length() && isApostrophe(text.charAt(end))) {
                    int suffix = end + 1;
                    while (suffix < text.length() && Character.isLetter(text.charAt(suffix))) suffix++;
                    if (suffix > end + 1) end = suffix;
                }
                return end;
            }
            from = at + 1;
        }
    }

    /** A character a player's name holds (Minecraft-style names: letters, digits, "_"). */
    private static boolean inName(int codePoint) {
        return Character.isLetterOrDigit(codePoint) || codePoint == '_';
    }

    private static boolean isApostrophe(char c) {
        return c == '\'' || c == '\u2019';
    }

    /** The badge inside a sentence: the space under it is as wide as the gap and the drawing, which sits on the line. */
    private static final class MarkSpan extends ReplacementSpan {
        private final MarkDrawable mMark;
        private final int mGap;
        private final boolean mRtl;

        MarkSpan(MarkDrawable mark, int gap, boolean rtl) {
            mMark = mark;
            mGap = gap;
            mRtl = rtl;
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
            // The line keeps the text's height: the badge is no taller than the words beside it.
            if (fm != null) paint.getFontMetricsInt(fm);
            return mGap + mMark.getIntrinsicWidth();
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom,
                         @NonNull Paint paint) {
            Paint.FontMetricsInt fm = paint.getFontMetricsInt();
            float middle = y + (fm.ascent + fm.descent) / 2f;
            int save = canvas.save();
            canvas.translate(mRtl ? x : x + mGap, middle - mMark.getIntrinsicHeight() / 2f);
            mMark.draw(canvas);
            canvas.restoreToCount(save);
        }
    }
}
