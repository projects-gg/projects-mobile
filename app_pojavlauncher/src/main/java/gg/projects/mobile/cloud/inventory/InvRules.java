package gg.projects.mobile.cloud.inventory;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The game's click rules for the player's own screen (AbstractContainerMenu.doClick, InventoryMenu.quickMoveStack), as
 * the launcher's {@code @pl/ui/inventory} rules.ts has them, used to show a move at once while the machine carries it
 * out. Only the player's screen is predicted: a server's menu decides its clicks itself (a shop takes the click and
 * sells), so a click there waits for the next frame instead.
 */
public final class InvRules {
    private InvRules() {}

    /** The player inventory as the view holds it: slots 5–45, the held hotbar slot, the cursor's item. */
    public static final class State {
        public final int held;
        @Nullable
        public final InvItem cursor;
        /** By slot; immutable. */
        public final Map<Integer, InvItem> slots;

        public State(int held, @Nullable InvItem cursor, Map<Integer, InvItem> slots) {
            this.held = held;
            this.cursor = cursor != null && cursor.count > 0 ? cursor : null;
            this.slots = Collections.unmodifiableMap(new TreeMap<>(slots));
        }

        public static State of(InvFrames.Inv frame) {
            Map<Integer, InvItem> slots = new TreeMap<>();
            for (InvItem item : frame.items) if (item.count > 0) slots.put(item.slot, item);
            return new State(frame.held, frame.cursor, slots);
        }

        public static State empty() {
            return new State(0, null, Collections.<Integer, InvItem>emptyMap());
        }

        @Nullable
        public InvItem at(int slot) {
            return slots.get(slot);
        }

        Mutable edit() {
            return new Mutable(held, cursor, new TreeMap<>(slots));
        }
    }

    static final class Mutable {
        int held;
        @Nullable
        InvItem cursor;
        final TreeMap<Integer, InvItem> slots;

        Mutable(int held, @Nullable InvItem cursor, TreeMap<Integer, InvItem> slots) {
            this.held = held;
            this.cursor = cursor;
            this.slots = slots;
        }

        void put(int slot, @Nullable InvItem stack) {
            if (stack != null && stack.count > 0) slots.put(slot, stack.withSlot(slot));
            else slots.remove(slot);
        }

        State done() {
            return new State(held, cursor, slots);
        }
    }

    private static final Set<String> ONE = new HashSet<>(Arrays.asList("potion", "splash_potion", "lingering_potion",
            "enchanted_book", "written_book", "writable_book", "cake", "saddle", "minecart", "totem_of_undying", "elytra", "shield",
            "bow", "crossbow", "trident", "mace", "fishing_rod", "carrot_on_a_stick", "warped_fungus_on_a_stick", "flint_and_steel",
            "shears", "brush", "spyglass", "bundle", "goat_horn", "knowledge_book", "debug_stick", "mushroom_stew", "rabbit_stew",
            "beetroot_soup", "suspicious_stew", "shulker_box"));
    private static final String[] ONE_SUFFIX = {"_sword", "_pickaxe", "_axe", "_shovel", "_hoe", "_spear", "_helmet", "_chestplate",
            "_leggings", "_boots", "_horse_armor", "_shulker_box", "_bed", "_boat", "_raft", "_minecart", "_bundle", "_banner_pattern"};
    private static final Set<String> SIXTEEN = new HashSet<>(Arrays.asList("ender_pearl", "snowball", "egg", "blue_egg", "brown_egg",
            "bucket", "honey_bottle", "armor_stand"));
    private static final String[] SIXTEEN_SUFFIX = {"_sign", "_hanging_sign", "_banner"};

    private static boolean endsWithAny(String path, String[] suffixes) {
        for (String s : suffixes) if (path.endsWith(s)) return true;
        return false;
    }

    /** How many of an item stack, as the game counts it for the common items (else 64); what is there already is possible. */
    public static int maxStack(InvItem item) {
        String path = InvText.bareId(item.id);
        int max = 64;
        if (item.wears() || ONE.contains(path) || path.startsWith("music_disc_")) max = 1;
        else if (path.endsWith("_bucket")) max = 1;
        else if (endsWithAny(path, ONE_SUFFIX)) max = 1;
        else if (SIXTEEN.contains(path) || endsWithAny(path, SIXTEEN_SUFFIX)) max = 16;
        return Math.max(max, item.count);
    }

    public enum Armor { HEAD, CHEST, LEGS, FEET }

    private static final Set<String> HEADWEAR = new HashSet<>(Arrays.asList("turtle_helmet", "carved_pumpkin", "player_head",
            "zombie_head", "creeper_head", "skeleton_skull", "wither_skeleton_skull", "piglin_head", "dragon_head"));

    @Nullable
    public static Armor armorPart(InvItem item) {
        String path = InvText.bareId(item.id);
        if (path.endsWith("_helmet") || HEADWEAR.contains(path)) return Armor.HEAD;
        if (path.endsWith("_chestplate") || path.equals("elytra")) return Armor.CHEST;
        if (path.endsWith("_leggings")) return Armor.LEGS;
        if (path.endsWith("_boots")) return Armor.FEET;
        return null;
    }

    private static int armorSlot(Armor part) {
        return InvLayout.ARMOR_FIRST + part.ordinal();
    }

    private static boolean isArmorSlot(int slot) {
        return slot >= 5 && slot <= 8;
    }

    /** Whether a slot takes an item: an armour slot only its own armour; the rest anything. */
    public static boolean mayPlace(int slot, InvItem item) {
        if (!isArmorSlot(slot)) return true;
        Armor part = armorPart(item);
        return part != null && armorSlot(part) == slot;
    }

    /** Whether an item may leave its slot: armour cursed with binding stays on. */
    public static boolean mayPickup(int slot, InvItem item) {
        if (!isArmorSlot(slot)) return true;
        for (InvItem.Ench e : item.ench) if ("binding_curse".equals(InvText.bareId(e.id))) return false;
        return true;
    }

    public static int slotLimit(int slot, InvItem item) {
        return isArmorSlot(slot) ? 1 : maxStack(item);
    }

    @Nullable
    private static InvItem withCount(InvItem stack, int count) {
        return count > 0 ? stack.withCount(count) : null;
    }

    /** Slot.safeInsert: up to {@code n} of the carried stack into the slot; returns what stays carried. */
    @Nullable
    private static InvItem safeInsert(Mutable state, int slot, InvItem carried, int n) {
        if (!mayPlace(slot, carried)) return carried;
        InvItem existing = state.slots.get(slot);
        int room = slotLimit(slot, carried) - (existing == null ? 0 : existing.count);
        int moved = Math.min(n, Math.min(carried.count, room));
        if (moved <= 0) return carried;
        if (existing == null) state.put(slot, withCount(carried, moved));
        else if (existing.sameItem(carried)) state.put(slot, withCount(existing, existing.count + moved));
        else return carried;
        return withCount(carried, carried.count - moved);
    }

    /** A pickup click (button 0 all, 1 half or one) on the player's screen. */
    public static State pickup(State before, int slot, int button) {
        Mutable next = before.edit();
        InvItem here = next.slots.get(slot);
        InvItem carried = next.cursor;
        if (here == null) {
            if (carried != null) next.cursor = safeInsert(next, slot, carried, button == 0 ? carried.count : 1);
            return next.done();
        }
        if (!mayPickup(slot, here)) return next.done();
        if (carried == null) {
            int take = button == 0 ? here.count : (here.count + 1) / 2;
            next.cursor = withCount(here, take);
            next.put(slot, withCount(here, here.count - take));
            return next.done();
        }
        if (mayPlace(slot, carried)) {
            if (here.sameItem(carried)) {
                next.cursor = safeInsert(next, slot, carried, button == 0 ? carried.count : 1);
            } else if (carried.count <= slotLimit(slot, carried)) {
                next.cursor = here;
                next.put(slot, carried);
            }
            return next.done();
        }
        if (here.sameItem(carried)) {
            int take = Math.min(here.count, maxStack(carried) - carried.count);
            if (take > 0) {
                next.cursor = withCount(carried, carried.count + take);
                next.put(slot, withCount(here, here.count - take));
            }
        }
        return next.done();
    }

    /** AbstractContainerMenu.moveItemStackTo over {@code order}: merge into equal stacks first, then the first empty slot. */
    @Nullable
    static InvItem moveInto(Mutable state, InvItem stack, List<Integer> order) {
        InvItem rest = stack;
        if (maxStack(stack) > 1) {
            for (int slot : order) {
                if (rest == null) break;
                InvItem existing = state.slots.get(slot);
                if (existing == null || !existing.sameItem(rest)) continue;
                int limit = slotLimit(slot, existing);
                int total = existing.count + rest.count;
                if (total <= limit) {
                    state.put(slot, withCount(existing, total));
                    rest = null;
                } else if (existing.count < limit) {
                    rest = withCount(rest, total - limit);
                    state.put(slot, withCount(existing, limit));
                }
            }
        }
        if (rest != null) {
            for (int slot : order) {
                if (state.slots.containsKey(slot) || !mayPlace(slot, rest)) continue;
                int moved = Math.min(slotLimit(slot, rest), rest.count);
                state.put(slot, withCount(rest, moved));
                rest = withCount(rest, rest.count - moved);
                break;
            }
        }
        return rest;
    }

    private static List<Integer> range(int start, int end) {
        List<Integer> out = new ArrayList<>();
        for (int i = start; i < end; i++) out.add(i);
        return out;
    }

    /** Where a shift-click on the player's screen sends a stack (the action sheet names it so). */
    public enum Target { INVENTORY, HOTBAR, EQUIPMENT, OFFHAND }

    private static Object[] quickMoveRange(Map<Integer, InvItem> slots, int slot, InvItem stack) {
        Armor part = armorPart(stack);
        if (isArmorSlot(slot) || slot == InvLayout.OFFHAND_SLOT) return new Object[]{Target.INVENTORY, 9, 45};
        if (part != null && !slots.containsKey(armorSlot(part))) {
            return new Object[]{Target.EQUIPMENT, armorSlot(part), armorSlot(part) + 1};
        }
        if ("shield".equals(InvText.bareId(stack.id)) && !slots.containsKey(InvLayout.OFFHAND_SLOT)) {
            return new Object[]{Target.OFFHAND, 45, 46};
        }
        if (slot >= 9 && slot < 36) return new Object[]{Target.HOTBAR, 36, 45};
        return new Object[]{Target.INVENTORY, 9, 36};
    }

    @Nullable
    public static Target quickMoveTarget(State state, int slot) {
        InvItem stack = state.slots.get(slot);
        return stack == null ? null : (Target) quickMoveRange(state.slots, slot, stack)[0];
    }

    private static boolean quickMoveOnce(Mutable state, int slot) {
        InvItem stack = state.slots.get(slot);
        if (stack == null) return false;
        Object[] r = quickMoveRange(state.slots, slot, stack);
        InvItem rest = moveInto(state, stack, range((Integer) r[1], (Integer) r[2]));
        boolean moved = (rest == null ? 0 : rest.count) != stack.count;
        if (moved) state.put(slot, rest);
        return moved;
    }

    /** A shift-click on the player's screen: the game repeats it while anything still moves. */
    public static State quickMove(State before, int slot) {
        Mutable next = before.edit();
        InvItem stack = next.slots.get(slot);
        if (stack == null || !mayPickup(slot, stack)) return next.done();
        for (int guard = 0; guard < 64 && quickMoveOnce(next, slot); guard++) {
            InvItem left = next.slots.get(slot);
            if (left == null || !left.sameItem(stack)) break;
        }
        return next.done();
    }

    /** Dropping one item, or the stack, from a slot. */
    public static State drop(State before, int slot, boolean stack) {
        Mutable next = before.edit();
        InvItem here = next.slots.get(slot);
        if (here == null || next.cursor != null || !mayPickup(slot, here)) return next.done();
        next.put(slot, stack ? null : withCount(here, here.count - 1));
        return next.done();
    }

    public static State hold(State before, int hotbar) {
        Mutable next = before.edit();
        next.held = hotbar;
        return next.done();
    }
}
