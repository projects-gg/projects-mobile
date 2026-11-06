package gg.projects.mobile.controls;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** M053: the key panel names keys as a player reads them, in both languages. */
public class KeyNamesTest {
    @Test
    public void namedKeysInBothLanguages() {
        assertEquals("Sol Shift", KeyNames.of("SHIFT_LEFT", true));
        assertEquals("Left Shift", KeyNames.of("SHIFT_LEFT", false));
        assertEquals("Yok", KeyNames.of("UNKNOWN", true));
        assertEquals("None", KeyNames.of("UNKNOWN", false));
        assertEquals("Sağ tık", KeyNames.of("SPECIAL_SEC", true));
        assertEquals("Boşluk", KeyNames.of("SPACE", true));
        assertEquals("Geri sil", KeyNames.of("DEL", true));
    }

    @Test
    public void othersBecomeWords() {
        assertEquals("Page Up", KeyNames.of("PAGE_UP", true));
        assertEquals("Caps Lock", KeyNames.of("CAPS_LOCK", false));
        assertEquals("Num 7", KeyNames.of("NUMPAD_7", true));
        assertEquals("A", KeyNames.of("A", true));
        assertEquals("F12", KeyNames.of("F12", false));
        assertEquals("Tab", KeyNames.of("TAB", true));
        assertEquals("Enter", KeyNames.of("ENTER", true));
    }

    @Test
    public void noRawCodeNameIsLeft() {
        String[] codes = {"HOME", "BACK", "POUND", "DPAD_UP", "COMMA", "ALT_LEFT", "TAB", "SPACE", "ENTER", "DEL", "GRAVE",
                "MINUS", "LEFT_BRACKET", "BACKSLASH", "SEMICOLON", "APOSTROPHE", "SLASH", "AT", "PLUS", "PAGE_DOWN", "ESCAPE",
                "CTRL_RIGHT", "CAPS_LOCK", "BREAK", "MOVE_HOME", "MOVE_END", "INSERT", "NUM_LOCK", "NUMPAD_DIVIDE",
                "NUMPAD_ENTER", "SPECIAL_Keyboard", "SPECIAL_GUI", "SPECIAL_PRI", "SPECIAL_Mouse", "SPECIAL_MID",
                "SPECIAL_SCROLLUP", "SPECIAL_SCROLLDOWN", "SPECIAL_MENU", "SPECIAL_BCK", "SPECIAL_FWD"};
        for (String code : codes) {
            for (boolean tr : new boolean[]{true, false}) {
                String name = KeyNames.of(code, tr);
                if (name.contains("_") || (name.length() > 3 && name.equals(name.toUpperCase(java.util.Locale.ROOT))
                        && !name.startsWith("Num"))) {
                    throw new AssertionError(code + " -> " + name);
                }
            }
        }
    }
}
