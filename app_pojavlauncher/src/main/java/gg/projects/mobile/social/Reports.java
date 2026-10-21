package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * "Rapor et" (MOD1; README "Reports"): the bodies of {@code POST /v1/reports} for a player, a crew or
 * a chat message, and the fixed reasons each takes. The note is for moderators only (200 characters,
 * cleaned like the service cleans it); "Engelle" also blocks the player (players and messages only).
 * Pure Java.
 */
public final class Reports {
    private Reports() {}

    public enum Target { PLAYER, CREW, MESSAGE }

    public static final int NOTE_MAX = 200;
    /** Earlier messages a message report carries for context. */
    public static final int CONTEXT = 20;

    public static final String NAME = "name";
    public static final String PROFILE = "profile";
    public static final String HARASSMENT = "harassment";
    public static final String INAPPROPRIATE = "inappropriate";
    public static final String SPAM = "spam";
    public static final String SCAM = "scam";
    public static final String OTHER = "other";

    /** The reasons a target takes, in the order the dialog lists them. */
    public static List<String> reasons(Target target) {
        switch (target) {
            case CREW:
                return Collections.unmodifiableList(Arrays.asList(NAME, HARASSMENT, SPAM, SCAM, OTHER));
            case MESSAGE:
                return Collections.unmodifiableList(Arrays.asList(HARASSMENT, INAPPROPRIATE, SPAM, SCAM, OTHER));
            case PLAYER:
            default:
                return Collections.unmodifiableList(Arrays.asList(NAME, PROFILE, HARASSMENT, SPAM, SCAM, OTHER));
        }
    }

    /** "Engelle" is offered (unticked) for a player and a message's sender. */
    public static boolean offersBlock(Target target) {
        return target != Target.CREW;
    }

    /** The note as the service keeps it: control characters as spaces, spaces collapsed, 200 at most. */
    public static String cleanNote(@Nullable String note) {
        if (note == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < note.length(); ) {
            int cp = note.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (Character.isISOControl(cp) || type == Character.FORMAT || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                out.append(' ');
            } else {
                out.appendCodePoint(cp);
            }
        }
        String text = out.toString().replaceAll("[\\s\\p{Z}]+", " ").trim();
        if (text.codePointCount(0, text.length()) > NOTE_MAX) text = text.substring(0, text.offsetByCodePoints(0, NOTE_MAX)).trim();
        return text;
    }

    private static JsonObject body(JsonObject target, String reason, @Nullable String note, boolean block) {
        JsonObject body = new JsonObject();
        body.add("target", target);
        body.addProperty("reason", reason);
        String clean = cleanNote(note);
        if (!clean.isEmpty()) body.addProperty("note", clean);
        if (block) body.addProperty("block", true);
        return body;
    }

    public static JsonObject player(String userId, String reason, @Nullable String note, boolean block) {
        JsonObject target = new JsonObject();
        target.addProperty("kind", "player");
        target.addProperty("id", userId);
        return body(target, reason, note, block);
    }

    /** {@code code}: only for a crew the player looked up by its code (null for their own crews and invites). */
    public static JsonObject crew(String teamId, @Nullable String code, String reason, @Nullable String note) {
        JsonObject target = new JsonObject();
        target.addProperty("kind", "crew");
        target.addProperty("id", teamId);
        if (code != null) target.addProperty("code", code);
        return body(target, reason, note, false);
    }

    /** A message as the device holds it, with up to 20 earlier ones of the conversation, oldest first. */
    public static JsonObject message(ChatMessage message, List<ChatMessage> before, String reason, @Nullable String note,
                                     boolean block) {
        JsonObject target = new JsonObject();
        target.addProperty("kind", "message");
        target.add("message", message.wire());
        JsonArray context = new JsonArray();
        int from = Math.max(0, before.size() - CONTEXT);
        for (int i = from; i < before.size(); i++) {
            ChatMessage m = before.get(i);
            if (m.conv.equals(message.conv) && m.id < message.id) context.add(m.wire());
        }
        if (context.size() > 0) target.add("before", context);
        return body(target, reason, note, block);
    }
}
