package gg.projects.mobile.content;

import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft version order of the content format ("Pack families", mirror of pl-content's
 * compare_minecraft_versions): the leading run of digits and dots is compared numerically,
 * component by component ({@code 1.21.10 > 1.21.9}, {@code 26.1 > 1.21.10}, missing components
 * count as 0); for equal numbers the plain release is newer than anything with a suffix
 * ({@code 1.21.4 > 1.21.4-rc1 > 1.21.4-pre1}, suffixes compare as text). Ids without a leading
 * number sort before all others, as text.
 */
public final class MinecraftVersions {
    private MinecraftVersions() {}

    /** Negative when {@code a} is older than {@code b}, positive when newer, 0 when equal. */
    public static int compare(String a, String b) {
        String x = a == null ? "" : a.trim();
        String y = b == null ? "" : b.trim();
        Split sx = split(x);
        Split sy = split(y);
        boolean noX = sx.numbers.isEmpty();
        boolean noY = sy.numbers.isEmpty();
        if (noX && noY) return Integer.signum(x.compareTo(y));
        if (noX) return -1;
        if (noY) return 1;
        int count = Math.max(sx.numbers.size(), sy.numbers.size());
        for (int i = 0; i < count; i++) {
            long p = i < sx.numbers.size() ? sx.numbers.get(i) : 0;
            long q = i < sy.numbers.size() ? sy.numbers.get(i) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        boolean plainX = sx.suffix.isEmpty();
        boolean plainY = sy.suffix.isEmpty();
        if (plainX && plainY) return 0;
        if (plainX) return 1;
        if (plainY) return -1;
        return Integer.signum(sx.suffix.compareTo(sy.suffix));
    }

    private static final class Split {
        final List<Long> numbers = new ArrayList<>();
        String suffix = "";
    }

    /** {@code 1.21.4-pre1} gives ([1, 21, 4], "-pre1"). */
    private static Split split(String version) {
        Split out = new Split();
        int end = 0;
        while (end < version.length()) {
            char c = version.charAt(end);
            if (!((c >= '0' && c <= '9') || c == '.')) break;
            end++;
        }
        for (String part : version.substring(0, end).split("\\.")) {
            if (part.isEmpty()) continue;
            long value;
            try {
                value = Long.parseLong(part);
            } catch (NumberFormatException e) {
                value = Long.MAX_VALUE;
            }
            out.numbers.add(value);
        }
        out.suffix = version.substring(end);
        return out;
    }
}
