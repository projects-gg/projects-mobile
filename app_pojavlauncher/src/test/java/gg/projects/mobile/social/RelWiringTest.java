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
        assertEquals("change() forgets when it sends and when it applies", 2, count(body(code, "private <T> void change("), "forgetRel();"));
        // A report with "Engelle" blocks: it is the player's own change too.
        has("report", body(code, "public void report("), "if (blocks) change(");
    }

    /** Spec 5 and 6: the version and the copy kept on the phone go wherever the copy stops being the service's answer. */
    @Test
    public void theVersionAndTheCopyGoWheneverTheyStopDescribingTheLists() throws IOException {
        String code = code(source("SocialService.java"));
        String forget = body(code, "private void forgetRel() {");
        has("forgetRel", forget, "mRel.forget();");
        has("forgetRel", forget, "mKept.clear();");
        for (String way : Arrays.asList(
                "private void forgetSession() {", // sign-out, the session's end, a restriction, "Verilerimi sil"
                "private void signedIn(JsonObject answer) {", // a new sign-in
                "private void refreshMeOnly() {", // the profile read outside the list
                "private void refreshTeam() {", // the crews read outside the list (a service without many crews)
                "public void forgetJoinRequest(String teamId, String userId) {", // a join request that went elsewhere
                "private void identityDone(")) { // a link's or a removal's profile
            has(way, body(code, way), "forgetRel();");
        }
        // Moderators' notes seen here: when they go and again when the service has them (a read in between keeps none).
        assertEquals("noticesSeen forgets twice", 2, count(body(code, "public void noticesSeen(final Set<String> ids) {"), "forgetRel();"));
        // Spec 7: a push forgets before it reads.
        // Spec 7 and the lead's decision (1 November): only a push that changes the lists forgets, before it reads.
        String push = body(code, "public void pushArrived(String type) {");
        has("pushArrived", push, "if (RelVersion.pushChangesLists(type)) forgetRel();");
        assertEquals("pushArrived forgets only through pushChangesLists", 1, count(push, "forgetRel();"));
        assertTrue("pushArrived forgets before it reads", push.indexOf("forgetRel();") < push.indexOf("refresh(false)"));
        // Every socket frame that changes the lists or the profile.
        String frames = body(code, "public void onMessage(PresenceConnection connection, JsonObject message) {");
        has("a frame that changes the lists", block(frames, "if (RelVersion.changesRelations(type)) {"), "forgetRel();");
        // Spec 6: a hello naming another version takes the copy kept for a start at once, a read on its way or not.
        String changed = block(frames, "if (hello == RelVersion.Hello.CHANGED) {");
        has("a hello with another version", changed, "if (mFetching) mListChanges++;");
        has("a hello with another version", changed, "forgetRel();");
        // "Verilerimi sil" takes the copy also when the socket's 4002 ended the session first.
        has("deleteAccount", body(code, "public void deleteAccount("), "mKept.clear();");
        // A start without a session keeps nothing of an account; with one, it loads the copy of its account.
        String start = body(code, "private SocialService(Context app) {");
        has("the service's start", start, "mKept.clear();");
        has("the service's start", start, "loadKept(saved.userId);");
    }

    /** Spec 1-4 and 7: the read, the connect and the hello use the version. */
    @Test
    public void theReadTheConnectAndTheHelloUseTheVersion() throws IOException {
        String raw = source("SocialService.java");
        String code = code(raw);
        // Spec 1.
        assertEquals("v1/friends?crews=lite&rel=1", KeptLists.FROM);
        has("SocialApi.friends", body(code(source("SocialApi.java")), "public JsonObject friends()"), "KeptLists.FROM");
        // Spec 2: one request when the answer brings the profile; GET /v1/me only without it. The version is kept only
        // when nothing changed since the read started.
        String read = body(code, "private void refresh(boolean force, boolean anyVersion) {");
        has("refresh", read, "KeptLists.Answer.of(friends)");
        has("refresh", read, "brought.me != null ? brought.me : mApi.me()");
        assertEquals("refresh reads GET /v1/me only in the fallback", 1, count(read, "mApi.me()"));
        has("refresh", read, "mRel.readStarts()");
        has("refresh", read, "mRel.keep(");
        // A crew shown from an older copy (its detail not read now) is not the answer: no version for it.
        has("refresh", read, "new CrewDetails.Noted(mApi::teamDetail)");
        has("refresh", read, "!crews.missed()");
        // Spec 7: an unforced read waits for the hello of the connection up or on its way.
        has("refresh", read, "mRel.refreshSkips(force, mState.loaded(), mConnection != null)");
        // Spec 3: the connect names the version held.
        has("ensureConnected", body(code, "private void ensureConnected() {"), "PresenceConnection.open(mApi, mToken, caps(), mRel.held(), this)");
        has("PresenceConnection", code(source("PresenceConnection.java")), "RelVersion.presencePath(caps, rel)");
        // Spec 4: the hello's version decides (the call in code, its "rel" as written).
        int[] frames = bodySpan(code, "public void onMessage(PresenceConnection connection, JsonObject message) {");
        int hello = code.indexOf("mRel.hello(Json.str(message, ", frames[0]);
        assertTrue("onMessage no longer asks mRel.hello", hello > 0 && hello < frames[1]);
        assertTrue("the hello's own rel decides", raw.startsWith("mRel.hello(Json.str(message, \"rel\"), mState.loaded())", hello));
        // A connection that ended before its hello reads the lists once in a run, not at every failed reconnect.
        has("onEnded", body(code, "public void onEnded(PresenceConnection connection, int code, boolean failed) {"),
                "if (!saidHello && mLastTry == 0) refresh(false, true);");
        // Spec 6: a start shows the copy only while nothing replaced it meanwhile.
        has("keptLoaded", body(code, "private void keptLoaded("), "!mState.loaded() && mRel.keep(kept.rel, since)");
    }

    /** Spec 6, also with the app not running: a friend request's push takes the copy kept for a start. */
    @Test
    public void aRequestsPushTakesTheCopyAlsoWithoutTheApp() throws IOException {
        String push = code(source("SocialPush.java"));
        // The running app's pushArrived, else (no service in this process) the request's own clear.
        int open = push.indexOf('{', push.indexOf("if (SocialService.exists()) {"));
        assertTrue("SocialPush no longer asks whether the service runs", open > 0);
        has("SocialPush", push.substring(open, close(push, open)), "SocialService.get(app).pushArrived(message.type);");
        String otherwise = push.substring(close(push, open) + 1);
        assertTrue("without the service, a push that changes the lists clears the copy",
                otherwise.trim().startsWith("else if (RelVersion.pushChangesLists(message.type)) {"));
        has("SocialPush", block(otherwise, "else if (RelVersion.pushChangesLists(message.type)) {"),
                "new KeptLists(KeptLists.folder(app.getNoBackupFilesDir(), debug.override)).clear();");
        // The same folder as the service's.
        has("the service's start", code(source("SocialService.java")), "new KeptLists(KeptLists.folder(app.getNoBackupFilesDir(), mDebug.override))");
    }

    /** A short failure: where, and what is missing (never the whole method). */
    private static void has(String where, String text, String part) {
        assertTrue(where + " no longer has: " + part, text.contains(part));
    }

    /** The block ({@code {…}}) that {@code opening} (ending with its brace) starts in {@code code}. */
    private static String block(String code, String opening) {
        int at = code.indexOf(opening);
        assertTrue("no longer there: " + opening, at >= 0);
        int open = at + opening.length() - 1;
        return code.substring(open, close(code, open) + 1);
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
