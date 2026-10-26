package gg.projects.mobile.cloud.inventory;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

/**
 * One move as the live socket carries it ({@code InvOp} of services/cloud-afk README "Inventory"): click, drop, use, hold,
 * close and snapshot. Nothing else is sent: no "drop all", no number-key swap, no click outside the window.
 */
public final class InvOp {
    public final String action;
    @Nullable
    public final InvLayout.Area area;
    public final int slot;
    public final int button;
    @Nullable
    public final String mode;
    public final boolean stack;
    public final long wseq;

    private InvOp(String action, @Nullable InvLayout.Area area, int slot, int button, @Nullable String mode, boolean stack, long wseq) {
        this.action = action;
        this.area = area;
        this.slot = slot;
        this.button = button;
        this.mode = mode;
        this.stack = stack;
        this.wseq = wseq;
    }

    /** A pickup ({@code mode} "pickup") or shift-click ({@code "quick_move"}); button 0 all, 1 half or one. */
    public static InvOp click(InvLayout.Area area, int slot, int button, String mode, long wseq) {
        return new InvOp("click", area, slot, button, mode, false, wseq);
    }

    public static InvOp drop(InvLayout.Area area, int slot, boolean stack, long wseq) {
        return new InvOp("drop", area, slot, 0, null, stack, wseq);
    }

    /** The hotbar slot's item (0–8), selected first when it is not held; never with a window open. */
    public static InvOp use(int hotbar) {
        return new InvOp("use", null, hotbar, 0, null, false, 0);
    }

    public static InvOp hold(int hotbar) {
        return new InvOp("hold", null, hotbar, 0, null, false, 0);
    }

    public static InvOp close(long wseq) {
        return new InvOp("close", null, -1, 0, null, false, wseq);
    }

    public static InvOp snapshot() {
        return new InvOp("snapshot", null, -1, 0, null, false, 0);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("action", action);
        switch (action) {
            case "click":
                o.addProperty("area", area == InvLayout.Area.WIN ? "win" : "inv");
                o.addProperty("slot", slot);
                o.addProperty("button", button);
                o.addProperty("mode", mode);
                o.addProperty("wseq", wseq);
                break;
            case "drop":
                o.addProperty("area", area == InvLayout.Area.WIN ? "win" : "inv");
                o.addProperty("slot", slot);
                o.addProperty("stack", stack);
                o.addProperty("wseq", wseq);
                break;
            case "use":
            case "hold":
                o.addProperty("slot", slot);
                break;
            case "close":
                o.addProperty("wseq", wseq);
                break;
            default:
                break;
        }
        return o;
    }

    /** The live socket frame: {@code {"t":"invop","acct","id","op"}} (at most 1 KiB). */
    public JsonObject frame(String acct, String id) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "invop");
        o.addProperty("acct", acct);
        o.addProperty("id", id);
        o.add("op", toJson());
        return o;
    }

    /** Whether a move id is one the service takes: 1–32 of {@code [A-Za-z0-9_-]}. */
    public static boolean validId(@Nullable String id) {
        return id != null && id.matches("^[A-Za-z0-9_-]{1,32}$");
    }

    @Override
    public String toString() {
        return toJson().toString();
    }
}
