package gg.projects.mobile.content;

/**
 * Family and item ids (docs/content-format.md): 1 to 64 characters from {@code [A-Za-z0-9._-]},
 * not {@code .} or {@code ..}. Modrinth project ids and slugs fit.
 */
public final class ContentIds {
    private ContentIds() {}

    public static final int MAX_LENGTH = 64;

    public static boolean isValid(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_LENGTH) return false;
        if (id.equals(".") || id.equals("..")) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }
}
