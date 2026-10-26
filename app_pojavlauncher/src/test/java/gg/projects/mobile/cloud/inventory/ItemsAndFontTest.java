package gg.projects.mobile.cloud.inventory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import gg.projects.mobile.cloud.items.ItemIndex;

/** Item pictures' index and order, the stand-ins, and the game's pixel letters. */
public class ItemsAndFontTest {
    private static final String INDEX = "{\"format\":1,\"minecraft\":\"26.1.2\",\"image\":\"atlas.png\",\"size\":[1024,512],"
            + "\"glint\":[0,480,32,32],\"icons\":{"
            + "\"minecraft:diamond\":{\"kind\":\"flat\",\"layers\":[{\"rect\":[0,0,16,16]}]},"
            + "\"minecraft:potion\":{\"kind\":\"flat\",\"layers\":[{\"rect\":[16,0,16,16],\"tint\":{\"source\":\"potion\",\"default\":\"#385dc6\"}},{\"rect\":[32,0,16,16]}]},"
            + "\"minecraft:wolf_armor\":{\"kind\":\"flat\",\"layers\":[{\"rect\":[48,0,16,16]},{\"rect\":[64,0,16,16],\"tint\":{\"source\":\"dye\"}}]},"
            + "\"minecraft:obsidian\":{\"kind\":\"block\",\"layers\":[{\"rect\":[0,16,48,48]}],\"dark\":true},"
            + "\"minecraft:broken\":{\"kind\":\"flat\",\"layers\":[{\"rect\":[1020,0,16,16]}]}}}";

    @Test
    public void indexReadsWhatItCanDraw() {
        ItemIndex index = ItemIndex.read(JsonParser.parseString(INDEX).getAsJsonObject());
        assertNotNull(index);
        assertEquals("26.1.2", index.minecraft);
        assertEquals(4, index.count());
        assertNotNull(index.icon("diamond"));
        assertNotNull(index.icon("Minecraft:Diamond"));
        assertNull(index.icon("minecraft:broken"));
        assertTrue(index.icon("obsidian").dark);
        assertFalse(index.icon("obsidian").flat());
        assertNull(ItemIndex.read(JsonParser.parseString("{\"format\":2,\"image\":\"a.png\",\"size\":[1,1],\"icons\":{}}").getAsJsonObject()));
        assertNull(ItemIndex.read(null));
        ItemIndex.Layer liquid = index.icon("potion").layers.get(0);
        assertEquals(0xf82423, ItemIndex.layerColor(liquid, null, "minecraft:strong_healing"));
        assertEquals(0x123456, ItemIndex.layerColor(liquid, 0x123456, "minecraft:healing"));
        assertEquals(0x385dc6, ItemIndex.layerColor(liquid, null, null));
        ItemIndex.Layer dye = index.icon("wolf_armor").layers.get(1);
        assertEquals(-1, ItemIndex.layerColor(dye, null, null));
        assertEquals(0xff0000, ItemIndex.layerColor(dye, 0xff0000, null));
        assertTrue(ItemIndex.glints("minecraft:enchanted_book", false));
        assertFalse(ItemIndex.glints("minecraft:book", false));
    }

    @Test
    public void standInsFollowTheLaunchersCategories() {
        assertEquals(ItemIndex.Category.BLOCK, ItemIndex.category("minecraft:oak_planks"));
        assertEquals(ItemIndex.Category.BLOCK, ItemIndex.category("cobblestone"));
        assertEquals(ItemIndex.Category.FOOD, ItemIndex.category("minecraft:golden_apple"));
        assertEquals(ItemIndex.Category.BOOK, ItemIndex.category("minecraft:enchanted_book"));
        assertEquals(ItemIndex.Category.OTHER, ItemIndex.category("minecraft:diamond_sword"));
        assertEquals(ItemIndex.Category.OTHER, ItemIndex.category("minecraft:milk_bucket"));
        assertEquals(ItemIndex.Category.OTHER, ItemIndex.category("minecraft:potion"));
        for (ItemIndex.Category c : ItemIndex.Category.values()) {
            String[] rows = ItemIndex.standIn(c);
            assertEquals(16, rows.length);
            for (String row : rows) assertEquals(16, row.length());
        }
        assertEquals(40, ItemIndex.drawingSize(80));
        assertEquals(48, ItemIndex.drawingSize(96));
        assertEquals(32, ItemIndex.drawingSize(128));
        assertEquals(28, ItemIndex.drawingSize(112));
        assertEquals(32, ItemIndex.drawingSize(64));
        assertEquals(48, ItemIndex.drawingSize(48));
        assertEquals(16, ItemIndex.drawingSize(12));
    }

    @Test
    public void pixelLettersMeasureAsTheGames() {
        assertEquals(6, PixelFont.advance('A'));
        assertEquals(4, PixelFont.advance(' '));
        assertEquals(12, PixelFont.advance("64"));
        assertEquals(11, PixelFont.width("64"));
        assertTrue(PixelFont.canDraw("Elmas Kılıç ĞÜŞİÖÇ ğüşıöç"));
        assertFalse(PixelFont.canDraw("中"));
        assertEquals("Elm", PixelFont.clip("Elmas", 15));
        List<String> lines = PixelFont.wrap("Keskinlik V ve Kırılmazlık III", 60);
        assertTrue(lines.size() > 1);
        for (String line : lines) assertTrue(line, PixelFont.advance(line) <= 60);
        assertEquals(Arrays.asList("abc"), PixelFont.wrap("abc", 100));
        final int[] lit = {0};
        PixelFont.runs("1", (x, y, w) -> lit[0] += w);
        assertTrue(lit[0] > 5);
    }
}
