package gg.projects.mobile.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * M18: a Fabric or Quilt version JSON a start can use, and which loader an id names (the player's
 * "Unable to read Version JSON for version fabric-loader-0.19.5-26.1.2").
 */
public class LoaderJsonTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String FABRIC = "{\"id\":\"fabric-loader-0.19.5-26.1.2\",\"inheritsFrom\":\"26.1.2\","
            + "\"releaseTime\":\"2026-10-01T00:00:00+0000\",\"type\":\"release\","
            + "\"mainClass\":\"net.fabricmc.loader.impl.launch.knot.KnotClient\","
            + "\"arguments\":{\"game\":[],\"jvm\":[]},\"libraries\":[{\"name\":\"net.fabricmc:fabric-loader:0.19.5\"}]}";

    @Test
    public void loaderIdsNameTheLoaderAndTheGame() {
        LoaderJson.Id fabric = LoaderJson.parse("fabric-loader-0.19.5-26.1.2");
        assertFalse(fabric.quilt);
        assertEquals("0.19.5", fabric.loader);
        assertEquals("26.1.2", fabric.minecraft);
        LoaderJson.Id snapshot = LoaderJson.parse("fabric-loader-0.19.5-26.4-snapshot-4");
        assertEquals("0.19.5", snapshot.loader);
        assertEquals("26.4-snapshot-4", snapshot.minecraft);
        LoaderJson.Id quilt = LoaderJson.parse("quilt-loader-0.26.4-1.21.1");
        assertTrue(quilt.quilt);
        assertEquals("0.26.4", quilt.loader);
        assertEquals("1.21.1", quilt.minecraft);
    }

    @Test
    public void otherIdsAreNoLoader() {
        assertNull(LoaderJson.parse("26.1.2"));
        assertNull(LoaderJson.parse("1.20.1-forge-47.2.0"));
        assertNull(LoaderJson.parse("neoforge-21.1.77"));
        assertNull(LoaderJson.parse("fabric-loader-"));
        assertNull(LoaderJson.parse("fabric-loader-0.19.5"));
        assertNull(LoaderJson.parse("fabric-loader-0.19.5-"));
        assertNull("no path tricks", LoaderJson.parse("fabric-loader-0.19.5-../../x"));
        assertNull(LoaderJson.parse(null));
    }

    @Test
    public void aWholeJsonForTheIdIsUsable() {
        assertTrue(LoaderJson.usable(FABRIC, "fabric-loader-0.19.5-26.1.2"));
        assertFalse("another version's", LoaderJson.usable(FABRIC, "fabric-loader-0.19.4-26.1.2"));
        assertFalse("cut short", LoaderJson.usable(FABRIC.substring(0, FABRIC.length() / 2), "fabric-loader-0.19.5-26.1.2"));
        assertFalse("empty", LoaderJson.usable("", "fabric-loader-0.19.5-26.1.2"));
        assertFalse("an error page", LoaderJson.usable("<html>502</html>", "fabric-loader-0.19.5-26.1.2"));
        assertFalse("no libraries", LoaderJson.usable("{\"id\":\"fabric-loader-0.19.5-26.1.2\",\"inheritsFrom\":\"26.1.2\"}",
                "fabric-loader-0.19.5-26.1.2"));
        assertFalse("nothing to start", LoaderJson.usable("{\"id\":\"fabric-loader-0.19.5-26.1.2\",\"libraries\":[]}",
                "fabric-loader-0.19.5-26.1.2"));
    }

    @Test
    public void theFileOnDiskIsCheckedTheSameWay() throws Exception {
        File missing = new File(tmp.getRoot(), "fabric-loader-0.19.5-26.1.2.json");
        assertFalse(LoaderJson.usable(missing, "fabric-loader-0.19.5-26.1.2"));
        File empty = tmp.newFile("empty.json");
        assertFalse(LoaderJson.usable(empty, "fabric-loader-0.19.5-26.1.2"));
        File whole = tmp.newFile("whole.json");
        Files.write(whole.toPath(), FABRIC.getBytes(StandardCharsets.UTF_8));
        assertTrue(LoaderJson.usable(whole, "fabric-loader-0.19.5-26.1.2"));
        File half = tmp.newFile("half.json");
        Files.write(half.toPath(), FABRIC.substring(0, 40).getBytes(StandardCharsets.UTF_8));
        assertFalse(LoaderJson.usable(half, "fabric-loader-0.19.5-26.1.2"));
    }
}
