package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * M048 (SOC-DISK on the phone; scale-soc.md, the phone's spec 6): the friend list's answer kept on the phone as the
 * service sent it, for the next start the same day. Read back only for its account, its day and its request, with a
 * version; anything else is no copy. Every account's copy goes with a clear, and a write decided before the clear
 * never lands after it. A start takes each crew from today's crew copies or reads the lists as before.
 */
public class KeptListsTest {
    private static final String ME = SocialStateTest.ME;
    private static final String MATE = SocialStateTest.KAAN;
    private static final String REL = "0123456789abcdef";
    private static final long DAY = 20758;
    private static final String CREW = "c0ffee42";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String me(String id, String name) {
        return "{\"id\":\"" + id + "\",\"username\":\"" + name.toLowerCase() + "\",\"displayName\":\"" + name
                + "\",\"avatar\":\"discord\",\"avatarHash\":null,\"friendCode\":\"PRJ-4168-QN7G\",\"status\":\"online\""
                + ",\"privacy\":{\"shareServer\":false,\"shareLastSeen\":true},\"createdOn\":\"2026-10-21\",\"settings\":null"
                + ",\"limited\":null,\"notices\":[]}";
    }

    private static String liteCrew(int rev) {
        return "{\"team\":{\"id\":\"" + CREW + "\",\"name\":\"Gece Kartalları\",\"tag\":\"GKR\",\"emblem\":\"flame\",\"color\":\"teal\""
                + ",\"frame\":\"shield\",\"hidden\":false,\"members\":2,\"rev\":" + rev + "},\"invited\":[]"
                + ",\"notifications\":{\"calls\":true,\"muted\":false,\"muteUntil\":null},\"requests\":[]}";
    }

    private static JsonObject fullCrew(int rev) {
        return SocialStateTest.json("{\"id\":\"" + CREW + "\",\"name\":\"Gece Kartalları\",\"tag\":\"GKR\",\"emblem\":\"flame\""
                + ",\"color\":\"teal\",\"frame\":\"shield\",\"hidden\":false,\"motto\":\"Gece bizimdir\",\"join\":\"open\""
                + ",\"code\":\"TKM-ABCD-EFGH\",\"createdOn\":\"2026-09-20\",\"rev\":" + rev + ",\"members\":["
                + "{\"id\":\"" + ME + "\",\"username\":\"blaze_runner\",\"displayName\":\"Blaze_Runner\",\"avatar\":\"discord\",\"role\":\"founder\",\"moderator\":false,\"joinedAt\":1}"
                + ",{\"id\":\"" + MATE + "\",\"username\":\"ender_fox\",\"displayName\":\"Ender_Fox\",\"avatar\":\"preset:fox\",\"role\":\"member\",\"moderator\":true,\"joinedAt\":2}]"
                + ",\"permissions\":{\"invite\":\"members\",\"call\":\"moderators\",\"kick\":\"moderators\",\"edit\":\"leader\"}}");
    }

    /** {@code GET /v1/friends?crews=lite&rel=1} as the service answers it: the light lists, {@code me} and {@code rel}. */
    static String answerText(String account, String name, String rel) {
        return "{\"friends\":[{\"id\":\"" + MATE + "\",\"username\":\"ender_fox\",\"displayName\":\"Ender_Fox\",\"avatar\":\"discord\""
                + ",\"avatarHash\":null,\"since\":\"2026-10-20\",\"badge\":null,\"later\":{\"kept\":[1,2.50,\"şimdi\"]}}]"
                + ",\"incoming\":[],\"outgoing\":[],\"blocked\":[],\"limits\":{\"friends\":200,\"teamMembers\":30}"
                + ",\"groups\":{\"version\":3,\"groups\":[]},\"crews\":[" + liteCrew(4) + "],\"crewLimit\":5,\"teamInvites\":[]"
                + ",\"teamRequests\":[],\"teamFeatures\":[\"look\",\"calls\",\"roles\",\"mute\",\"multi\",\"details\"]"
                + ",\"teamNotifications\":{\"calls\":true,\"muted\":false,\"muteUntil\":null},\"features\":[\"push\"]"
                + ",\"me\":" + me(account, name) + (rel == null ? "" : ",\"rel\":\"" + rel + "\"") + "}";
    }

    static JsonObject answer(String account, String name, String rel) {
        return SocialStateTest.json(answerText(account, name, rel));
    }

    private KeptLists kept() {
        return new KeptLists(new File(tmp.getRoot(), "lists"));
    }

    private File file(String account) {
        return new File(new File(tmp.getRoot(), "lists"), account + ".json");
    }

    private void write(String account, String text) throws IOException {
        File f = file(account);
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String wrap(String v, String day, String from, String answer) {
        return "{" + (v == null ? "" : "\"v\":" + v + ",") + "\"day\":" + day + (from == null ? "" : ",\"from\":\"" + from + "\"")
                + ",\"answer\":" + answer + "}";
    }

    @Test
    public void aKeptAnswerComesBackAsItCameForItsAccountOnItsDay() throws IOException {
        KeptLists kept = kept();
        JsonObject answer = answer(ME, "Blaze_Runner", REL);
        assertTrue(kept.save(kept.generation(), ME, DAY, answer.deepCopy()));

        KeptLists.Copy copy = kept.load(ME, DAY);
        assertNotNull(copy);
        assertEquals(REL, copy.rel);
        assertEquals(ME, Json.str(copy.me, "id"));
        assertEquals(answer, copy.answer);
        // As the service sent it: fields the phone does not read today and numbers as written stay.
        String text = new String(Files.readAllBytes(file(ME).toPath()), StandardCharsets.UTF_8);
        assertTrue(text, text.contains("\"later\":{\"kept\":[1,2.50,\"şimdi\"]}"));
        assertTrue(text, text.startsWith("{\"v\":1,\"day\":" + DAY + ",\"from\":\"v1/friends?crews=lite&rel=1\",\"answer\":"));
        // Nothing of the session: no token, no sign-in.
        for (String secret : Arrays.asList("token", "pls_", "verifier", "challenge", "Authorization")) {
            assertFalse(secret, text.contains(secret));
        }
        // The version holds the day, so another day has no copy; nor has another account.
        assertNull(kept.load(ME, DAY + 1));
        assertNull(kept.load(ME, DAY - 1));
        assertNull(kept.load(MATE, DAY));
        assertNull(kept.load(null, DAY));
        assertNull(kept.load("../" + ME, DAY));
        // A fresh copy at every load: what a start changes is not the next start's.
        copy.answer.remove("friends");
        assertEquals(answer, kept.load(ME, DAY).answer);
    }

    @Test
    public void onlyAWholeCopyOfThisRequestWithAVersionIsACopy() throws IOException {
        KeptLists kept = kept();
        String good = answerText(ME, "Blaze_Runner", REL);
        String from = KeptLists.FROM;
        String[] notCopies = {
                "",
                "[1,2]",
                "null",
                "{\"v\":1,\"day\":",
                wrap("1", String.valueOf(DAY), from, good).substring(0, 400),
                wrap("2", String.valueOf(DAY), from, good),
                wrap(null, String.valueOf(DAY), from, good),
                wrap("\"1\"", String.valueOf(DAY), from, good),
                wrap("1", "\"" + DAY + "\"", from, good),
                wrap("1", String.valueOf(DAY), null, good),
                wrap("1", String.valueOf(DAY), "v1/friends?crews=lite", good),
                wrap("1", String.valueOf(DAY), "v1/friends", good),
                wrap("1", String.valueOf(DAY), from, "[]"),
                wrap("1", String.valueOf(DAY), from, answerText(ME, "Blaze_Runner", null)),
                wrap("1", String.valueOf(DAY), from, answerText(ME, "Blaze_Runner", "0123456789ABCDEF")),
                wrap("1", String.valueOf(DAY), from, answerText(ME, "Blaze_Runner", "0123456789abcde")),
                wrap("1", String.valueOf(DAY), from, answerText(ME, "Blaze_Runner", "0123456789abcdef0")),
                wrap("1", String.valueOf(DAY), from, answerText(ME, "Blaze_Runner", "0123456789abcdeg")),
                // Another account's profile in this account's file.
                wrap("1", String.valueOf(DAY), from, answerText(MATE, "Ender_Fox", REL)),
                wrap("1", String.valueOf(DAY), from, good.replace(",\"me\":" + me(ME, "Blaze_Runner"), "")),
        };
        for (String text : notCopies) {
            write(ME, text);
            assertNull(text.length() > 80 ? text.substring(0, 80) : text, kept.load(ME, DAY));
        }
        // The same shape written by hand is a copy (the cases above fail only on what they change).
        write(ME, wrap("1", String.valueOf(DAY), from, good));
        assertNotNull(kept.load(ME, DAY));
        // Over 2 MB: no copy, however good the rest.
        StringBuilder big = new StringBuilder(wrap("1", String.valueOf(DAY), from, good));
        big.setLength(big.length() - 1);
        big.append(",\"pad\":\"");
        while (big.length() <= KeptLists.MAX_FILE) big.append("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        big.append("\"}");
        write(ME, big.toString());
        assertNull(kept.load(ME, DAY));
    }

    @Test
    public void onlyAnAnswerAStartCanUseIsKept() {
        KeptLists kept = kept();
        assertTrue(kept.save(kept.generation(), ME, DAY, answer(ME, "Blaze_Runner", REL)));
        // An older service's answer (no version): nothing kept, and the earlier copy is no longer current.
        assertFalse(kept.save(kept.generation(), ME, DAY, answer(ME, "Blaze_Runner", null)));
        assertFalse(file(ME).exists());
        assertFalse(kept.save(kept.generation(), ME, DAY, answer(ME, "Blaze_Runner", "NOT-A-VERSION")));
        // The answer of another account, or an account that is no account.
        assertFalse(kept.save(kept.generation(), ME, DAY, answer(MATE, "Ender_Fox", REL)));
        assertFalse(kept.save(kept.generation(), "../x", DAY, answer(ME, "Blaze_Runner", REL)));
        assertFalse(kept.save(kept.generation(), null, DAY, answer(ME, "Blaze_Runner", REL)));
        assertFalse(file(ME).exists());
        // Over 2 MB: not kept, and the earlier copy goes.
        assertTrue(kept.save(kept.generation(), ME, DAY, answer(ME, "Blaze_Runner", REL)));
        JsonObject huge = answer(ME, "Blaze_Runner", REL);
        StringBuilder pad = new StringBuilder();
        while (pad.length() <= KeptLists.MAX_FILE) pad.append("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        huge.addProperty("pad", pad.toString());
        assertFalse(kept.save(kept.generation(), ME, DAY, huge));
        assertFalse(file(ME).exists());
        assertNull(kept.load(ME, DAY));
    }

    @Test
    public void aClearTakesEveryCopyAndASaveDecidedBeforeItNeverLands() {
        KeptLists kept = kept();
        assertTrue(kept.save(kept.generation(), ME, DAY, answer(ME, "Blaze_Runner", REL)));
        assertTrue(kept.save(kept.generation(), MATE, DAY, answer(MATE, "Ender_Fox", REL)));
        assertTrue(file(ME).exists());
        assertTrue(file(MATE).exists());

        // A read's answer decided to be kept, then the copy changed (an event, a sign-out) before the write ran.
        long decided = kept.generation();
        kept.clear();
        assertFalse(file(ME).exists());
        assertFalse(file(MATE).exists());
        assertFalse(new File(tmp.getRoot(), "lists").exists());
        assertFalse(kept.save(decided, ME, DAY, answer(ME, "Blaze_Runner", REL)));
        assertFalse(file(ME).exists());
        assertNull(kept.load(ME, DAY));
        // A clear with nothing kept touches nothing and still drops what was decided before it.
        long before = kept.generation();
        kept.clear();
        assertFalse(kept.save(before, ME, DAY, answer(ME, "Blaze_Runner", REL)));
        // A read decided after the clear is kept.
        assertTrue(kept.save(kept.generation(), ME, DAY, answer(ME, "Blaze_Runner", REL)));
        assertNotNull(kept.load(ME, DAY));
    }

    @Test
    public void aClearAtStartTakesWhatAnEarlierRunLeft() throws IOException {
        // A file from an earlier run (and a write that never finished): a new run knows nothing of them, a clear takes both.
        write(ME, wrap("1", String.valueOf(DAY), KeptLists.FROM, answerText(ME, "Blaze_Runner", REL)));
        Files.write(new File(file(ME).getParentFile(), ME + ".json.tmp123").toPath(), new byte[] {1, 2, 3});
        KeptLists kept = kept();
        kept.clear();
        assertFalse(new File(tmp.getRoot(), "lists").exists());
    }

    @Test
    public void aStartTakesEveryCrewFromTodaysCopiesOrReadsAsBefore() {
        KeptLists kept = kept();
        assertTrue(kept.save(kept.generation(), ME, DAY, answer(ME, "Blaze_Runner", REL)));
        File crewRoot = new File(tmp.getRoot(), "crews");
        CrewCache crews = new CrewCache(crewRoot, ME);

        // No crew copy: the start reads the lists as before (nothing is fetched here).
        assertNull(KeptLists.listsWithCrews(kept.load(ME, DAY), crews, DAY));
        // An older revision of the crew, or yesterday's copy: the same.
        crews.store(CREW, "\"3-" + DAY + "\"", 3, DAY, fullCrew(3));
        assertNull(KeptLists.listsWithCrews(kept.load(ME, DAY), crews, DAY));
        crews.store(CREW, "\"4-" + (DAY - 1) + "\"", 4, DAY - 1, fullCrew(4));
        assertNull(KeptLists.listsWithCrews(kept.load(ME, DAY), crews, DAY));

        // Today's copy at the list's revision: the crew in full, the copy itself untouched.
        crews.store(CREW, "\"4-" + DAY + "\"", 4, DAY, fullCrew(4));
        KeptLists.Copy copy = kept.load(ME, DAY);
        JsonObject lists = KeptLists.listsWithCrews(copy, crews, DAY);
        assertNotNull(lists);
        assertTrue(Json.objects(lists, "crews").get(0).getAsJsonObject("team").get("members").isJsonArray());
        assertFalse(Json.objects(copy.answer, "crews").get(0).getAsJsonObject("team").get("members").isJsonArray());
        SocialModel.FriendsData data = SocialModel.FriendsData.read(lists);
        assertEquals(1, data.friends.size());
        assertEquals(MATE, data.friends.get(0).profile.id);
        assertEquals(1, data.crews.size());
        assertEquals(2, data.crews.get(0).team.members.size());
        SocialModel.Me me = SocialModel.Me.read(copy.me);
        assertNotNull(me);
        assertEquals(ME, me.profile.id);
    }
}
