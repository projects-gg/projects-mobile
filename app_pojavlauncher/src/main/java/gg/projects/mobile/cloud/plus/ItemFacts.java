package gg.projects.mobile.cloud.plus;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * What the order card and the order checks need to know of a vanilla item: the facts of the account version's item data
 * (FREE-ITEMS, "The file (format 1)"; {@code ItemData} implements this for the loaded file, tests implement it from the
 * vectors' {@code data}).
 */
public interface ItemFacts {
    /** The item {@code id} ({@code minecraft:…}), or null when the data has no such item. */
    @Nullable
    Item item(String id);

    /** An enchantment {@code id}, or null when the data has no such enchantment. */
    @Nullable
    Enchantment enchantment(String id);

    /** One item's facts. */
    final class Item {
        public final String id;
        /** Its stack size (1 for an item that does not stack). */
        public final int stack;
        /** Its durability; 0 for an item that does not wear out. */
        public final int maxDamage;
        /** The ids of the enchantments that fit it (its group), in the data's order. */
        public final List<String> enchantments;
        /** An operator item (extras {@code o}): never offered. */
        public final boolean operator;

        public Item(String id, int stack, int maxDamage, List<String> enchantments, boolean operator) {
            this.id = id;
            this.stack = stack;
            this.maxDamage = maxDamage;
            this.enchantments = Collections.unmodifiableList(enchantments);
            this.operator = operator;
        }
    }

    /** One enchantment's facts. */
    final class Enchantment {
        public final String id;
        public final int maxLevel;
        /** A curse (extras {@code c}). */
        public final boolean curse;
        /** The ids of the enchantments it excludes (extras {@code x}). */
        public final List<String> excludes;

        public Enchantment(String id, int maxLevel, boolean curse, List<String> excludes) {
            this.id = id;
            this.maxLevel = maxLevel;
            this.curse = curse;
            this.excludes = Collections.unmodifiableList(excludes);
        }
    }
}
