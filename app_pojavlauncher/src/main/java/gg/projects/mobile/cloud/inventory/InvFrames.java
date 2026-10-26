package gg.projects.mobile.cloud.inventory;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.List;

import gg.projects.mobile.social.Json;

/**
 * The live socket's inventory messages (services/cloud-afk README "Inventory" → Live socket): {@code invst},
 * {@code inv}, {@code win} and the answer to a move, {@code invres}. Read leniently; an unknown reason reads as the
 * most general one of its kind.
 */
public final class InvFrames {
    private InvFrames() {}

    /** {@code invst}: whether the inventory shows now, and why not. */
    public static final class Status {
        /** {@code ok} or {@code off}. */
        public final String state;
        /** With {@code off}: {@code not_running}, {@code setting}, {@code restart}, {@code version} or {@code service}. */
        @Nullable
        public final String reason;
        /** With {@code ok}: {@code trade} while an AFK+ deal or its trade window is open (moves are refused). */
        @Nullable
        public final String lock;

        public Status(String state, @Nullable String reason, @Nullable String lock) {
            this.state = "ok".equals(state) ? "ok" : "off";
            this.reason = reason;
            this.lock = lock;
        }

        public boolean ok() {
            return "ok".equals(state);
        }

        public boolean tradeLock() {
            return ok() && "trade".equals(lock);
        }

        public static Status read(@Nullable JsonObject o) {
            String reason = Json.str(o, "reason");
            if (reason != null) {
                switch (reason) {
                    case "not_running":
                    case "setting":
                    case "restart":
                    case "version":
                    case "service":
                        break;
                    default:
                        // A reason this app does not know yet: the view says nothing it cannot back up.
                        reason = "service";
                        break;
                }
            }
            return new Status(Json.str(o, "state", "off"), reason, Json.str(o, "lock"));
        }
    }

    /** {@code inv}: the player inventory (slots 5–45), the held hotbar slot and the cursor's item. */
    public static final class Inv {
        public final int held;
        @Nullable
        public final InvItem cursor;
        public final List<InvItem> items;
        public final boolean snap;

        public Inv(int held, @Nullable InvItem cursor, List<InvItem> items, boolean snap) {
            this.held = Math.max(0, Math.min(8, held));
            this.cursor = cursor;
            this.items = Collections.unmodifiableList(items);
            this.snap = snap;
        }

        public static Inv read(@Nullable JsonObject o) {
            JsonElement cursorEl = o == null ? null : o.get("cursor");
            InvItem cursor = cursorEl != null && cursorEl.isJsonObject() ? InvItem.read(cursorEl.getAsJsonObject(), false) : null;
            List<InvItem> items = InvItem.list(o != null && o.has("items") && o.get("items").isJsonArray() ? o.getAsJsonArray("items") : null, true);
            // The player inventory's own slots only (5–8 armour, 9–35, 36–44 hotbar, 45 off hand).
            for (int i = items.size() - 1; i >= 0; i--) {
                int slot = items.get(i).slot;
                if (slot < InvLayout.ARMOR_FIRST || slot > InvLayout.OFFHAND_SLOT) items.remove(i);
            }
            return new Inv((int) Json.num(o, "held", 0), cursor, items, Json.bool(o, "snap", false));
        }
    }

    /** {@code win}: the window the server opened (with its own slots' items), or that it closed. */
    public static final class Win {
        public final long wseq;
        @Nullable
        public final String type;
        public final String title;
        public final int size;
        public final List<InvItem> items;
        public final boolean closed;

        public Win(long wseq, @Nullable String type, String title, int size, List<InvItem> items, boolean closed) {
            this.wseq = wseq;
            this.type = type;
            this.title = title == null ? "" : title;
            this.size = Math.max(0, Math.min(54, size));
            this.items = Collections.unmodifiableList(items);
            this.closed = closed;
        }

        public static Win read(@Nullable JsonObject o) {
            long wseq = Json.num(o, "wseq", 0);
            if (Json.bool(o, "closed", false)) return new Win(wseq, null, "", 0, Collections.<InvItem>emptyList(), true);
            JsonObject open = Json.obj(o, "open");
            int size = (int) Json.num(open, "size", 0);
            List<InvItem> items = InvItem.list(o != null && o.has("items") && o.get("items").isJsonArray() ? o.getAsJsonArray("items") : null, true);
            int limit = size > 0 ? Math.min(size, 54) : 54;
            for (int i = items.size() - 1; i >= 0; i--) if (items.get(i).slot >= limit) items.remove(i);
            return new Win(wseq, Json.str(open, "type", "unknown"), Json.str(open, "title", ""), size, items, open == null);
        }
    }

    /** {@code invres}'s error: {@code code} with its {@code reason}, {@code field} and {@code retryAfter}. */
    public static final class Error {
        public final String code;
        @Nullable
        public final String reason;
        @Nullable
        public final String field;
        public final long retryAfter;

        public Error(String code, @Nullable String reason, @Nullable String field, long retryAfter) {
            this.code = code;
            this.reason = reason;
            this.field = field;
            this.retryAfter = Math.max(0, retryAfter);
        }

        public static Error read(@Nullable JsonObject o) {
            return new Error(Json.str(o, "code", "invalid"), Json.str(o, "reason"), Json.str(o, "field"), Json.num(o, "retryAfter", 0));
        }

        public static Error of(String code) {
            return new Error(code, null, null, 0);
        }
    }
}
