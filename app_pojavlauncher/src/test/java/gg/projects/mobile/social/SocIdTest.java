package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.social.SocialModel.Identity;
import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialModel.ModNotice;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.ui.AppDates;

/**
 * SOC-ID on the phone: ways into the friends service besides Discord (Microsoft), the name step of an account opened
 * with Microsoft, linked accounts and what an account without Discord does not offer. Answers in the shape of the
 * service's SOC-ID branch (src/identities.ts, src/model.ts, src/profile-name.ts; throwaway players), and an answer of
 * the deployed service from before (me-plain.json, me-limited.json).
 */
public class SocIdTest {
    private static final File DIR = new File("src/test/resources/social");
    private static final String BASE = "https://social.projects.gg/";

    private static JsonObject read(String name) throws Exception {
        JsonObject o = Json.parseObject(new String(Files.readAllBytes(new File(DIR, name).toPath()), StandardCharsets.UTF_8));
        assertNotNull(name, o);
        return o;
    }

    private static Profile card(SocialModel.FriendsData data, String id) {
        for (SocialModel.Friend f : data.friends) if (f.profile.id.equals(id)) return f.profile;
        throw new AssertionError("no friend " + id);
    }

    // ---- cards: discordId absent, null or a Discord id ----

    @Test
    public void cardsReadTheirDiscordByTheAbsentNullOrStringRule() throws Exception {
        SocialModel.FriendsData data = SocialModel.FriendsData.read(read("friends-socid.json"));
        assertEquals(4, data.friends.size());

        // Absent: an account opened with Discord, its number is its Discord id (every card before SOC-ID too).
        Profile discord = card(data, "100000000000000022");
        assertEquals("100000000000000022", discord.discordId);
        assertTrue(discord.hasDiscord());
        assertEquals("https://cdn.discordapp.com/avatars/100000000000000022/0123456789abcdef0123456789abcdef.png?size=64",
                discord.discordAvatarUrl(64));
        assertEquals("https://discord.com/users/100000000000000022", discord.discordProfileUrl());

        // null: no Discord (an account opened with Microsoft: 19 digits from 9, still a Java long).
        Profile microsoft = card(data, "9000000000000000123");
        assertNull(microsoft.discordId);
        assertFalse(microsoft.hasDiscord());
        assertNull(microsoft.discordAvatarUrl(64));
        assertNull("no \"Discord'dan mesaj at\"", microsoft.discordProfileUrl());
        assertEquals(9000000000000000123L, Long.parseLong(microsoft.id));

        // A string: that Discord account, never the number, for the picture and the profile.
        Profile linked = card(data, "9100000000000000456");
        assertEquals("100000000000000099", linked.discordId);
        assertEquals("https://cdn.discordapp.com/avatars/100000000000000099/fedcba9876543210fedcba9876543210.png?size=128",
                linked.discordAvatarUrl(100));
        assertEquals("https://discord.com/users/100000000000000099", linked.discordProfileUrl());

        // A Discord number whose Discord was removed: its old picture hash makes no Discord address any more.
        Profile removed = card(data, "100000000000000023");
        assertNull(removed.discordId);
        assertNull(removed.discordAvatarUrl(64));
        assertNull(removed.discordProfileUrl());

        // Requests and the blocked list are cards too; a malformed Discord id counts as none.
        assertNull(data.incoming.get(0).profile.discordId);
        assertNull(data.blocked.get(0).discordId);

        // A change of tag or picture keeps the Discord; the face key changes with it.
        assertEquals("100000000000000099", linked.withBadge(null).discordId);
        assertEquals("100000000000000099", linked.withAvatar("preset:fox", null).discordId);
        assertFalse(removed.faceKey().equals(new Profile(removed.id, removed.username, null, removed.avatar, removed.avatarHash).faceKey()));
    }

    @Test
    public void theAvatarMathUsesTheResolvedDiscordId() {
        String hash = "0123456789abcdef0123456789abcdef";
        Profile own = new Profile("100000000000000031", "a", null, "discord", hash);
        assertTrue(own.discordAvatarUrl(64).contains("/avatars/100000000000000031/"));
        Profile other = new Profile("9100000000000000031", "b", null, "discord", hash, null, "100000000000000032");
        assertTrue(other.discordAvatarUrl(64).contains("/avatars/100000000000000032/"));
        Profile none = new Profile("9100000000000000033", "c", null, "discord", hash, null, null);
        assertNull(none.discordAvatarUrl(64));
        // A preset or a Minecraft head never asks Discord, with or without one.
        assertNull(new Profile("100000000000000034", "d", null, "preset:owl", null).discordAvatarUrl(64));
    }

    // ---- me ----

    @Test
    public void anAccountOpenedWithMicrosoftReadsItsNewFields() throws Exception {
        Me me = Me.read(read("me-socid-microsoft.json"));
        assertNotNull(me);
        assertEquals("9123456789012345678", me.profile.id);
        assertNull(me.profile.discordId);
        assertFalse("7/24 Bulut is for the account's own Discord", me.discordNumber());
        assertTrue("the name step", me.nameUnset());
        assertEquals(Me.NAME_UNSET, me.nameSource);
        assertTrue(me.identitiesListed);
        assertEquals(1, me.identities.size());
        Identity microsoft = me.identities.get(0);
        assertEquals(SignInFlow.MICROSOFT, microsoft.provider);
        assertEquals("2026-11-01", microsoft.linkedOn);
        assertFalse(microsoft.isNew);
        assertNull(microsoft.hint);
        assertEquals(SignInFlow.MICROSOFT, me.signInVia);
        assertNull(me.identity(SignInFlow.DISCORD));
        assertNotNull(me.limited);
        assertTrue("Hesabın yeni olduğu için", me.limited.accountAge());
        assertEquals(SocialModel.Limited.REASON_ACCOUNT, me.limited.reason);
        // A copy with another status or without notices keeps them.
        Me copy = me.withStatus(SocialModel.Status.DND).withoutNotices(new java.util.HashSet<String>());
        assertTrue(copy.nameUnset());
        assertEquals(1, copy.identities.size());
        assertEquals(SignInFlow.MICROSOFT, copy.signInVia);
        assertNull(copy.profile.discordId);
    }

    @Test
    public void aLinkedAccountListsItsWaysInAndSkipsUnknownOnes() throws Exception {
        Me me = Me.read(read("me-socid-linked.json"));
        assertNotNull(me);
        assertTrue(me.discordNumber());
        assertFalse(me.nameUnset());
        assertEquals(Me.NAME_DISCORD, me.nameSource);
        List<String> providers = new ArrayList<>();
        for (Identity i : me.identities) providers.add(i.provider);
        assertEquals("a provider the app does not know is not listed", java.util.Arrays.asList("discord", "microsoft"), providers);
        assertEquals("blazerunner", me.identity(SignInFlow.DISCORD).hint);
        assertTrue(me.identity(SignInFlow.MICROSOFT).isNew);
        assertEquals("2026-10-30", me.identity(SignInFlow.MICROSOFT).linkedOn);
        assertEquals(SignInFlow.MICROSOFT, me.signInVia);
        assertNull(me.limited);
    }

    @Test
    public void noticesTheAppDoesNotKnowStayHidden() throws Exception {
        Me me = Me.read(read("me-socid-linked.json"));
        assertEquals(4, me.notices.size());
        Map<String, ModNotice> byId = new java.util.HashMap<>();
        for (ModNotice n : me.notices) byId.put(n.id, n);
        ModNotice linked = byId.get("1793400000000001");
        assertTrue(linked.identity());
        assertTrue(linked.shown());
        assertFalse(linked.moderation());
        assertEquals(SignInFlow.MICROSOFT, linked.provider);
        assertFalse("SKIN1's note is not shown as a warning any more", byId.get("1793400000000002").shown());
        assertTrue(byId.get("1793400000000003").moderation());
        ModNotice steam = byId.get("1793400000000004");
        assertNull(steam.provider);
        assertFalse("an unknown way in's note stays hidden", steam.shown());
        assertFalse(new ModNotice("x1", "crew_reset", 1, null).shown());
        assertTrue(new ModNotice("x2", ModNotice.PROFILE_RESET, 1, null).shown());
    }

    @Test
    public void anOlderServicesProfileIsItsDiscord() throws Exception {
        Me me = Me.read(read("me-plain.json"));
        assertNotNull(me);
        assertEquals(me.profile.id, me.profile.discordId);
        assertTrue(me.discordNumber());
        assertFalse(me.nameUnset());
        assertEquals(Me.NAME_DISCORD, me.nameSource);
        assertFalse("no linking or removing on that service", me.identitiesListed);
        assertEquals(1, me.identities.size());
        assertEquals(SignInFlow.DISCORD, me.identities.get(0).provider);
        assertEquals("ayse", me.identities.get(0).hint);
        assertEquals("2026-10-21", me.identities.get(0).linkedOn);
        assertNull(me.signInVia);
        Me limited = Me.read(read("me-limited.json"));
        assertNotNull(limited.limited);
        assertFalse("Discord hesabın yeni olduğu için", limited.limited.accountAge());
        assertEquals(SocialModel.Limited.REASON_DISCORD, limited.limited.reason);
        // The constructors the screens and older tests use keep the same defaults.
        Me made = new Me(new Profile("100000000000000041", "x", null, "discord", null), "PRJ-AAAA-BBBB", "online", false, true, null);
        assertTrue(made.discordNumber());
        assertFalse(made.identitiesListed);
    }

    @Test
    public void waysInAreListedDiscordFirstAndOnce() {
        JsonObject o = Json.parseObject("{\"id\":\"9100000000000000051\",\"username\":\"Oyuncu1111\",\"discordId\":\"100000000000000052\","
                + "\"identities\":[{\"provider\":\"microsoft\",\"linkedOn\":\"2026-11-01\",\"isNew\":false,\"hint\":null},"
                + "{\"provider\":\"discord\",\"linkedOn\":\"2026-11-01\",\"isNew\":true,\"hint\":\"  skyward  \"},"
                + "{\"provider\":\"microsoft\",\"linkedOn\":\"bad\",\"isNew\":true}],\"signInVia\":\"steam\",\"nameSource\":\"later\"}");
        Me me = Me.read(o);
        assertEquals(2, me.identities.size());
        assertEquals(SignInFlow.DISCORD, me.identities.get(0).provider);
        assertEquals("skyward", me.identities.get(0).hint);
        assertEquals("2026-11-01", me.identities.get(1).linkedOn);
        assertNull("an unknown way in is no device's", me.signInVia);
        assertEquals("an unknown source asks for nothing", Me.NAME_DISCORD, me.nameSource);
        assertFalse("its Discord is another id than its number", me.discordNumber());
        assertTrue(me.profile.hasDiscord());
    }

    // ---- the ways in the service offers ----

    @Test
    public void theProvidersAnswerSaysWhichWaysOpen() {
        SignInOptions off = SignInOptions.read(Json.parseObject(
                "{\"providers\":[{\"id\":\"discord\",\"signIn\":true,\"signUp\":true},{\"id\":\"microsoft\",\"signIn\":false,\"signUp\":true}]}"));
        assertEquals(SignInOptions.DISCORD_ONLY, off);
        assertFalse("a new account only through a sign-in", off.microsoftSignUp);
        assertTrue(off.offers(SignInFlow.DISCORD));
        assertFalse(off.offers(SignInFlow.MICROSOFT));

        SignInOptions signIn = SignInOptions.read(Json.parseObject(
                "{\"providers\":[{\"id\":\"discord\",\"signIn\":true,\"signUp\":true},{\"id\":\"microsoft\",\"signIn\":true,\"signUp\":false},"
                        + "{\"id\":\"steam\",\"signIn\":true,\"signUp\":true}]}"));
        assertTrue(signIn.microsoftSignIn);
        assertFalse("Discord leads, Microsoft under \"Microsoft hesabını bağladıysan\"", signIn.equalWays());
        assertTrue(signIn.offers(SignInFlow.MICROSOFT));
        assertFalse(signIn.offers("steam"));

        SignInOptions both = SignInOptions.read(Json.parseObject(
                "{\"providers\":[{\"id\":\"microsoft\",\"signIn\":true,\"signUp\":true}]}"));
        assertTrue(both.equalWays());
        assertTrue("Discord is always there", both.offers(SignInFlow.DISCORD));

        assertEquals(SignInOptions.DISCORD_ONLY, SignInOptions.read(null));
        assertEquals(SignInOptions.DISCORD_ONLY, SignInOptions.read(Json.parseObject("{\"providers\":\"no\"}")));
        assertEquals(both, SignInOptions.decode(both.encode()));
        assertEquals(signIn, SignInOptions.decode(signIn.encode()));
        assertEquals(SignInOptions.DISCORD_ONLY, SignInOptions.decode(null));
        assertEquals(SignInOptions.DISCORD_ONLY, SignInOptions.decode("1"));
    }

    // ---- the sign-in, links and removals ----

    @Test
    public void startPagesNameTheirWayIn() {
        String challenge = SignInFlow.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");
        assertEquals("https://social.projects.gg/auth/microsoft/start?challenge=" + challenge,
                SignInFlow.startUrl("https://social.projects.gg", SignInFlow.MICROSOFT, challenge));
        assertEquals("https://social.projects.gg/auth/discord/start?challenge=" + challenge,
                SignInFlow.startUrl(BASE, "steam", challenge));
        SignInFlow flow = new SignInFlow();
        SignInFlow.Attempt attempt = flow.begin(new SecureRandom(), 1000, SignInFlow.MICROSOFT);
        assertEquals(43, attempt.verifier.length());
        assertEquals(43, attempt.challenge.length());
        assertEquals(SignInFlow.MICROSOFT, attempt.provider);
        assertEquals(SignInFlow.Purpose.SIGN_IN, attempt.purpose);
        assertEquals(SignInFlow.CLAIM_SIGN_IN, attempt.claimPath());
        assertNull(flow.identityWaiting(1001));
    }

    @Test
    public void aLinkWaitingTakesTheDeepLinkToTheIdentityClaim() {
        String code = "Qm9fT2sXyZ-0123456789_abcdefghijklmnopqrstu";
        SignInFlow flow = new SignInFlow();
        SignInFlow.Attempt made = SignInFlow.make(new SecureRandom(), 2000, SignInFlow.MICROSOFT, SignInFlow.Purpose.LINK);
        assertNull("nothing waits until the page is known", flow.onDeepLink(code, 2001));
        flow.waitFor(made.withUrl(BASE + "auth/link?t=abc"));
        assertNotNull(flow.identityWaiting(2002));
        SignInFlow.Attempt claimed = flow.onDeepLink(code, 2003);
        assertNotNull(claimed);
        assertTrue(claimed.forIdentity());
        assertEquals(SignInFlow.CLAIM_IDENTITY, claimed.claimPath());
        assertEquals(made.verifier, claimed.verifier);
        assertNull("one claim per attempt", flow.onDeepLink(code, 2004));

        // A removal goes to the same claim; a sign-in to its own.
        SignInFlow.Attempt removal = SignInFlow.make(new SecureRandom(), 3000, SignInFlow.DISCORD, SignInFlow.Purpose.UNLINK);
        assertEquals(SignInFlow.CLAIM_IDENTITY, removal.claimPath());
        flow.reset();
        SignInFlow.Attempt signIn = flow.begin(new SecureRandom(), 3000);
        assertEquals(SignInFlow.CLAIM_SIGN_IN, flow.onDeepLink(code, 3001).claimPath());
        assertEquals(SignInFlow.DISCORD, signIn.provider);

        // Kept across Android ending the app: a link comes back with its page and way in; one without a page never.
        flow.reset();
        SignInFlow.Attempt kept = new SignInFlow.Attempt(made.verifier, made.challenge, 4000, "microsoft",
                SignInFlow.Purpose.of("link"), BASE + "auth/link?t=abc");
        flow.restore(kept, 4100);
        assertEquals(SignInFlow.Phase.WAITING, flow.phase());
        assertEquals(SignInFlow.Purpose.LINK, flow.attempt().purpose);
        flow.reset();
        flow.restore(new SignInFlow.Attempt(made.verifier, made.challenge, 4000, "microsoft", SignInFlow.Purpose.UNLINK, null), 4100);
        assertEquals(SignInFlow.Phase.IDLE, flow.phase());
        // What the session file writes reads back the same.
        assertEquals(SignInFlow.Purpose.SIGN_IN, SignInFlow.Purpose.of(SignInFlow.Purpose.SIGN_IN.key()));
        assertEquals(SignInFlow.Purpose.LINK, SignInFlow.Purpose.of(SignInFlow.Purpose.LINK.key()));
        assertEquals(SignInFlow.Purpose.UNLINK, SignInFlow.Purpose.of(SignInFlow.Purpose.UNLINK.key()));
        assertEquals(SignInFlow.Purpose.SIGN_IN, SignInFlow.Purpose.of(null));
        assertEquals("a file from before is Discord's", SignInFlow.DISCORD, new SignInFlow.Attempt("v", "c", 1, null, null, null).provider);
    }

    @Test
    public void onlyTheServicesOwnPagesOpen() {
        assertEquals(BASE + "auth/link?t=s1.abc", SignInFlow.pageUrl(BASE, BASE + "auth/link?t=s1.abc"));
        assertNull(SignInFlow.pageUrl(BASE, "https://evil.example/auth/link?t=x"));
        assertNull(SignInFlow.pageUrl(BASE, "http://social.projects.gg/auth/link?t=x"));
        assertNull(SignInFlow.pageUrl(BASE, "https://social.projects.gg:8443/auth/link"));
        assertNull(SignInFlow.pageUrl(BASE, "https://user@social.projects.gg/auth/link"));
        assertNull(SignInFlow.pageUrl(BASE, "javascript:alert(1)"));
        assertNull(SignInFlow.pageUrl(BASE, "intent://social.projects.gg/#Intent;end"));
        assertNull(SignInFlow.pageUrl(BASE, null));
        assertNull(SignInFlow.pageUrl(BASE, "not a url"));
        // A debug build's local service.
        assertEquals("http://10.0.2.2:8787/auth/link?t=x", SignInFlow.pageUrl("http://10.0.2.2:8787/", "http://10.0.2.2:8787/auth/link?t=x"));
    }

    // ---- the name step ----

    @Test
    public void profileNamesFollowTheServicesRules() {
        assertEquals(ProfileName.Problem.NONE, ProfileName.check("BlazeRunner", true));
        assertEquals(ProfileName.Problem.NONE, ProfileName.check("Gece Kuşu", true));
        assertEquals(ProfileName.Problem.NONE, ProfileName.check("Çağrı_07", true));
        assertEquals(ProfileName.Problem.NONE, ProfileName.check("ab_", true));
        assertEquals("16 is the most", ProfileName.Problem.NONE, ProfileName.check("abcdefghijklmnop", true));
        assertEquals(ProfileName.Problem.TOO_LONG, ProfileName.check("abcdefghijklmnopq", false));
        assertEquals("long at once", ProfileName.Problem.TOO_LONG, ProfileName.check("abcdefghijklmnopq", true));
        assertEquals(ProfileName.Problem.TOO_SHORT, ProfileName.check("a_", true));
        assertEquals("short only on submit", ProfileName.Problem.NONE, ProfileName.check("a_", false));
        assertEquals(ProfileName.Problem.EMPTY, ProfileName.check("   ", true));
        assertEquals(ProfileName.Problem.NONE, ProfileName.check("", false));
        assertEquals(ProfileName.Problem.LETTERS, ProfileName.check("___", true));
        assertEquals(ProfileName.Problem.LETTERS, ProfileName.check("a__", true));
        // Other alphabets' look-alikes, marks and emoji at once.
        String cyrillic = "" + (char) 0x041D + (char) 0x0438 + (char) 0x043A;
        assertEquals(ProfileName.Problem.CHARACTERS, ProfileName.check(cyrillic + "ita", false));
        assertEquals(ProfileName.Problem.CHARACTERS, ProfileName.check("Ali-Veli", false));
        assertEquals(ProfileName.Problem.CHARACTERS, ProfileName.check("Yıldız" + new String(Character.toChars(0x1F31F)), false));
        // Spaces as the service keeps them: every kind one space, trimmed.
        assertEquals("Ali Veli", ProfileName.clean("  Ali   Veli "));
        assertEquals("Ali Veli", ProfileName.clean("Ali" + (char) 0x00A0 + "Veli"));
        assertEquals(ProfileName.Problem.NONE, ProfileName.check("Ali" + (char) 0x00A0 + (char) 0x00A0 + "Veli", true));
        assertTrue(ProfileName.valid(" Steve "));
        // The field's first value: the Minecraft name when it passes.
        assertEquals("Steve_07", ProfileName.prefill("Steve_07"));
        assertEquals("", ProfileName.prefill("Demo.Player"));
        assertEquals("", ProfileName.prefill("__x"));
        assertEquals("", ProfileName.prefill(null));
    }

    // ---- what an account without Discord does not offer ----

    @Test
    public void peopleWithoutDiscordOfferNoDiscordMessage() {
        SocialMenus.PersonInput friend = new SocialMenus.PersonInput();
        friend.friend = true;
        assertTrue(friend.discord);
        assertTrue(actions(SocialMenus.person(friend)).contains(SocialMenus.PersonAction.DISCORD));
        friend.discord = false;
        List<SocialMenus.PersonAction> without = actions(SocialMenus.person(friend));
        assertFalse(without.contains(SocialMenus.PersonAction.DISCORD));
        assertTrue(without.contains(SocialMenus.PersonAction.FAVOURITE));
        assertTrue(without.contains(SocialMenus.PersonAction.REMOVE_FRIEND));

        SocialMenus.PersonInput crewmate = new SocialMenus.PersonInput();
        crewmate.crewmate = true;
        crewmate.discord = false;
        assertFalse(actions(SocialMenus.person(crewmate)).contains(SocialMenus.PersonAction.DISCORD));

        SocialMenus.PersonInput blocked = new SocialMenus.PersonInput();
        blocked.friend = true;
        blocked.blocked = true;
        blocked.discord = false;
        assertEquals(java.util.Collections.singletonList(SocialMenus.PersonAction.UNBLOCK), actions(SocialMenus.person(blocked)));
    }

    private static List<SocialMenus.PersonAction> actions(List<SocialMenus.Row<SocialMenus.PersonAction>> rows) {
        List<SocialMenus.PersonAction> out = new ArrayList<>();
        for (SocialMenus.Row<SocialMenus.PersonAction> r : rows) if (r != null) out.add(r.action);
        return out;
    }

    // ---- the service's new refusals ----

    @Test
    public void theNewRefusalsHaveTheirOwnKinds() throws Exception {
        JsonObject errors = read("errors-socid.json");
        Map<String, SocialProblem.Kind> expected = new java.util.LinkedHashMap<>();
        expected.put("identity_not_linked", SocialProblem.Kind.IDENTITY_NOT_LINKED);
        expected.put("provider_off", SocialProblem.Kind.PROVIDER_OFF);
        expected.put("identity_exists", SocialProblem.Kind.IDENTITY_EXISTS);
        expected.put("identity_not_found", SocialProblem.Kind.IDENTITY_NOT_FOUND);
        expected.put("last_identity", SocialProblem.Kind.LAST_IDENTITY);
        expected.put("identity_taken", SocialProblem.Kind.IDENTITY_TAKEN);
        expected.put("proof_failed", SocialProblem.Kind.PROOF_FAILED);
        expected.put("identity_unavailable", SocialProblem.Kind.IDENTITY_UNAVAILABLE);
        expected.put("auth_pending_not_found", SocialProblem.Kind.AUTH_PENDING_GONE);
        expected.put("invalid_name", SocialProblem.Kind.NAME_INVALID);
        expected.put("name_rejected", SocialProblem.Kind.NAME_REJECTED);
        expected.put("name_changes", SocialProblem.Kind.RATE_LIMITED);
        expected.put("identity_changes", SocialProblem.Kind.RATE_LIMITED);
        expected.put("discord_required", SocialProblem.Kind.DISCORD_REQUIRED);
        assertEquals(expected.size(), errors.size());
        for (Map.Entry<String, SocialProblem.Kind> e : expected.entrySet()) {
            JsonObject answer = Json.obj(errors, e.getKey());
            SocialProblem problem = SocialProblem.of((int) Json.num(answer, "status", 0), Json.obj(answer, "body").toString(), null, false);
            assertEquals(e.getKey(), e.getValue(), problem.kind);
            assertFalse(problem.restricted());
        }
        assertEquals("name_changes", SocialProblem.of(429, Json.obj(Json.obj(errors, "name_changes"), "body").toString(), null, false).item);
        SocialProblem changes = SocialProblem.of(429, Json.obj(Json.obj(errors, "identity_changes"), "body").toString(), null, false);
        assertEquals("identity_changes", changes.item);
        assertEquals(7200, changes.retryAfter);
    }

    // ---- "25 Ekim'de bağlandı" ----

    @Test
    public void linkedDaysTakeTheirTurkishEndings() {
        long now = AppDates.isoDay("2026-11-01") + 12 * 3_600_000L;
        assertEquals("25 Ekim'de", IdentityDays.linkedDay(true, "2026-10-25", now));
        assertEquals("3 Kasım'da", IdentityDays.linkedDay(true, "2026-11-03", now));
        assertEquals("1 Aralık'ta", IdentityDays.linkedDay(true, "2026-12-01", now));
        assertEquals("14 Mart'ta", IdentityDays.linkedDay(true, "2026-03-14", now));
        assertEquals("9 Eylül'de", IdentityDays.linkedDay(true, "2026-09-09", now));
        assertEquals("another year after the year as it is read", "3 Ocak 2025'te", IdentityDays.linkedDay(true, "2025-01-03", now));
        assertEquals("25 October", IdentityDays.linkedDay(false, "2026-10-25", now));
        assertEquals("3 January 2025", IdentityDays.linkedDay(false, "2025-01-03", now));
        assertNull(IdentityDays.linkedDay(true, null, now));
        assertNull(IdentityDays.linkedDay(true, "25.10.2026", now));
    }
}
