package gg.projects.mobile.voice.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.ColorUtils;

import net.kdt.pojavlaunch.R;

/**
 * M051 (SHARE-LIVE on the phone; design.md §6 "Live tag"): "YAYINDA" / "LIVE" right after the name of someone who shares
 * their screen, the one all-caps tag. The desktop's {@code Chip tone="jade"} at a crew tag's size, drawn as the phone's
 * crew tag is ({@link gg.projects.mobile.social.ui.CrewTagChip}: 18 dp, Tektur 11 sp, a 4 dp corner): jade words on jade
 * at 15 %. No light, no pulse, no motion. Not a button: the row it sits in says "yayında" to TalkBack.
 */
public final class LiveTag {
    private LiveTag() {}

    /** A tag, hidden until {@link #show} ({@code small}: 16 dp, for lists inside sheets). */
    public static TextView make(Context c, boolean small) {
        float dp = c.getResources().getDisplayMetrics().density;
        TextView t = new TextView(c);
        t.setText(R.string.projects_voice_live_tag);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setMaxLines(1);
        t.setLetterSpacing(0.06f);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, small ? 10f : 11f);
        int jade = ContextCompat.getColor(c, R.color.projects_jade_400);
        t.setTextColor(jade);
        try {
            t.setTypeface(ResourcesCompat.getFont(c, R.font.tektur_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        int height = Math.round((small ? 16 : 18) * dp);
        t.setMinHeight(height);
        t.setHeight(height);
        int pad = Math.round((small ? 4 : 6) * dp);
        t.setPadding(pad, 0, pad, 0);
        GradientDrawable plate = new GradientDrawable();
        plate.setCornerRadius(4 * dp);
        plate.setColor(ColorUtils.setAlphaComponent(jade, Math.round(255 * 0.15f)));
        t.setBackground(plate);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        t.setVisibility(View.GONE);
        return t;
    }

    /** The tag's place after a name: 6 dp from it, never shrinking. */
    public static LinearLayout.LayoutParams after(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(Math.round(6 * c.getResources().getDisplayMetrics().density));
        return lp;
    }

    public static void show(View tag, boolean live) {
        tag.setVisibility(live ? View.VISIBLE : View.GONE);
    }

    /**
     * SQOL ({@code share2}'s {@code st}): a paused share says "DURAKLATILDI", a minimized shared window "KÜÇÜLTÜLDÜ",
     * quiet (ash on ash); a live one "YAYINDA" in jade. Same size and place, no motion.
     */
    public static void state(TextView tag, @Nullable String st) {
        Context c = tag.getContext();
        boolean held = "hold".equals(st);
        boolean min = "min".equals(st);
        tag.setText(held ? R.string.projects_voice_paused_tag : min ? R.string.projects_voice_minimized_tag : R.string.projects_voice_live_tag);
        int tone = ContextCompat.getColor(c, held || min ? R.color.projects_ash_300 : R.color.projects_jade_400);
        tag.setTextColor(tone);
        if (tag.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) tag.getBackground().mutate()).setColor(ColorUtils.setAlphaComponent(tone, Math.round(255 * 0.15f)));
        }
    }

    /** "Name, yayında" / "Name, yayın duraklatıldı" / "Name, paylaşılan pencere küçültüldü" for a row's spoken label. */
    public static String spoken(Context c, String label, boolean live, @Nullable String st) {
        if (!live) return label;
        if ("hold".equals(st)) return label + ", " + c.getString(R.string.projects_voice_paused_spoken);
        if ("min".equals(st)) return label + ", " + c.getString(R.string.projects_voice_minimized_spoken);
        return spoken(c, label, true);
    }

    /**
     * A person row of the friends and crew lists ({@code item_projects_social_person}): its tag after the name, made the
     * first time it shows.
     */
    public static void bindRow(View row, boolean live) {
        View holder = row.findViewById(R.id.projects_person_name_line);
        if (!(holder instanceof LinearLayout)) return;
        LinearLayout line = (LinearLayout) holder;
        View tag = line.findViewWithTag(ROW_TAG);
        if (tag == null) {
            if (!live) return;
            tag = make(row.getContext(), false);
            tag.setTag(ROW_TAG);
            line.addView(tag, after(row.getContext()));
        }
        show(tag, live);
    }

    /** Whether voice chat says {@code id} shares their screen now (friends and crewmates the player sees). */
    public static boolean sharing(String id) {
        gg.projects.mobile.voice.VoiceHub hub = gg.projects.mobile.voice.VoiceHub.instance();
        return hub != null && hub.prefs().enabled() && hub.rings().sharing(id);
    }

    /**
     * What a person row shows of voice chat, for a list's diff: in a voice room ("Sesli sohbette") and sharing
     * ("YAYINDA"). Without it a row kept its old line when only voice chat changed.
     */
    public static String key(String id) {
        gg.projects.mobile.voice.VoiceHub hub = gg.projects.mobile.voice.VoiceHub.instance();
        if (hub == null || !hub.prefs().enabled()) return "";
        return (hub.rings().inVoice(id) ? "v" : "") + (hub.rings().sharing(id) ? "l" : "");
    }

    private static final String ROW_TAG = "projects-live-tag";

    /** "Name, yayında" for a row's spoken label. */
    public static String spoken(Context c, String label, boolean live) {
        return live ? label + ", " + c.getString(R.string.projects_voice_live_spoken) : label;
    }
}
