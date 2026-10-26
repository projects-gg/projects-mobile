package gg.projects.mobile.cloud;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.util.Map;

/**
 * Several accounts at once (services/cloud-afk README "Bulk management"): the account routes one account at a time, in
 * the list's order, waiting for each answer; one result line with the first failure's reason. Copying settings sends one
 * {@code PATCH} per target with its own revision; secret rows are sealed again for every target (a seal names its
 * account, row and server), from words the player types once for all or per account. Never the name, the language or
 * the inventory or movement choice; the server and version only when asked. No bulk delete.
 */
public final class CloudBulk {
    private CloudBulk() {}

    public enum Action { START, STOP, RESTART, COPY }

    /** The reasons the result line names (the contract's table), by the service's error code. */
    public enum Reason { EXPIRED, STAFF, BLOCKED, FULL, CHANGE_LIMIT, NOT_RUNNING, RATE_LIMITED, CONFLICT, OFFLINE, OTHER }

    public static Reason reason(@Nullable String code) {
        if (code == null) return Reason.OTHER;
        switch (code) {
            case "account_expired": return Reason.EXPIRED;
            case "account_suspended":
            case "player_restricted":
                return Reason.STAFF;
            case "server_blocked": return Reason.BLOCKED;
            case "server_full": return Reason.FULL;
            case "server_change_limit": return Reason.CHANGE_LIMIT;
            case "not_running": return Reason.NOT_RUNNING;
            case "rate_limited": return Reason.RATE_LIMITED;
            case "rev_conflict": return Reason.CONFLICT;
            case "host_offline": return Reason.OFFLINE;
            default: return Reason.OTHER;
        }
    }

    /** How a bulk action went: how many worked, how many did not and the first failure's reason. */
    public static final class Outcome {
        public int done;
        public int failed;
        @Nullable
        public Reason first;

        public void ok() {
            done++;
        }

        public void fail(@Nullable String code) {
            failed++;
            if (first == null) first = reason(code);
        }

        public int total() {
            return done + failed;
        }
    }

    /**
     * The copied part of {@code source}'s settings for one target, in the editor's form: anti-AFK, commands, replies and
     * the owner's name. Every secret row takes the words typed for it ({@code secrets} by row id; null or empty: the row
     * cannot be copied, the caller asks first). The inventory and movement choices are left out: the target keeps its
     * own (movement is never copied, README "Movement setting").
     */
    public static CloudEdit.Config copied(CloudModel.ConfigView source, Map<String, String> secrets) {
        CloudEdit.Config config = CloudEdit.from(source);
        config.inventory = null;
        config.movement = null;
        for (CloudEdit.Command c : config.commands) {
            if (c.words.kind == CloudEdit.Words.Kind.SEALED) c.words = CloudEdit.Words.secret(nonNull(secrets.get(c.id)));
            c.paused = false;
        }
        for (CloudEdit.Rule r : config.rules) {
            if (r.reply.kind == CloudEdit.Words.Kind.SEALED) r.reply = CloudEdit.Words.secret(nonNull(secrets.get(r.id)));
            r.paused = false;
        }
        return config;
    }

    private static String nonNull(@Nullable String s) {
        return s == null ? "" : s;
    }

    /** The secret rows of a config view, by id, with their previews ("/login ******"), in the list's order. */
    public static Map<String, String> secretRows(CloudModel.ConfigView view) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (CloudModel.CommandView c : view.commands) if (c.payload.secret) out.put(c.id, c.payload.preview == null ? "******" : c.payload.preview);
        for (CloudModel.RuleView r : view.rules) if (r.reply.secret) out.put(r.id, r.reply.preview == null ? "******" : r.reply.preview);
        return out;
    }

    /** The {@code PATCH} body for one target: the copied config, and the server and version when the player asked. */
    public static JsonObject patch(CloudModel.Account target, JsonObject config, @Nullable CloudServer server, @Nullable String version) {
        return CloudEdit.patch(target, null, server, version, null, config);
    }
}
