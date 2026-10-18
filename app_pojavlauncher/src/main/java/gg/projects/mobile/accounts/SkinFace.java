package gg.projects.mobile.accounts;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;

/**
 * Player heads drawn on the phone, like the desktop launcher's (pl-auth {@code avatar.rs}): the face
 * of a skin texture with its hat layer on top. A Microsoft account's texture comes from Mojang's own
 * texture host; an offline account shows the default skin Minecraft gives its name, read from a
 * client jar already on the phone. No third-party avatar service is asked (upstream asked
 * mc-heads.net for every Microsoft sign-in).
 */
public final class SkinFace {
    private SkinFace() {}

    /** Minecraft's default skins in the client's order (1.19.3 and later). */
    static final String[] DEFAULT_SKIN_NAMES = {"alex", "ari", "efe", "kai", "makena", "noor", "steve", "sunny", "zuri"};

    /** A default skin: its name and arm model. */
    public static final class DefaultSkin {
        public final String name;
        public final boolean slim;

        DefaultSkin(String name, boolean slim) {
            this.name = name;
            this.slim = slim;
        }

        /** Where the texture lives inside a 1.19.3+ client jar. */
        public String jarEntry() {
            return "assets/minecraft/textures/entity/player/" + (slim ? "slim" : "wide") + "/" + name + ".png";
        }
    }

    /**
     * The default skin the game shows for a profile without one: {@code DEFAULT_SKINS[floorMod(
     * uuid.hashCode(), 18)]}, the slim models first.
     */
    public static DefaultSkin defaultSkin(UUID uuid) {
        int count = DEFAULT_SKIN_NAMES.length;
        int index = Math.floorMod(uuid.hashCode(), 2 * count);
        return new DefaultSkin(DEFAULT_SKIN_NAMES[index % count], index < count);
    }

    /**
     * The face of a skin texture ({@code argb} row by row, {@code width}×{@code height}: 64×64, the
     * old 64×32, or a multiple of either up to 1024 wide), {@link #faceSize} pixels square: the face
     * made opaque, with the hat layer blended over it. Old 64×32 textures often painted the hat area
     * solid, so their hat counts only when that area has a transparent pixel (the desktop's rule).
     * Null when the picture is not a skin.
     */
    public static int[] face(int[] argb, int width, int height) {
        if (argb == null || width < 64 || width % 64 != 0 || width > 1024 || argb.length < width * height) return null;
        boolean legacy = height * 2 == width;
        if (height != width && !legacy) return null;
        int scale = width / 64;
        boolean hat = !legacy || legacyHatIsDrawn(argb, width, scale);
        int size = 8 * scale;
        int[] face = new int[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int base = argb[(8 * scale + y) * width + 8 * scale + x] | 0xFF000000;
                face[y * size + x] = hat ? over(argb[(8 * scale + y) * width + 40 * scale + x], base) : base;
            }
        }
        return face;
    }

    /** The side of {@link #face}'s square for a texture {@code width} pixels wide. */
    public static int faceSize(int width) {
        return 8 * (width / 64);
    }

    private static boolean legacyHatIsDrawn(int[] argb, int width, int scale) {
        for (int y = 0; y < 16 * scale; y++) {
            for (int x = 32 * scale; x < 64 * scale; x++) {
                if ((argb[y * width + x] >>> 24) < 128) return true;
            }
        }
        return false;
    }

    /** {@code top} drawn over an opaque {@code bottom} (straight alpha). */
    static int over(int top, int bottom) {
        int alpha = top >>> 24;
        if (alpha == 0) return bottom;
        if (alpha == 255) return top;
        int r = blend((top >> 16) & 0xFF, (bottom >> 16) & 0xFF, alpha);
        int g = blend((top >> 8) & 0xFF, (bottom >> 8) & 0xFF, alpha);
        int b = blend(top & 0xFF, bottom & 0xFF, alpha);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int blend(int top, int bottom, int alpha) {
        return (top * alpha + bottom * (255 - alpha) + 127) / 255;
    }

    /**
     * The https address of a Mojang skin texture ({@code textures.minecraft.net/texture/<id>}, which
     * the profile API gives as http), or null for any other address.
     */
    public static String mojangTextureUrl(String url) {
        if (url == null) return null;
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            if (!(scheme.equals("http") || scheme.equals("https")) || !host.equals("textures.minecraft.net")) return null;
            if (uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawQuery() != null) return null;
            if (!path.matches("/texture/[0-9a-fA-F]{1,128}")) return null;
            return "https://textures.minecraft.net" + path;
        } catch (Exception e) {
            return null;
        }
    }
}
