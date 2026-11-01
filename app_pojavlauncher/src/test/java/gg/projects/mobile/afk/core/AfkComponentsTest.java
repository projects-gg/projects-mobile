package gg.projects.mobile.afk.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import gg.projects.mobile.afk.api.AfkLine;

/** The game's text as the console shows it: colours, styles, § codes, vanilla keys in Turkish and English. */
public class AfkComponentsTest {
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static AfkLine.Span span(AfkText t, int i) {
        return t.spans.get(i);
    }

    @Test
    public void plainTextColoursAndStyles() {
        AfkText t = AfkComponents.render(map("text", "Merhaba ", "color", "gold", "extra",
                Arrays.asList(map("text", "dünya", "bold", (byte) 1, "color", "#12ab9F"), "!")), false);
        assertEquals("Merhaba dünya!", t.text);
        assertEquals(3, t.spans.size());
        assertEquals(0xFFAA00, span(t, 0).color);
        assertEquals(0, span(t, 0).start);
        assertEquals(8, span(t, 0).end);
        assertEquals(0x12AB9F, span(t, 1).color);
        assertEquals(AfkLine.BOLD, span(t, 1).style);
        // "!" inherits the parent's gold, not the sibling's bold.
        assertEquals(0xFFAA00, span(t, 2).color);
        assertEquals(0, span(t, 2).style);
    }

    @Test
    public void legacyCodesInsideTextAndHex() {
        AfkText t = AfkComponents.render("§cKırmızı §lkalın§r düz §x§1§2§3§4§5§6hex §kgizli", false);
        assertEquals("Kırmızı kalın düz hex gizli", t.text);
        assertEquals(0xFF5555, span(t, 0).color);
        assertEquals("Kırmızı ", t.text.substring(span(t, 0).start, span(t, 0).end));
        assertEquals(AfkLine.BOLD, span(t, 1).style);
        assertEquals(0x123456, span(t, 2).color);
        assertTrue((span(t, 3).style & AfkLine.OBFUSCATED) != 0);
        assertEquals("gizli", t.text.substring(span(t, 3).start, span(t, 3).end));
    }

    @Test
    public void vanillaKeysInBothLanguagesWithTheirArguments() {
        Object joined = map("translate", "multiplayer.player.joined", "with", Collections.singletonList(
                map("text", "Ember_77", "color", "yellow")));
        assertEquals("Ember_77 joined the game", AfkComponents.render(joined, false).text);
        AfkText tr = AfkComponents.render(joined, true);
        assertEquals("Ember_77 oyuna katıldı", tr.text);
        assertEquals(0xFFFF55, tr.spans.get(0).color);
        Object chat = map("translate", "chat.type.text", "with", Arrays.asList("Night_Owl", "selam %s 100%"));
        assertEquals("<Night_Owl> selam %s 100%", AfkComponents.render(chat, false).text);
        Object positional = map("translate", "death.attack.mob", "with", Arrays.asList("A_b", "Zombie"));
        assertEquals("A_b, Zombie tarafından öldürüldü", AfkComponents.render(positional, true).text);
        // Unknown: its fallback, a death's generic sentence, else the key as the game shows it.
        assertEquals("Özel", AfkComponents.render(map("translate", "my.key", "fallback", "Özel"), true).text);
        assertEquals("A_b öldü", AfkComponents.render(map("translate", "death.attack.sonicBoom.player",
                "with", Arrays.asList("A_b", "Warden")), true).text);
        assertEquals("plugin.only", AfkComponents.render(map("translate", "plugin.only"), false).text);
        assertEquals(Arrays.asList("chat.type.text"), AfkComponents.keys(chat));
    }

    @Test
    public void listsWrappedListsAndJson() {
        // A list: the first is the parent of the rest; NBT wraps mixed lists in {"": ...}.
        List<Object> list = Arrays.<Object>asList(map("text", "A", "italic", true), map("", "B"), "C");
        AfkText t = AfkComponents.render(list, false);
        assertEquals("ABC", t.text);
        assertEquals(1, t.spans.size());
        assertEquals(AfkLine.ITALIC, t.spans.get(0).style);
        Object json = AfkComponents.fromJson(JsonParser.parseString(
                "{\"translate\":\"multiplayer.disconnect.banned.reason\",\"with\":[\"hile\"]}"));
        assertEquals("You are banned from this server. Reason: hile", AfkComponents.english(json));
        assertEquals(Collections.singletonList("multiplayer.disconnect.banned.reason"), AfkComponents.keys(json));
        assertEquals("Bu sunucudan yasaklandın. Sebep: hile", AfkComponents.render(json, true).text);
    }

    @Test
    public void longTextIsCut() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 5000; i++) b.append('x');
        assertEquals(AfkComponents.MAX_CHARS, AfkComponents.render(b.toString(), false).text.length());
        AfkText capped = AfkText.plain("abcdef").capped(4);
        assertEquals("abc…", capped.text);
    }
}
