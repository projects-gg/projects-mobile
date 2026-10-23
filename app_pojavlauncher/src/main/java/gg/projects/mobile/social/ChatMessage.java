package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A chat message as the service sealed it (README "Chat"): {@code {conv, id, from, text, mac,
 * editedAt?, nonce?}}. {@code id} is the hub's clock in milliseconds when it took the message, the
 * order key within a conversation, never changed by an edit. {@code mac} is the service's seal of
 * this version: kept with the message and sent back unchanged in an edit, a delete or a report.
 *
 * <p>CHAT2 ("Chat extras"): a message is a text or a sticker ({@code sticker} in place of
 * {@code text}, sealed over the sticker, never edited); a reply carries the link to the message it
 * answers ({@code reply: {id, from}}) with the link's own seal {@code rmac}, never the quoted text
 * (the device shows it from its own history); reactions ride along as {@code [{e, by[]}]}, kinds in
 * the order they were first given, people in the order they reacted. Pure Java.
 */
public final class ChatMessage {
    private static final Pattern MAC = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    /** A sticker of a set: {@code pl1/gg} (the service's own rule). */
    private static final Pattern STICKER = Pattern.compile("[a-z0-9]{1,8}/[a-z0-9-]{1,24}");

    /** One emoji on a message and who gave it. */
    public static final class Reaction {
        public final String e;
        /** User ids in the order they reacted. */
        public final List<String> by;

        public Reaction(String e, List<String> by) {
            this.e = e;
            this.by = Collections.unmodifiableList(new ArrayList<>(by));
        }

        public boolean has(@Nullable String userId) {
            return userId != null && by.contains(userId);
        }

        public int count() {
            return by.size();
        }
    }

    public final String conv;
    public final long id;
    public final String from;
    /** The text; "" for a sticker. */
    public final String text;
    /** CHAT2: the sticker ({@code pl1/gg}), or null for a text message. */
    @Nullable
    public final String sticker;
    public final String mac;
    /** When it was edited (ms); 0 when never. */
    public final long editedAt;
    /** The sending device's own key (only ever on the sender's devices); null otherwise. */
    @Nullable
    public final String nonce;
    /** CHAT2: the message it answers (0: not a reply), its sender and the link's seal. */
    public final long replyId;
    @Nullable
    public final String replyFrom;
    @Nullable
    public final String rmac;
    /** CHAT2: the reactions as this player sees them (empty: none, or a service without them). */
    public final List<Reaction> reactions;

    public ChatMessage(String conv, long id, String from, String text, String mac, long editedAt, @Nullable String nonce) {
        this(conv, id, from, text, null, mac, editedAt, nonce, 0, null, null, Collections.<Reaction>emptyList());
    }

    public ChatMessage(String conv, long id, String from, String text, @Nullable String sticker, String mac, long editedAt,
                       @Nullable String nonce, long replyId, @Nullable String replyFrom, @Nullable String rmac,
                       List<Reaction> reactions) {
        this.conv = conv;
        this.id = id;
        this.from = from;
        this.text = sticker != null ? "" : text;
        this.sticker = sticker;
        this.mac = mac;
        this.editedAt = editedAt;
        this.nonce = nonce;
        boolean linked = replyId > 0 && SocialModel.isUserId(replyFrom) && rmac != null && MAC.matcher(rmac).matches();
        this.replyId = linked ? replyId : 0;
        this.replyFrom = linked ? replyFrom : null;
        this.rmac = linked ? rmac : null;
        this.reactions = Collections.unmodifiableList(new ArrayList<>(reactions));
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
        String sticker = Json.str(o, "sticker");
        String mac = Json.str(o, "mac");
        if (id <= 0 || !SocialModel.isUserId(from) || mac == null || !MAC.matcher(mac).matches()) return null;
        if (sticker != null) {
            if (!isSticker(sticker)) return null;
            text = "";
        } else if (text == null || text.isEmpty() || text.length() > 4000) {
            return null;
        }
        long edited = Math.max(0, Json.num(o, "editedAt", 0));
        String nonce = Json.str(o, "nonce");
        if (nonce != null && !NONCE.matcher(nonce).matches()) nonce = null;
        JsonObject reply = Json.obj(o, "reply");
        long replyId = Json.num(reply, "id", 0);
        String replyFrom = Json.str(reply, "from");
        return new ChatMessage(c, id, from, text, sticker, mac, edited, nonce, replyId, replyFrom, Json.str(o, "rmac"),
                readReactions(o));
    }

    /**
     * {@code reactions} of a message or of a {@code reacted} entry: one kind at most once, each player
     * once per kind, kinds without anyone left out.
     */
    public static List<Reaction> readReactions(@Nullable JsonObject o) {
        List<Reaction> out = new ArrayList<>();
        if (o == null) return out;
        Set<String> kinds = new LinkedHashSet<>();
        for (JsonObject r : Json.objects(o, "reactions")) {
            String e = Json.str(r, "e");
            if (e == null || e.isEmpty() || e.length() > 32 || !kinds.add(e)) continue;
            Set<String> by = new LinkedHashSet<>();
            for (String id : Json.strings(r, "by")) if (SocialModel.isUserId(id)) by.add(id);
            if (!by.isEmpty()) out.add(new Reaction(e, new ArrayList<>(by)));
        }
        return out;
    }

    /** The JSON form of reactions (what the store keeps; the wire's shape). */
    public static JsonArray reactionsJson(List<Reaction> reactions) {
        JsonArray out = new JsonArray();
        for (Reaction r : reactions) {
            JsonObject o = new JsonObject();
            o.addProperty("e", r.e);
            JsonArray by = new JsonArray();
            for (String id : r.by) by.add(id);
            o.add("by", by);
            out.add(o);
        }
        return out;
    }

    /** Reactions from the store's JSON text (null or broken: none). */
    public static List<Reaction> reactionsFrom(@Nullable String json) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        JsonElement parsed = Json.parse(json);
        if (parsed == null || !parsed.isJsonArray()) return Collections.emptyList();
        JsonObject holder = new JsonObject();
        holder.add("reactions", parsed);
        return readReactions(holder);
    }

    public static boolean isNonce(@Nullable String nonce) {
        return nonce != null && NONCE.matcher(nonce).matches();
    }

    public static boolean isSticker(@Nullable String ref) {
        return ref != null && STICKER.matcher(ref).matches();
    }

    public boolean edited() {
        return editedAt > 0;
    }

    public boolean isSticker() {
        return sticker != null;
    }

    public boolean isReply() {
        return replyId > 0;
    }

    /**
     * The message as the service wants it back (edit, delete, report, the answered message of a reply):
     * its seal covers each field; a reply's link comes with its own seal (both must check out).
     */
    public JsonObject wire() {
        JsonObject o = new JsonObject();
        o.addProperty("conv", conv);
        o.addProperty("id", id);
        o.addProperty("from", from);
        if (sticker != null) o.addProperty("sticker", sticker);
        else o.addProperty("text", text);
        o.addProperty("mac", mac);
        if (editedAt > 0) o.addProperty("editedAt", editedAt);
        if (replyId > 0) {
            JsonObject reply = new JsonObject();
            reply.addProperty("id", replyId);
            reply.addProperty("from", replyFrom);
            o.add("reply", reply);
            o.addProperty("rmac", rmac);
        }
        return o;
    }

    /**
     * A newer copy of a message this device holds ({@code before}): this device's {@code nonce}, the
     * reply link (an edit never changes it; a copy from a device that did not send it may lack it) and
     * the reactions when the newer copy says nothing of them ({@code reactionsKnown} false).
     */
    public ChatMessage mergedWith(@Nullable ChatMessage before, boolean reactionsKnown) {
        if (before == null) return this;
        String keepNonce = nonce != null ? nonce : before.nonce;
        boolean keepLink = replyId == 0 && before.replyId > 0;
        List<Reaction> keepReactions = reactionsKnown ? reactions : before.reactions;
        if (keepNonce == nonce && !keepLink && keepReactions == reactions) return this;
        return new ChatMessage(conv, id, from, text, sticker, mac, editedAt, keepNonce,
                keepLink ? before.replyId : replyId, keepLink ? before.replyFrom : replyFrom, keepLink ? before.rmac : rmac,
                keepReactions);
    }

    /** The same message keeping this device's {@code nonce} when the newer copy has none. */
    public ChatMessage keepingNonce(@Nullable ChatMessage before) {
        if (nonce != null || before == null || before.nonce == null) return this;
        return new ChatMessage(conv, id, from, text, sticker, mac, editedAt, before.nonce, replyId, replyFrom, rmac, reactions);
    }

    /** The same message with other reactions. */
    public ChatMessage withReactions(List<Reaction> next) {
        return new ChatMessage(conv, id, from, text, sticker, mac, editedAt, nonce, replyId, replyFrom, rmac, next);
    }

    /** Same id in the same conversation. */
    public boolean same(ChatMessage other) {
        return other != null && id == other.id && conv.equals(other.conv);
    }

    /** Whether two lists of reactions say the same (kinds, order, people). */
    public static boolean sameReactions(List<Reaction> a, List<Reaction> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).e.equals(b.get(i).e) || !a.get(i).by.equals(b.get(i).by)) return false;
        }
        return true;
    }
}
