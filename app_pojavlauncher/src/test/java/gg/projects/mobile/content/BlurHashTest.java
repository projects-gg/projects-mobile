package gg.projects.mobile.content;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Cover placeholders (woltapp/blurhash), checked against hashes the Discord bot produced. */
public class BlurHashTest {
    private static final String CHARS =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~";

    private static String encode83(int value, int length) {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i <= length; i++) {
            int digit = (value / (int) Math.pow(83, length - i)) % 83;
            out.append(CHARS.charAt(digit));
        }
        return out.toString();
    }

    @Test
    public void aSingleColourDecodesToThatColour() {
        int rgb = 0xE89A2C; // the brand gold
        String hash = encode83(0, 1) + encode83(0, 1) + encode83(rgb, 4);
        int[] pixels = BlurHash.decode(hash, 8, 6, 1);
        assertNotNull(pixels);
        assertEquals(48, pixels.length);
        for (int pixel : pixels) {
            assertEquals(0xFF, pixel >>> 24);
            assertTrue(Integer.toHexString(pixel), Math.abs(((pixel >> 16) & 255) - 0xE8) <= 1);
            assertTrue(Math.abs(((pixel >> 8) & 255) - 0x9A) <= 1);
            assertTrue(Math.abs((pixel & 255) - 0x2C) <= 1);
        }
    }

    @Test
    public void theBotsHashesDecode() {
        // 4 x 3 components from the N1-A sample feed (D:\ProLauncher-build\feed-e2e), the bot's encoder on an
        // M9 fixture cover, and the spec example.
        for (String hash : new String[]{"L9G$-K0i$ySh|@1M^ORkE4tPJAay", "LHHJ590h-mEN}qI@s-jZ5A-mENkB", "LKO2?U%2Tw=w]~RBVZRi};RPxuwH"}) {
            int[] pixels = BlurHash.decode(hash, 32, 18, 1);
            assertNotNull(hash, pixels);
            assertEquals(32 * 18, pixels.length);
            int first = pixels[0];
            boolean varies = false;
            for (int pixel : pixels) varies |= pixel != first;
            assertTrue("a 4 x 3 hash is not a flat colour", varies);
        }
    }

    @Test
    public void invalidHashesAreRefused() {
        for (String hash : new String[]{null, "", "L9G$-", "L9G$-K0i$ySh|@1M^ORkE4tPJAa", "L9G$-K0i$ySh|@1M^ORkE4tPJAayy",
                "L9G$-K0i$ySh|@1M^ORkE4tPJA\"y"}) {
            assertNull(hash, BlurHash.decode(hash, 8, 8, 1));
        }
        assertNull(BlurHash.decode("LKO2?U%2Tw=w]~RBVZRi};RPxuwH", 0, 8, 1));
    }
}
