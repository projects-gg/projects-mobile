package gg.projects.mobile.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * APK-UPD: the Android channel file (docs/content-format.md, "Android release channel"): the real signer's
 * fixture, every field rule, the notes as the card shows them, and the trusted comment's binding.
 */
public class UpdateManifestTest {
    private static final String SHA = "5f2b0c1d9e8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c";
    private static final String CERT = "0aab804584dfccff406c80f982d05b5ffec537a89132e2fb4fd3fedde8108ba3";

    /** A valid channel file with {@code replace} pairs applied to its text. */
    private static String doc(String... replace) {
        String json = "{\"schema\":1,\"channel\":\"stable\",\"applicationId\":\"gg.projects.mobile\",\"versionName\":\"0.4.6\","
                + "\"versionCode\":22,\"minSdk\":21,\"pubDate\":\"2026-11-02T12:00:00Z\",\"apk\":{\"urls\":"
                + "[\"https://cdn.projects.gg/v1/android/0.4.6/projects-launcher-0.4.6.apk\"],\"size\":159383552,"
                + "\"sha256\":\"" + SHA + "\",\"signerSha256\":[\"" + CERT + "\"]},"
                + "\"notes\":{\"tr\":\"Yeni\",\"en\":\"New\"},\"source\":{\"tag\":\"android-0.4.6\","
                + "\"url\":\"https://github.com/projects-gg/projects-mobile/tree/android-0.4.6\"},\"future\":{\"x\":1}}";
        for (int i = 0; i + 1 < replace.length; i += 2) {
            if (!json.contains(replace[i])) throw new IllegalArgumentException("no " + replace[i]);
            json = json.replace(replace[i], replace[i + 1]);
        }
        return json;
    }

    private static UpdateManifest parse(String json, boolean allowHttp) throws UpdateException {
        return UpdateManifest.parse(UpdateFixtures.utf8(json), "stable", allowHttp);
    }

    private static void refused(UpdateException.Kind kind, String json) {
        try {
            parse(json, false);
            fail("accepted " + json);
        } catch (UpdateException e) {
            assertEquals(json + ": " + e.getMessage(), kind, e.kind);
        }
    }

    @Test
    public void theSignedFixtureReads() throws UpdateException {
        UpdateManifest m = UpdateManifest.read(UpdateFixtures.resource("android-stable.json"), UpdateFixtures.resource("android-stable.json.sig"),
                "stable", Minisign.publicKeys(Collections.singletonList(UpdateFixtures.FIXTURE_KEY)), false);
        assertEquals("stable", m.channel);
        assertEquals("gg.projects.mobile", m.applicationId);
        assertEquals("0.4.6", m.versionName);
        assertEquals(22, m.versionCode);
        assertEquals(21, m.minSdk);
        assertEquals(1793620800000L, m.pubDateMillis);
        assertEquals(Collections.singletonList("https://cdn.projects.gg/v1/android/0.4.6/projects-launcher-0.4.6.apk"), m.urls);
        assertEquals(159383552L, m.size);
        assertEquals(SHA, m.sha256);
        assertEquals(Collections.singletonList(CERT), m.signers);
        assertEquals(Arrays.asList("Uygulama art\u0131k kendini g\u00fcnceller.", "Arkada\u015flar\u0131n\u0131n hangi s\u00fcr\u00fcmde oynad\u0131\u011f\u0131n\u0131 g\u00f6r\u00fcrs\u00fcn."),
                m.notes(true));
        assertEquals(Arrays.asList("The app now updates itself.", "See which version your friends are playing."), m.notes(false));
        assertEquals("android-0.4.6", m.sourceTag);
        assertEquals("https://github.com/projects-gg/projects-mobile/tree/android-0.4.6", m.sourceUrl);
    }

    @Test
    public void theTrustedCommentMustNameThisChannelAndVersion() throws UpdateException {
        UpdateFixtures.TestKey key = new UpdateFixtures.TestKey();
        List<Minisign.PublicKey> keys = Collections.singletonList(key.publicKey());
        byte[] json = UpdateFixtures.utf8(doc());
        assertEquals(22, UpdateManifest.read(json, key.channelSig(json, "stable", "0.4.6"), "stable", keys, false).versionCode);
        String[][] wrong = {
                {"file:android-beta.json\tversion:0.4.6"},
                {"file:Projects.Launcher_0.4.6_x64-setup.exe\tversion:0.4.6"},
                {"file:android-stable.json\tversion:0.4.5"},
                {"file:android-stable.json"},
                {"timestamp:1\tversion:0.4.6"},
        };
        for (String[] comment : wrong) {
            try {
                UpdateManifest.read(json, key.sigFile(json, comment[0]), "stable", keys, false);
                fail("accepted a signature with " + comment[0]);
            } catch (UpdateException e) {
                assertEquals(UpdateException.Kind.BAD_SIGNATURE, e.kind);
            }
        }
        // A beta file is not a stable one, even when signed for its own name.
        byte[] beta = UpdateFixtures.utf8(doc("\"channel\":\"stable\"", "\"channel\":\"beta\""));
        try {
            UpdateManifest.read(beta, key.channelSig(beta, "beta", "0.4.6"), "stable", keys, false);
            fail("read a beta file as stable");
        } catch (UpdateException e) {
            assertEquals(UpdateException.Kind.INVALID, e.kind);
        }
        assertEquals("beta", UpdateManifest.read(beta, key.channelSig(beta, "beta", "0.4.6"), "beta", keys, false).channel);
        // Too large to be a channel file: refused before anything is parsed.
        byte[] big = new byte[UpdateManifest.MAX_BYTES + 1];
        try {
            UpdateManifest.read(big, key.channelSig(big, "stable", "0.4.6"), "stable", keys, false);
            fail("read an oversized file");
        } catch (UpdateException e) {
            assertEquals(UpdateException.Kind.INVALID, e.kind);
        }
    }

    @Test
    public void everyFieldRule() throws UpdateException {
        parse(doc(), false);
        refused(UpdateException.Kind.INVALID, "not json");
        refused(UpdateException.Kind.INVALID, "[]");
        refused(UpdateException.Kind.INVALID, doc("\"schema\":1,", ""));
        refused(UpdateException.Kind.INVALID, doc("\"schema\":1", "\"schema\":0"));
        refused(UpdateException.Kind.UNSUPPORTED, doc("\"schema\":1", "\"schema\":2"));
        refused(UpdateException.Kind.INVALID, doc("\"channel\":\"stable\"", "\"channel\":\"Stable\""));
        refused(UpdateException.Kind.INVALID, doc("\"gg.projects.mobile\"", "\"gg\""));
        refused(UpdateException.Kind.INVALID, doc("\"gg.projects.mobile\"", "\"Gg.projects.mobile\""));
        refused(UpdateException.Kind.INVALID, doc("\"gg.projects.mobile\"", "\"gg..mobile\""));
        refused(UpdateException.Kind.INVALID, doc("\"versionName\":\"0.4.6\"", "\"versionName\":\"\""));
        refused(UpdateException.Kind.INVALID, doc("\"versionName\":\"0.4.6\"", "\"versionName\":\"0.4.6 beta\""));
        refused(UpdateException.Kind.INVALID, doc("\"versionName\":\"0.4.6\"", "\"versionName\":\"0.4.6.1.2.3.4.5.6.7.8.9.10.11.12.13\""));
        refused(UpdateException.Kind.INVALID, doc("\"versionCode\":22", "\"versionCode\":0"));
        refused(UpdateException.Kind.INVALID, doc("\"versionCode\":22", "\"versionCode\":2200000000"));
        refused(UpdateException.Kind.INVALID, doc("\"versionCode\":22", "\"versionCode\":22.5"));
        refused(UpdateException.Kind.INVALID, doc("\"minSdk\":21,", ""));
        refused(UpdateException.Kind.INVALID, doc("\"minSdk\":21", "\"minSdk\":0"));
        refused(UpdateException.Kind.INVALID, doc("2026-11-02T12:00:00Z", "2 November 2026"));
        refused(UpdateException.Kind.INVALID, doc("\"size\":159383552", "\"size\":0"));
        refused(UpdateException.Kind.INVALID, doc("\"size\":159383552", "\"size\":600000000"));
        refused(UpdateException.Kind.INVALID, doc(SHA, SHA.substring(1)));
        refused(UpdateException.Kind.INVALID, doc(SHA, SHA.substring(1) + "g"));
        refused(UpdateException.Kind.INVALID, doc("[\"https://cdn.projects.gg/v1/android/0.4.6/projects-launcher-0.4.6.apk\"]", "[]"));
        refused(UpdateException.Kind.INVALID, doc("https://cdn.projects.gg/v1/android", "http://cdn.projects.gg/v1/android"));
        refused(UpdateException.Kind.INVALID, doc("https://cdn.projects.gg/v1/android", "javascript:alert(1)//cdn.projects.gg/v1/android"));
        refused(UpdateException.Kind.INVALID, doc("\"https://cdn.projects.gg/v1/android/0.4.6/projects-launcher-0.4.6.apk\"",
                "\"https://a.example/1.apk\",\"https://a.example/2.apk\",\"https://a.example/3.apk\",\"https://a.example/4.apk\",\"https://a.example/5.apk\""));
        refused(UpdateException.Kind.INVALID, doc("[\"" + CERT + "\"]", "[]"));
        refused(UpdateException.Kind.INVALID, doc("[\"" + CERT + "\"]", "[\"0A:AB:80\"]"));
        refused(UpdateException.Kind.INVALID, doc("\"apk\":{", "\"apkx\":{"));
    }

    @Test
    public void lenientWhereItIsSafe() throws UpdateException {
        // Debug builds' local test server: http only with allowHttp.
        String http = doc("https://cdn.projects.gg/v1/android", "http://10.0.2.2:8791/v1/android");
        assertEquals("http://10.0.2.2:8791/v1/android/0.4.6/projects-launcher-0.4.6.apk", parse(http, true).urls.get(0));
        // Upper-case hex and a repeated certificate.
        UpdateManifest m = parse(doc(SHA, SHA.toUpperCase(), "[\"" + CERT + "\"]", "[\"" + CERT.toUpperCase() + "\",\"" + CERT + "\"]"), false);
        assertEquals(SHA, m.sha256);
        assertEquals(Collections.singletonList(CERT), m.signers);
        // Without notes or source the file still reads.
        UpdateManifest bare = parse(doc(",\"notes\":{\"tr\":\"Yeni\",\"en\":\"New\"}", "", ",\"source\":{\"tag\":\"android-0.4.6\","
                + "\"url\":\"https://github.com/projects-gg/projects-mobile/tree/android-0.4.6\"}", ""), false);
        assertEquals(Collections.<String>emptyList(), bare.notes(true));
        assertNull(bare.sourceTag);
        assertNull(bare.sourceUrl);
        // A broken source is left out, not the file.
        UpdateManifest odd = parse(doc("\"tag\":\"android-0.4.6\"", "\"tag\":\"../../x\""), false);
        assertNull(odd.sourceTag);
        assertNull(odd.sourceUrl);
    }

    @Test
    public void notesAreDisplayLines() {
        assertEquals(Arrays.asList("a", "b", "c", "d"), UpdateManifest.noteLines("- a\n\n* b\r\n\u2022 c\n  d  \n"));
        assertEquals(Collections.singletonList("tab and bell gone"), UpdateManifest.noteLines("tab\t and bell\u0007 gone"));
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 30; i++) many.append("line ").append(i).append('\n');
        List<String> lines = UpdateManifest.noteLines(many.toString());
        assertEquals(UpdateManifest.MAX_NOTE_LINES, lines.size());
        assertEquals("line 19", lines.get(19));
        StringBuilder longLine = new StringBuilder();
        for (int i = 0; i < 400; i++) longLine.append('x');
        String cut = UpdateManifest.noteLines(longLine.toString()).get(0);
        assertEquals(UpdateManifest.MAX_NOTE_LINE + 1, cut.length());
        assertEquals('\u2026', cut.charAt(cut.length() - 1));
        assertEquals(Collections.<String>emptyList(), UpdateManifest.noteLines(null));
        assertEquals(Collections.<String>emptyList(), UpdateManifest.noteLines("\n - \n"));
    }

    @Test
    public void notesFallBackToTheOtherLanguage() throws UpdateException {
        UpdateManifest onlyTurkish = parse(doc("\"en\":\"New\"", "\"en\":\"\""), false);
        assertEquals(Collections.singletonList("Yeni"), onlyTurkish.notes(false));
        UpdateManifest onlyEnglish = parse(doc("\"tr\":\"Yeni\",", ""), false);
        assertEquals(Collections.singletonList("New"), onlyEnglish.notes(true));
    }
}
