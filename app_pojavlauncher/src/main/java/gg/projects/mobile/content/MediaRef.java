package gg.projects.mobile.content;

import androidx.annotation.Keep;

import java.util.Locale;

/** An image or video stored at {@code media/<sha256>.<ext>}. */
@Keep
public final class MediaRef {
    public String path;
    public String sha256;
    public long size;
    /** {@code image} or {@code video}; unknown kinds show the poster or nothing. */
    public String kind;
    public Integer width;
    public Integer height;
    public String blurhash;
    public MediaRef poster;
    public String alt;

    public boolean isImage() {
        return kind == null || kind.equals("image");
    }

    /** The image to show: this media when it is an image, otherwise its poster (if any). */
    public MediaRef displayImage() {
        MediaRef current = this;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            if (current.isImage()) return current;
            current = current.poster;
        }
        return null;
    }

    public boolean isValid() {
        return ContentPaths.isMirrorPath(path) && Hashes.normalizeHex(sha256, 64) != null
                && size > 0 && size <= ContentConfig.MAX_MEDIA_BYTES;
    }

    /** Lowercase extension from the path (alphanumeric, at most 8 characters) or {@code bin}. */
    public String extension() {
        String name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String ext = name.substring(dot + 1);
            if (ext.length() >= 1 && ext.length() <= 8 && isAsciiAlphanumeric(ext)) return ext.toLowerCase(Locale.ROOT);
        }
        return "bin";
    }

    public String cacheFileName() {
        return Hashes.normalizeHex(sha256, 64) + "." + extension();
    }

    private static boolean isAsciiAlphanumeric(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }
}
