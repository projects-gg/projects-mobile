package gg.projects.mobile.cloud.inventory;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where everything sits, in GUI pixels (the game's units: a slot is 18, an item 16), as the launcher's
 * {@code @pl/ui/inventory} lays it out (layout.ts; docs/design.md §6 "Cloud inventory"): the game's container screens
 * (chests 9 × N, hopper, 3 × 3, shulker box, rows of 9 for anything else) and, without a window, the player's own screen
 * as tall as the hopper's: "Ekipman" as its title, head, chest, legs and feet from the grid's left edge and the off hand
 * a group's gap after them, then the inventory as under any window.
 */
public final class InvLayout {
    private InvLayout() {}

    public static final int SLOT = 18;
    public static final int PANEL_WIDTH = 176;
    public static final int ARMOR_FIRST = 5;
    public static final int MAIN_FIRST = 9;
    public static final int HOTBAR_FIRST = 36;
    public static final int OFFHAND_SLOT = 45;
    private static final int GROUP_GAP = 4;

    public enum Area { INV, WIN }

    public enum Kind { WINDOW, MAIN, HOTBAR, HEAD, CHEST, LEGS, FEET, OFFHAND }

    public enum ScreenKind { PLAYER, GENERIC, HOPPER, DISPENSER, SHULKER, SINGLE }

    /** One slot: its area and number, and the item's top-left (the slot square is one pixel out, 18 wide). */
    public static final class Spot {
        public final Area area;
        public final int slot;
        public final Kind kind;
        public final int x;
        public final int y;

        Spot(Area area, int slot, Kind kind, int x, int y) {
            this.area = area;
            this.slot = slot;
            this.kind = kind;
            this.x = x;
            this.y = y;
        }

        public String key() {
            return key(area, slot);
        }

        public boolean armor() {
            return kind == Kind.HEAD || kind == Kind.CHEST || kind == Kind.LEGS || kind == Kind.FEET;
        }

        public static String key(Area area, int slot) {
            return (area == Area.INV ? "inv:" : "win:") + slot;
        }
    }

    public static final class Screen {
        public final ScreenKind kind;
        public final int width;
        public final int height;
        /** The title's pen (x, y) and whether it is centred on the panel; null for none. */
        @Nullable
        public final int[] title;
        public final boolean titleCentered;
        /** The "Envanter" label above the player's rows; null for none. */
        @Nullable
        public final int[] inventoryLabel;
        public final List<Spot> slots;
        public final int windowRows;

        Screen(ScreenKind kind, int width, int height, @Nullable int[] title, boolean titleCentered, @Nullable int[] inventoryLabel,
               List<Spot> slots, int windowRows) {
            this.kind = kind;
            this.width = width;
            this.height = height;
            this.title = title;
            this.titleCentered = titleCentered;
            this.inventoryLabel = inventoryLabel;
            this.slots = Collections.unmodifiableList(slots);
            this.windowRows = windowRows;
        }

        @Nullable
        public Spot spot(String key) {
            for (Spot s : slots) if (s.key().equals(key)) return s;
            return null;
        }

        @Nullable
        public Spot spot(Area area, int slot) {
            for (Spot s : slots) if (s.area == area && s.slot == slot) return s;
            return null;
        }

        /** The slot under a GUI pixel (its 18-pixel square), or null. */
        @Nullable
        public Spot at(float gx, float gy) {
            for (Spot s : slots) {
                if (gx >= s.x - 1 && gx < s.x + 17 && gy >= s.y - 1 && gy < s.y + 17) return s;
            }
            return null;
        }
    }

    private static Spot spot(Area area, int slot, Kind kind, int x, int y) {
        return new Spot(area, slot, kind, x, y);
    }

    private static List<Spot> playerRows(int mainTop, int hotbarTop) {
        List<Spot> slots = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                slots.add(spot(Area.INV, MAIN_FIRST + row * 9 + col, Kind.MAIN, 8 + col * SLOT, mainTop + row * SLOT));
            }
        }
        for (int col = 0; col < 9; col++) slots.add(spot(Area.INV, HOTBAR_FIRST + col, Kind.HOTBAR, 8 + col * SLOT, hotbarTop));
        return slots;
    }

    private static final Pattern GENERIC = Pattern.compile("^generic_9x([1-6])$");

    /** The game's rows for a generic window: its type's N, else enough rows of 9 for its size. */
    public static int windowRows(@Nullable String type, int size) {
        Matcher m = GENERIC.matcher(type == null ? "" : type);
        if (m.matches()) return Integer.parseInt(m.group(1));
        return Math.max(1, Math.min(6, (int) Math.ceil(Math.max(1, size) / 9.0)));
    }

    private static Screen player() {
        List<Spot> slots = new ArrayList<>();
        Kind[] armor = {Kind.HEAD, Kind.CHEST, Kind.LEGS, Kind.FEET};
        for (int i = 0; i < 4; i++) slots.add(spot(Area.INV, ARMOR_FIRST + i, armor[i], 8 + i * SLOT, 20));
        slots.add(spot(Area.INV, OFFHAND_SLOT, Kind.OFFHAND, 8 + 4 * SLOT + GROUP_GAP, 20));
        slots.addAll(playerRows(51, 109));
        return new Screen(ScreenKind.PLAYER, PANEL_WIDTH, 133, new int[]{8, 6}, false, new int[]{8, 39}, slots, 0);
    }

    /** A screen for the window the server opened, with the player's rows under it; {@code type} null: the player's own. */
    public static Screen screenFor(@Nullable String type, int sizeIn) {
        if (type == null) return player();
        int size = Math.max(0, Math.min(54, sizeIn));
        if ("lectern".equals(type)) {
            List<Spot> slots = new ArrayList<>();
            int n = Math.max(1, size);
            for (int i = 0; i < n; i++) slots.add(spot(Area.WIN, i, Kind.WINDOW, 8 + (i % 9) * SLOT, 18 + (i / 9) * SLOT));
            int rows = (int) Math.ceil(n / 9.0);
            return new Screen(ScreenKind.SINGLE, PANEL_WIDTH, 18 + rows * SLOT + 7, new int[]{8, 6}, false, null, slots, rows);
        }
        if ("hopper".equals(type)) {
            List<Spot> slots = new ArrayList<>();
            int n = Math.min(5, size == 0 ? 5 : size);
            for (int i = 0; i < n; i++) slots.add(spot(Area.WIN, i, Kind.WINDOW, 44 + i * SLOT, 20));
            slots.addAll(playerRows(51, 109));
            return new Screen(ScreenKind.HOPPER, PANEL_WIDTH, 133, new int[]{8, 6}, false, new int[]{8, 39}, slots, 1);
        }
        if ("generic_3x3".equals(type) || "crafter_3x3".equals(type)) {
            List<Spot> slots = new ArrayList<>();
            int n = Math.min(9, size == 0 ? 9 : size);
            for (int i = 0; i < n; i++) slots.add(spot(Area.WIN, i, Kind.WINDOW, 62 + (i % 3) * SLOT, 17 + (i / 3) * SLOT));
            slots.addAll(playerRows(84, 142));
            return new Screen(ScreenKind.DISPENSER, PANEL_WIDTH, 166, new int[]{0, 6}, true, new int[]{8, 72}, slots, 3);
        }
        if ("shulker_box".equals(type)) {
            List<Spot> slots = new ArrayList<>();
            int n = Math.min(27, size == 0 ? 27 : size);
            for (int i = 0; i < n; i++) slots.add(spot(Area.WIN, i, Kind.WINDOW, 8 + (i % 9) * SLOT, 18 + (i / 9) * SLOT));
            slots.addAll(playerRows(84, 142));
            return new Screen(ScreenKind.SHULKER, PANEL_WIDTH, 167, new int[]{8, 6}, false, new int[]{8, 73}, slots, 3);
        }
        // generic_9xN, and any other window as rows of 9 (the contract's fallback).
        int rows = windowRows(type, size);
        int height = 114 + rows * SLOT;
        int count = GENERIC.matcher(type).matches() ? rows * 9 : Math.min(size, rows * 9);
        List<Spot> slots = new ArrayList<>();
        for (int i = 0; i < count; i++) slots.add(spot(Area.WIN, i, Kind.WINDOW, 8 + (i % 9) * SLOT, 18 + (i / 9) * SLOT));
        slots.addAll(playerRows(height - 83, height - 25));
        return new Screen(ScreenKind.GENERIC, PANEL_WIDTH, height, new int[]{8, 6}, false, new int[]{8, height - 94}, slots, rows);
    }

    /** Whether moves may name this slot while the window is open (armour and the off hand may not). */
    public static boolean slotUsable(Screen screen, Spot spot) {
        if (spot.area == Area.WIN) return true;
        if (screen.kind == ScreenKind.PLAYER) return true;
        if (screen.kind == ScreenKind.SINGLE) return false;
        return spot.kind == Kind.MAIN || spot.kind == Kind.HOTBAR;
    }

    /**
     * The GUI pixel's size in device pixels for {@code widthPx} of room: the largest whole number that fits, at least 1
     * (docs/design.md §6: the game's screen on whole device pixels). Item drawings are made for it (ItemAtlas).
     */
    public static int guiScale(int widthPx) {
        return Math.max(1, widthPx / PANEL_WIDTH);
    }
}
