package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * MOB1 (CHAT2, README "Chat extras"): stickers and reply links on the wire and in the book, the
 * reaction rules (five per person, ten kinds, the middle finger) with the player's changes shown at
 * once until answered, read marks ("Görüldü" and the unread counts in step), the week-back sync, the
 * typing rules, link cards and the link drawing helpers. Older services: nothing of it.
 */
public class Chat2Test {
    private static final String ME = "100000000000000011";
    private static final String B = "100000000000000012";
    private static final String C = "100000000000000013";
    private static final String CREW = "0a1b2c3d4e5f6a7b";
    private static final String DM = ChatConv.direct(ME, B);
    private static final String CREW_CHAT = ChatConv.crew(CREW);
    private static final long T = 1_792_521_000_000L;
    private static final String MAC = "AbCdEfGhIjKlMnOpQrStUv";
    private static final String RMAC = "RmAcRmAcRmAcRmAcRmAc12";
    private static final String THUMBS = new String(Character.toChars(0x1F44D));
    private static final String HEART = new String(Character.toChars(0x2764));
    private static final String HEART_VS16 = HEART + (char) 0xFE0F;
    private static final String FINGER_TONED = new String(Character.toChars(0x1F595)) + new String(Character.toChars(0x1F3FD));

    private static JsonObject text(String conv, long id, String from, String text) {
        JsonObject m = new JsonObject();
        m.addProperty("conv", conv);
        m.addProperty("id", id);
        m.addProperty("from", from);
        m.addProperty("text", text);
        m.addProperty("mac", MAC);
        return m;
    }

    private static JsonObject sticker(String conv, long id, String from, String ref) {
        JsonObject m = new JsonObject();
        m.addProperty("conv", conv);
        m.addProperty("id", id);
        m.addProperty("from", from);
        m.addProperty("sticker", ref);
        m.addProperty("mac", MAC);
        return m;
    }

    private static JsonObject frame(String type, JsonObject msg) {
        JsonObject f = new JsonObject();
        f.addProperty("t", type);
        f.add("msg", msg);
        return f;
    }

    private static JsonObject replyTo(JsonObject msg, long id, String from) {
        JsonObject r = new JsonObject();
        r.addProperty("id", id);
        r.addProperty("from", from);
        msg.add("reply", r);
        msg.addProperty("rmac", RMAC);
        return msg;
    }

    private static JsonArray reactions(Object... pairs) {
        JsonArray out = new JsonArray();
        for (int i = 0; i < pairs.length; i += 2) {
            JsonObject r = new JsonObject();
            r.addProperty("e", (String) pairs[i]);
            JsonArray by = new JsonArray();
            for (String id : (String[]) pairs[i + 1]) by.add(id);
            r.add("by", by);
            out.add(r);
        }
        return out;
    }

    // ---- the wire ----

    @Test
    public void aStickerIsAMessageWithoutText() {
        ChatMessage m = ChatMessage.read(sticker(DM, T, B, "pl1/gg"), null);
        assertNotNull(m);
        assertTrue(m.isSticker());
        assertEquals("", m.text);
        assertEquals("pl1/gg", m.sticker);
        JsonObject wire = m.wire();
        assertEquals("pl1/gg", Json.str(wire, "sticker"));
        assertFalse(wire.has("text"));
        // A broken id is no sticker; a text message stays text.
        assertNull(ChatMessage.read(sticker(DM, T, B, "PL1/Gg!"), null));
        assertNull(ChatMessage.read(sticker(DM, T, B, "gg"), null));
        assertFalse(ChatMessage.read(text(DM, T, B, "selam"), null).isSticker());
    }

    @Test
    public void aReplyKeepsOnlyItsLinkAndSendsItBackWithItsSeal() {
        ChatMessage m = ChatMessage.read(replyTo(text(DM, T + 5, ME, "evet"), T, B), null);
        assertEquals(T, m.replyId);
        assertEquals(B, m.replyFrom);
        assertEquals(RMAC, m.rmac);
        JsonObject wire = m.wire();
        assertEquals(T, Json.num(Json.obj(wire, "reply"), "id", 0));
        assertEquals(RMAC, Json.str(wire, "rmac"));
        // A link without its seal is no link.
        JsonObject broken = text(DM, T + 6, ME, "evet");
        JsonObject r = new JsonObject();
        r.addProperty("id", T);
        r.addProperty("from", B);
        broken.add("reply", r);
        assertEquals(0, ChatMessage.read(broken, null).replyId);
    }

    @Test
    public void anEditWithoutTheLinkOrTheReactionsKeepsWhatTheBookHolds() {
        ChatBook book = new ChatBook(ME);
        JsonObject first = replyTo(text(CREW_CHAT, T + 5, B, "evet"), T, C);
        first.add("reactions", reactions(THUMBS, new String[]{ME, C}));
        book.apply(frame("chat", first));
        JsonObject edit = text(CREW_CHAT, T + 5, B, "evet evet");
        edit.addProperty("editedAt", T + 9);
        edit.addProperty("mac", "EdItEdItEdItEdItEdItEd");
        book.apply(frame("chat_edited", edit));
        ChatMessage held = book.message(CREW_CHAT, T + 5);
        assertEquals("evet evet", held.text);
        assertEquals(T, held.replyId);
        assertEquals(1, held.reactions.size());
        assertEquals(Arrays.asList(ME, C), held.reactions.get(0).by);
    }

    @Test
    public void anEditWithoutReactionsHasNoneOnASocketThatTookThem() {
        // The README: chat_edited and ack.msg carry the reactions to a socket with `reactions`, absent when there are none.
        ChatBook book = new ChatBook(ME);
        book.setReactionsTaken(true);
        JsonObject first = text(CREW_CHAT, T + 5, B, "evet");
        first.add("reactions", reactions(THUMBS, new String[]{ME, C}));
        book.apply(frame("chat", first));
        assertEquals(1, book.message(CREW_CHAT, T + 5).reactions.size());
        JsonObject edit = text(CREW_CHAT, T + 5, B, "evet evet");
        edit.addProperty("editedAt", T + 9);
        edit.addProperty("mac", "EdItEdItEdItEdItEdItEd");
        book.apply(frame("chat_edited", edit));
        assertTrue(book.message(CREW_CHAT, T + 5).reactions.isEmpty());
        // Listed, they are the message's.
        JsonObject again = text(CREW_CHAT, T + 5, B, "evet evet evet");
        again.addProperty("editedAt", T + 12);
        again.addProperty("mac", "AgAiNaGaInAgAiNaGaInAg");
        again.add("reactions", reactions(HEART, new String[]{C}));
        book.apply(frame("chat_edited", again));
        assertEquals(HEART, book.message(CREW_CHAT, T + 5).reactions.get(0).e);
        // The player's own edit: its ack without them says the same.
        JsonObject mine = text(DM, T + 20, ME, "selam");
        mine.add("reactions", reactions(THUMBS, new String[]{B}));
        book.apply(frame("chat", mine));
        JsonObject ack = text(DM, T + 20, ME, "selam!");
        ack.addProperty("editedAt", T + 25);
        ack.addProperty("mac", "AcKaCkAcKaCkAcKaCkAcKa");
        book.edited(DM, T + 20, ack);
        assertTrue(book.message(DM, T + 20).reactions.isEmpty());
    }

    @Test
    public void aReportedReplyTakesTheAnsweredMessageWhenItIsNotAmongTheEarlierOnes() {
        ChatMessage answered = ChatMessage.read(text(DM, T, B, "Geliyor musun?"), null);
        ChatMessage between = ChatMessage.read(text(DM, T + 10, ME, "evet"), null);
        ChatMessage reply = ChatMessage.read(replyTo(text(DM, T + 50, B, "kötü söz"), T, B), null);
        JsonObject target = Reports.message(reply, Collections.singletonList(between), answered, Reports.HARASSMENT, null, false)
                .getAsJsonObject("target");
        assertEquals(T, Json.num(target.getAsJsonObject("quoted"), "id", 0));
        assertEquals(MAC, Json.str(target.getAsJsonObject("quoted"), "mac"));
        // Among the earlier ones it goes once, there.
        target = Reports.message(reply, Arrays.asList(answered, between), answered, Reports.HARASSMENT, null, false)
                .getAsJsonObject("target");
        assertFalse(target.has("quoted"));
        assertEquals(2, target.getAsJsonArray("before").size());
        // A message that answers nothing, or another message than the link names: none.
        ChatMessage plain = ChatMessage.read(text(DM, T + 60, B, "selam"), null);
        assertFalse(Reports.message(plain, Collections.<ChatMessage>emptyList(), answered, Reports.HARASSMENT, null, false)
                .getAsJsonObject("target").has("quoted"));
        ChatMessage other = ChatMessage.read(text(DM, T + 1, B, "başka"), null);
        assertFalse(Reports.message(reply, Collections.<ChatMessage>emptyList(), other, Reports.HARASSMENT, null, false)
                .getAsJsonObject("target").has("quoted"));
    }

    // ---- replies ----

    @Test
    public void aQuoteComesFromTheBookOrSaysWhyNot() {
        ChatBook book = new ChatBook(ME);
        book.apply(frame("chat", text(DM, T, B, "Geliyor musun?")));
        assertEquals(ChatBook.Quote.Kind.MESSAGE, book.quote(DM, T, B).kind);
        assertEquals("Geliyor musun?", book.quote(DM, T, B).message.text);
        assertEquals(ChatBook.Quote.Kind.EARLIER, book.quote(DM, T - 99, B).kind);
        JsonObject deleted = new JsonObject();
        deleted.addProperty("t", "chat_deleted");
        deleted.addProperty("conv", DM);
        deleted.addProperty("id", T);
        deleted.addProperty("by", B);
        book.apply(deleted);
        assertEquals(ChatBook.Quote.Kind.DELETED, book.quote(DM, T, B).kind);
    }

    @Test
    public void aReplyGoesWithTheAnsweredMessageAsHeldAndWithoutItOnceThatIsGone() {
        ChatBook book = new ChatBook(ME);
        book.apply(frame("chat", text(DM, T, B, "Geliyor musun?")));
        ChatMessage answered = book.message(DM, T);
        ChatBook.Outbox o = book.write(DM, "evet", null, answered, "n1", 1000);
        assertEquals(T, o.replyId());
        JsonObject f = book.frameOf(o);
        JsonObject msg = Json.obj(Json.obj(f, "reply"), "msg");
        assertEquals(MAC, Json.str(msg, "mac"));
        assertEquals("Geliyor musun?", Json.str(msg, "text"));
        // The service no longer has it: the message goes again without the link.
        book.sending("n1", 1000);
        book.notSent("n1", "message_not_found", null, 0, 1100);
        ChatBook.Outbox again = book.outbox("n1");
        assertEquals(ChatBook.Outbox.State.WAITING, again.state);
        assertNull(again.reply);
        assertFalse(book.frameOf(again).has("reply"));
    }

    @Test
    public void aStickerWaitsAndGoesLikeAMessage() {
        ChatBook book = new ChatBook(ME);
        ChatBook.Outbox o = book.write(CREW_CHAT, "", "pl1/gg", null, "s1", 1000);
        assertEquals("pl1/gg", o.sticker);
        assertEquals("pl1/gg", Json.str(o.frame(), "sticker"));
        assertFalse(o.frame().has("text"));
        List<ChatBook.Entry> entries = book.entries(CREW_CHAT);
        assertEquals("pl1/gg", entries.get(0).sticker());
        JsonObject ack = sticker(CREW_CHAT, T, ME, "pl1/gg");
        book.sending("s1", 1000);
        book.sent("s1", ack);
        assertEquals("pl1/gg", book.message(CREW_CHAT, T).sticker);
        assertEquals("s1", book.message(CREW_CHAT, T).nonce);
        // A sticker is never edited.
        assertNull(book.edit(CREW_CHAT, T, "x"));
        assertFalse(book.canEdit(book.entries(CREW_CHAT).get(0), T + 1000));
    }

    @Test
    public void todaysStickersAndLinksAndAFeatureOffSayWhy() {
        assertEquals(ChatBook.Failure.STICKERS, ChatBook.failure("rate_limited", null, 3600, "stickers"));
        assertEquals(ChatBook.Failure.LINKS, ChatBook.failure("rate_limited", null, 3600, "links"));
        assertEquals(ChatBook.Failure.LIMITED_LINKS, ChatBook.failure("account_limited", null, 0, "chat_links"));
        assertEquals(ChatBook.Failure.FEATURE_OFF, ChatBook.failure("chat_feature_off", null, 0, "stickers"));
        ChatBook book = new ChatBook(ME);
        book.write(DM, "bak https://x.example", "n2", 1000);
        book.sending("n2", 1000);
        book.notSent("n2", "chat_rejected", "link", 0, 1100, null, "shortener");
        ChatBook.Outbox failed = book.outbox("n2");
        assertEquals(ChatBook.Failure.LINK, failed.failure);
        assertEquals("shortener", failed.rule);
    }

    // ---- reactions ----

    @Test
    public void aPlayerGivesAtMostFiveAndAMessageTakesAtMostTenKinds() {
        ChatBook book = new ChatBook(ME);
        assertEquals(ChatBook.ReactVerdict.REFUSED, book.reactVerdict(Collections.<ChatMessage.Reaction>emptyList(), FINGER_TONED));
        List<ChatMessage.Reaction> list = Collections.emptyList();
        String[] emoji = new String[12];
        for (int i = 0; i < emoji.length; i++) emoji[i] = new String(Character.toChars(0x1F600 + i));
        for (int i = 0; i < 5; i++) list = ChatBook.withReaction(list, emoji[i], ME, true);
        assertEquals(ChatBook.ReactVerdict.MINE, book.reactVerdict(list, emoji[5]));
        assertEquals(ChatBook.ReactVerdict.REMOVE, book.reactVerdict(list, emoji[0]));
        // Ten kinds by others: a new one has no room, "Ben de" on one there always works.
        List<ChatMessage.Reaction> full = Collections.emptyList();
        for (int i = 0; i < 10; i++) full = ChatBook.withReaction(full, emoji[i], B, true);
        assertEquals(ChatBook.ReactVerdict.KINDS, book.reactVerdict(full, emoji[11]));
        assertEquals(ChatBook.ReactVerdict.ADD, book.reactVerdict(full, emoji[3]));
        // The heart with or without its variation selector is the same reaction.
        List<ChatMessage.Reaction> hearts = ChatBook.withReaction(Collections.<ChatMessage.Reaction>emptyList(), HEART_VS16, B, true);
        assertTrue(ChatBook.sameEmoji(HEART, HEART_VS16));
        assertEquals(1, ChatBook.withReaction(hearts, HEART, ME, true).size());
    }

    @Test
    public void thePlayersChangeShowsAtOnceUntilTheServiceAnswers() {
        ChatBook book = new ChatBook(ME);
        JsonObject m = text(CREW_CHAT, T, B, "gg");
        m.add("reactions", reactions(THUMBS, new String[]{C}));
        book.apply(frame("chat", m));
        JsonObject f = book.react(CREW_CHAT, T, THUMBS, true, T + 1000);
        assertNotNull(f);
        assertEquals("chat_react", Json.str(f, "t"));
        assertTrue(Json.bool(f, "on", false));
        ChatBook.Entry shown = book.entries(CREW_CHAT).get(0);
        assertEquals(Arrays.asList(C, ME), shown.reactions.get(0).by);
        // Someone else's reaction meanwhile keeps the player's change on top.
        JsonObject other = new JsonObject();
        other.addProperty("t", "chat_reacted");
        other.addProperty("conv", CREW_CHAT);
        other.addProperty("id", T);
        other.addProperty("by", "100000000000000014");
        other.addProperty("e", THUMBS);
        other.addProperty("on", true);
        book.apply(other);
        shown = book.entries(CREW_CHAT).get(0);
        assertEquals(Arrays.asList(C, "100000000000000014", ME), shown.reactions.get(0).by);
        // The answer: the service's list stands.
        JsonObject ack = new JsonObject();
        ack.addProperty("t", "ack");
        ack.add("reactions", reactions(THUMBS, new String[]{C, "100000000000000014", ME}));
        book.reactAnswered(CREW_CHAT, T, THUMBS, true, ack);
        shown = book.entries(CREW_CHAT).get(0);
        assertEquals(3, shown.reactions.get(0).count());
        // A refusal takes the change back.
        book.react(CREW_CHAT, T, HEART_VS16, true, T + 2000);
        assertEquals(2, book.entries(CREW_CHAT).get(0).reactions.size());
        book.reactAnswered(CREW_CHAT, T, HEART_VS16, true, null);
        assertEquals(1, book.entries(CREW_CHAT).get(0).reactions.size());
    }

    @Test
    public void reactionsComeWithSyncsAndLeaveWithTheirLastPerson() {
        ChatBook book = new ChatBook(ME);
        book.apply(frame("chat", text(DM, T, B, "gg")));
        JsonObject sync = new JsonObject();
        sync.addProperty("t", "chat_sync");
        sync.addProperty("conv", DM);
        sync.add("messages", new JsonArray());
        JsonArray reacted = new JsonArray();
        JsonObject one = new JsonObject();
        one.addProperty("id", T);
        one.add("reactions", reactions(THUMBS, new String[]{B}));
        reacted.add(one);
        sync.add("reacted", reacted);
        book.apply(sync);
        assertEquals(1, book.message(DM, T).reactions.size());
        JsonObject off = new JsonObject();
        off.addProperty("t", "chat_reacted");
        off.addProperty("conv", DM);
        off.addProperty("id", T);
        off.addProperty("by", B);
        off.addProperty("e", THUMBS);
        off.addProperty("on", false);
        book.apply(off);
        assertTrue(book.message(DM, T).reactions.isEmpty());
        // Older than a week: no reactions offered.
        assertFalse(book.reactable(book.entries(DM).get(0), T + ChatBook.REACTION_WINDOW_MS));
        assertNull(book.react(DM, T, THUMBS, true, T + ChatBook.REACTION_WINDOW_MS));
    }

    // ---- read marks ----

    @Test
    public void seenShowsUnderTheLastMessageTheFriendReadUntilTheyAnswer() {
        ChatBook book = new ChatBook(ME);
        JsonObject a = text(DM, T, ME, "selam");
        a.addProperty("nonce", "k1");
        JsonObject b = text(DM, T + 10, ME, "geliyor musun");
        b.addProperty("nonce", "k2");
        book.apply(frame("chat", a));
        book.apply(frame("chat", b));
        assertNull(book.seenKey(DM));
        JsonObject read = new JsonObject();
        read.addProperty("t", "chat_read");
        read.addProperty("conv", DM);
        read.addProperty("by", B);
        read.addProperty("id", T + 10);
        assertEquals(ChatBook.Event.Kind.READ, book.apply(read).get(0).kind);
        assertEquals("n:k2", book.seenKey(DM));
        // Only forward.
        read.addProperty("id", T);
        assertTrue(book.apply(read).isEmpty());
        assertEquals(T + 10, book.peerRead(DM));
        // The friend answered: their answer says it was seen.
        book.apply(frame("chat", text(DM, T + 20, B, "evet")));
        assertNull(book.seenKey(DM));
        assertEquals(T + 20, book.newestTheirs(DM));
        // The player wrote last: nothing to mark.
        book.apply(frame("chat", text(DM, T + 30, ME, "tamam")));
        assertEquals(0, book.newestTheirs(DM));
    }

    @Test
    public void thePlayersOwnMarkFromAnotherDeviceKeepsTheUnreadCountsInStep() {
        ChatBook book = new ChatBook(ME);
        book.apply(frame("chat", text(CREW_CHAT, T, B, "a")));
        book.apply(frame("chat", text(CREW_CHAT, T + 1, C, "b")));
        assertEquals(2, book.unread(CREW_CHAT));
        JsonObject read = new JsonObject();
        read.addProperty("t", "chat_read");
        read.addProperty("conv", CREW_CHAT);
        read.addProperty("by", ME);
        read.addProperty("id", T);
        book.apply(read);
        assertEquals(1, book.unread(CREW_CHAT));
        // A crewmate's mark is never the player's business (crew chat has no "Görüldü").
        read.addProperty("by", B);
        read.addProperty("id", T + 1);
        book.apply(read);
        assertEquals(0, book.peerRead(CREW_CHAT));
        assertNull(book.seenKey(CREW_CHAT));
    }

    // ---- catching up ----

    @Test
    public void aPhoneThatGainsTheExtrasSyncsOnceFromAWeekBack() {
        ChatBook book = new ChatBook(ME);
        book.apply(frame("chat", text(DM, T, B, "a")));
        assertEquals(T - ChatBook.SYNC_OVERLAP_MS, book.syncSince());
        assertEquals(T - ChatBook.SYNC_OVERLAP_MS, book.syncSince(false));
        assertEquals(T - ChatBook.CATCH_UP_MS, book.syncSince(true));
        book.caughtUp();
        assertEquals(T - ChatBook.SYNC_OVERLAP_MS, book.syncSince(true));
    }

    // ---- typing ----

    @Test
    public void typingShowsForSixSecondsAndEndsWithTheirMessage() {
        ChatTyping typing = new ChatTyping();
        typing.saw(CREW_CHAT, B, 1000);
        typing.saw(CREW_CHAT, C, 2000);
        assertEquals(Arrays.asList(B, C), typing.writers(CREW_CHAT, 3000));
        assertEquals(7000, typing.nextEnd());
        assertEquals(Collections.singletonList(C), typing.writers(CREW_CHAT, 7500));
        assertTrue(typing.prune(7500));
        assertTrue(typing.stop(CREW_CHAT, C));
        assertFalse(typing.anyone(CREW_CHAT, 7600));
        assertEquals(0, typing.nextEnd());
        typing.saw(DM, B, 10_000);
        assertTrue(typing.offline(B));
        assertFalse(typing.anyone(DM, 10_001));
        // The player's own frames: the first key, then at most every 4 seconds; a sent message starts over.
        assertTrue(typing.wrote(DM, 20_000));
        assertFalse(typing.wrote(DM, 23_999));
        assertTrue(typing.wrote(DM, 24_000));
        typing.sent(DM);
        assertTrue(typing.wrote(DM, 24_100));
        assertEquals(3, ChatTyping.shape(Arrays.asList("a", "b", "c", "d"))[0]);
        assertEquals(2, ChatTyping.shape(Arrays.asList("a", "b", "c", "d"))[1]);
    }

    // ---- link cards ----

    @Test
    public void aCardIsAskedOnceOneAtATimeAndKeptWithTheMessage() {
        ChatCards cards = new ChatCards();
        assertTrue(cards.want(DM, T, "https://youtu.be/abc", "youtu.be", 1000));
        assertFalse(cards.want(DM, T, "https://youtu.be/abc", "youtu.be", 1000));
        assertTrue(cards.want(DM, T + 1, "https://modrinth.com/mod/x", "modrinth.com", 1000));
        // Never for a message older than a week.
        assertFalse(cards.want(DM, T + 2, "https://a.example", "a.example", ChatCards.MAX_AGE_MS));
        ChatCards.Ask first = cards.next(0);
        assertNotNull(first);
        assertNull(cards.next(0));
        JsonObject ack = new JsonObject();
        ack.addProperty("t", "ack");
        JsonObject card = new JsonObject();
        card.addProperty("url", "https://youtu.be/abc");
        card.addProperty("site", "YouTube");
        card.addProperty("icon", "youtube");
        card.addProperty("kind", "video");
        card.addProperty("title", "Yeni sezon");
        card.addProperty("image", "data:image/jpeg;base64,AAAA");
        card.addProperty("w", 320);
        card.addProperty("h", 180);
        ack.add("card", card);
        assertEquals(ChatCards.Outcome.CARD, cards.answered(first, ack, 0));
        ChatCards.Card kept = cards.card(DM, T);
        assertEquals("YouTube", kept.site);
        assertEquals(ChatLinkUi.Shape.BANNER, ChatLinkUi.shapeOf(kept));
        // The store's form reads back the same.
        assertEquals("Yeni sezon", ChatCards.Card.fromJson(kept.toJson()).title);
        // A busy hub is asked once more, later; a refusal by the rules turns the link into text.
        ChatCards.Ask second = cards.next(0);
        JsonObject busy = new JsonObject();
        busy.addProperty("t", "error");
        busy.addProperty("code", "unfurl_busy");
        busy.addProperty("retryAfter", 2);
        assertEquals(ChatCards.Outcome.BUSY, cards.answered(second, busy, 0));
        assertNull(cards.next(1000));
        ChatCards.Ask again = cards.next(ChatCards.BUSY_RETRY_MS + 1);
        assertNotNull(again);
        JsonObject refused = new JsonObject();
        refused.addProperty("t", "error");
        refused.addProperty("code", "unfurl_failed");
        refused.addProperty("rule", "grabber");
        assertEquals(ChatCards.Outcome.REFUSED, cards.answered(again, refused, ChatCards.BUSY_RETRY_MS + 2));
        assertEquals(ChatCards.State.REFUSED, cards.card(DM, T + 1).state);
    }

    @Test
    public void aCardWithoutAPictureOrBrokenOneIsDrawnInOneRow() {
        JsonObject card = new JsonObject();
        card.addProperty("site", "Modrinth");
        card.addProperty("kind", "project");
        card.addProperty("image", "javascript:alert(1)");
        ChatCards.Card c = ChatCards.Card.read(card, "https://modrinth.com/mod/x");
        assertNull(c.image);
        assertEquals(ChatLinkUi.Shape.ROW, ChatLinkUi.shapeOf(c));
        assertNull(ChatCards.Card.read(new JsonObject(), "https://x.example"));
    }

    // ---- drawing links ----

    @Test
    public void theWarningNamesTheSitesOwnDomain() {
        assertEquals("youtube.com", ChatLinkUi.domainOf("www.youtube.com"));
        assertEquals("twitch.tv", ChatLinkUi.domainOf("clips.twitch.tv"));
        assertEquals("projects.gg", ChatLinkUi.domainOf("forum.projects.gg"));
        assertEquals("example.com.tr", ChatLinkUi.domainOf("example.com.tr"));
        assertEquals("example.com.tr", ChatLinkUi.domainOf("forum.example.com.tr"));
        assertEquals("example.co.uk", ChatLinkUi.domainOf("shop.example.co.uk"));
        assertEquals("blogspot.com", ChatLinkUi.domainOf("kotu.blogspot.com"));
        assertEquals("kotu.example", ChatLinkUi.domainOf("youtube.com.kotu.example"));
    }

    @Test
    public void aCardHoldsAWidePlaceForAVideoAClipAndAProjectsPage() {
        assertEquals(ChatLinkUi.Shape.BANNER, ChatLinkUi.shapeFor("https://youtu.be/abc", "youtu.be"));
        assertEquals(ChatLinkUi.Shape.BANNER, ChatLinkUi.shapeFor("https://www.youtube.com/watch?v=abc", "www.youtube.com"));
        assertEquals(ChatLinkUi.Shape.ROW, ChatLinkUi.shapeFor("https://www.youtube.com/@kanal", "www.youtube.com"));
        assertEquals(ChatLinkUi.Shape.BANNER, ChatLinkUi.shapeFor("https://clips.twitch.tv/abc", "clips.twitch.tv"));
        assertEquals(ChatLinkUi.Shape.ROW, ChatLinkUi.shapeFor("https://www.twitch.tv/kanal", "www.twitch.tv"));
        assertEquals(ChatLinkUi.Shape.BANNER, ChatLinkUi.shapeFor("https://projects.gg/haber/1", "projects.gg"));
        assertEquals(ChatLinkUi.Shape.ROW, ChatLinkUi.shapeFor("https://wiki.projects.gg/x", "wiki.projects.gg"));
        assertEquals("ornek.com/adalar", ChatLinkUi.addressOf("https://www.ornek.com/adalar/", "www.ornek.com"));
        assertEquals("http", ChatLinkUi.ruleWords("http"));
        assertEquals("other", ChatLinkUi.ruleWords("grabber"));
    }

    // ---- the chat state ----

    @Test
    public void anExtraIsOfferedOnlyWhileStudioHasItAndTheSocketTookIt() {
        JsonObject chat = new JsonObject();
        chat.addProperty("on", true);
        JsonObject extras = new JsonObject();
        for (String e : new String[]{"replies", "stickers", "reactions", "typing", "reads", "links"}) extras.addProperty(e, true);
        extras.addProperty("typing", false);
        chat.add("extras", extras);
        JsonArray sets = new JsonArray();
        sets.add("pl1");
        chat.add("stickers", sets);
        JsonObject links = new JsonObject();
        links.addProperty("rules", 2);
        chat.add("links", links);
        SocialModel.ChatState state = SocialModel.ChatState.read(chat);
        HashSet<String> caps = new HashSet<>(Arrays.asList("chat", "replies", "stickers", "reactions", "typing"));
        assertTrue(state.offers("replies", caps));
        assertFalse(state.offers("typing", caps));
        assertFalse(state.offers("reads", caps));
        assertTrue(state.offers("links", caps));
        assertEquals(Collections.singletonList("pl1"), state.stickerSets);
        assertNotNull(state.links);
        // An older service: none of it.
        SocialModel.ChatState old = SocialModel.ChatState.read(new JsonObject());
        assertFalse(old.hasExtras());
        assertFalse(old.offers("links", caps));
    }

    /** A store that keeps reactions and the friend's mark like the app's file. */
    @Test
    public void theStoreKeepsReactionsStickersAndTheFriendsMark() {
        final Map<String, ChatMessage> rows = new HashMap<>();
        final Map<String, ChatBook.Meta> meta = new HashMap<>();
        final boolean[] caught = new boolean[1];
        ChatBook.Store store = new ChatBook.Store() {
            @Override public void putMessage(ChatMessage m) { rows.put(m.conv + "#" + m.id, m); }
            @Override public void removeMessage(String conv, long id) { rows.remove(conv + "#" + id); }
            @Override public void putTombstone(String conv, long id, String by, long at) { rows.remove(conv + "#" + id); }
            @Override public void putOutbox(ChatBook.Outbox entry) {}
            @Override public void removeOutbox(String nonce) {}
            @Override public void putMeta(String conv, ChatBook.Meta m) { meta.put(conv, m); }
            @Override public void clear(String conv) {}
            @Override public void putSyncCursor(long newestSeen) {}
            @Override public void putCaughtUp() { caught[0] = true; }
        };
        ChatBook book = new ChatBook(ME, store, ChatBook.Snapshot.empty());
        book.apply(frame("chat", sticker(DM, T, B, "pl1/lol")));
        JsonObject reacted = new JsonObject();
        reacted.addProperty("t", "chat_reacted");
        reacted.addProperty("conv", DM);
        reacted.addProperty("id", T);
        reacted.addProperty("by", ME);
        reacted.addProperty("e", THUMBS);
        reacted.addProperty("on", true);
        book.apply(reacted);
        ChatMessage kept = rows.get(DM + "#" + T);
        assertEquals("pl1/lol", kept.sticker);
        assertEquals(1, kept.reactions.size());
        assertEquals(1, ChatMessage.reactionsFrom(ChatMessage.reactionsJson(kept.reactions).toString()).size());
        JsonObject read = new JsonObject();
        read.addProperty("t", "chat_read");
        read.addProperty("conv", DM);
        read.addProperty("by", B);
        read.addProperty("id", T);
        book.apply(read);
        assertEquals(T, meta.get(DM).peerRead);
        book.caughtUp();
        assertTrue(caught[0]);
    }
}
