package gg.projects.mobile.content;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Small file helpers: bounded reads and write-to-temp-then-rename. */
public final class AtomicFiles {
    private AtomicFiles() {}

    public static byte[] read(File file, long maxBytes) throws IOException {
        if (file.length() > maxBytes) throw new IOException(file + " is larger than " + maxBytes + " bytes");
        try (InputStream in = new FileInputStream(file)) {
            return readBounded(in, maxBytes);
        }
    }

    public static byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) throw new IOException("response is larger than " + maxBytes + " bytes");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * Writes {@code bytes} to a temporary sibling, syncs it, then renames it over {@code target}. The
     * sibling has a name of its own (M17), so two writers of one file never share it: the last
     * rename wins and neither deletes what the other wrote.
     */
    public static void write(File target, byte[] bytes) throws IOException {
        File parent = target.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create " + parent);
        }
        File tmp = File.createTempFile(target.getName() + ".tmp", null, parent);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        } catch (IOException | RuntimeException e) {
            tmp.delete();
            throw e;
        }
        if (!tmp.renameTo(target)) {
            // Some file systems (Windows, used by unit tests) refuse to rename over a file.
            if (!target.delete() && target.exists()) {
                tmp.delete();
                throw new IOException("cannot replace " + target);
            }
            if (!tmp.renameTo(target)) {
                tmp.delete();
                throw new IOException("cannot move " + tmp + " to " + target);
            }
        }
    }
}
