package gg.projects.mobile.cloud.movement;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One movement operation as the phone sends it ({@code {"t":"move","acct","id","op"}}; services/cloud-afk README
 * "Movement on the live socket"), and the contract's shape check ({@code test-vectors/move-op-v1.json} {@code shape}):
 * the phone builds only well-formed operations, and the tests hold the check to the vectors.
 */
public final class MoveOp {
    public static final List<String> ACTIONS = Collections.unmodifiableList(
            Arrays.asList("step", "jump", "sneak", "goto", "approach", "follow", "stop"));
    /** Relative to where the account faces ({@code pos.facing}). */
    public static final List<String> DIRS = Collections.unmodifiableList(Arrays.asList("forward", "back", "left", "right"));
    /** The most blocks one step operation goes (a held button sends this). */
    public static final int STEP_MAX = 8;
    /** The farthest a walk to coordinates goes. */
    public static final int GOTO_MAX_BLOCKS = 256;
    public static final int COORD_MAX = 30_000_000;
    public static final int Y_MIN = -2048;
    public static final int Y_MAX = 2048;
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Map<String, List<String>> FIELDS = new HashMap<>();

    static {
        FIELDS.put("step", Arrays.asList("dir", "n"));
        FIELDS.put("jump", Collections.<String>emptyList());
        FIELDS.put("sneak", Collections.singletonList("on"));
        FIELDS.put("goto", Arrays.asList("x", "y", "z"));
        FIELDS.put("approach", Collections.singletonList("name"));
        FIELDS.put("follow", Collections.singletonList("name"));
        FIELDS.put("stop", Collections.<String>emptyList());
    }

    public final JsonObject op;

    private MoveOp(JsonObject op) {
        this.op = op;
    }

    public String action() {
        JsonElement a = op.get("action");
        return a == null ? "" : a.getAsString();
    }

    private static MoveOp of(String action) {
        JsonObject o = new JsonObject();
        o.addProperty("action", action);
        return new MoveOp(o);
    }

    /** {@code n} blocks toward {@code dir} (forward, back, left, right) of where the account faces. */
    public static MoveOp step(String dir, int n) {
        MoveOp m = of("step");
        m.op.addProperty("dir", dir);
        m.op.addProperty("n", Math.max(1, Math.min(STEP_MAX, n)));
        return m;
    }

    public static MoveOp jump() {
        return of("jump");
    }

    public static MoveOp sneak(boolean on) {
        MoveOp m = of("sneak");
        m.op.addProperty("on", on);
        return m;
    }

    public static MoveOp go(int x, int y, int z) {
        MoveOp m = of("goto");
        m.op.addProperty("x", x);
        m.op.addProperty("y", y);
        m.op.addProperty("z", z);
        return m;
    }

    /** Walk next to one of the accounts the service lists ({@code features.movement.follow}), then stop. */
    public static MoveOp approach(String name) {
        MoveOp m = of("approach");
        m.op.addProperty("name", name);
        return m;
    }

    /** Keep walking after one of those accounts (at most 30 minutes). */
    public static MoveOp follow(String name) {
        MoveOp m = of("follow");
        m.op.addProperty("name", name);
        return m;
    }

    public static MoveOp stop() {
        return of("stop");
    }

    /** The client frame with this operation. */
    public JsonObject frame(String acct, String id) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "move");
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

    /** Null when the operation is well-formed, else what is wrong (the contract's {@code shapeProblem}). */
    @Nullable
    public static Problem problem(@Nullable JsonElement element) {
        if (element == null || !element.isJsonObject()) return new Problem("type", null);
        JsonObject op = element.getAsJsonObject();
        JsonElement actionEl = op.get("action");
        String action = actionEl != null && actionEl.isJsonPrimitive() && actionEl.getAsJsonPrimitive().isString() ? actionEl.getAsString() : null;
        if (action == null || !ACTIONS.contains(action)) return new Problem("action", null);
        List<String> fields = FIELDS.get(action);
        for (String key : op.keySet()) {
            if (!"action".equals(key) && !fields.contains(key)) return new Problem("field", key);
        }
        for (String key : fields) if (!op.has(key)) return new Problem(key, null);
        switch (action) {
            case "step": {
                String dir = string(op.get("dir"));
                if (dir == null || !DIRS.contains(dir)) return new Problem("dir", null);
                Long n = integer(op.get("n"));
                if (n == null || n < 1 || n > STEP_MAX) return new Problem("n", null);
                return null;
            }
            case "sneak": {
                JsonElement on = op.get("on");
                return on != null && on.isJsonPrimitive() && on.getAsJsonPrimitive().isBoolean() ? null : new Problem("on", null);
            }
            case "goto": {
                Long x = integer(op.get("x"));
                if (x == null || Math.abs(x) > COORD_MAX) return new Problem("x", null);
                Long y = integer(op.get("y"));
                if (y == null || y < Y_MIN || y > Y_MAX) return new Problem("y", null);
                Long z = integer(op.get("z"));
                if (z == null || Math.abs(z) > COORD_MAX) return new Problem("z", null);
                return null;
            }
            case "approach":
            case "follow": {
                String name = string(op.get("name"));
                return name != null && NAME.matcher(name).matches() ? null : new Problem("name", null);
            }
            default:
                return null;
        }
    }

    @Nullable
    private static String string(@Nullable JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    /** A JSON number that is a whole number within JavaScript's safe range; else null (a string, 1.5, too big). */
    @Nullable
    private static Long integer(@Nullable JsonElement e) {
        if (e == null || !e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) return null;
        double d = p.getAsDouble();
        if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d) || Math.abs(d) > 9007199254740991d) return null;
        return (long) d;
    }

    /** Whether a block is within a walk's reach from where the account stands (block to block; the machine decides). */
    public static boolean withinReach(MoveFrames.Pos from, int x, int y, int z) {
        double dx = x - from.x;
        double dy = y - from.y;
        double dz = z - from.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= GOTO_MAX_BLOCKS;
    }

    /** A name the service may list as a target: what the contract's shape accepts. */
    public static boolean nameOk(@Nullable String name) {
        return name != null && NAME.matcher(name).matches();
    }
}
