package gg.projects.mobile.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * M14: the wiring that gives upstream's screens the Projects press feedback and look, which an
 * upstream merge of styles.xml or the manifest could quietly drop.
 */
public class ThemeWiringTest {
    private static final File RES = new File("src/main/res");
    private static final File JAVA = new File("src/main/java");

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /** The items of {@code style} in {@code xml}, as one string. */
    private static String style(String xml, String style) {
        Matcher m = Pattern.compile("<style name=\"" + Pattern.quote(style) + "\"[^>]*>(.*?)</style>", Pattern.DOTALL).matcher(xml);
        assertTrue("style " + style, m.find());
        return m.group(1);
    }

    @Test
    public void everyThemeInflatesThePressingWidgets() throws Exception {
        String projects = read(new File(RES, "values/projects_styles.xml"));
        String upstream = read(new File(RES, "values/styles.xml"));
        String inflater = "<item name=\"viewInflaterClass\">gg.projects.mobile.ui.ProjectsViewInflater</item>";
        assertTrue("the activities' theme", style(projects, "Theme.Projects.Base").contains(inflater));
        assertTrue("dialogs use their own theme", style(upstream, "ProjectsAlertDialog").contains(inflater));
        assertTrue(new File(JAVA, "gg/projects/mobile/ui/ProjectsViewInflater.java").isFile());
        for (String file : new String[]{"values/styles.xml", "values-land/styles.xml"}) {
            Matcher appTheme = Pattern.compile("<style name=\"AppTheme\" parent=\"([^\"]+)\"").matcher(read(new File(RES, file)));
            assertTrue(file, appTheme.find());
            assertEquals(file + ": AppTheme inherits the brand theme", "@style/Theme.Projects.Base", appTheme.group(1));
        }
    }

    @Test
    public void theLauncherSpeaksFigtreeButTheGameKeepsItsControlsFont() throws Exception {
        String manifest = read(new File("src/main/AndroidManifest.xml"));
        Matcher launcher = Pattern.compile("<activity[^>]*android:name=\"\\.LauncherActivity\"[^>]*>", Pattern.DOTALL).matcher(manifest);
        assertTrue(launcher.find());
        assertTrue(launcher.group().contains("android:theme=\"@style/AppTheme.Launcher\""));
        Matcher game = Pattern.compile("<activity[^>]*android:name=\"\\.MainActivity\"[^>]*>", Pattern.DOTALL).matcher(manifest);
        assertTrue(game.find());
        assertTrue("the game activity has no font theme of its own", !game.group().contains("AppTheme.Launcher"));
        assertTrue(style(read(new File(RES, "values/projects_styles.xml")), "AppTheme.Launcher").contains("@font/figtree"));
        assertTrue("Theme.Projects.Base sets no font (the in-game controls take the theme's)",
                !style(read(new File(RES, "values/projects_styles.xml")), "Theme.Projects.Base").contains("fontFamily"));
    }

    @Test
    public void themesLeaveTheTextAppearancesTheirFontsAndSizes() throws Exception {
        // A theme-level value outranks every text appearance (AppCompat reads the theme's app
        // fontFamily before a layout's own android:fontFamily): titles turned Figtree, and upstream's
        // theme-wide text size gave settings' titles, summaries and dialog texts one size.
        String projects = read(new File(RES, "values/projects_styles.xml"));
        String upstream = read(new File(RES, "values/styles.xml"));
        String appFont = "<item name=\"fontFamily\">";
        assertTrue(!style(projects, "AppTheme.Launcher").contains(appFont));
        assertTrue(!style(upstream, "ProjectsAlertDialog").contains(appFont));
        String noSize = "<item name=\"android:textSize\">@null</item>";
        assertTrue(style(projects, "ProjectsPreferenceTheme").contains(noSize));
        assertTrue(style(upstream, "ProjectsAlertDialog").contains(noSize));
        for (String header : new String[]{"layout/projects_screen_header.xml", "layout/projects_preference_header.xml"}) {
            String xml = read(new File(RES, header));
            assertTrue(header, xml.contains("android:fontFamily=\"@font/tektur_semibold\""));
            assertTrue(header, xml.contains("android:textSize=\"@dimen/projects_text_title\""));
        }
    }

    @Test
    public void theLaunchScreenHasAStillSheenBeforeAndroid71() throws Exception {
        // drawable-v25 holds the render-thread animation (gradients need 7.0, render-thread vectors 7.1);
        // without a plain drawable of the same name the screen would not open on Android 5 to 7.0.
        assertTrue(new File(RES, "drawable-v25/projects_launch_sheen.xml").isFile());
        String fallback = read(new File(RES, "drawable/projects_launch_sheen.xml"));
        assertTrue(!fallback.contains("animated-vector") && !fallback.contains("gradient"));
        for (String still : new String[]{"projects_launch_emblem.xml", "projects_launch_brackets_static.xml"}) {
            assertTrue(still, !read(new File(RES, "drawable/" + still)).contains("aapt:attr"));
        }
    }
}
