package gg.projects.mobile.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The Sodium family of a profile the player made: read from each jar's own metadata. */
public class ModIdsTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static ModIds.Info json(String text) {
        ModIds.Info info = new ModIds.Info();
        ModIds.readJson(text, info);
        return info;
    }

    private static ModIds.Info toml(String text) {
        ModIds.Info info = new ModIds.Info();
        ModIds.readToml(text, info);
        return info;
    }

    @Test
    public void metadataOfEachLoader() {
        ModIds.Info fabric = json("{\"id\":\"sodium-extra\",\"depends\":{\"sodium\":\">=0.8\",\"minecraft\":\"*\"},"
                + "\"recommends\":{\"iris\":\"*\"}}");
        assertEquals(new HashSet<>(Arrays.asList("sodium-extra")), fabric.ids);
        assertEquals("recommended mods are not dependencies", new HashSet<>(Arrays.asList("sodium", "minecraft")), fabric.depends);

        ModIds.Info quilt = json("{\"quilt_loader\":{\"id\":\"Indium\",\"depends\":[\"sodium\",{\"id\":\"quilt_loader\"}]}}");
        assertEquals(new HashSet<>(Arrays.asList("indium")), quilt.ids);
        assertEquals(new HashSet<>(Arrays.asList("sodium", "quilt_loader")), quilt.depends);

        ModIds.Info forge = toml("modLoader=\"javafml\"\n[[mods]]\nmodId=\"oculus_addon\"\n"
                + "[[dependencies.oculus_addon]]\n    modId=\"oculus\"\n    mandatory=true\n"
                + "[[dependencies.oculus_addon]]\n    modId=\"embeddium\"\n    mandatory=false\n"
                + "[[dependencies.oculus_addon]]\n    modId = \"jei\"\n    type=\"optional\"\n");
        assertEquals(new HashSet<>(Arrays.asList("oculus_addon")), forge.ids);
        assertEquals("only required dependencies", new HashSet<>(Arrays.asList("oculus")), forge.depends);

        assertTrue(json("not json").ids.isEmpty());
    }

    @Test
    public void theFamilyIsSodiumItsForksIrisAndWhatNeedsThem() {
        Map<String, ModIds.Info> jars = new LinkedHashMap<>();
        jars.put("sodium-fabric.jar", json("{\"id\":\"sodium\"}"));
        jars.put("sodium-extra.jar", json("{\"id\":\"sodium-extra\",\"depends\":{\"sodium\":\"*\"}}"));
        jars.put("sei.jar", json("{\"id\":\"sodiumextrainformation\",\"depends\":{\"sodium-extra\":\"*\",\"anvianslib\":\"*\"}}"));
        jars.put("anvianslib.jar", json("{\"id\":\"anvianslib\"}"));
        jars.put("iris.jar", json("{\"id\":\"iris\",\"depends\":{\"sodium\":\"*\"}}"));
        jars.put("lithium.jar", json("{\"id\":\"lithium\"}"));
        jars.put("immediatelyfast.jar", json("{\"id\":\"immediatelyfast\",\"recommends\":{\"sodium\":\"*\"}}"));
        jars.put("irisflw.jar", json("{\"id\":\"irisflw\",\"depends\":{\"iris\":\"*\"}}"));
        assertEquals(new HashSet<>(Arrays.asList("sodium-fabric.jar", "sodium-extra.jar", "sei.jar", "iris.jar", "irisflw.jar")),
                ModIds.sodiumFamily(jars));
    }

    @Test
    public void aModsFolderIsReadFromTheJars() throws Exception {
        File mods = tmp.newFolder("mods");
        jar(new File(mods, "embeddium-1.0.jar"), "META-INF/mods.toml", "[[mods]]\nmodId=\"embeddium\"\n");
        jar(new File(mods, "addon.jar"), "fabric.mod.json", "{\"id\":\"addon\",\"depends\":{\"embeddium\":\"*\"}}");
        jar(new File(mods, "other.jar"), "fabric.mod.json", "{\"id\":\"other\"}");
        assertTrue(new File(mods, "notes.txt").createNewFile());
        assertEquals(new HashSet<>(Arrays.asList("embeddium-1.0.jar", "addon.jar")), new HashSet<>(ModIds.sodiumFamily(mods)));
    }

    private static void jar(File file, String entry, String content) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
