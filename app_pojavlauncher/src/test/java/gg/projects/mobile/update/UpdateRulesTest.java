package gg.projects.mobile.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import gg.projects.mobile.content.ContentException;
import gg.projects.mobile.content.ContentSources;

/**
 * APK-UPD: the smaller rules: whose APK may be installed, what Android's install answers mean, where the
 * channel and the download live, and the debug override's update fields.
 */
public class UpdateRulesTest {
    private static final String A = "0aab804584dfccff406c80f982d05b5ffec537a89132e2fb4fd3fedde8108ba3";
    private static final String B = "1bbc804584dfccff406c80f982d05b5ffec537a89132e2fb4fd3fedde8108ba3";

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    @Test
    public void onlyTheInstalledAppsSigners() {
        assertTrue(Signers.sameAsInstalled(set(A), set(A)));
        assertFalse("another key", Signers.sameAsInstalled(set(B), set(A)));
        assertFalse("one more signer", Signers.sameAsInstalled(set(A, B), set(A)));
        assertFalse("one signer fewer", Signers.sameAsInstalled(set(A), set(A, B)));
        assertFalse("unsigned", Signers.sameAsInstalled(set(), set()));
        assertTrue(Signers.announced(set(A), Arrays.asList(A, B)));
        assertFalse("not in the channel file", Signers.announced(set(B), Collections.singletonList(A)));
        assertFalse("unsigned", Signers.announced(set(), Collections.singletonList(A)));
        // apksigner's colon-less lowercase hex of the DER bytes.
        assertEquals("a665a45920422f9d417e4867efdc4fb8a04a1f3fff1fa07e998e86f7f7a27ae3", Signers.sha256("123".getBytes()));
    }

    @Test
    public void androidsInstallAnswers() {
        assertEquals(InstallStatus.Outcome.CONFIRM, InstallStatus.outcome(InstallStatus.PENDING_USER_ACTION));
        assertEquals(InstallStatus.Outcome.DONE, InstallStatus.outcome(InstallStatus.SUCCESS));
        assertEquals(InstallStatus.Outcome.DECLINED, InstallStatus.outcome(InstallStatus.FAILURE_ABORTED));
        for (int status : new int[]{InstallStatus.FAILURE, InstallStatus.FAILURE_BLOCKED, InstallStatus.FAILURE_INVALID,
                InstallStatus.FAILURE_CONFLICT, InstallStatus.FAILURE_STORAGE, InstallStatus.FAILURE_INCOMPATIBLE,
                InstallStatus.FAILURE_TIMEOUT, 99}) {
            assertEquals(InstallStatus.Outcome.FAILED, InstallStatus.outcome(status));
        }
        assertEquals(UpdateException.Kind.BLOCKED, InstallStatus.failure(InstallStatus.FAILURE_BLOCKED));
        assertEquals(UpdateException.Kind.CORRUPT, InstallStatus.failure(InstallStatus.FAILURE_INVALID));
        assertEquals(UpdateException.Kind.FOREIGN, InstallStatus.failure(InstallStatus.FAILURE_CONFLICT));
        assertEquals(UpdateException.Kind.SPACE, InstallStatus.failure(InstallStatus.FAILURE_STORAGE));
        assertEquals(UpdateException.Kind.INCOMPATIBLE, InstallStatus.failure(InstallStatus.FAILURE_INCOMPATIBLE));
        assertEquals(UpdateException.Kind.INSTALL_FAILED, InstallStatus.failure(InstallStatus.FAILURE));
        assertEquals(UpdateException.Kind.INSTALL_FAILED, InstallStatus.failure(InstallStatus.FAILURE_TIMEOUT));
    }

    @Test
    public void androidsStatusValues() {
        // The values Android documents for PackageInstaller (kept here for the JVM).
        assertEquals(android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION, InstallStatus.PENDING_USER_ACTION);
        assertEquals(android.content.pm.PackageInstaller.STATUS_SUCCESS, InstallStatus.SUCCESS);
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE, InstallStatus.FAILURE);
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_BLOCKED, InstallStatus.FAILURE_BLOCKED);
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED, InstallStatus.FAILURE_ABORTED);
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_INVALID, InstallStatus.FAILURE_INVALID);
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT, InstallStatus.FAILURE_CONFLICT);
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_STORAGE, InstallStatus.FAILURE_STORAGE);
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, InstallStatus.FAILURE_INCOMPATIBLE);
        assertEquals(android.content.pm.PackageInstaller.STATUS_FAILURE_TIMEOUT, InstallStatus.FAILURE_TIMEOUT);
    }

    @Test
    public void whereThingsLive() throws UpdateException {
        assertEquals("android/stable.json", UpdateConfig.channelPath("stable"));
        assertEquals("stable", UpdateConfig.CHANNEL);
        assertEquals("android-stable.json", UpdateManifest.signedFileName("stable"));
        String json = "{\"schema\":1,\"channel\":\"stable\",\"applicationId\":\"gg.projects.mobile\",\"versionName\":\"0.4.6\","
                + "\"versionCode\":22,\"minSdk\":21,\"pubDate\":\"2026-11-02T12:00:00Z\",\"apk\":{\"urls\":"
                + "[\"https://cdn.projects.gg/v1/android/0.4.6/projects-launcher-0.4.6.apk\"],\"size\":10,"
                + "\"sha256\":\"5f2b0c1d9e8a7b6c5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c\",\"signerSha256\":[\"" + A + "\"]}}";
        assertEquals("projects-launcher-22.apk", UpdateConfig.apkName(UpdateManifest.parse(UpdateFixtures.utf8(json), "stable", false)));
    }

    @Test
    public void theDebugOverridesUpdateFields() throws ContentException {
        UpdateFixtures.TestKey key = new UpdateFixtures.TestKey();
        ContentSources sources = ContentSources.withDebugOverride(UpdateFixtures.utf8(
                "{\"updateMirrors\":[\"http://10.0.2.2:8791/v1\"],\"updateTestKey\":\"" + key.publicLine() + "\"}"));
        assertEquals(Collections.singletonList("http://10.0.2.2:8791/v1/"), sources.updateMirrors);
        assertEquals(key.publicLine(), sources.updateTestKey);
        assertTrue(sources.override);
        ContentSources none = ContentSources.withDebugOverride(UpdateFixtures.utf8("{}"));
        assertEquals(Collections.<String>emptyList(), none.updateMirrors);
        assertNull(none.updateTestKey);
        assertEquals(Collections.<String>emptyList(), ContentSources.production().updateMirrors);
        assertNull(ContentSources.production().updateTestKey);
        for (String bad : new String[]{"{\"updateTestKey\":\"muj3CSu3m9jpB7fUP5TbkRSnISGEH13XbTt6agmIK68=\"}",
                "{\"updateMirrors\":[\"ftp://x/v1/\"]}"}) {
            try {
                ContentSources.withDebugOverride(UpdateFixtures.utf8(bad));
                fail("accepted " + bad);
            } catch (ContentException e) {
                assertEquals(ContentException.Kind.INVALID, e.kind);
            }
        }
    }
}
