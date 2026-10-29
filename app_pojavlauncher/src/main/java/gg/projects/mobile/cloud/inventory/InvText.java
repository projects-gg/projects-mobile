package gg.projects.mobile.cloud.inventory;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Text that reaches the inventory from anyone (item names, lore, window titles) and the game-styled lines built from it,
 * as the launcher's text.ts: plain text only, never markup, never styled as the app's own interface (a menu titled
 * "Projects Güvenlik" must not look like ours). Item names come in the app's language from the frame's labels.
 */
public final class InvText {
    private InvText() {}

    /** The words the lines are built from (the app's strings; tests pass their own). */
    public interface Words {
        /** "Dayanıklılık: {left} / {max}". */
        String durability(int left, int max);

        /** "{item} x {n}". */
        String insideStack(String item, int n);

        /** "ve {n} tane daha...". */
        String insideMore(int n);

        /** "ve daha fazlası...". */
        String insideMoreUnknown();

        /** "Bazı özellikleri gösterilemiyor.". */
        String partial();

        /** "{n} adet". */
        String count(int n);

        /** "dayanıklılık yüzde {p}". */
        String durabilityPercent(int p);

        /** "büyülü". */
        String enchanted();
    }

    private static boolean isControl(int cp) {
        return cp <= 0x1f || (cp >= 0x7f && cp <= 0x9f) || cp == 0x61c || cp == 0x200e || cp == 0x200f
                || (cp >= 0x202a && cp <= 0x202e) || (cp >= 0x2066 && cp <= 0x2069);
    }

    /** A resource pack's private-use glyphs (ItemsAdder's menu pictures and spacers, icons in lore). */
    public static boolean isPrivateUse(int cp) {
        return (cp >= 0xe000 && cp <= 0xf8ff) || (cp >= 0xf0000 && cp <= 0x10ffff);
    }

    private static final String LEGACY_FORMAT = "0123456789abcdefklmnorx";

    /** The code points of a text without controls and the game's legacy formatting codes ("§a"). */
    private static int[] plain(String text) {
        List<Integer> out = new ArrayList<>();
        boolean skipNext = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (skipNext) {
                skipNext = false;
                if (cp < 0x80 && LEGACY_FORMAT.indexOf(Character.toLowerCase((char) cp)) >= 0) continue;
            }
            if (cp == 0xa7) {
                skipNext = true;
                continue;
            }
            if (isControl(cp)) continue;
            out.add(cp);
        }
        int[] a = new int[out.size()];
        for (int i = 0; i < a.length; i++) a[i] = out.get(i);
        return a;
    }

    /**
     * Anyone's text as the view shows it: no controls, direction marks, formatting codes or private-use glyphs, runs of
     * space folded. The machine already masks and cuts it; this is the view's own guard.
     */
    public static String cleanText(@Nullable String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int cp : plain(text)) if (!isPrivateUse(cp)) out.appendCodePoint(cp);
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    /** "minecraft:diamond_sword" → "diamond_sword". */
    public static String bareId(String id) {
        int colon = id.indexOf(':');
        return (colon < 0 ? id : id.substring(colon + 1)).toLowerCase(Locale.ROOT);
    }

    private static String labelOf(InvItem.Label label, String lang) {
        String preferred = "en".equals(lang) ? label.en : label.tr;
        String text = preferred.isEmpty() ? (label.tr.isEmpty() ? label.en : label.tr) : preferred;
        return cleanText(text);
    }

    /** The name the game shows: the custom name, else the item type's, else the id's own words. */
    public static String itemName(InvItem item, String lang) {
        String custom = item.name == null ? "" : cleanText(item.name);
        if (!custom.isEmpty()) return custom;
        String label = labelOf(item.label, lang);
        return label.isEmpty() ? bareId(item.id).replace('_', ' ') : label;
    }

    public enum Rarity { COMMON, UNCOMMON, RARE, EPIC }

    private static final Set<String> EPIC = new HashSet<>(Arrays.asList("enchanted_golden_apple", "dragon_egg", "dragon_head",
            "elytra", "mace", "heavy_core", "command_block", "chain_command_block", "repeating_command_block", "command_block_minecart",
            "structure_block", "jigsaw", "debug_stick", "knowledge_book"));
    private static final Set<String> RARE = new HashSet<>(Arrays.asList("beacon", "conduit", "end_crystal", "golden_apple", "trident",
            "nether_star", "ominous_trial_key"));
    private static final Set<String> UNCOMMON = new HashSet<>(Arrays.asList("experience_bottle", "dragon_breath", "enchanted_book",
            "player_head", "zombie_head", "creeper_head", "skeleton_skull", "wither_skeleton_skull", "piglin_head", "heart_of_the_sea",
            "totem_of_undying", "ominous_bottle", "trial_key", "sniffer_egg", "wind_charge", "breeze_rod"));

    private static Rarity baseRarity(String path) {
        if (EPIC.contains(path)) return Rarity.EPIC;
        if (RARE.contains(path) || path.startsWith("music_disc_")) return Rarity.RARE;
        if (UNCOMMON.contains(path) || path.endsWith("_armor_trim_smithing_template")) return Rarity.UNCOMMON;
        return Rarity.COMMON;
    }

    /** The name's colour class: an enchanted common or uncommon item reads rare, a rare one epic. */
    public static Rarity rarityOf(InvItem item) {
        String path = bareId(item.id);
        Rarity rarity = baseRarity(path);
        if (!item.glints() || path.equals("enchanted_book")) return rarity;
        return rarity == Rarity.RARE || rarity == Rarity.EPIC ? Rarity.EPIC : Rarity.RARE;
    }

    /** The game's text colours (0xRRGGBB). Lore's dark purple is lifted to read on the tooltip. */
    public static final int WHITE = 0xffffff;
    public static final int YELLOW = 0xffff55;
    public static final int AQUA = 0x55ffff;
    public static final int LIGHT_PURPLE = 0xff55ff;
    public static final int GRAY = 0xaaaaaa;
    public static final int RED = 0xff5555;
    public static final int LORE = 0xbc55bc;
    public static final int TITLE = 0x404040;

    public static int rarityColor(Rarity rarity) {
        switch (rarity) {
            case UNCOMMON: return YELLOW;
            case RARE: return AQUA;
            case EPIC: return LIGHT_PURPLE;
            default: return WHITE;
        }
    }

    /** The game's drop shadow for a text colour: a quarter of it. */
    public static int shadowOf(int rgb) {
        return (rgb & 0xfcfcfc) >> 2;
    }

    private static final Set<String> CURSES = new HashSet<>(Arrays.asList("binding_curse", "vanishing_curse"));

    /** One line of a tooltip: its words, colour and the game's italic. */
    public static final class Line {
        public final String text;
        public final int color;
        public final boolean italic;

        public Line(String text, int color, boolean italic) {
            this.text = text;
            this.color = color;
            this.italic = italic;
        }
    }

    /**
     * The lines of an item's tooltip, top to bottom, in the game's order (a container's contents, enchantments, lore, the
     * wear line last) and colours.
     */
    public static List<Line> tooltip(InvItem item, String lang, Words words) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(itemName(item, lang), rarityColor(rarityOf(item)), false));
        InvItem.Inside inside = item.inside;
        if (inside != null && !inside.top.isEmpty()) {
            int shown = Math.min(5, inside.top.size());
            int listed = 0;
            for (InvItem stack : inside.top) listed += stack.count;
            for (int i = 0; i < shown; i++) {
                InvItem stack = inside.top.get(i);
                lines.add(new Line(words.insideStack(labelOf(stack.label, lang).isEmpty() ? itemName(stack, lang) : labelOf(stack.label, lang),
                        stack.count), WHITE, false));
            }
            if (inside.stacks != null) {
                // CAFK-SVC: the machine says how many stacks it holds: "ve N tane daha" is exact.
                int more = inside.stacks - shown;
                if (more > 0) lines.add(new Line(words.insideMore(more), WHITE, true));
            } else if (listed < inside.count) {
                lines.add(new Line(words.insideMoreUnknown(), WHITE, true));
            } else if (inside.top.size() > shown) {
                lines.add(new Line(words.insideMore(inside.top.size() - shown), WHITE, true));
            }
        }
        for (InvItem.Ench e : item.ench) {
            String text = labelOf(e.label, lang);
            if (!text.isEmpty()) lines.add(new Line(text, CURSES.contains(bareId(e.id)) ? RED : GRAY, false));
        }
        for (String l : item.lore) {
            String text = cleanText(l);
            if (!text.isEmpty()) lines.add(new Line(text, LORE, false));
        }
        if (item.wears() && item.durabilityLeft < item.durabilityMax) {
            lines.add(new Line(words.durability(item.durabilityLeft, item.durabilityMax), WHITE, false));
        }
        if (item.partial) lines.add(new Line(words.partial(), GRAY, false));
        return lines;
    }

    /** Whole percent of durability left, or -1 for items that do not wear. */
    public static int durabilityPercent(InvItem item) {
        if (!item.wears()) return -1;
        return Math.max(0, Math.min(100, Math.round(item.durabilityLeft * 100f / item.durabilityMax)));
    }

    /** What a screen reader says for an item: name, count, wear, enchantment ("Elmas Kılıç, 1 adet, …" without the 1). */
    public static String speech(InvItem item, String lang, Words words) {
        StringBuilder out = new StringBuilder(itemName(item, lang));
        if (item.count > 1) out.append(", ").append(words.count(item.count));
        int percent = durabilityPercent(item);
        if (percent >= 0 && item.durabilityLeft < item.durabilityMax) out.append(", ").append(words.durabilityPercent(percent));
        if (item.glints()) out.append(", ").append(words.enchanted());
        return out.toString();
    }

    /** The game's wear bar width (of 13 pixels): Math.round(13 - damage * 13 / max). */
    public static int durabilityWidth(int left, int max) {
        if (max <= 0) return 0;
        return Math.max(0, Math.min(13, Math.round(13f - ((max - left) * 13f) / max)));
    }

    /** The game's wear colour, red to green by what is left: Mth.hsvToRgb(fraction / 3, 1, 1) (0xRRGGBB). */
    public static int durabilityColor(int left, int max) {
        float fraction = max > 0 ? Math.max(0f, Math.min(1f, left / (float) max)) : 0f;
        float scaled = (fraction / 3f) * 6f;
        int sector = ((int) Math.floor(scaled)) % 6;
        float f = scaled - (float) Math.floor(scaled);
        int rise = (int) (f * 255);
        int fall = (int) ((1 - f) * 255);
        int r;
        int g;
        int b;
        switch (sector) {
            case 0: r = 255; g = rise; b = 0; break;
            case 1: r = fall; g = 255; b = 0; break;
            case 2: r = 0; g = 255; b = rise; break;
            case 3: r = 0; g = fall; b = 255; break;
            case 4: r = rise; g = 0; b = 255; break;
            default: r = 255; g = 0; b = fall; break;
        }
        return (r << 16) | (g << 8) | b;
    }

    /** A window title as the view shows it: without private-use glyphs (no server art on the phone), cleaned. */
    public static String title(@Nullable String title) {
        return cleanText(title);
    }
}
