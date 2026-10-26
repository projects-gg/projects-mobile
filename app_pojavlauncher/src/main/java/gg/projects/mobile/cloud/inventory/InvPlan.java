package gg.projects.mobile.cloud.inventory;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What a tap or an action sheet row sends: one move (the launcher's plan.ts). The phone has no number keys, so no
 * chained swaps: a tap picks a stack up, a tap on another slot puts it down or swaps (the game's own two clicks).
 */
public final class InvPlan {
    private InvPlan() {}

    /** What the view holds when a move is planned. */
    public static final class State {
        public final InvLayout.Screen screen;
        /** The player inventory as shown (with moves on their way). */
        public final InvRules.State inv;
        /** The open window's items by slot. */
        public final Map<Integer, InvItem> win;
        /** The window the player sees, 0 for none. */
        public final long wseq;
        /** Where the cursor's item came from, when this view picked it up ("inv:9"). */
        @Nullable
        public final String cursorOrigin;
        public final String lang;

        public State(InvLayout.Screen screen, InvRules.State inv, Map<Integer, InvItem> win, long wseq, @Nullable String cursorOrigin,
                     String lang) {
            this.screen = screen;
            this.inv = inv;
            this.win = win;
            this.wseq = wseq;
            this.cursorOrigin = cursorOrigin;
            this.lang = lang;
        }

        @Nullable
        public InvItem at(InvLayout.Spot spot) {
            return spot.area == InvLayout.Area.INV ? inv.at(spot.slot) : win.get(spot.slot);
        }

        boolean predicts() {
            return screen.kind == InvLayout.ScreenKind.PLAYER;
        }
    }

    public interface Predict {
        InvRules.State apply(InvRules.State state);
    }

    /** One move: the op, the slots that show it waiting ("inv:9", "win:0", "cursor") and what the view guesses meanwhile. */
    public static final class Step {
        public final InvOp op;
        public final List<String> keys;
        /** The player's screen changes at once (never a server's menu). */
        @Nullable
        public final Predict predict;
        /** A move that must change something: no change after it means the server refused it. */
        public final boolean mustChange;
        /** What a screen reader hears when it went through: a word key and the item's name. */
        @Nullable
        public final String speech;
        @Nullable
        public final String speechItem;
        /** The slot the cursor's item comes from (a pickup into an empty cursor). */
        @Nullable
        public final String origin;

        Step(InvOp op, List<String> keys, @Nullable Predict predict, boolean mustChange, @Nullable String speech,
             @Nullable String speechItem, @Nullable String origin) {
            this.op = op;
            this.keys = Collections.unmodifiableList(keys);
            this.predict = predict;
            this.mustChange = mustChange;
            this.speech = speech;
            this.speechItem = speechItem;
            this.origin = origin;
        }
    }

    /** A left (0) or right (1) click: take, place, swap, half, one. */
    @Nullable
    public static Step click(State state, InvLayout.Spot spot, final int button) {
        InvItem here = state.at(spot);
        InvItem carried = state.inv.cursor;
        if (here == null && carried == null) return null;
        final int slot = spot.slot;
        Predict predict = state.predicts() ? s -> InvRules.pickup(s, slot, button) : null;
        String speech = null;
        String item = null;
        String origin = null;
        if (here != null && carried == null) {
            speech = "pickedUp";
            item = InvText.itemName(here, state.lang);
            origin = spot.key();
        } else if (here == null) {
            speech = "placed";
            item = InvText.itemName(carried, state.lang);
        } else {
            boolean same = here.sameItem(carried);
            speech = same ? "placed" : "swapped";
            item = InvText.itemName(same ? carried : here, state.lang);
        }
        return new Step(InvOp.click(spot.area, spot.slot, button, "pickup", state.wseq), Arrays.asList(spot.key(), "cursor"), predict,
                true, speech, item, origin);
    }

    /** A shift-click: the stack moves to the other part (window and player, main and hotbar). */
    @Nullable
    public static Step quickMove(State state, InvLayout.Spot spot) {
        InvItem here = state.at(spot);
        if (here == null) return null;
        final int slot = spot.slot;
        Predict predict = state.predicts() ? s -> InvRules.quickMove(s, slot) : null;
        return new Step(InvOp.click(spot.area, spot.slot, 0, "quick_move", state.wseq), Collections.singletonList(spot.key()), predict,
                true, "moved", InvText.itemName(here, state.lang), null);
    }

    /** One item or the stack. Never with an item on the cursor (the contract refuses). */
    @Nullable
    public static Step drop(State state, InvLayout.Spot spot, final boolean stack) {
        InvItem here = state.at(spot);
        if (here == null || state.inv.cursor != null) return null;
        final int slot = spot.slot;
        Predict predict = state.predicts() ? s -> InvRules.drop(s, slot, stack) : null;
        return new Step(InvOp.drop(spot.area, spot.slot, stack, state.wseq), Collections.singletonList(spot.key()), predict, true,
                "dropped", InvText.itemName(here, state.lang), null);
    }

    /** "Kullan": the hotbar slot's item, as a right click into the air (never with a window open). */
    @Nullable
    public static Step use(State state, int hotbar) {
        if (state.wseq != 0 || state.screen.kind != InvLayout.ScreenKind.PLAYER) return null;
        InvItem item = state.inv.at(InvLayout.HOTBAR_FIRST + hotbar);
        if (item == null) return null;
        return new Step(InvOp.use(hotbar), Collections.singletonList(InvLayout.Spot.key(InvLayout.Area.INV, InvLayout.HOTBAR_FIRST + hotbar)),
                null, false, "used", InvText.itemName(item, state.lang), null);
    }

    /** "Elde tut": the hotbar slot the account holds. */
    @Nullable
    public static Step hold(State state, final int hotbar) {
        if (state.inv.held == hotbar) return null;
        InvItem item = state.inv.at(InvLayout.HOTBAR_FIRST + hotbar);
        return new Step(InvOp.hold(hotbar), Collections.singletonList(InvLayout.Spot.key(InvLayout.Area.INV, InvLayout.HOTBAR_FIRST + hotbar)),
                s -> InvRules.hold(s, hotbar), false, item == null ? null : "held", item == null ? null : InvText.itemName(item, state.lang),
                null);
    }

    /**
     * The cursor's item goes back (back, a tap on the hand slot, "Yerine koy"): to the slot it came from when that is
     * free, else the first free slot of the main inventory and hotbar, else a stack it joins. It never drops.
     */
    @Nullable
    public static Step putBack(State state) {
        InvItem carried = state.inv.cursor;
        if (carried == null) return null;
        InvLayout.Spot target = null;
        if (state.cursorOrigin != null) {
            InvLayout.Spot origin = state.screen.spot(state.cursorOrigin);
            if (origin != null && free(state, origin, carried)) target = origin;
        }
        if (target == null) {
            for (InvLayout.Spot s : state.screen.slots) {
                if (s.area == InvLayout.Area.INV && s.slot >= InvLayout.MAIN_FIRST && s.slot < 45 && free(state, s, carried)) {
                    target = s;
                    break;
                }
            }
        }
        if (target == null) {
            for (InvLayout.Spot s : state.screen.slots) {
                if (s.area != InvLayout.Area.INV || s.slot < InvLayout.MAIN_FIRST || s.slot >= 45) continue;
                InvItem here = state.at(s);
                if (here != null && here.sameItem(carried) && here.count < InvRules.slotLimit(s.slot, here)) {
                    target = s;
                    break;
                }
            }
        }
        if (target == null) return null;
        final int slot = target.slot;
        Predict predict = state.predicts() ? s -> InvRules.pickup(s, slot, 0) : null;
        return new Step(InvOp.click(target.area, target.slot, 0, "pickup", state.wseq), Arrays.asList(target.key(), "cursor"), predict,
                true, "putBackDone", InvText.itemName(carried, state.lang), null);
    }

    private static boolean free(State state, InvLayout.Spot s, InvItem carried) {
        return state.at(s) == null && (s.area == InvLayout.Area.WIN || InvRules.mayPlace(s.slot, carried));
    }

    /** Closing the window (never with an item on the cursor: the contract refuses). */
    @Nullable
    public static Step close(State state) {
        if (state.wseq == 0 || state.inv.cursor != null) return null;
        return new Step(InvOp.close(state.wseq), Collections.<String>emptyList(), null, false, null, null, null);
    }

    // ---- the slot's actions (long press) ----

    public interface Planner {
        @Nullable
        Step plan(State state);
    }

    /** A row of the slot's action sheet: its word key, whether it loses something, and what it sends when chosen. */
    public static final class Row {
        public final String word;
        public final boolean danger;
        /** A row after a gap (dropping comes apart). */
        public final boolean apart;
        public final Planner planner;

        Row(String word, boolean danger, boolean apart, Planner planner) {
            this.word = word;
            this.danger = danger;
            this.apart = apart;
            this.planner = planner;
        }
    }

    /**
     * The slot's actions. On the player's own screen they are the game's clicks by what they do: take, half, the
     * shift-click named by where the stack goes, hold, use; with a stack carried, place (or swap) and place one. In a
     * server's window the server decides what a click does (a shop buys, a menu opens another), so the rows name the
     * clicks themselves. Dropping comes apart, only from the player's own slots. Empty when nothing can be done there.
     */
    public static List<Row> rows(final State state, final InvLayout.Spot spot, boolean canMove, boolean locked) {
        List<Row> rows = new ArrayList<>();
        if (!canMove || locked || !InvLayout.slotUsable(state.screen, spot)) return rows;
        InvItem here = state.at(spot);
        InvItem carried = state.inv.cursor;
        if (state.screen.kind != InvLayout.ScreenKind.PLAYER) {
            if (here != null || carried != null) {
                rows.add(new Row("click", false, false, s -> click(s, spot, 0)));
                rows.add(new Row("rightClick", false, false, s -> click(s, spot, 1)));
            }
            if (here != null && carried == null) rows.add(new Row("shiftClick", false, false, s -> quickMove(s, spot)));
            if (carried != null) rows.add(new Row("putBack", false, false, InvPlan::putBack));
        } else if (carried != null) {
            if (InvRules.mayPlace(spot.slot, carried)) {
                boolean joins = here == null || here.sameItem(carried);
                rows.add(new Row(joins ? "place" : "swap", false, false, s -> click(s, spot, 0)));
                if (joins) rows.add(new Row("placeOne", false, false, s -> click(s, spot, 1)));
            }
            rows.add(new Row("putBack", false, false, InvPlan::putBack));
        } else {
            if (here != null) {
                InvRules.Target target = InvRules.quickMoveTarget(state.inv, spot.slot);
                String moveWord = target == InvRules.Target.HOTBAR ? "toHotbar" : target == InvRules.Target.EQUIPMENT ? "putOn"
                        : target == InvRules.Target.OFFHAND ? "toOffhand" : "toInventory";
                rows.add(new Row("take", false, false, s -> click(s, spot, 0)));
                if (here.count > 1) rows.add(new Row("takeHalf", false, false, s -> click(s, spot, 1)));
                rows.add(new Row(moveWord, false, false, s -> quickMove(s, spot)));
            }
            final int hotbar = spot.kind == InvLayout.Kind.HOTBAR ? spot.slot - InvLayout.HOTBAR_FIRST : -1;
            if (hotbar >= 0) {
                if (state.inv.held != hotbar) rows.add(new Row("hold", false, false, s -> hold(s, hotbar)));
                if (here != null) rows.add(new Row("use", false, false, s -> use(s, hotbar)));
            }
        }
        if (here != null && carried == null && spot.area == InvLayout.Area.INV) {
            boolean apart = !rows.isEmpty();
            if (here.count > 1) {
                rows.add(new Row("dropOne", true, apart, s -> drop(s, spot, false)));
                rows.add(new Row("dropStack", true, false, s -> drop(s, spot, true)));
            } else {
                rows.add(new Row("drop", true, apart, s -> drop(s, spot, false)));
            }
        }
        return rows;
    }
}
