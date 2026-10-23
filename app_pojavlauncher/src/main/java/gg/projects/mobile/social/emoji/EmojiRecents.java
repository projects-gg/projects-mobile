package gg.projects.mobile.social.emoji;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The picker's memory on the phone (CHAT2 sections 3.1 and 8.5; the desktop's
 * features/emoji/recents.ts and store.ts): "Son kullanilanlar", the emoji and stickers the player
 * sent last, newest first, and the skin tone. Kept on the phone only (they change with every
 * message; a backup would upload them for nothing). The quick reactions read the same emoji.
 *
 * <p>Emoji are kept bare (no skin tone, no VS16), stickers by reference ("pl1/gg"). Every list
 * this returns is a snapshot that never changes: take the recents when the picker opens and
 * keep them, so a pick never shifts the cells under the finger. Thread-safe.
 *
 * <p>Stored as one ASCII JSON string ({@link #toStored()}, {@link #fromStored(String)}), the
 * desktop's keys; whatever was stored is checked, never trusted.
 */
public final class EmojiRecents {
    /** How many the picker keeps: three rows of emoji, two of stickers. */
    public static final int RECENT_EMOJI = 24;
    public static final int RECENT_STICKERS = 8;

    private List<String> emoji = Collections.emptyList();
    private List<String> stickers = Collections.emptyList();
    private int tone = EmojiTone.DEFAULT;

    /** Empty recents and the default tone (a new player). */
    public EmojiRecents() {}

    /** The list with {@code item} first, once, at most {@code max} long (a new list). */
    public static List<String> remember(List<String> list, String item, int max) {
        List<String> out = new ArrayList<>(Math.min(list.size() + 1, Math.max(max, 0)));
        if (max > 0) out.add(item);
        for (String other : list) {
            if (out.size() >= max) break;
            if (!item.equals(other)) out.add(other);
        }
        return Collections.unmodifiableList(out);
    }

    /** Only texts, once each, at most {@code max} (a stored list may come from an older version). */
    public static List<String> cleanList(@Nullable JsonElement value, int max) {
        if (value == null || !value.isJsonArray()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                String text = item.getAsString();
                if (!text.isEmpty() && !out.contains(text)) out.add(text);
            }
            if (out.size() == max) break;
        }
        return Collections.unmodifiableList(out);
    }

    /** The recent emoji that are in the data (by their bare form), in the recents' order. */
    public static List<EmojiData.Entry> recentEntries(EmojiData data, List<String> recents) {
        List<EmojiData.Entry> out = new ArrayList<>();
        for (String recent : recents) {
            EmojiData.Entry entry = data.entryOf(recent);
            if (entry != null && !out.contains(entry)) out.add(entry);
        }
        return out;
    }

    /** The recent emoji, bare, newest first (a snapshot). */
    public synchronized List<String> emoji() {
        return emoji;
    }

    /** The recent sticker references, newest first (a snapshot). */
    public synchronized List<String> stickers() {
        return stickers;
    }

    /** The picker's skin tone, 0 to 5. */
    public synchronized int tone() {
        return tone;
    }

    /** An emoji went into a message or a reaction (any form: it is kept bare). */
    public synchronized void usedEmoji(String used) {
        String bare = EmojiTone.bareEmoji(used);
        if (!bare.isEmpty()) emoji = remember(emoji, bare, RECENT_EMOJI);
    }

    /** A sticker was sent, by its reference ("pl1/gg"). */
    public synchronized void usedSticker(String ref) {
        if (!ref.isEmpty()) stickers = remember(stickers, ref, RECENT_STICKERS);
    }

    public synchronized void setTone(int value) {
        if (!EmojiTone.isSkinTone(value)) throw new IllegalArgumentException("No skin tone " + value);
        tone = value;
    }

    /** The recents and the tone as one string, to keep (in ASCII: emoji are escaped). */
    public synchronized String toStored() {
        JsonObject state = new JsonObject();
        state.add("recentEmoji", array(emoji));
        state.add("recentStickers", array(stickers));
        state.addProperty("tone", tone);
        return asciiOnly(state.toString());
    }

    /** What {@link #toStored()} kept; anything missing or broken falls back to the default. */
    public static EmojiRecents fromStored(@Nullable String stored) {
        EmojiRecents recents = new EmojiRecents();
        if (stored == null || stored.isEmpty()) return recents;
        JsonObject state;
        try {
            JsonElement parsed = JsonParser.parseString(stored);
            if (!parsed.isJsonObject()) return recents;
            state = parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            return recents;
        }
        recents.emoji = cleanList(state.get("recentEmoji"), RECENT_EMOJI);
        recents.stickers = cleanList(state.get("recentStickers"), RECENT_STICKERS);
        recents.tone = toneOf(state.get("tone"));
        return recents;
    }

    private static int toneOf(@Nullable JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return EmojiTone.DEFAULT;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isNumber()) return EmojiTone.DEFAULT;
        double number = primitive.getAsDouble();
        int tone = (int) number;
        return tone == number && EmojiTone.isSkinTone(tone) ? tone : EmojiTone.DEFAULT;
    }

    private static JsonArray array(List<String> list) {
        JsonArray out = new JsonArray();
        for (String item : list) out.add(item);
        return out;
    }

    /** JSON with every character outside printable ASCII written as a JSON escape. */
    private static String asciiOnly(String json) {
        StringBuilder out = new StringBuilder(json.length() + 32);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c >= 0x20 && c < 0x7F) {
                out.append(c);
            } else {
                // Built from parts so no unicode escape sequence appears in this source.
                String hex = Integer.toHexString(c);
                out.append('\\').append('u');
                for (int pad = hex.length(); pad < 4; pad++) out.append('0');
                out.append(hex);
            }
        }
        return out.toString();
    }
}
