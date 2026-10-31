package gg.projects.mobile.social.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * TABLET-M (TASTE-2 r1): where "Mesajlar" shows a conversation follows the window alone. A conversation on its own screen
 * in a window with room for two panes goes back beside the list, whatever the turns before; the flag the code reads and
 * the layout the window inflates are made from one qualifier set, so they never disagree.
 */
public class ChatPanesTest {
    private static final File RES = new File("src/main/res");

    @Test
    public void aWideWindowAlwaysShowsTheConversationBesideTheList() {
        // Turned back to landscape over the list (the narrow window had sent the conversation to its own screen).
        assertEquals(ChatPanes.Move.BACK_TO_LIST, ChatPanes.onWindow(true, true, true));
        // Opened on its own screen elsewhere (a friend's card upright), then the window turned wide.
        assertEquals(ChatPanes.Move.LIST_INSTEAD, ChatPanes.onWindow(true, true, false));
        // Beside the list already, or a narrow window: nothing moves.
        assertEquals(ChatPanes.Move.STAY, ChatPanes.onWindow(true, false, true));
        assertEquals(ChatPanes.Move.STAY, ChatPanes.onWindow(true, false, false));
        assertEquals(ChatPanes.Move.STAY, ChatPanes.onWindow(false, true, true));
        assertEquals(ChatPanes.Move.STAY, ChatPanes.onWindow(false, true, false));
        assertEquals(ChatPanes.Move.STAY, ChatPanes.onWindow(false, false, false));
    }

    @Test
    public void whatIsWrittenGoesAlongOnlyRightAfterTheMove() {
        // A turn makes the screen again within a second or two.
        assertTrue(ChatPanes.fresh(1_000, 1_000));
        assertTrue(ChatPanes.fresh(1_000, 2_500));
        assertTrue(ChatPanes.fresh(1_000, 1_000 + ChatPanes.HANDOFF_MS));
        // Kept when the app went away, the conversation opened much later: nothing stale comes back.
        assertFalse(ChatPanes.fresh(1_000, 1_001 + ChatPanes.HANDOFF_MS));
        // A clock that went backwards (another boot) takes nothing.
        assertFalse(ChatPanes.fresh(5_000, 4_999));
    }

    @Test
    public void theFlagAndTheLayoutShareOneQualifierSet() throws IOException {
        // The flag: false by default, true only for wide and tall windows, nowhere else.
        assertTrue(read(new File(RES, "values/projects_bools.xml")).contains("name=\"projects_chat_two_pane\">false<"));
        assertTrue(read(new File(RES, "values-w840dp-h480dp/projects_bools.xml")).contains("name=\"projects_chat_two_pane\">true<"));
        List<String> flagDirs = dirsHaving("values", "projects_chat_two_pane");
        assertEquals("[values, values-w840dp-h480dp]", flagDirs.toString());
        // The layout: the same two folders; only the wide one has the side that says what to do.
        List<String> layoutDirs = dirsHaving("layout", "projects_chats_detail");
        assertEquals("[layout, layout-w840dp-h480dp]", layoutDirs.toString());
        String narrow = read(new File(RES, "layout/fragment_projects_chat_list.xml"));
        String wide = read(new File(RES, "layout-w840dp-h480dp/fragment_projects_chat_list.xml"));
        assertTrue(wide.contains("@+id/projects_chats_pick"));
        assertFalse(narrow.contains("projects_chats_pick"));
        // The narrow layout keeps the conversation's place hidden (a conversation restored from two panes lands there).
        int detail = narrow.indexOf("@+id/projects_chats_detail");
        assertTrue(detail > 0 && narrow.indexOf("android:visibility=\"gone\"", detail) > detail);
    }

    /** The folders whose files (of the given kind) name {@code needle}, sorted. */
    private static List<String> dirsHaving(String kind, String needle) throws IOException {
        List<String> out = new ArrayList<>();
        File[] dirs = RES.listFiles();
        if (dirs == null) return out;
        for (File dir : dirs) {
            String name = dir.getName();
            if (!dir.isDirectory() || !(name.equals(kind) || name.startsWith(kind + "-"))) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.getName().endsWith(".xml") && read(f).contains(needle)) {
                    out.add(name);
                    break;
                }
            }
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
