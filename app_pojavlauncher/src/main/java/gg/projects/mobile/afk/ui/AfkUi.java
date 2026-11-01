package gg.projects.mobile.afk.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.FontRes;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.util.HashMap;
import java.util.Map;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.afk.api.AfkReason;
import gg.projects.mobile.afk.api.AfkStatus;
import gg.projects.mobile.afk.api.AfkTexts;
import gg.projects.mobile.afk.api.PhoneAfk;
import gg.projects.mobile.cloud.CloudStatus;
import gg.projects.mobile.cloud.ui.CloudUi;
import gg.projects.mobile.cloud.ui.SignalGlyph;
import gg.projects.mobile.ui.HeadPlaceholderDrawable;
import gg.projects.mobile.ui.PixelHeadDrawable;

/**
 * What the phone AFK screens share (PHONE-AFK, AFK-M-UI): whether the feature shows, the way to its screens, an
 * account's state in the desktop's words with the server bars, and its head. The pieces are 7/24 Bulut's
 * ({@link CloudUi}), so the two AFK places on the phone look the same.
 */
public final class AfkUi {
    private AfkUi() {}

    /** The site's 7/24 Bulut page, counted as coming from phone AFK (phone-afk.md §1.5, {@code kaynak=telefon-afk}). */
    public static final String CLOUD_URL = "https://afk.projects.gg/?kaynak=telefon-afk";

    /**
     * The AFK screens show on this phone: this build and Android have it and Studio's {@code features.phoneAfk} is on
     * in the latest content; before the content is read, accounts still running keep it (the switch turned off stops
     * them, so running accounts mean it was on).
     */
    @MainThread
    public static boolean available(Context context) {
        if (!PhoneAfk.phoneSupported()) return false;
        gg.projects.mobile.content.Snapshot snapshot = ProjectsContent.current();
        if (PhoneAfk.available(snapshot == null ? null : snapshot.config)) return true;
        return snapshot == null && PhoneAfk.get(context).runningCount() > 0;
    }

    /** The AFK list (from anywhere in the launcher: the home row, a notification). */
    public static void openList(FragmentActivity activity) {
        Tools.swapFragment(activity, AfkFragment.class, AfkFragment.TAG, null);
    }

    /** The list, then one account's console on top of it (back walks the same way). */
    public static void openAccount(FragmentActivity activity, String accountId) {
        openList(activity);
        Tools.swapFragment(activity, AfkConsoleFragment.class, AfkConsoleFragment.TAG, AfkConsoleFragment.args(accountId));
    }

    // ---- words ----

    /**
     * An account's state line, the desktop's words: "Oyunda, 2 saat 5 dakika", "45 saniye sonra yeniden bağlanacak",
     * "Wi-Fi bekleniyor", "Durdu: Giriş şifresi yanlış", "Durduruldu".
     */
    public static String state(Context c, AfkStatus s, long now) {
        long up = AfkLook.uptime(s, now);
        if (up >= 0) return c.getString(R.string.projects_afk_ui_state_in_game, span(c, up));
        if (AfkLook.stoppedBecause(s)) {
            return c.getString(R.string.projects_afk_ui_state_stopped_because, AfkTexts.reason(c, s.reason));
        }
        return AfkTexts.state(c, s, now);
    }

    /** A span in words, two units at most ("1 saat 5 dakika", "45 saniye"). */
    public static String span(Context c, long ms) {
        int[] parts = AfkLook.span(ms);
        StringBuilder out = new StringBuilder(unit(c, parts[0], parts[1]));
        if (parts.length > 2) out.append(' ').append(unit(c, parts[2], parts[3]));
        return out.toString();
    }

    private static String unit(Context c, int value, int unit) {
        int plural;
        switch (AfkLook.Unit.values()[unit]) {
            case SECOND: plural = R.plurals.projects_afk_ui_span_seconds; break;
            case MINUTE: plural = R.plurals.projects_afk_ui_span_minutes; break;
            case HOUR: plural = R.plurals.projects_afk_ui_span_hours; break;
            default: plural = R.plurals.projects_afk_ui_span_days; break;
        }
        return c.getResources().getQuantityString(plural, value, value);
    }

    /** The reason's words, the empty string for none. */
    public static String reason(Context c, @Nullable AfkReason reason) {
        return AfkTexts.reason(c, reason);
    }

    // ---- looks ----

    public static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    public static int color(Context c, @ColorRes int res) {
        return ContextCompat.getColor(c, res);
    }

    public static Typeface font(Context c, @FontRes int font) {
        Typeface face = ResourcesCompat.getFont(c, font);
        return face == null ? Typeface.DEFAULT : face;
    }

    /** The words' colour for a tone: bone, ash, bone after the gold triangle, flare (design.md §2). */
    @ColorRes
    public static int toneColor(AfkLook.Tone tone) {
        switch (tone) {
            case OK:
            case WARN:
                return R.color.projects_bone_100;
            case ERROR:
                return R.color.projects_flare_400;
            default:
                return R.color.projects_ash_300;
        }
    }

    public static CloudStatus.Signal signal(AfkLook.Signal signal) {
        switch (signal) {
            case ONLINE: return CloudStatus.Signal.ONLINE;
            case PENDING: return CloudStatus.Signal.PENDING;
            case OFFLINE: return CloudStatus.Signal.OFFLINE;
            default: return CloudStatus.Signal.IDLE;
        }
    }

    /** The server bars and the state's words on one line (the desktop's state line). */
    public static final class StateLine {
        public final LinearLayout view;
        public final TextView text;
        private final SignalGlyph mGlyph;
        private String mShown = "";
        private AfkLook.Tone mTone;

        public StateLine(Context c, float sp) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            int unit = Math.max(1, Math.round(c.getResources().getDisplayMetrics().density * 1.25f));
            mGlyph = new SignalGlyph(unit);
            ImageView mark = new ImageView(c);
            mark.setImageDrawable(mGlyph);
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view.addView(mark, new LinearLayout.LayoutParams(mGlyph.getIntrinsicWidth(), mGlyph.getIntrinsicHeight()));
            text = CloudUi.text(c, "", sp, R.color.projects_ash_300, false);
            text.setSingleLine(false);
            text.setMaxLines(2);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(dp(c, 8));
            view.addView(text, lp);
        }

        /** Writes the account's state; the words and the bars change only when they changed (no relayout per tick). */
        public void set(AfkStatus s, long now) {
            Context c = view.getContext();
            String words = state(c, s, now);
            AfkLook.Tone tone = AfkLook.tone(s);
            if (!words.equals(mShown)) {
                mShown = words;
                text.setText(words);
            }
            if (tone != mTone) {
                mTone = tone;
                text.setTextColor(color(c, toneColor(tone)));
                CloudUi.warnMark(text, tone == AfkLook.Tone.WARN);
            }
            mGlyph.setSignal(signal(AfkLook.signal(s)));
        }

        public String words() {
            return mShown;
        }
    }

    // ---- heads ----

    private static final Map<String, Drawable> HEADS = new HashMap<>();

    /**
     * The account's head (the desktop's {@code afk-head.tsx}): the phone's own head for the same name when it has one
     * (an offline account's, drawn from the game jar), else the quiet placeholder tile. No avatar service.
     */
    public static Drawable head(Context c, String name, int sizeDp) {
        String key = name.toLowerCase(java.util.Locale.ROOT) + "@" + sizeDp;
        Drawable cached = HEADS.get(key);
        if (cached != null) return cached;
        float density = c.getResources().getDisplayMetrics().density;
        Bitmap face = null;
        try {
            face = MinecraftAccount.getSkinFace(name);
        } catch (RuntimeException ignored) {
            // A damaged account file: the placeholder.
        }
        if (face == null) return new HeadPlaceholderDrawable(c, Math.round(sizeDp * density), 3 * density);
        Drawable drawable = new PixelHeadDrawable(face, Math.round(sizeDp * density), 3 * density);
        if (HEADS.size() > 64) HEADS.clear();
        HEADS.put(key, drawable);
        return drawable;
    }

    /** A 20 dp icon in ash for a row's lead (the phone settings' rows). */
    public static ImageView glyph(Context c, int icon) {
        ImageView glyph = new ImageView(c);
        glyph.setImageResource(icon);
        glyph.setImageTintList(ColorStateList.valueOf(color(c, R.color.projects_ash_300)));
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return glyph;
    }
}
