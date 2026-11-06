package gg.projects.mobile.controls;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * M053 (MOB-0410's P3): the controls editor's key lists in words a player reads ("Sol Shift", "Sağ tık", "Boşluk")
 * instead of Android's code names ("SHIFT_LEFT", "SPECIAL_SEC", "SPACE"). Only what the panel shows changes: the
 * lists keep upstream's order and the layout file its key codes.
 */
public final class KeyNames {
    private KeyNames() {}

    /** Code name → {Turkish, English}. */
    private static final Map<String, String[]> NAMES = new HashMap<>();

    private static void put(String code, String tr, String en) {
        NAMES.put(code, new String[]{tr, en});
    }

    static {
        // The launcher's own actions (ControlData.buildSpecialButtonArray: "SPECIAL_" + the button's name).
        put("SPECIAL_Keyboard", "Klavye", "Keyboard");
        put("SPECIAL_GUI", "Tuşları gizle", "Hide keys");
        put("SPECIAL_PRI", "Sol tık", "Left click");
        put("SPECIAL_SEC", "Sağ tık", "Right click");
        put("SPECIAL_MID", "Orta tık", "Middle click");
        put("SPECIAL_Mouse", "Fare", "Mouse");
        put("SPECIAL_SCROLLUP", "Tekerlek yukarı", "Scroll up");
        put("SPECIAL_SCROLLDOWN", "Tekerlek aşağı", "Scroll down");
        put("SPECIAL_MENU", "Menü", "Menu");
        put("SPECIAL_BCK", "Fare geri", "Mouse back");
        put("SPECIAL_FWD", "Fare ileri", "Mouse forward");
        // Keyboard keys (EfficientAndroidLWJGLKeycode, Android's names without "KEYCODE_").
        put("UNKNOWN", "Yok", "None");
        put("BACK", "Geri", "Back");
        put("POUND", "#", "#");
        put("DPAD_UP", "Yukarı ok", "Up arrow");
        put("DPAD_DOWN", "Aşağı ok", "Down arrow");
        put("DPAD_LEFT", "Sol ok", "Left arrow");
        put("DPAD_RIGHT", "Sağ ok", "Right arrow");
        put("COMMA", ",", ",");
        put("PERIOD", ".", ".");
        put("ALT_LEFT", "Sol Alt", "Left Alt");
        put("ALT_RIGHT", "Sağ Alt", "Right Alt");
        put("SHIFT_LEFT", "Sol Shift", "Left Shift");
        put("SHIFT_RIGHT", "Sağ Shift", "Right Shift");
        put("CTRL_LEFT", "Sol Ctrl", "Left Ctrl");
        put("CTRL_RIGHT", "Sağ Ctrl", "Right Ctrl");
        put("SPACE", "Boşluk", "Space");
        put("TAB", "Tab", "Tab");
        put("DEL", "Geri sil", "Backspace");
        put("FORWARD_DEL", "Delete", "Delete");
        put("GRAVE", "`", "`");
        put("MINUS", "-", "-");
        put("EQUALS", "=", "=");
        put("LEFT_BRACKET", "[", "[");
        put("RIGHT_BRACKET", "]", "]");
        put("BACKSLASH", "\\", "\\");
        put("SEMICOLON", ";", ";");
        put("APOSTROPHE", "'", "'");
        put("SLASH", "/", "/");
        put("AT", "@", "@");
        put("PLUS", "+", "+");
        put("ESCAPE", "Esc", "Esc");
        put("BREAK", "Pause", "Pause");
        put("MOVE_HOME", "Home", "Home");
        put("MOVE_END", "End", "End");
        put("NUMPAD_DIVIDE", "Num /", "Num /");
        put("NUMPAD_MULTIPLY", "Num *", "Num *");
        put("NUMPAD_SUBTRACT", "Num -", "Num -");
        put("NUMPAD_ADD", "Num +", "Num +");
        put("NUMPAD_DOT", "Num .", "Num .");
        put("NUMPAD_COMMA", "Num ,", "Num ,");
        put("NUMPAD_ENTER", "Num Enter", "Num Enter");
        put("NUMPAD_EQUALS", "Num =", "Num =");
    }

    /** The name a player reads for a code name of the editor's lists; unknown ones as words ("PAGE_UP" → "Page Up"). */
    public static String of(String code, boolean turkish) {
        if (code == null || code.isEmpty()) return "";
        String[] names = NAMES.get(code);
        if (names != null) return names[turkish ? 0 : 1];
        if (code.startsWith("NUMPAD_")) return "Num " + words(code.substring(7));
        if (code.startsWith("SPECIAL_")) return words(code.substring(8));
        return words(code);
    }

    /** "CAPS_LOCK" → "Caps Lock"; single letters, digits and "F1" stay as they are. */
    static String words(String code) {
        if (code.length() <= 3) return code;
        StringBuilder out = new StringBuilder();
        for (String part : code.split("_")) {
            if (part.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(part.charAt(0)).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }
}
