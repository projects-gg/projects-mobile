package gg.projects.mobile.cloud;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

import gg.projects.mobile.social.Json;

/**
 * What 7/24 Bulut remembers on the phone between starts, all of it harmless to lose: whether the service and AFK+
 * were on (so the main menu does not pop its row in and out at each start, and a push that wakes the app knows
 * whether to show), the last summary of the player's accounts for the main menu's first frame, and the items'
 * names for notifications ("Elytra" instead of {@code minecraft:elytra}). No token, no secret, no message text.
 */
public final class CloudPrefs {
    private static final String FILE = "projects_cloud";
    private final SharedPreferences mPrefs;

    public CloudPrefs(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public boolean offerOn() {
        return mPrefs.getBoolean("offerOn", false);
    }

    public boolean plusOn() {
        return mPrefs.getBoolean("plusOn", false);
    }

    public void setOffer(boolean on, boolean plus) {
        if (on == offerOn() && plus == plusOn()) return;
        mPrefs.edit().putBoolean("offerOn", on).putBoolean("plusOn", plus).apply();
    }

    /** The main menu's last summary: accounts, in game, approvals waiting, a stopped account's name (or ""). */
    public static final class Summary {
        public final String user;
        public final int count;
        public final int inGame;
        public final int approvals;
        public final int unread;
        public final String stopped;

        public Summary(String user, int count, int inGame, int approvals, int unread, String stopped) {
            this.user = user;
            this.count = count;
            this.inGame = inGame;
            this.approvals = approvals;
            this.unread = unread;
            this.stopped = stopped;
        }
    }

    @Nullable
    public Summary summary() {
        String user = mPrefs.getString("summaryUser", null);
        if (user == null) return null;
        return new Summary(user, mPrefs.getInt("summaryCount", 0), mPrefs.getInt("summaryInGame", 0), mPrefs.getInt("summaryApprovals", 0),
                mPrefs.getInt("summaryUnread", 0), mPrefs.getString("summaryStopped", ""));
    }

    public void setSummary(@Nullable Summary s) {
        SharedPreferences.Editor e = mPrefs.edit();
        if (s == null) {
            e.remove("summaryUser").remove("summaryCount").remove("summaryInGame").remove("summaryApprovals").remove("summaryUnread")
                    .remove("summaryStopped");
        } else {
            e.putString("summaryUser", s.user).putInt("summaryCount", s.count).putInt("summaryInGame", s.inGame)
                    .putInt("summaryApprovals", s.approvals).putInt("summaryUnread", s.unread).putString("summaryStopped", s.stopped);
        }
        e.apply();
    }

    /**
     * Items' Turkish and English names the screens showed (the item data's, and each account's custom items under
     * {@code <acct>|custom:<k>}), for notices that come while no screen has the data: {@code names} maps a key to
     * {tr, en}.
     */
    public void rememberItems(Map<String, String[]> remembered) {
        if (remembered.isEmpty()) return;
        JsonObject names = Json.parseObject(mPrefs.getString("itemNames", null));
        if (names == null) names = new JsonObject();
        boolean changed = false;
        for (Map.Entry<String, String[]> item : remembered.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("tr", item.getValue()[0]);
            entry.addProperty("en", item.getValue()[1]);
            if (!entry.equals(names.get(item.getKey()))) {
                names.remove(item.getKey());
                names.add(item.getKey(), entry);
                changed = true;
            }
        }
        // A bounded record: the newest names win.
        if (names.size() > 600) {
            JsonObject trimmed = new JsonObject();
            int skip = names.size() - 600;
            for (Map.Entry<String, JsonElement> e : names.entrySet()) {
                if (skip-- > 0) continue;
                trimmed.add(e.getKey(), e.getValue());
            }
            names = trimmed;
            changed = true;
        }
        if (changed) mPrefs.edit().putString("itemNames", names.toString()).apply();
    }

    /** A remembered name in {@code lang} ({@code key} as {@link #rememberItems} keeps it), or null. */
    @androidx.annotation.Nullable
    public String rememberedName(String key, String lang) {
        JsonObject names = Json.parseObject(mPrefs.getString("itemNames", null));
        JsonObject entry = Json.obj(names, key);
        String name = entry == null ? null : Json.str(entry, "en".equals(lang) ? "en" : "tr");
        return name == null || name.isEmpty() ? null : name;
    }

    /** An item's name in {@code lang}; one the app never saw reads without its {@code minecraft:} part. */
    public String itemName(String id, String lang) {
        String name = rememberedName(id, lang);
        if (name != null) return name;
        return bareName(id);
    }

    /** {@code minecraft:diamond_block} → "Diamond block" (an item no data names yet). */
    public static String bareName(String id) {
        int colon = id.lastIndexOf(':');
        String bare = (colon >= 0 ? id.substring(colon + 1) : id).replace('_', ' ');
        return bare.isEmpty() ? id : Character.toUpperCase(bare.charAt(0)) + bare.substring(1);
    }

    /** Signed out: the player's summary goes (item names are not the player's). */
    public void clearAccount() {
        setSummary(null);
    }
}
