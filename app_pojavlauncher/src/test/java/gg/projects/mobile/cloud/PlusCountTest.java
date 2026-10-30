package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 0.4.6 (TASTE-2): item counts in the launcher's one form (words.ts qtyItem, goodsText and their tests), never a guessed
 * English plural: TR "64 Elmas", EN "64 x Diamond" with no-break spaces around the sign.
 */
public class PlusCountTest {
    private static final String X = "\u00a0\u00d7\u00a0";

    @Test
    public void countsAsTheLauncherWritesThem() {
        assertEquals("64 Elmas", PlusFormat.qtyItem(64, "Elmas", "tr"));
        assertEquals("64" + X + "Diamond", PlusFormat.qtyItem(64, "Diamond", "en"));
        // A price paid in items keeps a single one's count.
        assertEquals("1" + X + "Totem of Undying", PlusFormat.qtyItem(1, "Totem of Undying", "en"));
        assertEquals("1 Elmas", PlusFormat.qtyItem(1, "Elmas", "tr"));
        // The count is grouped as an amount in each language.
        assertEquals("1.000 Elmas", PlusFormat.qtyItem(1000, "Elmas", "tr"));
        assertEquals("1,000" + X + "Diamond", PlusFormat.qtyItem(1000, "Diamond", "en"));
    }

    @Test
    public void aDealsSubjectDropsASingleOnesCountInEnglishOnly() {
        assertEquals("Elytra", PlusFormat.goods(1, "Elytra", "en"));
        assertEquals("1 Elytra", PlusFormat.goods(1, "Elytra", "tr"));
        assertEquals("2" + X + "Emerald", PlusFormat.goods(2, "Emerald", "en"));
        assertEquals("2 Z\u00fcmr\u00fct", PlusFormat.goods(2, "Z\u00fcmr\u00fct", "tr"));
        assertEquals("2" + X + "Netherite Ingot", PlusFormat.goods(2, "Netherite Ingot", "en"));
    }

    @Test
    public void aTitleIsBareForOneInBothLanguages() {
        assertEquals("Elytra", PlusFormat.titleItem(1, "Elytra", "en"));
        assertEquals("Elitra", PlusFormat.titleItem(1, "Elitra", "tr"));
        assertEquals("2" + X + "Emerald", PlusFormat.titleItem(2, "Emerald", "en"));
        assertEquals("2 Z\u00fcmr\u00fct", PlusFormat.titleItem(2, "Z\u00fcmr\u00fct", "tr"));
        // Never the old forms "2 Emeralds" or "Diamond x52".
        assertEquals("52" + X + "Diamond", PlusFormat.titleItem(52, "Diamond", "en"));
    }
}
