package gg.projects.mobile.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** M053: the ignore list as the desktop keeps it (oldest first, at most 200) and the person menu's place for it. */
public class IgnoreListTest {
    @Test
    public void addRemoveAndCap() {
        List<String> list = IgnoreList.with(Collections.<String>emptyList(), "a", true);
        list = IgnoreList.with(list, "b", true);
        assertEquals(Arrays.asList("a", "b"), list);
        assertEquals(list, IgnoreList.with(list, "a", true));
        assertEquals(Collections.singletonList("b"), IgnoreList.with(list, "a", false));
        assertEquals(list, IgnoreList.with(list, "", true));
        List<String> full = new ArrayList<>();
        for (int i = 0; i < IgnoreList.MAX; i++) full.add("u" + i);
        List<String> over = IgnoreList.with(full, "new", true);
        assertEquals(IgnoreList.MAX, over.size());
        assertEquals("u1", over.get(0));
        assertEquals("new", over.get(IgnoreList.MAX - 1));
    }

    @Test
    public void storedAsJson() {
        assertEquals(Arrays.asList("a", "b"), IgnoreList.parse(IgnoreList.toJson(Arrays.asList("a", "b"))));
        assertTrue(IgnoreList.parse("{broken").isEmpty());
        assertTrue(IgnoreList.parse(null).isEmpty());
        assertEquals(Collections.singletonList("a"), IgnoreList.parse("[\"a\",\"a\",3,\"\"]").subList(0, 1));
    }

    @Test
    public void theMenuOffersItForFriendsAndCrewmates() {
        SocialMenus.PersonInput in = new SocialMenus.PersonInput();
        in.friend = true;
        in.ignoreList = true;
        assertTrue(actions(in).contains(SocialMenus.PersonAction.IGNORE));
        in.ignored = true;
        assertTrue(actions(in).contains(SocialMenus.PersonAction.UNIGNORE));
        int at = actions(in).indexOf(SocialMenus.PersonAction.UNIGNORE);
        assertEquals(SocialMenus.PersonAction.BLOCK, actions(in).get(at + 1));
        assertEquals(SocialMenus.PersonAction.REMOVE_FRIEND, actions(in).get(at - 1));
        in.ignoreList = false;
        assertTrue(!actions(in).contains(SocialMenus.PersonAction.UNIGNORE) && !actions(in).contains(SocialMenus.PersonAction.IGNORE));
        SocialMenus.PersonInput blocked = new SocialMenus.PersonInput();
        blocked.friend = true;
        blocked.blocked = true;
        blocked.ignoreList = true;
        assertTrue(!actions(blocked).contains(SocialMenus.PersonAction.IGNORE));
    }

    private static List<SocialMenus.PersonAction> actions(SocialMenus.PersonInput in) {
        List<SocialMenus.PersonAction> out = new ArrayList<>();
        for (SocialMenus.Row<SocialMenus.PersonAction> row : SocialMenus.person(in)) if (row != null) out.add(row.action);
        return out;
    }
}
