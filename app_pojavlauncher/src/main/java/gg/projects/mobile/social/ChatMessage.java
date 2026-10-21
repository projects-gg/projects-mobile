package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.util.regex.Pattern;

/**
 * A chat message as the service sealed it (README "Chat"): {@code {conv, id, from, text, mac,
 * editedAt?, nonce?}}. {@code id} is the hub's clock in milliseconds when it took the message, the
 * order key within a conversation, never changed by an edit. {@code mac} is the service's seal of
 * this version: kept with the message and sent back unchanged in an edit, a delete or a report.
 * Pure Java.
 */
public final class ChatMessage {
    private static final Pattern MAC = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{1,16}");

    public final String conv;
    public final long id;
    public final String from;
    public final String text;
    public final String mac;
    /** When it was edited (ms); 0 when never. */
    public final long editedAt;
    /** The sending device's own key (only ever on the sender's devices); null otherwise. */
    @Nullable
    public final String nonce;

    public ChatMessage(String conv, long id, String from, String text, String mac, long editedAt, @Nullable String nonce) {
        this.conv = conv;
        this.id = id;
        this.from = from;
        this.text = text;
        this.mac = mac;
        this.editedAt = editedAt;
        this.nonce = nonce;
    }

    /** The message in {@code o}; a sync answer's messages carry no {@code conv} ({@code conv} given). Null when broken. */
    @Nullable
    public static ChatMessage read(@Nullable JsonObject o, @Nullable String conv) {
        if (o == null) return null;
        String c = Json.str(o, "conv");
        if (c == null) c = conv;
        if (!ChatConv.valid(c)) return null;
        long id = Json.num(o, "id", 0);
        String from = Json.str(o, "from");
        String text = Json.str(o, "text");
        String mac = Json.str(o, "mac");
        if (id <= 0 || !SocialModel.isUserId(from) || text == null || text.isEmpty() || text.length() > 4000
                || mac == null || !MAC.matcher(mac).matches()) {
            return null;
        }
        long edited = Math.max(0, Json.num(o, "editedAt", 0));
        String nonce = Json.str(o, "nonce");
        if (nonce != null && !NONCE.matcher(nonce).matches()) nonce = null;
        return new ChatMessage(c, id, from, text, mac, edited, nonce);
    }

    public static boolean isNonce(@Nullable String nonce) {
        return nonce != null && NONCE.matcher(nonce).matches();
    }

    public boolean edited() {
        return editedAt > 0;
    }

    /** The message as the service wants it back (edit, delete, report): its seal covers each field. */
    public JsonObject wire() {
        JsonObject o = new JsonObject();
        o.addProperty("conv", conv);
        o.addProperty("id", id);
        o.addProperty("from", from);
        o.addProperty("text", text);
        o.addProperty("mac", mac);
        if (editedAt > 0) o.addProperty("editedAt", editedAt);
        return o;
    }

    /** The same message keeping this device's {@code nonce} when the newer copy has none. */
    public ChatMessage keepingNonce(@Nullable ChatMessage before) {
        if (nonce != null || before == null || before.nonce == null) return this;
        return new ChatMessage(conv, id, from, text, mac, editedAt, before.nonce);
    }

    /** Same id in the same conversation. */
    public boolean same(ChatMessage other) {
        return other != null && id == other.id && conv.equals(other.conv);
    }
}
