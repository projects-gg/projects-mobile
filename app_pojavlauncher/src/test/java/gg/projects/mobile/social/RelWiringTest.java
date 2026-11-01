package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SOC-REL-M: the relations version's rules where they meet the Android service (scale-soc.md, the phone's spec 1-7).
 * JVM tests cannot start SocialService; the decisions themselves are tested in RelVersionTest and KeptListsTest. This
 * guards, from the sources, that the service still calls them where the spec says, so a merge that adds an endpoint, a
 * way out of the session or another read cannot skip them unnoticed (AccountGatesTest's way).
 */
public class RelWiringTest {
    /** SocialApi's calls that change what {@code GET /v1/friends} shows: the service sends them only through change(). */
    private static final Set<String> CHANGES = new TreeSet<>(Arrays.asList(
            "accept", "acceptJoinRequest", "acceptTeamInvite", "block", "cancelRequest", "createInviteLink", "createTeam",
            "crewNotifications", "crewOrder", "decline", "declineJoinRequest", "declineTeamInvite", "deleteInviteLink",
            "disbandTeam", "handOver", "inviteToTeam", "joinTeam", "leaveTeam", "patchMe", "patchTeam", "removeFriend",
            "removeMember", "request", "requestCrewmate", "requestFromBadge", "rotateFriendCode", "teamNotifications",
            "unblock", "withdrawJoinRequest"));
    /**
     * The others: reads, the session's own calls, and the few with a way of their own, checked below (moderators' notes
     * seen, a report that blocks, a link's or a removal's answer, the crews' and the profile's reads outside the list).
     */
    private static final Set<String> OTHERS = new TreeSet<>(Arrays.asList(
            "claim", "claimIdentity", "deleteAccount", "deletePushDevice", "friends", "health", "linkIdentity", "logout",
            "lookup", "lookupTeam", "lookupTeamByLink", "markCatalog", "me", "noticesSeen", "providers", "putPushDevice",
            "report", "teamDetail", "teams", "unlinkIdentity"));

    @Test
    public void everyCallOfTheApiSaysWhetherItChangesTheLists() throws IOException {
        Set<String> api = new TreeSet<>();
        Matcher m = Pattern.compile("public\\s+[\\w<>\\[\\]., ]+?\\s+(\\w+)\\([^)]*\\)\\s*throws\\s+SocialProblem")
                .matcher(code(source("SocialApi.java")));
        while (m.find()) api.add(m.group(1));
        Set<String> unsorted = new TreeSet<>(api);
        unsorted.removeAll(CHANGES);
        unsorted.removeAll(OTHERS);
        // A new call goes in one of the two: does it change the profile or the lists a start keeps (spec 5)?
        assertTrue("SocialApi calls in neither CHANGES nor OTHERS: " + unsorted, unsorted.isEmpty());
        Set<String> gone = new TreeSet<>(CHANGES);
        gone.addAll(OTHERS);
        gone.removeAll(api);
        assertTrue("no longer in SocialApi: " + gone, gone.isEmpty());
    }

    /** Spec 5: the player's own change forgets the version (and the copy on the phone) when it goes and when it lands. */
    @Test
    public void everyChangeOfThePlayersOwnGoesThroughChange() throws IOException {
        String code = code(source("SocialService.java"));
        List<int[]> spans = new ArrayList<>();
        Matcher calls = Pattern.compile("(?<![\\w.$])change\\(").matcher(code);
        while (calls.find()) spans.add(new int[] {calls.end() - 1, close(code, calls.end() - 1)});
        List<String> outside = new ArrayList<>();
        int seen = 0;
        Matcher api = Pattern.compile("mApi(?:\\.|::)(\\w+)").matcher(code);
        while (api.find()) {
            if (!CHANGES.contains(api.group(1))) continue;
            seen++;
            boolean inside = false;
            for (int[] span : spans) inside |= api.start() > span[0] && api.start() < span[1];
            if (!inside) outside.add(api.group(1) + " (line " + line(code, api.start()) + ")");
        }
        assertTrue("changes sent without change(): " + outside, outside.isEmpty());
        assertTrue("found " + seen, seen >= CHANGES.size());
        assertEquals(2, count(body(code, "private <T> void change("), "forgetRel();"));
        // A report with "Engelle" blocks: it is the player's own change too.
        String report = body(code, "public void report(");
        assertTrue(report, Pattern.compile("if \\(blocks\\) change\\(").matcher(report).find());
    }

    /** Spec 5 and 6: the version and the copy kept on the phone go wherever the copy stops being the service's answer. */
    @Test
    public void theVersionAndTheCopyGoWheneverTheyStopDescribingTheLists() throws IOException {
        String code = code(source("SocialService.java"));
        String forget = body(code, "private void forgetRel() {");
        assertTrue(forget, forget.contains("mRel.forget();") && forget.contains("mKept.clear();"));
        for (String way : Arrays.asList(
                "private void forgetSession() {", // sign-out, the session's end, a restriction, "Verilerimi sil"
                "private void signedIn(JsonObject answer) {", // a new sign-in
                "public void noticesSeen(final Set<String> ids) {", // moderators' notes seen here
                "private void refreshMeOnly() {", // the profile read outside the list
                "private void identityDone(")) { // a link's or a removal's profile
            assertTrue(way, body(code, way).contains("forgetRel();"));
        }
        // Spec 7: a push forgets before it reads.
        String push = body(code, "public void pushArrived() {");
        assertTrue(push, push.contains("forgetRel();") && push.indexOf("forgetRel();") < push.indexOf("refresh(false)"));
        // Every socket frame that changes the lists or the profile.
        String frames = body(code, "public void onMessage(PresenceConnection connection, JsonObject message) {");
        int check = frames.indexOf("RelVersion.changesRelations(type)");
        assertTrue(frames, check > 0 && frames.indexOf("forgetRel();", check) > check);
        // "Verilerimi sil" takes the copy also when the socket's 4002 ended the session first.
        assertTrue(body(code, "public void deleteAccount(").contains("mKept.clear();"));
        // A start without a session keeps nothing of an account; with one, it loads the copy of its account.
        String start = body(code, "private SocialService(Context app) {");
        assertTrue(start, start.contains("mKept.clear();") && start.contains("loadKept(saved.userId);"));
    }

    /** Spec 1-4 and 7: the read, the connect and the hello use the version. */
    @Test
    public void theReadTheConnectAndTheHelloUseTheVersion() throws IOException {
        String raw = source("SocialService.java");
        String code = code(raw);
        // Spec 1.
        assertEquals("v1/friends?crews=lite&rel=1", KeptLists.FROM);
        assertTrue(body(code(source("SocialApi.java")), "public JsonObject friends()").contains("KeptLists.FROM"));
        // Spec 2: one request when the answer brings the profile; GET /v1/me only without it. The version is kept only
        // when nothing changed since the read started.
        String read = body(code, "private void refresh(boolean force, boolean anyVersion) {");
        assertTrue(read, read.contains("KeptLists.Answer.of(friends)"));
        assertTrue(read, read.contains("brought.me != null ? brought.me : mApi.me()"));
        assertEquals(1, count(read, "mApi.me()"));
        assertTrue(read, read.contains("mRel.readStarts()") && read.contains("mRel.keep("));
        // Spec 7: an unforced read waits for the hello of the connection up or on its way.
        assertTrue(read, read.contains("mRel.refreshSkips(force, mState.loaded(), mConnection != null)"));
        // Spec 3: the connect names the version held.
        assertTrue(body(code, "private void ensureConnected() {").contains("PresenceConnection.open(mApi, mToken, caps(), mRel.held(), this)"));
        assertTrue(code(source("PresenceConnection.java")).contains("RelVersion.presencePath(caps, rel)"));
        // Spec 4: the hello's version decides.
        String frames = rawBody(raw, code, "public void onMessage(PresenceConnection connection, JsonObject message) {");
        assertTrue(frames, frames.contains("mRel.hello(Json.str(message, \"rel\"), mState.loaded())"));
        // Spec 6: a start shows the copy only while nothing replaced it meanwhile.
        String kept = body(code, "private void keptLoaded(");
        assertTrue(kept, kept.contains("!mState.loaded() && mRel.keep(kept.rel, since)"));
    }

    /** Spec 6, also with the app not running: a friend request's push takes the copy kept for a start. */
    @Test
    public void aRequestsPushTakesTheCopyAlsoWithoutTheApp() throws IOException {
        String push = source("SocialPush.java");
        assertTrue(push.contains("SocialService.get(app).pushArrived();"));
        assertTrue(push.contains("else if (PushMessage.REQUEST.equals(message.type))"));
        assertTrue(push.contains("new KeptLists(KeptLists.folder(app.getNoBackupFilesDir(), debug.override)).clear();"));
        // The same folder as the service's.
        assertTrue(source("SocialService.java").contains("new KeptLists(KeptLists.folder(app.getNoBackupFilesDir(), mDebug.override))"));
    }

    // ---- reading the sources ----

    private static String source(String name) throws IOException {
        for (String root : new String[] {"src/main/java/gg/projects/mobile/social/", "app_pojavlauncher/src/main/java/gg/projects/mobile/social/"}) {
            File f = new File(root + name);
            if (f.isFile()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
        throw new AssertionError("run from the app module or the repository: " + new File(".").getAbsolutePath());
    }

    /** The text with comments and the insides of strings and chars blanked (same length and lines): code only. */
    static String code(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '/') {
                while (i < n && text.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                while (i < end) {
                    out.append(text.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
            } else if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < n && text.charAt(i) != c && text.charAt(i) != '\n') {
                    if (text.charAt(i) == '\\' && i + 1 < n) {
                        out.append(' ');
                        i++;
                    }
                    out.append(' ');
                    i++;
                }
                if (i < n) {
                    out.append(text.charAt(i));
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The bracket that closes the one at {@code open} in {@code code}. */
    private static int close(String code, int open) {
        char o = code.charAt(open);
        char c = o == '(' ? ')' : '}';
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch == o) {
                depth++;
            } else if (ch == c && --depth == 0) {
                return i;
            }
        }
        throw new AssertionError("no close for line " + line(code, open));
    }

    private static int[] bodySpan(String code, String signature) {
        int at = code.indexOf(signature);
        assertTrue("not in the source: " + signature, at >= 0);
        assertEquals("twice in the source: " + signature, -1, code.indexOf(signature, at + 1));
        int open = code.indexOf('{', at + signature.length() - 1);
        return new int[] {open, close(code, open) + 1};
    }

    /** The body of the one method that starts with {@code signature}, as code (comments and strings blanked). */
    private static String body(String code, String signature) {
        int[] span = bodySpan(code, signature);
        return code.substring(span[0], span[1]);
    }

    /** The same body as written (for what sits in a string). */
    private static String rawBody(String raw, String code, String signature) {
        int[] span = bodySpan(code, signature);
        return raw.substring(span[0], span[1]);
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) n++;
        return n;
    }

    private static int line(String code, int index) {
        int n = 1;
        for (int i = 0; i < index && i < code.length(); i++) if (code.charAt(i) == '\n') n++;
        return n;
    }
}
