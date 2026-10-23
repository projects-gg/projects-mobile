package gg.projects.mobile.social.emoji;

import static gg.projects.mobile.social.emoji.EmojiDataTest.TR;
import static gg.projects.mobile.social.emoji.EmojiDataTest.t;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The picker's recents on the phone (the desktop's features/emoji/recents.test.ts and store.ts). */
public class EmojiRecentsTest {
    private static final String JOY = t("{1F602}");
    private static final String FIRE = t("{1F525}");
    private static final String RED_HEART = t("{2764}{FE0F}");
    private static final String UP = t("{1F44D}");

    private static String tone(int n) {
        return t("{" + Integer.toHexString(0x1F3FA + n) + "}");
    }

    @Test
    public void remembersTheLatestFirstOnce() {
        assertEquals(Arrays.asList(JOY), EmojiRecents.remember(Collections.<String>emptyList(), JOY, 24));
        assertEquals(Arrays.asList(FIRE, JOY), EmojiRecents.remember(Arrays.asList(JOY, FIRE), FIRE, 24));
        assertEquals(Arrays.asList(UP, JOY, FIRE, RED_HEART),
                EmojiRecents.remember(Arrays.asList(JOY, FIRE, RED_HEART), UP, 24));
    }

    @Test
    public void keepsAtMostTheLimitDroppingTheOldest() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < 30; i++) list = EmojiRecents.remember(list, "e" + i, EmojiRecents.RECENT_EMOJI);
        assertEquals(EmojiRecents.RECENT_EMOJI, list.size());
        assertEquals("e29", list.get(0));
        assertEquals("e6", list.get(list.size() - 1));
        assertEquals(Collections.emptyList(), EmojiRecents.remember(list, "x", 0));
    }

    @Test
    public void cleansAStoredListToTextsOnceEachUpToTheLimit() {
        assertEquals(Arrays.asList(JOY, FIRE), EmojiRecents.cleanList(
                JsonParser.parseString("[\"" + JOY + "\", 4, null, \"" + JOY + "\", \"\", \"" + FIRE + "\"]"), 24));
        assertEquals(Collections.emptyList(), EmojiRecents.cleanList(JsonParser.parseString("\"x\""), 24));
        assertEquals(Collections.emptyList(), EmojiRecents.cleanList(null, 24));
        assertEquals(Arrays.asList("a", "b"), EmojiRecents.cleanList(JsonParser.parseString("[\"a\",\"b\",\"c\"]"), 2));
    }

    @Test
    public void findsTheRecentsInTheDataWhateverToneTheyWereSentIn() {
        List<EmojiData.Entry> found = EmojiRecents.recentEntries(TR,
                Arrays.asList(FIRE, UP + tone(3), "not an emoji", JOY, FIRE));
        List<String> emoji = new ArrayList<>();
        for (EmojiData.Entry entry : found) emoji.add(entry.emoji);
        assertEquals(Arrays.asList(FIRE, UP, JOY), emoji);
    }

    @Test
    public void keepsEmojiBareAndStickersByReferenceNewestFirst() {
        EmojiRecents recents = new EmojiRecents();
        assertEquals(Collections.emptyList(), recents.emoji());
        assertEquals(0, recents.tone());
        recents.usedEmoji(RED_HEART);
        recents.usedEmoji(UP + tone(2));
        recents.usedEmoji(t("{2764}"));
        assertEquals(Arrays.asList(t("{2764}"), UP), recents.emoji());
        recents.usedSticker("pl1/gg");
        recents.usedSticker("pl1/hi");
        recents.usedSticker("pl1/gg");
        assertEquals(Arrays.asList("pl1/gg", "pl1/hi"), recents.stickers());
        for (int i = 0; i < 12; i++) recents.usedSticker("pl1/s" + i);
        assertEquals(EmojiRecents.RECENT_STICKERS, recents.stickers().size());
    }

    @Test
    public void givesSnapshotsThatAPickDoesNotChange() {
        EmojiRecents recents = new EmojiRecents();
        recents.usedEmoji(JOY);
        List<String> opened = recents.emoji();
        recents.usedEmoji(FIRE);
        assertEquals(Arrays.asList(JOY), opened);
        assertEquals(Arrays.asList(FIRE, JOY), recents.emoji());
        try {
            opened.add(UP);
            throw new AssertionError("a snapshot is read only");
        } catch (UnsupportedOperationException expected) {
            // read only
        }
    }

    @Test
    public void storesItselfAsOneAsciiStringAndReadsItBack() {
        EmojiRecents recents = new EmojiRecents();
        recents.usedEmoji(JOY);
        recents.usedEmoji(t("{1F3F3}{FE0F}{200D}{1F308}"));
        recents.usedSticker("pl1/gg");
        recents.setTone(3);
        String stored = recents.toStored();
        for (int i = 0; i < stored.length(); i++) assertTrue(stored, stored.charAt(i) < 0x7F);
        EmojiRecents back = EmojiRecents.fromStored(stored);
        assertEquals(recents.emoji(), back.emoji());
        assertEquals(Arrays.asList(t("{1F3F3}{200D}{1F308}"), JOY), back.emoji());
        assertEquals(Arrays.asList("pl1/gg"), back.stickers());
        assertEquals(3, back.tone());
    }

    @Test
    public void neverTrustsWhatWasStored() {
        for (String broken : new String[] {null, "", "{", "[]", "42", "{\"tone\":9}", "{\"tone\":\"2\"}",
                "{\"tone\":2.5,\"recentEmoji\":\"x\",\"recentStickers\":[1,2]}"}) {
            EmojiRecents recents = EmojiRecents.fromStored(broken);
            assertEquals(broken, 0, recents.tone());
            assertEquals(broken, Collections.emptyList(), recents.emoji());
            assertEquals(broken, Collections.emptyList(), recents.stickers());
        }
        EmojiRecents some = EmojiRecents.fromStored("{\"recentEmoji\":[\"a\",\"a\",7,\"b\"],\"tone\":5}");
        assertEquals(Arrays.asList("a", "b"), some.emoji());
        assertEquals(5, some.tone());
    }
}
