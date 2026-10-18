package gg.projects.mobile.content;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure helpers of the mod screen (mirror of the desktop's modcenter/selection.ts): which items
 * end up installed for a set of pending choices, why, and what changed.
 */
public final class ModsSelection {
    private ModsSelection() {}

    /**
     * The choices the screen starts from (libraries are never choices): the recorded ones, except
     * that an item held off on this phone shows as off, since it is not installed.
     */
    public static Map<String, Boolean> choicesOf(List<ItemResolver.ItemStatus> statuses) {
        return choicesOf(statuses, false);
    }

    /**
     * {@link #choicesOf(List)}; once the player lifted the hold in this visit ({@code holdLifted}:
     * they switched a held item on), held items show their recorded choices again, since that is
     * what applying installs.
     */
    public static Map<String, Boolean> choicesOf(List<ItemResolver.ItemStatus> statuses, boolean holdLifted) {
        Map<String, Boolean> choices = new LinkedHashMap<>();
        for (ItemResolver.ItemStatus status : statuses) if (!status.library) choices.put(status.id, baseline(status, holdLifted));
        return choices;
    }

    /** What the screen shows for an item before the player touches it. */
    public static boolean baseline(ItemResolver.ItemStatus status, boolean holdLifted) {
        return status.selected && (holdLifted || !status.heldOff);
    }

    /** Installed items for {@code choices}: the selected ones plus, transitively, what they require. */
    public static Set<String> enabledSet(PackManifest manifest, Map<String, Boolean> choices) {
        Set<String> enabled = new HashSet<>();
        for (PackManifest.PackItem item : manifest.projects.items) {
            if (!item.library && Boolean.TRUE.equals(choices.get(item.id))) visit(manifest, item.id, enabled);
        }
        return enabled;
    }

    private static void visit(PackManifest manifest, String id, Set<String> enabled) {
        if (enabled.contains(id)) return;
        PackManifest.PackItem item = manifest.projects.item(id);
        if (item == null) return;
        enabled.add(id);
        for (String required : item.requires) visit(manifest, required, enabled);
    }

    /** Enabled items that need {@code id}, in manifest order. */
    public static List<PackManifest.PackItem> neededBy(PackManifest manifest, Set<String> enabled, String id) {
        List<PackManifest.PackItem> out = new ArrayList<>();
        for (PackManifest.PackItem item : manifest.projects.items) {
            if (!item.id.equals(id) && enabled.contains(item.id) && item.requires.contains(id)) out.add(item);
        }
        return out;
    }

    /** What {@code id} brings along (its requirements, transitively), except items in {@code skip}. */
    public static List<PackManifest.PackItem> bringsAlong(PackManifest manifest, String id, Set<String> skip) {
        List<PackManifest.PackItem> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(id);
        collect(manifest, id, skip, seen, found);
        return found;
    }

    private static void collect(PackManifest manifest, String current, Set<String> skip, Set<String> seen,
                                List<PackManifest.PackItem> found) {
        PackManifest.PackItem item = manifest.projects.item(current);
        if (item == null) return;
        for (String required : item.requires) {
            if (!seen.add(required)) continue;
            PackManifest.PackItem requiredItem = manifest.projects.item(required);
            if (requiredItem != null && !skip.contains(required)) found.add(requiredItem);
            collect(manifest, required, skip, seen, found);
        }
    }

    /**
     * The selection to apply: every choice that differs from what the screen started from
     * ({@link #choicesOf}), plus reverts; null when nothing changed. Switching on an item held off on
     * this phone is such a change (it overrides the hold).
     */
    public static PackSelection pending(List<ItemResolver.ItemStatus> statuses, Map<String, Boolean> choices, Set<String> revert) {
        return pending(statuses, choices, revert, false);
    }

    /**
     * {@link #pending(List, Map, Set)} measured from {@link #choicesOf(List, boolean)}: after the
     * hold is lifted, a held item that keeps its recorded choice is no change (applying with the
     * hold lifted installs it as recorded).
     */
    public static PackSelection pending(List<ItemResolver.ItemStatus> statuses, Map<String, Boolean> choices, Set<String> revert,
                                        boolean holdLifted) {
        PackSelection selection = new PackSelection();
        for (ItemResolver.ItemStatus status : statuses) {
            if (status.library) continue;
            Boolean next = choices.get(status.id);
            if (next != null && next != baseline(status, holdLifted)) selection.items.put(status.id, next);
        }
        if (revert != null) selection.revert.addAll(revert);
        return selection.isEmpty() ? null : selection;
    }

    /** True when {@code selection} switches on one of {@code ids} (the Sodium family). */
    public static boolean switchesOn(PackSelection selection, Set<String> ids) {
        if (selection == null) return false;
        for (Map.Entry<String, Boolean> entry : selection.items.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue()) && ids.contains(entry.getKey())) return true;
        }
        return false;
    }

    /** True when {@code selection} changes one of {@code ids} either way. */
    public static boolean touches(PackSelection selection, Set<String> ids) {
        if (selection == null) return false;
        for (String id : selection.items.keySet()) if (ids.contains(id)) return true;
        return false;
    }

    /** Files added, updated and removed across every category. */
    public static int[] totals(PackSyncPlanner.Summary summary) {
        return new int[]{summary.added.total(), summary.updated.total(), summary.removed.total()};
    }

    /**
     * What the apply bar counts: for each of added, updated and removed, the mods (mods, resource
     * and shader packs: what the player picks) and, separately, other files (configs). The bar
     * names other files only when no mod changes in that action; the download size covers all.
     */
    public static int[][] barCounts(PackSyncPlanner.Summary summary) {
        PackSyncPlanner.Counts[] counts = {summary.added, summary.updated, summary.removed};
        int[][] out = new int[3][2];
        for (int i = 0; i < counts.length; i++) {
            out[i][0] = counts[i].mods + counts[i].resourcePacks + counts[i].shaderPacks;
            out[i][1] = counts[i].configs + counts[i].other;
        }
        return out;
    }
}
