package gg.projects.mobile.social.emoji;

/**
 * Code point classes for emoji, without regex Unicode properties (Android's ICU regex may lack
 * them). The tables are V8's (Unicode 16.0, the desktop's engine): {@code Extended_Pictographic}
 * as the desktop's {@code \p{Extended_Pictographic}} sees it; the skin tone bases are the emoji
 * data's (Emoji 17.0) plus the middle finger. Also a grapheme cluster walk (UAX #29, the rules
 * that matter for emoji: marks, ZWJ sequences, flags, Hangul) and JavaScript's white space.
 */
final class EmojiChars {
    private EmojiChars() {}

    static final int VS16 = 0xFE0F;
    static final int ZWJ = 0x200D;
    static final int KEYCAP = 0x20E3;
    static final int MIDDLE_FINGER = 0x1F595;
    static final int BLACK_FLAG = 0x1F3F4;
    static final int CANCEL_TAG = 0xE007F;
    /** The five skin tone modifiers, light to dark. */
    static final int MODIFIER_FIRST = 0x1F3FB;
    static final int MODIFIER_LAST = 0x1F3FF;

    /** Extended_Pictographic, as start/end pairs (V8, Unicode 16.0). */
    private static final int[] EXT_PICT = {
        0xA9, 0xA9, 0xAE, 0xAE, 0x203C, 0x203C, 0x2049, 0x2049, 0x2122, 0x2122, 0x2139, 0x2139,
        0x2194, 0x2199, 0x21A9, 0x21AA, 0x231A, 0x231B, 0x2328, 0x2328, 0x2388, 0x2388,
        0x23CF, 0x23CF, 0x23E9, 0x23F3, 0x23F8, 0x23FA, 0x24C2, 0x24C2, 0x25AA, 0x25AB,
        0x25B6, 0x25B6, 0x25C0, 0x25C0, 0x25FB, 0x25FE, 0x2600, 0x2605, 0x2607, 0x2612,
        0x2614, 0x2685, 0x2690, 0x2705, 0x2708, 0x2712, 0x2714, 0x2714, 0x2716, 0x2716,
        0x271D, 0x271D, 0x2721, 0x2721, 0x2728, 0x2728, 0x2733, 0x2734, 0x2744, 0x2744,
        0x2747, 0x2747, 0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757,
        0x2763, 0x2767, 0x2795, 0x2797, 0x27A1, 0x27A1, 0x27B0, 0x27B0, 0x27BF, 0x27BF,
        0x2934, 0x2935, 0x2B05, 0x2B07, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55,
        0x3030, 0x3030, 0x303D, 0x303D, 0x3297, 0x3297, 0x3299, 0x3299,
        0x1F000, 0x1F0FF, 0x1F10D, 0x1F10F, 0x1F12F, 0x1F12F, 0x1F16C, 0x1F171,
        0x1F17E, 0x1F17F, 0x1F18E, 0x1F18E, 0x1F191, 0x1F19A, 0x1F1AD, 0x1F1E5,
        0x1F201, 0x1F20F, 0x1F21A, 0x1F21A, 0x1F22F, 0x1F22F, 0x1F232, 0x1F23A,
        0x1F23C, 0x1F23F, 0x1F249, 0x1F3FA, 0x1F400, 0x1F53D, 0x1F546, 0x1F64F,
        0x1F680, 0x1F6FF, 0x1F774, 0x1F77F, 0x1F7D5, 0x1F7FF, 0x1F80C, 0x1F80F,
        0x1F848, 0x1F84F, 0x1F85A, 0x1F85F, 0x1F888, 0x1F88F, 0x1F8AE, 0x1F8FF,
        0x1F90C, 0x1F93A, 0x1F93C, 0x1F945, 0x1F947, 0x1FAFF, 0x1FC00, 0x1FFFD,
    };

    /**
     * Code points a skin tone may follow, as start/end pairs: the data's tone bases and the middle
     * finger (Emoji_Modifier_Base without the family, which RGI never tones).
     */
    private static final int[] MODIFIER_BASES = {
        0x261D, 0x261D, 0x26F9, 0x26F9, 0x270A, 0x270D, 0x1F385, 0x1F385, 0x1F3C2, 0x1F3C4,
        0x1F3C7, 0x1F3C7, 0x1F3CA, 0x1F3CC, 0x1F442, 0x1F443, 0x1F446, 0x1F450,
        0x1F466, 0x1F469, 0x1F46B, 0x1F478, 0x1F47C, 0x1F47C, 0x1F481, 0x1F483,
        0x1F485, 0x1F487, 0x1F48F, 0x1F48F, 0x1F491, 0x1F491, 0x1F4AA, 0x1F4AA,
        0x1F574, 0x1F575, 0x1F57A, 0x1F57A, 0x1F590, 0x1F590, 0x1F595, 0x1F596,
        0x1F645, 0x1F647, 0x1F64B, 0x1F64F, 0x1F6A3, 0x1F6A3, 0x1F6B4, 0x1F6B6,
        0x1F6C0, 0x1F6C0, 0x1F6CC, 0x1F6CC, 0x1F90C, 0x1F90C, 0x1F90F, 0x1F90F,
        0x1F918, 0x1F91F, 0x1F926, 0x1F926, 0x1F930, 0x1F939, 0x1F93C, 0x1F93E,
        0x1F977, 0x1F977, 0x1F9B5, 0x1F9B6, 0x1F9B8, 0x1F9B9, 0x1F9BB, 0x1F9BB,
        0x1F9CD, 0x1F9CF, 0x1F9D1, 0x1F9DD, 0x1FAC3, 0x1FAC5, 0x1FAF0, 0x1FAF8,
    };

    /**
     * Pictographs shown as text by default (V8: assigned, Extended_Pictographic, not
     * Emoji_Presentation), as start/end pairs: inside an RGI ZWJ sequence each is followed by VS16
     * or a skin tone. Code points newer than V8's tables count as emoji by default, as every new
     * emoji is.
     */
    private static final int[] TEXT_DEFAULT = {
        0xA9, 0xA9, 0xAE, 0xAE, 0x203C, 0x203C, 0x2049, 0x2049, 0x2122, 0x2122, 0x2139, 0x2139,
        0x2194, 0x2199, 0x21A9, 0x21AA, 0x2328, 0x2328, 0x2388, 0x2388, 0x23CF, 0x23CF, 0x23ED, 0x23EF,
        0x23F1, 0x23F2, 0x23F8, 0x23FA, 0x24C2, 0x24C2, 0x25AA, 0x25AB, 0x25B6, 0x25B6, 0x25C0, 0x25C0,
        0x25FB, 0x25FC, 0x2600, 0x2605, 0x2607, 0x2612, 0x2616, 0x2647, 0x2654, 0x267E, 0x2680, 0x2685,
        0x2690, 0x2692, 0x2694, 0x26A0, 0x26A2, 0x26A9, 0x26AC, 0x26BC, 0x26BF, 0x26C3, 0x26C6, 0x26CD,
        0x26CF, 0x26D3, 0x26D5, 0x26E9, 0x26EB, 0x26F1, 0x26F4, 0x26F4, 0x26F6, 0x26F9, 0x26FB, 0x26FC,
        0x26FE, 0x2704, 0x2708, 0x2709, 0x270C, 0x2712, 0x2714, 0x2714, 0x2716, 0x2716, 0x271D, 0x271D,
        0x2721, 0x2721, 0x2733, 0x2734, 0x2744, 0x2744, 0x2747, 0x2747, 0x2763, 0x2767, 0x27A1, 0x27A1,
        0x2934, 0x2935, 0x2B05, 0x2B07, 0x3030, 0x3030, 0x303D, 0x303D, 0x3297, 0x3297, 0x3299, 0x3299,
        0x1F000, 0x1F003, 0x1F005, 0x1F02B, 0x1F030, 0x1F093, 0x1F0A0, 0x1F0AE, 0x1F0B1, 0x1F0BF,
        0x1F0C1, 0x1F0CE, 0x1F0D1, 0x1F0F5, 0x1F10D, 0x1F10F, 0x1F12F, 0x1F12F, 0x1F16C, 0x1F171,
        0x1F17E, 0x1F17F, 0x1F1AD, 0x1F1AD, 0x1F202, 0x1F202, 0x1F237, 0x1F237, 0x1F260, 0x1F265,
        0x1F321, 0x1F32C, 0x1F336, 0x1F336, 0x1F37D, 0x1F37D, 0x1F394, 0x1F39F, 0x1F3CB, 0x1F3CE,
        0x1F3D4, 0x1F3DF, 0x1F3F1, 0x1F3F3, 0x1F3F5, 0x1F3F7, 0x1F43F, 0x1F43F, 0x1F441, 0x1F441,
        0x1F4FD, 0x1F4FE, 0x1F546, 0x1F54A, 0x1F54F, 0x1F54F, 0x1F568, 0x1F579, 0x1F57B, 0x1F594,
        0x1F597, 0x1F5A3, 0x1F5A5, 0x1F5FA, 0x1F6C6, 0x1F6CB, 0x1F6CD, 0x1F6CF, 0x1F6D3, 0x1F6D4,
        0x1F6E0, 0x1F6EA, 0x1F6F0, 0x1F6F3, 0x1F774, 0x1F776, 0x1F77B, 0x1F77F, 0x1F7D5, 0x1F7D9,
        0x1F8B0, 0x1F8BB, 0x1F8C0, 0x1F8C1, 0x1FA00, 0x1FA53, 0x1FA60, 0x1FA6D,
    };

    /** The regions with a flag (the data's 259: RGI's, Emoji 17.0), two letters each. */
    static final String FLAGS =
            "ACADAEAFAGAIALAMAOAQARASATAUAWAXAZBABBBDBEBFBGBHBIBJBLBMBNBOBQBRBSBTBVBWBYBZCACCCDCF"
            + "CGCHCICKCLCMCNCOCPCQCRCUCVCWCXCYCZDEDGDJDKDMDODZEAECEEEGEHERESETEUFIFJFKFMFOFRGAGBGD"
            + "GEGFGGGHGIGLGMGNGPGQGRGSGTGUGWGYHKHMHNHRHTHUICIDIEILIMINIOIQIRISITJEJMJOJPKEKGKHKIKM"
            + "KNKPKRKWKYKZLALBLCLILKLRLSLTLULVLYMAMCMDMEMFMGMHMKMLMMMNMOMPMQMRMSMTMUMVMWMXMYMZNANC"
            + "NENFNGNINLNONPNRNUNZOMPAPEPFPGPHPKPLPMPNPRPSPTPWPYQARERORSRURWSASBSCSDSESGSHSISJSKSL"
            + "SMSNSOSRSSSTSVSXSYSZTATCTDTFTGTHTJTKTLTMTNTOTRTTTVTWTZUAUGUMUNUSUYUZVAVCVEVGVIVNVUWF"
            + "WSXKYEYTZAZMZW";

    /** RGI's subdivision flags (England, Scotland, Wales): their tag letters. */
    private static final String[] SUBDIVISIONS = {"gbeng", "gbsct", "gbwls"};

    private static boolean inRanges(int[] ranges, int cp) {
        int low = 0;
        int high = ranges.length / 2 - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (cp < ranges[mid * 2]) high = mid - 1;
            else if (cp > ranges[mid * 2 + 1]) low = mid + 1;
            else return true;
        }
        return false;
    }

    static boolean isExtPict(int cp) {
        return cp >= 0xA9 && inRanges(EXT_PICT, cp);
    }

    static boolean isRegional(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    static boolean isModifier(int cp) {
        return cp >= MODIFIER_FIRST && cp <= MODIFIER_LAST;
    }

    static boolean isModifierBase(int cp) {
        return inRanges(MODIFIER_BASES, cp);
    }

    /** JavaScript's white space and line terminators (what {@code trim} and {@code \s} take). */
    static boolean isJsSpace(int cp) {
        switch (cp) {
            case 0x09: case 0x0A: case 0x0B: case 0x0C: case 0x0D: case 0x20: case 0xA0:
            case 0x1680: case 0x2028: case 0x2029: case 0x202F: case 0x205F: case 0x3000:
            case 0xFEFF:
                return true;
            default:
                return cp >= 0x2000 && cp <= 0x200A;
        }
    }

    /** JavaScript's {@code trim} (Java's own trims only ASCII control characters and spaces). */
    static String jsTrim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isJsSpace(text.charAt(start))) start++;
        while (end > start && isJsSpace(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    static boolean isAllSpace(String text) {
        if (text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            if (!isJsSpace(text.charAt(i))) return false;
        }
        return true;
    }

    static int[] codePoints(String text) {
        int[] out = new int[text.codePointCount(0, text.length())];
        for (int i = 0, k = 0; i < text.length(); k++) {
            out[k] = text.codePointAt(i);
            i += Character.charCount(out[k]);
        }
        return out;
    }

    // --- Grapheme clusters -------------------------------------------------------------------

    private static boolean isExtend(int cp) {
        if (isModifier(cp) || cp == 0x200C || (cp >= 0xE0020 && cp <= 0xE007F)) return true;
        int type = Character.getType(cp);
        // Spacing marks join too (GB9a), so every mark does.
        return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK;
    }

    private static boolean isControl(int cp) {
        if (cp == 0x200C || cp == ZWJ || (cp >= 0xE0020 && cp <= 0xE007F)) return false;
        int type = Character.getType(cp);
        return type == Character.CONTROL || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }

    private static final int HANGUL_NONE = 0;
    private static final int HANGUL_L = 1;
    private static final int HANGUL_V = 2;
    private static final int HANGUL_T = 3;
    private static final int HANGUL_LV = 4;
    private static final int HANGUL_LVT = 5;

    private static int hangul(int cp) {
        if ((cp >= 0x1100 && cp <= 0x115F) || (cp >= 0xA960 && cp <= 0xA97C)) return HANGUL_L;
        if ((cp >= 0x1160 && cp <= 0x11A7) || (cp >= 0xD7B0 && cp <= 0xD7C6)) return HANGUL_V;
        if ((cp >= 0x11A8 && cp <= 0x11FF) || (cp >= 0xD7CB && cp <= 0xD7FB)) return HANGUL_T;
        if (cp >= 0xAC00 && cp <= 0xD7A3) return (cp - 0xAC00) % 28 == 0 ? HANGUL_LV : HANGUL_LVT;
        return HANGUL_NONE;
    }

    private static boolean hangulJoins(int prev, int next) {
        int a = hangul(prev);
        if (a == HANGUL_NONE) return false;
        int b = hangul(next);
        if (a == HANGUL_L) return b == HANGUL_L || b == HANGUL_V || b == HANGUL_LV || b == HANGUL_LVT;
        if (a == HANGUL_LV || a == HANGUL_V) return b == HANGUL_V || b == HANGUL_T;
        return b == HANGUL_T;
    }

    private static final int PICT_NONE = 0;
    /** After a pictograph and its marks. */
    private static final int PICT = 1;
    /** After a pictograph, its marks and a ZWJ: the next pictograph joins (GB11). */
    private static final int PICT_ZWJ = 2;

    /** Where the grapheme cluster that starts at {@code start} (a UTF-16 index) ends. */
    static int graphemeEnd(String text, int start) {
        int n = text.length();
        if (start >= n) return n;
        int cp = text.codePointAt(start);
        int i = start + Character.charCount(cp);
        if (cp == '\r') return i < n && text.charAt(i) == '\n' ? i + 1 : i;
        if (cp == '\n' || isControl(cp)) return i;
        int prev = cp;
        int pict = isExtPict(cp) ? PICT : PICT_NONE;
        int regional = isRegional(cp) ? 1 : 0;
        while (i < n) {
            int next = text.codePointAt(i);
            boolean join;
            if (next == '\r' || next == '\n' || isControl(next)) join = false;
            else if (hangulJoins(prev, next)) join = true;
            else if (next == ZWJ || isExtend(next)) join = true;
            else if (prev == ZWJ && pict == PICT_ZWJ && isExtPict(next)) join = true;
            else join = regional % 2 == 1 && isRegional(next);
            if (!join) break;
            if (isExtPict(next)) pict = PICT;
            else if (next == ZWJ) pict = pict == PICT ? PICT_ZWJ : PICT_NONE;
            else if (!isExtend(next) || pict != PICT) pict = PICT_NONE;
            regional = isRegional(next) ? regional + 1 : 0;
            prev = next;
            i += Character.charCount(next);
        }
        return i;
    }

    // --- One emoji -----------------------------------------------------------------------------

    /**
     * Whether one grapheme is one emoji, as the desktop's {@code bigEmojiCount} asks: an RGI emoji
     * or a lone pictograph with or without VS16. RGI is read by its shape (keycap, flag of a known
     * region, subdivision flag, a tone after a tone base, pictographs joined by ZWJ), since its
     * list is not on the phone; a made-up ZWJ sequence of pictographs counts as one.
     */
    static boolean isEmoji(String grapheme) {
        int[] cps = codePoints(grapheme);
        int n = cps.length;
        if (n == 0) return false;
        int first = cps[0];
        if (isExtPict(first) && (n == 1 || (n == 2 && cps[1] == VS16))) return true;
        // A skin tone swatch alone is an RGI emoji.
        if (n == 1) return isModifier(first);
        if (n == 3 && cps[2] == KEYCAP) {
            return cps[1] == VS16 && ((first >= '0' && first <= '9') || first == '#' || first == '*');
        }
        if (n == 2 && isRegional(first) && isRegional(cps[1])) return isFlag(first, cps[1]);
        if (first == BLACK_FLAG && n > 2 && cps[n - 1] == CANCEL_TAG) return isSubdivision(cps);
        return isZwjChain(cps);
    }

    private static boolean isFlag(int a, int b) {
        char first = (char) ('A' + (a - 0x1F1E6));
        char second = (char) ('A' + (b - 0x1F1E6));
        for (int i = 0; i + 1 < FLAGS.length(); i += 2) {
            if (FLAGS.charAt(i) == first && FLAGS.charAt(i + 1) == second) return true;
        }
        return false;
    }

    private static boolean isSubdivision(int[] cps) {
        StringBuilder tags = new StringBuilder();
        for (int i = 1; i < cps.length - 1; i++) {
            if (cps[i] < 0xE0020 || cps[i] > 0xE007E) return false;
            tags.append((char) (cps[i] - 0xE0000));
        }
        String code = tags.toString();
        for (String subdivision : SUBDIVISIONS) {
            if (subdivision.equals(code)) return true;
        }
        return false;
    }

    /**
     * Pictographs (each maybe with VS16, or a tone after a tone base) joined by ZWJ; in a sequence
     * of more than one, fully qualified as RGI's are: a text-default pictograph has its VS16 or
     * tone ("a judge" with the scales but no VS16 is not one emoji).
     */
    private static boolean isZwjChain(int[] cps) {
        int i = 0;
        int n = cps.length;
        int elements = 0;
        boolean qualified = true;
        while (true) {
            int cp = cps[i++];
            if (!isExtPict(cp)) return false;
            elements++;
            if (i < n && cps[i] == VS16) {
                i++;
            } else if (i < n && isModifier(cps[i])) {
                if (!isModifierBase(cp)) return false;
                i++;
            } else if (inRanges(TEXT_DEFAULT, cp)) {
                qualified = false;
            }
            if (i == n) return elements == 1 || qualified;
            if (cps[i] != ZWJ || i + 1 == n) return false;
            i++;
        }
    }
}
