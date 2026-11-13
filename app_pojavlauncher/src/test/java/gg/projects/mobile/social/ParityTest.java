package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import gg.projects.mobile.accounts.SkinFace;
import gg.projects.mobile.social.SocialModel.FriendsData;
import gg.projects.mobile.social.SocialModel.Member;
import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialModel.Permissions;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialModel.Team;
import gg.projects.mobile.social.SocialModel.TeamSummary;

/**
 * MOB-PARITY: friend categories, the profile picture's choices, the sessions' days, crew roles and the menus' new rows,
 * in the shapes the service sends (services/social src/groups.ts, account.ts, roles.ts; throwaway players).
 */
public class ParityTest {
    private static final String A = "100000000000000001";
    private static final String B = "100000000000000002";
    private static final String C = "100000000000000003";

    private static JsonObject json(String text) {
        return Json.parseObject(text);
    }

    // ---- friend categories ----

    @Test
    public void groupsReadKeepsOrderAndDropsWhatTheServiceWouldRefuse() {
        FriendGroups doc = FriendGroups.read(json("{\"version\":4,\"groups\":[{\"id\":\"okul\",\"name\":\"  Okul   arkadaşları \"},"
                + "{\"id\":\"rest\",\"name\":\"x\"},{\"id\":\"BAD\",\"name\":\"x\"},{\"id\":\"okul\",\"name\":\"twice\"},"
                + "{\"id\":\"ekip1\",\"name\":\"Ekip\"}],\"members\":{\"" + A + "\":\"okul\",\"" + B + "\":\"gone\",\"nope\":\"okul\"}}"));
        assertNotNull(doc);
        assertEquals(4, doc.version);
        assertEquals(2, doc.groups.size());
        assertEquals("okul", doc.groups.get(0).id);
        assertEquals("Okul arkadaşları", doc.groups.get(0).name);
        assertEquals("ekip1", doc.groups.get(1).id);
        assertEquals("okul", doc.groupOf(A));
        assertNull(doc.groupOf(B));
        assertEquals(1, doc.members.size());
        assertTrue(doc.valid());
    }

    @Test
    public void noGroupsFromAnOlderServiceIsNull() {
        assertNull(FriendGroups.read(null));
        FriendsData data = FriendsData.read(json("{\"friends\":[]}"));
        assertNull(data.groups);
        FriendsData withGroups = FriendsData.read(json("{\"friends\":[],\"groups\":{\"version\":0,\"groups\":[],\"members\":{}}}"));
        assertNotNull(withGroups.groups);
        assertFalse(withGroups.groups.hasGroups());
    }

    @Test
    public void groupsEditKeepTheRules() {
        FriendGroups doc = FriendGroups.EMPTY.withNew("Okul", new Random(1)).withNew("Sunucu", new Random(2));
        assertEquals(2, doc.groups.size());
        String okul = doc.groups.get(0).id;
        String sunucu = doc.lastId();
        assertTrue(okul.matches("[a-z0-9]{8}"));
        assertFalse(okul.equals(sunucu));
        doc = doc.withMember(A, okul).withMember(B, sunucu).withMember(C, sunucu);
        assertEquals(okul, doc.groupOf(A));
        // Moving up and down, and nothing past the ends.
        FriendGroups up = doc.moved(sunucu, -1);
        assertEquals(sunucu, up.groups.get(0).id);
        assertSame(up, up.moved(sunucu, -1));
        // Renaming cleans the name; an empty one changes nothing.
        assertEquals("Okul 2", doc.withName(okul, " Okul\t2 ").group(okul).name);
        assertSame(doc, doc.withName(okul, "   "));
        // Deleting sends its friends back to "Arkadaşlar".
        FriendGroups without = doc.without(sunucu);
        assertNull(without.groupOf(B));
        assertEquals(okul, without.groupOf(A));
        // Back to "Arkadaşlar" by null or "rest".
        assertNull(doc.withMember(A, FriendGroups.REST).groupOf(A));
        // Friends who left are pruned before a save.
        FriendGroups pruned = doc.pruned(new HashSet<>(Arrays.asList(A, B)));
        assertNull(pruned.groupOf(C));
        assertEquals(2, pruned.members.size());
        // The body is {groups, members} only.
        JsonObject body = doc.body();
        assertEquals(2, body.size());
        assertEquals(2, body.getAsJsonArray("groups").size());
        assertEquals(3, body.getAsJsonObject("members").size());
        assertTrue(FriendGroups.read(withVersion(body, 9)).key().equals(doc.key()));
    }

    private static JsonObject withVersion(JsonObject body, long version) {
        JsonObject o = body.deepCopy();
        o.addProperty("version", version);
        return o;
    }

    @Test
    public void groupsStopAtTwenty() {
        FriendGroups doc = FriendGroups.EMPTY;
        Random random = new Random(7);
        for (int i = 0; i < 25; i++) doc = doc.withNew("K" + i, random);
        assertEquals(FriendGroups.MAX_GROUPS, doc.groups.size());
        assertTrue(doc.full());
        assertTrue(doc.valid());
    }

    @Test
    public void groupNamesAreCleanedAndCut() {
        assertEquals("a b", FriendGroups.cleanName(" a \n b "));
        assertEquals("ab", FriendGroups.cleanName("a​b"));
        String longName = FriendGroups.cleanName("abcdefghijklmnopqrstuvwxyz");
        assertEquals(24, longName.length());
        assertEquals("", FriendGroups.cleanName(null));
    }

    @Test
    public void groupsFrameReplacesOnlyWithANewerCopy() {
        SocialState state = new SocialState();
        FriendsData data = FriendsData.read(json("{\"friends\":[],\"groups\":{\"version\":3,\"groups\":[{\"id\":\"a1\",\"name\":\"Bir\"}],\"members\":{}}}"));
        state.applyFriends(data);
        assertEquals(3, state.groups().version);
        state.apply(json("{\"t\":\"groups\",\"groups\":{\"version\":2,\"groups\":[],\"members\":{}}}"), 0);
        assertEquals(3, state.groups().version);
        state.apply(json("{\"t\":\"groups\",\"groups\":{\"version\":5,\"groups\":[{\"id\":\"b2\",\"name\":\"İki\"}],\"members\":{}}}"), 0);
        assertEquals(5, state.groups().version);
        assertEquals("İki", state.groups().groups.get(0).name);
        // An older list read meanwhile does not take it back.
        state.applyFriends(data);
        assertEquals(5, state.groups().version);
        assertTrue(RelVersion.changesRelations("groups"));
    }

    @Test
    public void inGroupKeepsTheListOrder() {
        FriendGroups doc = FriendGroups.EMPTY.withNew("Okul", new Random(3));
        String okul = doc.lastId();
        doc = doc.withMember(B, okul).withMember(C, okul);
        List<String> ordered = Arrays.asList(C, A, B);
        assertEquals(Arrays.asList(C, B), FriendGroups.inGroup(ordered, doc, okul, f -> f));
        assertEquals(Collections.singletonList(A), FriendGroups.inGroup(ordered, doc, null, f -> f));
    }

    // ---- profile picture ----

    @Test
    public void photoChoicesFollowTheDesktop() {
        String photo = "https://cdn.discordapp.com/avatars/" + A + "/a_0123456789abcdef0123456789abcdef.png?size=128";
        String texture = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        List<OwnPhoto.Choice> all = OwnPhoto.choices(photo, texture);
        assertEquals(26, all.size());
        assertEquals("discord", all.get(0).value);
        assertEquals("minecraft:" + texture, all.get(1).value);
        assertEquals("preset:golden-dragon", all.get(2).value);
        assertEquals("preset:elf-ranger", all.get(25).value);
        // No Discord picture, no head: the 24 avatars.
        List<OwnPhoto.Choice> presets = OwnPhoto.choices(null, null);
        assertEquals(24, presets.size());
        // Discord's stand-in and other hosts never make a tile.
        assertEquals(24, OwnPhoto.choices("https://cdn.discordapp.com/embed/avatars/1.png", "XYZ").size());
        assertEquals("preset:ninja", OwnPhoto.chosen("preset:ninja", all));
        assertNull(OwnPhoto.chosen("discord", presets));
        Profile me = new Profile(A, "Blaze_Runner", null, "preset:ninja", null);
        Profile shown = OwnPhoto.preview(me, all.get(0));
        assertEquals("discord", shown.avatar);
        assertNotNull(shown.discordAvatarUrl(64));
        assertEquals("ninja", OwnPhoto.preview(me, presets.get(1)).presetSlug());
        assertEquals(texture, OwnPhoto.preview(me, all.get(1)).textureHash());
    }

    @Test
    public void everyPresetHasItsPicture() {
        File dir = new File("src/main/assets/avatars");
        if (!dir.isDirectory()) dir = new File("app_pojavlauncher/src/main/assets/avatars");
        for (String slug : OwnPhoto.PRESETS) assertTrue(slug, new File(dir, slug + ".webp").isFile());
    }

    @Test
    public void meKeepsTheDiscordPhoto() {
        Me me = Me.read(json("{\"id\":\"" + A + "\",\"username\":\"Blaze_Runner\",\"avatar\":\"preset:fox\",\"friendCode\":\"X\","
                + "\"discordAvatarUrl\":\"https://cdn.discordapp.com/avatars/" + A + "/0123456789abcdef0123456789abcdef.png\"}"));
        assertNotNull(me);
        assertNotNull(me.discordPhoto);
        assertEquals(me.discordPhoto, me.withStatus("dnd").discordPhoto);
        assertNull(Me.discordPhotoOf("https://cdn.discordapp.com/embed/avatars/0.png"));
        assertNull(Me.discordPhotoOf("https://example.com/a.png"));
        assertNull(Me.discordPhotoOf(""));
    }

    @Test
    public void skinTextureIdComesFromMojangOnly() {
        String hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertEquals(hash, SkinFace.textureId("http://textures.minecraft.net/texture/" + hash.toUpperCase()));
        assertNull(SkinFace.textureId("https://evil.example/texture/" + hash));
        assertNull(SkinFace.textureId("http://textures.minecraft.net/texture/abc"));
        assertNull(SkinFace.textureId(null));
    }

    // ---- sessions ----

    @Test
    public void sessionDays() {
        long now = 1_794_000_000_000L; // a day in November 2026, mid-day UTC
        String today = isoDayOf(now);
        assertEquals(SessionDays.Kind.TODAY, SessionDays.lastUsed(today, now).kind);
        assertEquals(SessionDays.Kind.YESTERDAY, SessionDays.lastUsed(isoDayOf(now - 86_400_000L), now).kind);
        assertEquals(SessionDays.Kind.DAY, SessionDays.lastUsed(isoDayOf(now - 3 * 86_400_000L), now).kind);
        assertEquals(SessionDays.Kind.TODAY, SessionDays.lastUsed(isoDayOf(now + 86_400_000L), now).kind);
        assertNull(SessionDays.lastUsed("dün", now));
        assertNull(SessionDays.lastUsed(null, now));
    }

    private static String isoDayOf(long millis) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(millis));
    }

    // ---- crews ----

    private static Team team(Permissions permissions, Member... members) {
        TeamSummary summary = new TeamSummary("abcdef0123456789", "Gece Kuşları", "GK", "sword", "gold", "cut", null, members.length);
        return new Team(summary, "", null, new ArrayList<>(Arrays.asList(members)), permissions);
    }

    private static Member member(String id, boolean founder, boolean moderator) {
        return new Member(new Profile(id, "Player" + id.substring(15), null, "discord", null), founder, moderator, 0);
    }

    @Test
    public void rolesChipsMoveTheLevel() {
        assertEquals(Permissions.MODERATORS, CrewRoleRules.toggleModerators(Permissions.LEADER));
        assertEquals(Permissions.LEADER, CrewRoleRules.toggleModerators(Permissions.MODERATORS));
        // Moderators off takes it from members too.
        assertEquals(Permissions.LEADER, CrewRoleRules.toggleModerators(Permissions.MEMBERS));
        // Members on gives it to moderators too; off leaves it with them.
        assertEquals(Permissions.MEMBERS, CrewRoleRules.toggleMembers(Permissions.LEADER));
        assertEquals(Permissions.MODERATORS, CrewRoleRules.toggleMembers(Permissions.MEMBERS));
        assertEquals(4, CrewRoleRules.rights(false).size());
        assertEquals(5, CrewRoleRules.rights(true).size());
        for (CrewRoleRules.Right r : CrewRoleRules.rights(true)) {
            assertEquals(r.key.equals("invite") || r.key.equals("call"), r.members);
        }
        assertEquals(Permissions.LEADER, CrewRoleRules.level(Permissions.DEFAULTS, "edit"));
        assertEquals(Permissions.MEMBERS, CrewRoleRules.level(Permissions.DEFAULTS, "invite"));
    }

    @Test
    public void whoMayEditTheCrew() {
        Team defaults = team(Permissions.DEFAULTS, member(A, true, false), member(B, false, true), member(C, false, false));
        assertTrue(CrewRoleRules.mayEdit(defaults, A, false));
        assertFalse(CrewRoleRules.mayEdit(defaults, B, true));
        Permissions moderatorsEdit = new Permissions(Permissions.MEMBERS, Permissions.MEMBERS, Permissions.MODERATORS,
                Permissions.MODERATORS, Permissions.MODERATORS);
        Team open = team(moderatorsEdit, member(A, true, false), member(B, false, true), member(C, false, false));
        assertTrue(CrewRoleRules.mayEdit(open, B, true));
        // Without roles on the service, only the leader.
        assertFalse(CrewRoleRules.mayEdit(open, B, false));
        assertFalse(CrewRoleRules.mayEdit(open, C, true));
        assertFalse(CrewRoleRules.mayEdit(open, "100000000000000009", true));
    }

    @Test
    public void menusOfferModeratorsAndCategories() {
        SocialMenus.PersonInput in = new SocialMenus.PersonInput();
        in.friend = true;
        in.crewmate = true;
        in.leaderHere = true;
        in.removableHere = true;
        List<SocialMenus.PersonAction> before = actions(SocialMenus.person(in));
        assertFalse(before.contains(SocialMenus.PersonAction.MAKE_MODERATOR));
        assertFalse(before.contains(SocialMenus.PersonAction.MOVE_TO_GROUP));
        in.rolesHere = true;
        in.categories = true;
        List<SocialMenus.Row<SocialMenus.PersonAction>> rows = SocialMenus.person(in);
        List<SocialMenus.PersonAction> after = actions(rows);
        assertEquals(after.indexOf(SocialMenus.PersonAction.FAVOURITE) + 1, after.indexOf(SocialMenus.PersonAction.MOVE_TO_GROUP));
        assertEquals(after.indexOf(SocialMenus.PersonAction.HAND_OVER) - 1, after.indexOf(SocialMenus.PersonAction.MAKE_MODERATOR));
        in.moderatorHere = true;
        assertTrue(actions(SocialMenus.person(in)).contains(SocialMenus.PersonAction.UNMAKE_MODERATOR));
        in.moderatorHere = false;
        // CREW-MODS (0.5.4): no cap on moderators, "Moderatör Yap" is never dim.
        boolean offered = false;
        for (SocialMenus.Row<SocialMenus.PersonAction> r : SocialMenus.person(in)) {
            if (r != null && r.action == SocialMenus.PersonAction.MAKE_MODERATOR) {
                offered = true;
                assertFalse(r.disabled);
            }
        }
        assertTrue(offered);
        // Not the leader: no moderator rows.
        in.leaderHere = false;
        assertFalse(actions(SocialMenus.person(in)).contains(SocialMenus.PersonAction.MAKE_MODERATOR));
    }

    private static List<SocialMenus.PersonAction> actions(List<SocialMenus.Row<SocialMenus.PersonAction>> rows) {
        List<SocialMenus.PersonAction> out = new ArrayList<>();
        for (SocialMenus.Row<SocialMenus.PersonAction> r : rows) if (r != null) out.add(r.action);
        return out;
    }
}
