package gg.projects.mobile.join;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** MOB2: a deleted profile waits 30 days in the trash beside launcher_profiles.json, then is gone. */
public class ProfileTrashTest {
    private static final long DAY = 86_400_000L;
    /** 2026-10-17T12:00:00Z. */
    private static final long NOW = 1_792_238_400_000L;

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private ProfileTrash trash() {
        return ProfileTrash.beside(new File(folder.getRoot(), "launcher_profiles.json"));
    }

    private static JsonObject profile(String name, String renderer) {
        JsonObject profile = new JsonObject();
        profile.addProperty("name", name);
        profile.addProperty("icon", "projects");
        profile.addProperty("lastVersionId", "fabric-loader-0.19.5-26.1.2");
        profile.addProperty("gameDir", "./custom_instances/projects");
        if (renderer != null) profile.addProperty("pojavRendererName", renderer);
        return profile;
    }

    private static List<String> keys(List<ProfileTrash.Entry> entries) {
        List<String> keys = new ArrayList<>();
        for (ProfileTrash.Entry entry : entries) keys.add(entry.key);
        return keys;
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void aDeletedProfileComesBackAsItWasStored() throws IOException {
        JsonObject stored = profile("Projects", "opengles3_ltw");
        stored.addProperty("javaArgs", "-Xmx2G");
        // A field this version of the app does not know comes back too.
        stored.addProperty("somethingNewer", "kept");
        trash().put("key-a", stored, NOW);
        assertEquals("beside launcher_profiles.json", folder.getRoot(), trash().file().getParentFile());

        ProfileTrash.Entry entry = trash().find("key-a", NOW + DAY);
        assertNotNull(entry);
        assertEquals("key-a", entry.key);
        assertEquals(NOW, entry.deletedAt);
        assertEquals(stored, entry.profile);
        assertTrue(trash().contains("key-a", NOW + DAY));
        assertNull(trash().find("key-b", NOW + DAY));
        assertNull(trash().find(null, NOW));
    }

    @Test
    public void restoringTakesTheEntryOut() throws IOException {
        ProfileTrash trash = trash();
        trash.put("key-a", profile("Projects", null), NOW);
        trash.put("key-b", profile("Fabric", null), NOW + 1);
        assertTrue(trash.remove("key-a", NOW + DAY));
        assertNull(trash.find("key-a", NOW + DAY));
        assertNotNull("the others stay", trash.find("key-b", NOW + DAY));
        assertFalse("taken out once", trash.remove("key-a", NOW + DAY));
        assertTrue(trash.remove("key-b", NOW + DAY));
        assertFalse("an empty trash leaves no file", trash.file().exists());
        assertFalse(trash.remove("key-b", NOW + DAY));
    }

    @Test
    public void keptThirtyDaysThenGone() throws IOException {
        ProfileTrash trash = trash();
        trash.put("old", profile("Eski", null), NOW - 30 * DAY);
        trash.put("recent", profile("Yeni", null), NOW - 29 * DAY);
        assertNull("30 days old", trash.find("old", NOW));
        assertFalse(trash.contains("old", NOW));
        assertNotNull("29 days old", trash.find("recent", NOW));
        assertEquals(Collections.singletonList("recent"), keys(trash.entries(NOW)));

        // Lazily: an expired entry stays in the file until the trash is written.
        assertTrue(text(trash.file()).contains("\"old\""));
        assertEquals(1, trash.purge(NOW));
        assertFalse(text(trash.file()).contains("\"old\""));
        assertEquals(0, trash.purge(NOW));

        // Any write drops what has expired by then.
        trash.put("other", profile("Başka", null), NOW + DAY);
        assertFalse(text(trash.file()).contains("\"recent\""));
        assertEquals(Collections.singletonList("other"), keys(trash.entries(NOW + DAY)));
        // Taking out an expired entry finds nothing, and the file loses it.
        trash.put("late", profile("Geç", null), NOW);
        assertFalse(trash.remove("late", NOW + 30 * DAY));
        assertFalse(text(trash.file()).contains("\"late\""));
    }

    @Test
    public void deletingAgainKeepsTheNewestOnly() throws IOException {
        ProfileTrash trash = trash();
        trash.put("key-a", profile("Projects", "gl4es"), NOW - DAY);
        trash.put("key-b", profile("Fabric", null), NOW - DAY + 1);
        trash.put("key-a", profile("Projects", "opengles3_ltw"), NOW);
        List<ProfileTrash.Entry> entries = trash.entries(NOW);
        assertEquals(Arrays.asList("key-a", "key-b"), keys(entries));
        assertEquals("opengles3_ltw", entries.get(0).profile.get("pojavRendererName").getAsString());
        assertEquals(NOW, entries.get(0).deletedAt);
    }

    @Test
    public void newestFirstAndBounded() throws IOException {
        ProfileTrash trash = trash();
        int count = ProfileTrash.MAX_ENTRIES + 3;
        for (int i = 0; i < count; i++) trash.put("key-" + i, profile("P" + i, null), NOW + i);
        List<ProfileTrash.Entry> entries = trash.entries(NOW + count);
        assertEquals(ProfileTrash.MAX_ENTRIES, entries.size());
        assertEquals("key-" + (count - 1), entries.get(0).key);
        assertNull("the oldest went first", trash.find("key-0", NOW + count));
        assertNotNull(trash.find("key-3", NOW + count));
    }

    @Test
    public void anUnreadableTrashReadsEmptyAndIsReplaced() throws IOException {
        ProfileTrash trash = trash();
        write(trash.file(), "{ not json");
        assertTrue(trash.entries(NOW).isEmpty());
        assertNull(trash.find("key-a", NOW));
        trash.put("key-a", profile("Projects", null), NOW);
        assertNotNull(trash.find("key-a", NOW));

        // Entries without a key, a profile or a time are skipped; the rest still read.
        write(trash.file(), "{\"entries\":[{\"key\":\"x\"},{\"key\":\"y\",\"deletedAt\":" + NOW
                + ",\"profile\":{\"name\":\"Y\"}},3,{\"key\":\"z\",\"deletedAt\":\"soon\",\"profile\":{}}]}");
        assertEquals(Collections.singletonList("y"), keys(trash.entries(NOW)));
        write(trash.file(), "[]");
        assertTrue(trash.entries(NOW).isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void aProfileIsNeeded() throws IOException {
        trash().put("key-a", null, NOW);
    }
}
