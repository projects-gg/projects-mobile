package gg.projects.mobile.social.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.annotation.DrawableRes;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.language.LanguageManager;
import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.emoji.EmojiData;
import gg.projects.mobile.social.emoji.EmojiRecents;
import gg.projects.mobile.social.emoji.EmojiSearch;
import gg.projects.mobile.social.emoji.EmojiText;
import gg.projects.mobile.social.emoji.QuickReactions;
import gg.projects.mobile.social.emoji.StickerAnimator;
import gg.projects.mobile.social.emoji.StickerSet;

/**
 * MOB1 (CHAT2 §3, §8): the phone's side of emoji and stickers: the emoji data of the app's language
 * (read off the main thread when chat first needs it, once per process), the player's recent emoji and
 * stickers and their skin tone (this phone only: they change often and are nobody else's), the quick
 * reactions they feed, the Küp set's drawables and names, and the one-time sticker motions (only
 * transform and opacity; with animations off an arrival only fades in and a press does nothing).
 */
public final class EmojiStore {
    private EmojiStore() {}

    private static final String TAG = "ProjectsSocial";
    private static final String PREFS = "projects_emoji";
    private static final String KEY = "recents";

    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Nullable
    private static EmojiRecents sRecents;
    @Nullable
    private static StickerSet sStickers;
    @Nullable
    private static volatile EmojiData sData;
    private static boolean sLoading;
    private static final List<Runnable> WAITING = new ArrayList<>();
    private static final Map<String, Integer> DRAWABLES = new HashMap<>();

    // ---- recents and tone ----

    private static EmojiRecents recents(Context context) {
        if (sRecents == null) {
            SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            sRecents = EmojiRecents.fromStored(prefs.getString(KEY, null));
        }
        return sRecents;
    }

    private static void save(Context context) {
        if (sRecents == null) return;
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, sRecents.toStored()).apply();
    }

    /** The six quick reactions: the player's recent emoji first, then 👍 ❤️ 😂 😭 🔥 😮 (never the middle finger). */
    public static List<String> quickReactions(Context context) {
        return QuickReactions.of(recents(context).emoji(), sData);
    }

    public static List<String> recentEmoji(Context context) {
        return recents(context).emoji();
    }

    public static List<String> recentStickers(Context context) {
        return recents(context).stickers();
    }

    public static void usedEmoji(Context context, String emoji) {
        recents(context).usedEmoji(emoji);
        save(context);
    }

    public static void usedSticker(Context context, String ref) {
        recents(context).usedSticker(ref);
        save(context);
    }

    /** The chosen skin tone (0: the default yellow; 1–5 the five tones). */
    public static int tone(Context context) {
        return recents(context).tone();
    }

    public static void setTone(Context context, int tone) {
        recents(context).setTone(tone);
        save(context);
    }

    // ---- the emoji data ----

    /** The data of the app's language when it is read already, else null (see {@link #whenLoaded}). */
    @Nullable
    public static EmojiData data() {
        return sData;
    }

    /** Runs {@code then} on the main thread once the data is read (at once when it is). */
    @MainThread
    public static void whenLoaded(Context context, final Runnable then) {
        final String lang = LanguageManager.isTurkish(context) ? "tr" : "en";
        EmojiData data = sData;
        if (data != null && data.lang().equals(lang)) {
            then.run();
            return;
        }
        WAITING.add(then);
        if (sLoading) return;
        sLoading = true;
        final Context app = context.getApplicationContext();
        LOADER.execute(() -> {
            EmojiData read = null;
            try (InputStream in = app.getAssets().open(EmojiData.assetPath(lang))) {
                read = EmojiData.parse(in);
                EmojiSearch.prepare(read);
                EmojiSheet.warm(read);
            } catch (Exception e) {
                Log.w(TAG, "emoji data not read", e);
            }
            final EmojiData done = read;
            MAIN.post(() -> {
                sLoading = false;
                if (done != null) sData = done;
                List<Runnable> waiting = new ArrayList<>(WAITING);
                WAITING.clear();
                for (Runnable r : waiting) r.run();
            });
        });
    }

    /** One to three emoji alone: drawn big without a bubble (36 sp; WhatsApp's way). */
    public static boolean isBigEmoji(@Nullable String text) {
        return EmojiText.bigEmojiCount(text) > 0;
    }

    // ---- stickers ----

    /** The Küp set (read once from the app's assets). */
    @Nullable
    public static StickerSet stickers(Context context) {
        if (sStickers == null) {
            try (InputStream in = context.getAssets().open(StickerSet.PL1_ASSET)) {
                sStickers = StickerSet.parse(in);
            } catch (Exception e) {
                Log.w(TAG, "sticker set not read", e);
            }
        }
        return sStickers;
    }

    /** The drawable of a sticker the app has ({@code pl1/gg}), or 0 (an unknown one: a quiet "Çıkartma" box). */
    @DrawableRes
    public static int stickerRes(Context context, @Nullable String ref) {
        StickerSet set = stickers(context);
        StickerSet.Sticker sticker = set == null ? null : set.byRef(ref);
        if (sticker == null) return 0;
        Integer known = DRAWABLES.get(sticker.drawable);
        if (known != null) return known;
        int id = context.getResources().getIdentifier(sticker.drawable, "drawable", context.getPackageName());
        DRAWABLES.put(sticker.drawable, id);
        return id;
    }

    /** A sticker's name in the app's language ("GG"), or null for one the app does not have. */
    @Nullable
    public static String stickerName(Context context, @Nullable String ref) {
        StickerSet set = stickers(context);
        StickerSet.Sticker sticker = set == null ? null : set.byRef(ref);
        return sticker == null ? null : sticker.name(LanguageManager.isTurkish(context) ? "tr" : "en");
    }

    /**
     * A sticker's one motion (its own pattern; a running one finishes first). With animations off an
     * arrival only fades in and a press does nothing.
     */
    public static void playSticker(View image, @Nullable String ref, boolean arrival) {
        Context context = image.getContext();
        StickerSet set = stickers(context);
        StickerSet.Sticker sticker = set == null ? null : set.byRef(ref);
        if (sticker == null) return;
        boolean motion = Motion.enabled(context);
        if (!motion && !arrival) return;
        StickerAnimator.play(image, sticker.motion, !motion);
    }

    /** A recycled view stops what it was doing for another sticker. */
    public static void stopSticker(View image) {
        StickerAnimator.cancel(image);
    }
}
