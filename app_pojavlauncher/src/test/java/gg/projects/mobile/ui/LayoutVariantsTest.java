package gg.projects.mobile.ui;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TABLET-M: every window variant of a layout ({@code layout-land}, {@code layout-w600dp}, {@code layout-w840dp-h480dp}...)
 * has every id and every included layout of the base layout, so a screen's code finds the same views in every window.
 * 0.4.0 to 0.4.6's landscape home had no 7/24 Bulut row, and {@code CloudHomeRow} crashed it on every turn to landscape.
 */
public class LayoutVariantsTest {
    private static final File RES = new File("src/main/res");
    private static final Pattern ID = Pattern.compile("@\\+id/([A-Za-z0-9_]+)");
    private static final Pattern INCLUDE = Pattern.compile("<include[^>]*layout=\"@layout/([A-Za-z0-9_]+)\"", Pattern.DOTALL);

    @Test
    public void everyVariantHasTheBaseLayoutsViews() throws IOException {
        File base = new File(RES, "layout");
        File[] dirs = RES.listFiles();
        assertTrue(dirs != null);
        int checked = 0;
        for (File dir : dirs) {
            if (!dir.isDirectory() || !dir.getName().startsWith("layout-")) continue;
            File[] variants = dir.listFiles();
            if (variants == null) continue;
            for (File variant : variants) {
                File original = new File(base, variant.getName());
                if (!original.isFile()) continue;
                String a = read(original);
                String b = read(variant);
                Set<String> missing = all(ID, a);
                missing.removeAll(all(ID, b));
                assertTrue(dir.getName() + "/" + variant.getName() + " lacks the ids " + missing, missing.isEmpty());
                Set<String> includes = all(INCLUDE, a);
                includes.removeAll(all(INCLUDE, b));
                assertTrue(dir.getName() + "/" + variant.getName() + " lacks the included " + includes, includes.isEmpty());
                checked++;
            }
        }
        // The home (land, w600dp, w840dp-h480dp), the launcher's frame (land) and the chats (w840dp-h480dp) at least.
        assertTrue("variants checked: " + checked, checked >= 5);
    }

    private static Set<String> all(Pattern pattern, String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
