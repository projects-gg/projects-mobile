package gg.projects.mobile.content;

import java.util.Locale;

/** Kind of a pack file, by its top-level folder (mirror of pl-content's FileCategory). */
public enum FileCategory {
    MOD, RESOURCE_PACK, SHADER_PACK, CONFIG, OTHER;

    private static final String[] CONFIG_EXTENSIONS = {
            ".txt", ".json", ".json5", ".toml", ".properties", ".cfg", ".yml", ".yaml"};

    public static FileCategory of(String path) {
        int slash = path.indexOf('/');
        String first = slash >= 0 ? path.substring(0, slash) : "";
        String rest = slash >= 0 ? path.substring(slash + 1) : path;
        if (asciiEqualsIgnoreCase(first, "mods")) return MOD;
        if (asciiEqualsIgnoreCase(first, "resourcepacks")) return RESOURCE_PACK;
        if (asciiEqualsIgnoreCase(first, "shaderpacks")) return SHADER_PACK;
        if (asciiEqualsIgnoreCase(first, "config") || asciiEqualsIgnoreCase(first, "defaultconfigs")) return CONFIG;
        if (first.isEmpty()) {
            String lower = rest.toLowerCase(Locale.ROOT);
            for (String extension : CONFIG_EXTENSIONS) if (lower.endsWith(extension)) return CONFIG;
        }
        return OTHER;
    }

    /** Case-insensitive for ASCII letters only (like Rust's eq_ignore_ascii_case). */
    static boolean asciiEqualsIgnoreCase(String a, String b) {
        if (a.length() != b.length()) return false;
        for (int i = 0; i < a.length(); i++) {
            char x = a.charAt(i);
            char y = b.charAt(i);
            if (x >= 'A' && x <= 'Z') x = (char) (x + 32);
            if (y >= 'A' && y <= 'Z') y = (char) (y + 32);
            if (x != y) return false;
        }
        return true;
    }
}
