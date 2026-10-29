package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.gson.JsonObject;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SOC9 (TEAM4-B, soc9.md §3): the light friend list's crews put back in full from the phone's copies or the crews'
 * details: read once, then from the copy the same day with the same rev; again with the copy's ETag on a new rev or day;
 * gone with team_not_found; an older copy (the list's look over it) when the detail cannot be read; team frames as the
 * newest copy.
 */
public class CrewDetailsTest {
    private static final String ME = SocialStateTest.ME;
    private static final String MATE = SocialStateTest.KAAN;
    private static final String A = "0123456789abcdef";
    private static final String B = "fedcba9876543210";
    private static final long DAY = 20755;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static String lite(String id, String name, int rev) {
        return "{\"team\":{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"tag\":\"GKR\",\"emblem\":\"flame\",\"color\":\"teal\""
                + ",\"frame\":\"shield\",\"hidden\":false,\"members\":2,\"rev\":" + rev + "},\"invited\":[]"
                + ",\"notifications\":{\"calls\":true,\"muted\":false,\"muteUntil\":null},\"requests\":[]}";
    }

    private static JsonObject full(String id, String name, int rev) {
        return SocialStateTest.json("{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"tag\":\"GKR\",\"emblem\":\"flame\""
                + ",\"color\":\"teal\",\"frame\":\"shield\",\"hidden\":false,\"motto\":\"Gece bizimdir\",\"join\":\"open\""
                + ",\"code\":\"TKM-ABCD-EFGH\",\"createdOn\":\"2026-09-20\",\"rev\":" + rev + ",\"members\":["
                + "{\"id\":\"" + ME + "\",\"username\":\"blaze_runner\",\"displayName\":\"Blaze_Runner\",\"avatar\":\"discord\",\"role\":\"founder\",\"moderator\":false,\"joinedAt\":1}"
                + ",{\"id\":\"" + MATE + "\",\"username\":\"ender_fox\",\"displayName\":\"Ender_Fox\",\"avatar\":\"preset:fox\",\"role\":\"member\",\"moderator\":true,\"joinedAt\":2}]"
                + ",\"permissions\":{\"invite\":\"members\",\"call\":\"moderators\",\"kick\":\"moderators\",\"edit\":\"leader\"}}");
    }

    private static JsonObject list(String... crews) {
        StringBuilder all = new StringBuilder();
        for (String c : crews) all.append(all.length() == 0 ? "" : ",").append(c);
        return SocialStateTest.json("{\"friends\":[],\"incoming\":[],\"outgoing\":[],\"blocked\":[]"
                + ",\"limits\":{\"friends\":200,\"teamMembers\":30},\"crews\":[" + all + "],\"crewLimit\":5,\"teamInvites\":[]"
                + ",\"teamFeatures\":[\"look\",\"calls\",\"roles\",\"mute\",\"multi\",\"details\"],\"features\":[\"push\"]}");
    }

    /** Answers from a table, and remembers what was asked ("<crew>|<etag>"). */
    private static final class Fake implements CrewDetails.Fetcher {
        final List<String> asked = new ArrayList<>();
        final Map<String, CrewDetails.Detail> answers = new HashMap<>();
        SocialProblem failure;

        @Override
        public CrewDetails.Detail fetch(String crewId, String etag) throws SocialProblem {
            asked.add(crewId + "|" + etag);
            if (failure != null) throw failure;
            CrewDetails.Detail d = answers.get(crewId);
            if (d == null) throw SocialProblem.unavailable("no answer in the test");
            return d;
        }
    }

    private CrewCache cache() {
        return new CrewCache(tmp.getRoot(), ME);
    }

    @Test
    public void aLightCrewIsReadOnceThenFromTheCopyTheSameDay() throws Exception {
        Fake fake = new Fake();
        fake.answers.put(A, new CrewDetails.Detail(CrewDetails.Detail.Status.OK, "\"3-20755\"", full(A, "Gece Kurtları", 3)));
        JsonObject friends = list(lite(A, "Gece Kurtları", 3));
        assertEquals(1, CrewDetails.resolve(friends, cache(), fake, DAY));
        SocialModel.FriendsData data = SocialModel.FriendsData.read(friends);
        assertNotNull(data.crews);
        assertEquals(1, data.crews.size());
        SocialModel.Crew crew = data.crews.get(0);
        assertEquals("the members came from the detail", 2, crew.team.members.size());
        assertTrue(crew.team.member(ME).founder);
        assertTrue(crew.team.member(MATE).moderator);
        assertEquals("TKM-ABCD-EFGH", crew.team.code);
        assertEquals("Gece bizimdir", crew.team.motto);
        assertEquals("the list's own crew fields stay", true, crew.mute.calls);
        CrewCache.Entry copy = cache().load(A);
        assertNotNull(copy);
        assertEquals("the ETag as it came", "\"3-20755\"", copy.etag);
        assertEquals(3, copy.rev);
        assertEquals(DAY, copy.day);

        JsonObject again = list(lite(A, "Gece Kurtları", 3));
        assertEquals("same rev, same day: no request", 0, CrewDetails.resolve(again, cache(), fake, DAY));
        assertEquals(1, fake.asked.size());
        assertEquals(2, SocialModel.FriendsData.read(again).crews.get(0).team.members.size());
    }

    @Test
    public void aNewRevOrAnotherDayReadsAgainWithTheCopysEtag() throws Exception {
        cache().store(A, "\"3-20755\"", 3, DAY, full(A, "Gece Kurtları", 3));
        Fake fake = new Fake();
        fake.answers.put(A, new CrewDetails.Detail(CrewDetails.Detail.Status.OK, "\"4-20755\"", full(A, "Gece Kartalları", 4)));
        JsonObject friends = list(lite(A, "Gece Kartalları", 4));
        assertEquals(1, CrewDetails.resolve(friends, cache(), fake, DAY));
        assertEquals(A + "|\"3-20755\"", fake.asked.get(0));
        assertEquals("Gece Kartalları", SocialModel.FriendsData.read(friends).crews.get(0).team.name);
        assertEquals(4, cache().load(A).rev);

        // The next day the copy is read again even with the same rev: members' names and pictures stay at most a day old.
        fake.answers.put(A, new CrewDetails.Detail(CrewDetails.Detail.Status.OK, "\"4-20756\"", full(A, "Gece Kartalları", 4)));
        assertEquals(1, CrewDetails.resolve(list(lite(A, "Gece Kartalları", 4)), cache(), fake, DAY + 1));
        assertEquals(A + "|\"4-20755\"", fake.asked.get(1));
        assertEquals(DAY + 1, cache().load(A).day);
        assertEquals("\"4-20756\"", cache().load(A).etag);
    }

    @Test
    public void notModifiedKeepsTheCopy() throws Exception {
        cache().store(A, "\"3-20754\"", 3, DAY - 1, full(A, "Gece Kurtları", 3));
        Fake fake = new Fake();
        fake.answers.put(A, new CrewDetails.Detail(CrewDetails.Detail.Status.NOT_MODIFIED, "\"3-20755\"", null));
        JsonObject friends = list(lite(A, "Gece Kurtları", 3));
        CrewDetails.resolve(friends, cache(), fake, DAY);
        assertEquals(2, SocialModel.FriendsData.read(friends).crews.get(0).team.members.size());
        CrewCache.Entry copy = cache().load(A);
        assertEquals(DAY, copy.day);
        assertEquals("\"3-20755\"", copy.etag);
    }

    @Test
    public void aCrewTheDetailNoLongerGivesLeavesTheList() throws Exception {
        cache().store(B, null, 1, DAY - 3, full(B, "Taş Ustaları", 1));
        Fake fake = new Fake();
        fake.answers.put(A, new CrewDetails.Detail(CrewDetails.Detail.Status.OK, "\"3-20755\"", full(A, "Gece Kurtları", 3)));
        fake.answers.put(B, new CrewDetails.Detail(CrewDetails.Detail.Status.GONE, null, null));
        JsonObject friends = list(lite(A, "Gece Kurtları", 3), lite(B, "Taş Ustaları", 2));
        CrewDetails.resolve(friends, cache(), fake, DAY);
        List<SocialModel.Crew> crews = SocialModel.FriendsData.read(friends).crews;
        assertEquals(1, crews.size());
        assertEquals(A, crews.get(0).id());
        assertNull(cache().load(B));
    }

    @Test
    public void anUnreadCrewKeepsItsOlderCopyOrFailsTheRead() throws Exception {
        cache().store(A, "\"3-20754\"", 3, DAY - 1, full(A, "Gece Kurtları", 3));
        Fake fake = new Fake();
        fake.failure = SocialProblem.unavailable("offline");
        JsonObject friends = list(lite(A, "Gece Kartalları", 4));
        CrewDetails.resolve(friends, cache(), fake, DAY);
        SocialModel.Crew crew = SocialModel.FriendsData.read(friends).crews.get(0);
        assertEquals("the older copy's members", 2, crew.team.members.size());
        assertEquals("with the list's look over it", "Gece Kartalları", crew.team.name);
        assertEquals("read again next time: the copy keeps its rev", 3, cache().load(A).rev);

        try {
            CrewDetails.resolve(list(lite(B, "Taş Ustaları", 1)), cache(), fake, DAY);
            fail("a crew with no copy and no detail fails the read");
        } catch (SocialProblem expected) {
            assertEquals(SocialProblem.Kind.UNAVAILABLE, expected.kind);
        }

        fake.failure = new SocialProblem(SocialProblem.Kind.SIGNED_OUT, 401, "session ended", 0);
        try {
            CrewDetails.resolve(list(lite(A, "Gece Kartalları", 4)), cache(), fake, DAY);
            fail("an ended session ends the read even with a copy");
        } catch (SocialProblem expected) {
            assertEquals(SocialProblem.Kind.SIGNED_OUT, expected.kind);
        }
    }

    @Test
    public void anOlderServicesFullCrewsNeedNothing() throws Exception {
        Fake fake = new Fake();
        JsonObject friends = list("{\"team\":" + full(A, "Gece Kurtları", 3) + ",\"invited\":[]}");
        assertEquals(0, CrewDetails.resolve(friends, cache(), fake, DAY));
        assertTrue(fake.asked.isEmpty());
        assertEquals(2, SocialModel.FriendsData.read(friends).crews.get(0).team.members.size());
        assertEquals(0, CrewDetails.resolve(SocialStateTest.json("{\"friends\":[]}"), cache(), fake, DAY));
    }

    @Test
    public void copiesOfCrewsNoLongerListedGo() throws Exception {
        cache().store(A, "\"3-20755\"", 3, DAY, full(A, "Gece Kurtları", 3));
        cache().store(B, "\"1-20755\"", 1, DAY, full(B, "Taş Ustaları", 1));
        Fake fake = new Fake();
        CrewDetails.resolve(list(lite(A, "Gece Kurtları", 3)), cache(), fake, DAY);
        assertNotNull(cache().load(A));
        assertNull("left while the phone was away", cache().load(B));
    }

    @Test
    public void teamFramesKeepTheNewestCopy() {
        CrewCache cache = cache();
        JsonObject frame = SocialStateTest.json("{\"t\":\"team\",\"crew\":\"" + A + "\",\"team\":" + full(A, "Gece Kurtları", 5) + "}");
        CrewDetails.onTeamFrame(frame, cache, DAY);
        CrewCache.Entry copy = cache.load(A);
        assertNotNull(copy);
        assertEquals(5, copy.rev);
        assertNull("no ETag from a frame: the next read asks without one", copy.etag);
        CrewDetails.onTeamFrame(SocialStateTest.json("{\"t\":\"team\",\"crew\":\"" + A + "\",\"team\":null}"), cache, DAY);
        assertNull("left, removed or ended", cache.load(A));
        CrewDetails.onTeamFrame(SocialStateTest.json("{\"t\":\"team\",\"crew\":\"" + A + "\",\"team\":" + lite(A, "Gece Kurtları", 6)
                .replaceFirst("^\\{\"team\":", "").replaceFirst(",\"invited\".*$", "") + "}"), cache, DAY);
        assertNull("a short team is no copy", cache.load(A));
    }

    @Test
    public void signingOutClearsEveryCopy() {
        cache().store(A, null, 1, DAY, full(A, "Gece Kurtları", 1));
        new CrewCache(tmp.getRoot(), MATE).store(B, null, 1, DAY, full(B, "Taş Ustaları", 1));
        CrewCache.clear(tmp.getRoot());
        File[] left = tmp.getRoot().listFiles();
        assertTrue(left == null || left.length == 0);
        assertFalse(new File(tmp.getRoot(), ME).exists());
    }

    @Test
    public void theDayIsTurkeys() {
        assertEquals(20755, CrewDetails.turkeyDay(1793275200L));
        assertEquals("00:30 in Turkey is already the next day", 20756, CrewDetails.turkeyDay(1793309400L));
        assertEquals(20755, CrewDetails.turkeyDay(1793306999L));
    }
}
