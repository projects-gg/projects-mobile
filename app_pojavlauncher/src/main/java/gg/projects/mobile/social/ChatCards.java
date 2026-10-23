package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CHAT2 link cards (README "Chat extras", "Link cards"; chat2.md §9): the hub fetches the page itself
 * (the player's IP address never reaches the site) and answers {@code chat_unfurl} with a card. The
 * phone asks once per message when the message comes on screen, never for one older than a week, one
 * request at a time; a busy hub is asked once more later; the card stays with the message on the
 * phone (the store keeps it). A card that cannot be made is the plain one (the icon and the domain);
 * one whose link leads where the rules refuse turns the link back into plain text ("Bu bağlantı
 * açılamaz."). The composer's card (what a typed link will show) is asked the same way, by address,
 * and kept only in memory. Pure Java; the screen draws the picture.
 */
public final class ChatCards {
    /** Never asked for a message older than this (the mailbox's week). */
    public static final long MAX_AGE_MS = 7 * 86_400_000L;
    /** A busy hub is asked again this long after (when it says nothing itself). */
    public static final long BUSY_RETRY_MS = 5_000;

    public enum State { READY, PLAIN, REFUSED }

    /** A card as the hub made it (or the plain one). */
    public static final class Card {
        public final State state;
        /** The link as written in the message. */
        public final String url;
        /** A name to show (YouTube, Twitch, Kick, Modrinth, Projects, Minecraft, Minecraft Wiki, else the domain). */
        public final String site;
        /** One of the launcher's own icons ({@code projects}, {@code youtube}, ... {@code globe}). */
        public final String icon;
        /** {@code video} or {@code stream} (a 16:9 picture), {@code project} (a square icon beside), {@code page}. */
        public final String kind;
        @Nullable
        public final String title;
        @Nullable
        public final String author;
        @Nullable
        public final String desc;
        /** A {@code data:image/...;base64,} picture (trusted sites only), or null. */
        @Nullable
        public final String image;
        public final int w;
        public final int h;
        /** With {@link State#REFUSED}: the rule the link broke. */
        @Nullable
        public final String rule;

        public Card(State state, String url, String site, String icon, String kind, @Nullable String title, @Nullable String author,
                    @Nullable String desc, @Nullable String image, int w, int h, @Nullable String rule) {
            this.state = state;
            this.url = url;
            this.site = site;
            this.icon = icon;
            this.kind = kind;
            this.title = title;
            this.author = author;
            this.desc = desc;
            this.image = image;
            this.w = w;
            this.h = h;
            this.rule = rule;
        }

        /** The hub's card ({@code ack.card}); null when it is not one. */
        @Nullable
        public static Card read(@Nullable JsonObject o, String url) {
            if (o == null) return null;
            String site = clip(Json.str(o, "site"), 80);
            if (site == null) return null;
            String image = Json.str(o, "image");
            if (image != null && (image.length() > 100_000 || !image.matches("(?s)data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+"))) {
                image = null;
            }
            String kind = Json.str(o, "kind", "page");
            if (!kind.equals("video") && !kind.equals("stream") && !kind.equals("project")) kind = "page";
            String icon = Json.str(o, "icon", "globe");
            if (!icon.matches("[a-z]{1,16}")) icon = "globe";
            return new Card(State.READY, url, site, icon, kind, clip(Json.str(o, "title"), 120), clip(Json.str(o, "author"), 60),
                    clip(Json.str(o, "desc"), 200), image, (int) Math.max(0, Json.num(o, "w", 0)), (int) Math.max(0, Json.num(o, "h", 0)),
                    null);
        }

        /** The plain card: the globe, the domain, the address. */
        public static Card plain(String url, String domain) {
            return new Card(State.PLAIN, url, domain, "globe", "page", null, null, null, null, 0, 0, null);
        }

        /** The link leads where the rules refuse: plain text, "Bu bağlantı açılamaz.". */
        public static Card refused(String url, String domain, @Nullable String rule) {
            return new Card(State.REFUSED, url, domain, "globe", "page", null, null, null, null, 0, 0, rule == null ? "other" : rule);
        }

        /** A wide picture over the text (a video, a stream, a page with a wide picture). */
        public boolean widePicture() {
            if (image == null) return false;
            if (kind.equals("video") || kind.equals("stream")) return true;
            return !kind.equals("project") && w > 0 && h > 0 && w >= h * 1.3f;
        }

        /** A square picture beside the text (a mod's icon, a channel's face). */
        public boolean sidePicture() {
            return image != null && !widePicture();
        }

        /** The form the store keeps. */
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("state", state.name());
            o.addProperty("url", url);
            o.addProperty("site", site);
            o.addProperty("icon", icon);
            o.addProperty("kind", kind);
            if (title != null) o.addProperty("title", title);
            if (author != null) o.addProperty("author", author);
            if (desc != null) o.addProperty("desc", desc);
            if (image != null) o.addProperty("image", image);
            if (w > 0) o.addProperty("w", w);
            if (h > 0) o.addProperty("h", h);
            if (rule != null) o.addProperty("rule", rule);
            return o;
        }

        @Nullable
        public static Card fromJson(@Nullable JsonObject o) {
            if (o == null) return null;
            String url = Json.str(o, "url");
            if (url == null) return null;
            State state;
            try {
                state = State.valueOf(Json.str(o, "state", "PLAIN"));
            } catch (IllegalArgumentException e) {
                return null;
            }
            if (state == State.READY) {
                Card card = read(o, url);
                return card;
            }
            String site = Json.str(o, "site", "");
            return state == State.REFUSED ? refused(url, site, Json.str(o, "rule")) : plain(url, site);
        }

        @Nullable
        private static String clip(@Nullable String text, int max) {
            if (text == null) return null;
            String t = text.trim();
            if (t.isEmpty()) return null;
            return t.codePointCount(0, t.length()) <= max ? t : t.substring(0, t.offsetByCodePoints(0, max));
        }
    }

    /** One request to make: a message's link (or the composer's, {@code id} 0). */
    public static final class Ask {
        public final String conv;
        public final long id;
        public final String url;
        public final String domain;

        Ask(String conv, long id, String url, String domain) {
            this.conv = conv;
            this.id = id;
            this.url = url;
            this.domain = domain;
        }

        public String key() {
            return id > 0 ? conv + "|" + id : "url|" + url;
        }
    }

    /** What the hub answered (the screen's words follow from it). */
    public enum Outcome {
        CARD, PLAIN, REFUSED, BUSY, LATER, OFF,
        /** The plain card for this run only (a rate limit, a second busy answer): not kept in the store. */
        PLAIN_NOW
    }

    private final Map<String, Card> mCards = new HashMap<>();
    private final Map<String, Card> mComposer = new HashMap<>();
    private final LinkedHashMap<String, Ask> mQueue = new LinkedHashMap<>();
    /** Asked in this run of the app (an answer that is not kept, like a rate limit, is not asked again). */
    private final Set<String> mAsked = new HashSet<>();
    /** Busy answers already given a second try. */
    private final Set<String> mRetried = new HashSet<>();
    @Nullable
    private Ask mInFlight;
    /** No asking before this (ms; a busy hub or a rate limit). */
    private long mNotBefore;

    /** The card kept with a message, or null. */
    @Nullable
    public Card card(String conv, long id) {
        return mCards.get(conv + "|" + id);
    }

    /** The composer's card for an address, or null. */
    @Nullable
    public Card composerCard(String url) {
        return mComposer.get(url);
    }

    /** Cards the store held for a conversation (when it opened). */
    public void loaded(String conv, Map<Long, Card> cards) {
        for (Map.Entry<Long, Card> e : cards.entrySet()) {
            String key = conv + "|" + e.getKey();
            if (!mCards.containsKey(key)) mCards.put(key, e.getValue());
        }
    }

    /**
     * A message with a link came on screen: its card is asked for once (not for one older than a week,
     * not when the card is kept already). True when it was queued.
     */
    public boolean want(String conv, long id, String url, String domain, long ageMs) {
        String key = conv + "|" + id;
        if (id <= 0 || ageMs >= MAX_AGE_MS || mCards.containsKey(key) || mAsked.contains(key) || mQueue.containsKey(key)) return false;
        if (mInFlight != null && mInFlight.key().equals(key)) return false;
        mQueue.put(key, new Ask(conv, id, url, domain));
        return true;
    }

    /** The composer shows a link the rules let through: its card is asked for (in memory only). */
    public boolean wantComposer(String url, String domain) {
        String key = "url|" + url;
        if (mComposer.containsKey(url) || mAsked.contains(key) || mQueue.containsKey(key)) return false;
        if (mInFlight != null && mInFlight.key().equals(key)) return false;
        mQueue.put(key, new Ask("", 0, url, domain));
        return true;
    }

    /** The next request to send now, or null (one at a time; not while the hub asked to wait). */
    @Nullable
    public Ask next(long nowMs) {
        if (mInFlight != null || mQueue.isEmpty() || nowMs < mNotBefore) return null;
        String key = mQueue.keySet().iterator().next();
        Ask ask = mQueue.remove(key);
        mInFlight = ask;
        mAsked.add(key);
        return ask;
    }

    /** When asking may go on (ms), 0: at once (or nothing waits). */
    public long waitUntil() {
        return mQueue.isEmpty() ? 0 : mNotBefore;
    }

    /**
     * The answer to {@code ask}: {@code ack} with {@code card}, or an {@code error} (null: none came,
     * the socket went). Returns the card kept for it (null when nothing is kept) and what it means.
     */
    public Outcome answered(Ask ask, @Nullable JsonObject answer, long nowMs) {
        if (mInFlight != null && mInFlight.key().equals(ask.key())) mInFlight = null;
        if (answer == null) {
            // No answer: it may be asked again on the next look.
            mAsked.remove(ask.key());
            return Outcome.LATER;
        }
        if ("ack".equals(Json.str(answer, "t"))) {
            Card card = Card.read(Json.obj(answer, "card"), ask.url);
            if (card == null) card = Card.plain(ask.url, ask.domain);
            keep(ask, card);
            return card.state == State.READY ? Outcome.CARD : Outcome.PLAIN;
        }
        String code = Json.str(answer, "code", "");
        switch (code) {
            case "unfurl_failed": {
                String rule = Json.str(answer, "rule");
                Card card = rule != null ? Card.refused(ask.url, ask.domain, rule) : Card.plain(ask.url, ask.domain);
                keep(ask, card);
                return rule != null ? Outcome.REFUSED : Outcome.PLAIN;
            }
            case "unfurl_busy": {
                long wait = Math.max(BUSY_RETRY_MS, Math.min(60, Json.num(answer, "retryAfter", 0)) * 1000L);
                mNotBefore = nowMs + wait;
                if (mRetried.add(ask.key())) {
                    // Asked once more, later.
                    mAsked.remove(ask.key());
                    mQueue.put(ask.key(), ask);
                    return Outcome.BUSY;
                }
                keepSession(ask);
                return Outcome.PLAIN_NOW;
            }
            case "rate_limited":
                mNotBefore = nowMs + 60_000;
                keepSession(ask);
                return Outcome.PLAIN_NOW;
            case "chat_feature_off":
                mQueue.clear();
                return Outcome.OFF;
            default:
                keepSession(ask);
                return Outcome.PLAIN_NOW;
        }
    }

    /** The socket went: what waits is asked again on the next look. */
    public void connectionLost() {
        if (mInFlight != null) mAsked.remove(mInFlight.key());
        for (String key : mQueue.keySet()) mAsked.remove(key);
        mInFlight = null;
        mQueue.clear();
    }

    /** Signed out: nothing stays. */
    public void clear() {
        mCards.clear();
        mComposer.clear();
        mQueue.clear();
        mAsked.clear();
        mRetried.clear();
        mInFlight = null;
        mNotBefore = 0;
    }

    /** A message's card went with the message (deleted, history cleared). */
    public void forget(String conv, long id) {
        mCards.remove(conv + "|" + id);
    }

    public void forgetConversation(String conv) {
        List<String> gone = new ArrayList<>();
        for (String key : mCards.keySet()) if (key.startsWith(conv + "|")) gone.add(key);
        for (String key : gone) mCards.remove(key);
    }

    private void keep(Ask ask, Card card) {
        if (ask.id > 0) mCards.put(ask.key(), card);
        else mComposer.put(ask.url, card);
    }

    /** A plain card for this run only (not kept with the message: a later run may get the real one). */
    private void keepSession(Ask ask) {
        Card plain = Card.plain(ask.url, ask.domain);
        if (ask.id > 0) mCards.put(ask.key(), plain);
        else mComposer.put(ask.url, plain);
    }

    /** Whether the card of {@code key} came from the hub (and so is worth keeping in the store). */
    public static boolean worthKeeping(Outcome outcome) {
        return outcome == Outcome.CARD || outcome == Outcome.PLAIN || outcome == Outcome.REFUSED;
    }
}
