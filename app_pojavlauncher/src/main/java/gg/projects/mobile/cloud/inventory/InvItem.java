package gg.projects.mobile.cloud.inventory;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.social.Json;

/**
 * One item of a cloud account's live inventory ({@code InvItem} of services/cloud-afk README "Inventory"), or a stack
 * without a slot (the cursor's item, a shulker box's contents). Read leniently: a field of another type reads as absent.
 * Names, lore and window titles are anyone's text: the view cleans them ({@link InvText#cleanText}) and draws them plain.
 */
public final class InvItem {
    /** A stack without a place of its own (the cursor's, a container's contents). */
    public static final int NO_SLOT = -1;

    public static final class Label {
        public final String tr;
        public final String en;

        public Label(String tr, String en) {
            this.tr = tr == null ? "" : tr;
            this.en = en == null ? "" : en;
        }

        static Label read(@Nullable JsonObject o) {
            return new Label(Json.str(o, "tr", ""), Json.str(o, "en", ""));
        }

        boolean same(Label o) {
            return tr.equals(o.tr) && en.equals(o.en);
        }
    }

    public static final class Ench {
        public final String id;
        public final int lvl;
        public final Label label;

        public Ench(String id, int lvl, Label label) {
            this.id = id;
            this.lvl = lvl;
            this.label = label;
        }
    }

    /** A shulker box's or bundle's contents: the total and its first stacks (at most 9). */
    public static final class Inside {
        public final int count;
        public final List<InvItem> top;

        public Inside(int count, List<InvItem> top) {
            this.count = count;
            this.top = Collections.unmodifiableList(new ArrayList<>(top));
        }
    }

    public final int slot;
    public final String id;
    public final int count;
    public final Label label;
    @Nullable
    public final String name;
    public final List<String> lore;
    /** -1 when the item does not wear. */
    public final int durabilityLeft;
    public final int durabilityMax;
    @Nullable
    public final Integer cmd;
    @Nullable
    public final String model;
    public final List<Ench> ench;
    public final boolean enchanted;
    @Nullable
    public final Inside inside;
    @Nullable
    public final String potion;
    @Nullable
    public final Integer color;
    public final boolean partial;

    private InvItem(int slot, String id, int count, Label label, @Nullable String name, List<String> lore, int durabilityLeft,
                    int durabilityMax, @Nullable Integer cmd, @Nullable String model, List<Ench> ench, boolean enchanted,
                    @Nullable Inside inside, @Nullable String potion, @Nullable Integer color, boolean partial) {
        this.slot = slot;
        this.id = id;
        this.count = count;
        this.label = label;
        this.name = name;
        this.lore = lore;
        this.durabilityLeft = durabilityLeft;
        this.durabilityMax = durabilityMax;
        this.cmd = cmd;
        this.model = model;
        this.ench = ench;
        this.enchanted = enchanted;
        this.inside = inside;
        this.potion = potion;
        this.color = color;
        this.partial = partial;
    }

    /** A plain item (tests, the mock machine). */
    public static InvItem of(int slot, String id, int count, String tr, String en) {
        return new InvItem(slot, id, count, new Label(tr, en), null, Collections.<String>emptyList(), -1, -1, null, null,
                Collections.<Ench>emptyList(), false, null, null, null, false);
    }

    public InvItem withCount(int n) {
        return new InvItem(slot, id, n, label, name, lore, durabilityLeft, durabilityMax, cmd, model, ench, enchanted, inside, potion,
                color, partial);
    }

    public InvItem withSlot(int s) {
        return new InvItem(s, id, count, label, name, lore, durabilityLeft, durabilityMax, cmd, model, ench, enchanted, inside, potion,
                color, partial);
    }

    public InvItem withName(@Nullable String n) {
        return new InvItem(slot, id, count, label, n, lore, durabilityLeft, durabilityMax, cmd, model, ench, enchanted, inside, potion,
                color, partial);
    }

    public InvItem withLore(List<String> l) {
        return new InvItem(slot, id, count, label, name, Collections.unmodifiableList(new ArrayList<>(l)), durabilityLeft, durabilityMax,
                cmd, model, ench, enchanted, inside, potion, color, partial);
    }

    public InvItem withDurability(int left, int max) {
        return new InvItem(slot, id, count, label, name, lore, left, max, cmd, model, ench, enchanted, inside, potion, color, partial);
    }

    public InvItem withEnch(List<Ench> e) {
        return new InvItem(slot, id, count, label, name, lore, durabilityLeft, durabilityMax, cmd, model,
                Collections.unmodifiableList(new ArrayList<>(e)), !e.isEmpty() || enchanted, inside, potion, color, partial);
    }

    public InvItem withInside(@Nullable Inside in) {
        return new InvItem(slot, id, count, label, name, lore, durabilityLeft, durabilityMax, cmd, model, ench, enchanted, in, potion,
                color, partial);
    }

    public InvItem withPotion(@Nullable String p, @Nullable Integer c) {
        return new InvItem(slot, id, count, label, name, lore, durabilityLeft, durabilityMax, cmd, model, ench, enchanted, inside, p, c,
                partial);
    }

    public InvItem withArt(@Nullable Integer customModel, @Nullable String itemModel) {
        return new InvItem(slot, id, count, label, name, lore, durabilityLeft, durabilityMax, customModel, itemModel, ench, enchanted,
                inside, potion, color, partial);
    }

    public InvItem withPartial(boolean p) {
        return new InvItem(slot, id, count, label, name, lore, durabilityLeft, durabilityMax, cmd, model, ench, enchanted, inside, potion,
                color, p);
    }

    public boolean wears() {
        return durabilityMax > 0 && durabilityLeft >= 0;
    }

    /** Whether the item shows the glint (enchanted, or with the glint by itself). */
    public boolean glints() {
        return enchanted || !ench.isEmpty();
    }

    /** Reads one item; null when it has no id or no count (a frame's broken entry is left out, never shown). */
    @Nullable
    public static InvItem read(@Nullable JsonObject o, boolean withSlot) {
        if (o == null) return null;
        String id = Json.str(o, "id");
        int count = (int) Json.num(o, "count", 0);
        if (id == null || id.isEmpty() || count <= 0) return null;
        int slot = withSlot ? (int) Json.num(o, "slot", NO_SLOT) : NO_SLOT;
        if (withSlot && slot < 0) return null;
        List<String> lore = new ArrayList<>();
        JsonElement loreEl = o.get("lore");
        if (loreEl != null && loreEl.isJsonArray()) {
            for (JsonElement l : loreEl.getAsJsonArray()) {
                if (l.isJsonPrimitive()) lore.add(l.getAsString());
                if (lore.size() >= 4) break;
            }
        }
        JsonObject dur = Json.obj(o, "durability");
        int left = dur == null ? -1 : (int) Json.num(dur, "left", -1);
        int max = dur == null ? -1 : (int) Json.num(dur, "max", -1);
        if (max <= 0) {
            left = -1;
            max = -1;
        }
        Long cmdValue = Json.numOrNull(o, "cmd");
        List<Ench> ench = new ArrayList<>();
        JsonElement enchEl = o.get("ench");
        if (enchEl != null && enchEl.isJsonArray()) {
            for (JsonElement e : enchEl.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject eo = e.getAsJsonObject();
                String eid = Json.str(eo, "id");
                if (eid == null) continue;
                ench.add(new Ench(eid, (int) Json.num(eo, "lvl", 1), Label.read(Json.obj(eo, "label"))));
            }
        }
        Inside inside = null;
        JsonObject in = Json.obj(o, "inside");
        if (in != null) {
            List<InvItem> top = new ArrayList<>();
            JsonElement topEl = in.get("top");
            if (topEl != null && topEl.isJsonArray()) {
                for (JsonElement t : topEl.getAsJsonArray()) {
                    InvItem stack = t.isJsonObject() ? read(t.getAsJsonObject(), false) : null;
                    if (stack != null) top.add(stack);
                    if (top.size() >= 9) break;
                }
            }
            inside = new Inside((int) Math.max(0, Json.num(in, "count", 0)), top);
        }
        Long colorValue = Json.numOrNull(o, "color");
        return new InvItem(slot, id, count, Label.read(Json.obj(o, "label")), Json.str(o, "name"),
                Collections.unmodifiableList(lore), left, max, cmdValue == null ? null : cmdValue.intValue(), Json.str(o, "model"),
                Collections.unmodifiableList(ench), Json.bool(o, "enchanted", false), inside, Json.str(o, "potion"),
                colorValue == null ? null : (int) (colorValue & 0xFFFFFF), Json.bool(o, "partial", false));
    }

    /** The items of a frame's array (entries that do not read are left out). */
    public static List<InvItem> list(@Nullable JsonArray array, boolean withSlot) {
        List<InvItem> out = new ArrayList<>();
        if (array == null) return out;
        for (JsonElement e : array) {
            InvItem item = e.isJsonObject() ? read(e.getAsJsonObject(), withSlot) : null;
            if (item != null) out.add(item);
        }
        return out;
    }

    /** The game's "same item, same components", from what the frame shows of them (rules.ts {@code sameItem}). */
    public boolean sameItem(InvItem b) {
        if (!id.equals(b.id)) return false;
        if (!(name == null ? "" : name).equals(b.name == null ? "" : b.name)) return false;
        if (!lore.equals(b.lore)) return false;
        if (ench.size() != b.ench.size()) return false;
        for (int i = 0; i < ench.size(); i++) {
            if (!ench.get(i).id.equals(b.ench.get(i).id) || ench.get(i).lvl != b.ench.get(i).lvl) return false;
        }
        if (!(potion == null ? "" : potion).equals(b.potion == null ? "" : b.potion)) return false;
        if ((color == null ? -1 : color) != (b.color == null ? -1 : b.color)) return false;
        if (durabilityLeft != b.durabilityLeft || durabilityMax != b.durabilityMax) return false;
        if ((cmd == null ? -1 : cmd) != (b.cmd == null ? -1 : b.cmd)) return false;
        if (!(model == null ? "" : model).equals(b.model == null ? "" : b.model)) return false;
        return glints() == b.glints();
    }

    /** The same stack as {@code b} in every field the view draws (a frame that changed nothing). */
    public boolean sameStack(@Nullable InvItem b) {
        return b != null && count == b.count && sameItem(b) && label.same(b.label) && partial == b.partial;
    }
}
