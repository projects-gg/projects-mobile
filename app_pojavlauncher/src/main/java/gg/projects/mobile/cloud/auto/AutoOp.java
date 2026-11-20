package gg.projects.mobile.cloud.auto;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * One auto operation as the phone sends it ({@code {"t":"auto","acct","id","op"}}; services/cloud-afk README "Auto on
 * the live socket"): pause or resume a mode (resume anchors the spot again), or aim where the account looks while a
 * mode is granted. The contract's shape check ({@code test-vectors/auto-op-v1.json} {@code shape}) holds what the phone
 * builds.
 */
public final class AutoOp {
    public static final List<String> MODES = Collections.unmodifiableList(Arrays.asList(AutoSettings.FISHING, AutoSettings.MINING));
    public static final List<String> ACTIONS = Collections.unmodifiableList(Arrays.asList("pause", "resume", "aim"));
    /** Yaw -180 to 180 (the game's: 0 south, 90 west, 180 or -180 north, -90 east); pitch -90 (up) to 90 (down). */
    public static final int YAW_MAX = 180;
    public static final int PITCH_MAX = 90;

    public final JsonObject op;

    private AutoOp(JsonObject op) {
        this.op = op;
    }

    public String action() {
        JsonElement a = op.get("action");
        return a == null ? "" : a.getAsString();
    }

    private static AutoOp mode(String mode, String action) {
        JsonObject o = new JsonObject();
        o.addProperty("mode", mode);
        o.addProperty("action", action);
        return new AutoOp(o);
    }

    public static AutoOp pause(String mode) {
        return mode(mode, "pause");
    }

    /** Goes on where it stands now: the spot is anchored again. */
    public static AutoOp resume(String mode) {
        return mode(mode, "resume");
    }

    /** Looks this way: whole degrees, within the contract's range. */
    public static AutoOp aim(int yaw, int pitch) {
        JsonObject o = new JsonObject();
        o.addProperty("action", "aim");
        o.addProperty("yaw", Math.max(-YAW_MAX, Math.min(YAW_MAX, yaw)));
        o.addProperty("pitch", Math.max(-PITCH_MAX, Math.min(PITCH_MAX, pitch)));
        return new AutoOp(o);
    }

    /** The client frame with this operation. */
    public JsonObject frame(String acct, String id) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "auto");
        o.addProperty("acct", acct);
        o.addProperty("id", id);
        o.add("op", op.deepCopy());
        return o;
    }

    /** {@code invalid}'s details: {@code reason} and, for a field the action does not take, {@code field}. */
    public static final class Problem {
        public final String reason;
        @Nullable
        public final String field;

        Problem(String reason, @Nullable String field) {
            this.reason = reason;
            this.field = field;
        }

        @Override
        public String toString() {
            return field == null ? reason : reason + ":" + field;
        }
    }

    /** Null when the operation is well-formed, else what is wrong (the service's {@code autoOpProblem}). */
    @Nullable
    public static Problem problem(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) return new Problem("type", null);
        JsonObject op = element.getAsJsonObject();
        JsonElement actionEl = op.get("action");
        String action = actionEl != null && actionEl.isJsonPrimitive() && actionEl.getAsJsonPrimitive().isString() ? actionEl.getAsString() : null;
        if (action == null || !ACTIONS.contains(action)) return new Problem("action", null);
        List<String> fields = "aim".equals(action) ? Arrays.asList("yaw", "pitch") : Collections.singletonList("mode");
        for (String key : op.keySet()) {
            if (!"action".equals(key) && !fields.contains(key)) return new Problem("field", key);
        }
        for (String key : fields) if (!op.has(key)) return new Problem(key, null);
        if (!"aim".equals(action)) {
            JsonElement mode = op.get("mode");
            boolean known = mode.isJsonPrimitive() && mode.getAsJsonPrimitive().isString() && MODES.contains(mode.getAsString());
            return known ? null : new Problem("mode", null);
        }
        if (!angle(op.get("yaw"), YAW_MAX)) return new Problem("yaw", null);
        if (!angle(op.get("pitch"), PITCH_MAX)) return new Problem("pitch", null);
        return null;
    }

    /** A finite JSON number from -{@code max} to {@code max} (fractions allowed). */
    private static boolean angle(@Nullable JsonElement e, int max) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return false;
        double d = e.getAsDouble();
        return !Double.isNaN(d) && !Double.isInfinite(d) && d >= -max && d <= max;
    }
}
