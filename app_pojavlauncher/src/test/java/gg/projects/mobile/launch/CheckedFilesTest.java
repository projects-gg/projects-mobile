package gg.projects.mobile.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.content.AtomicFiles;

/** The start's file check remembers what it hashed (M16). */
public class CheckedFilesTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static final String SHA = "a9993e364706816aba3e25717850c26c9cd0d89d"; // SHA-1 of "abc"
    private static final int DAY = 20_745;

    private static CheckedFiles.Stamp stamp(long size, long modified, long changed, long inode) {
        return new CheckedFiles.Stamp(size, modified, changed, inode);
    }

    @Test
    public void aFileIsKnownWhileItKeepsItsStamp() {
        File base = temp.getRoot();
        CheckedFiles files = CheckedFiles.empty(base, DAY);
        File asset = new File(base, "assets/objects/a9/" + SHA);
        CheckedFiles.Stamp s = stamp(3, 1_700_000_000_123L, 1_700_000_000_456L, 42);
        assertNull(files.known(asset, s));
        files.put(asset, s, SHA.toUpperCase());
        assertEquals(SHA, files.known(asset, s));
        // Anything different: hashed again.
        assertNull("size", files.known(asset, stamp(4, s.modified, s.changed, s.inode)));
        assertNull("modified", files.known(asset, stamp(3, s.modified + 1, s.changed, s.inode)));
        assertNull("written again: the change time moved, though the modification time was put back",
                files.known(asset, stamp(3, s.modified, s.changed + 1, s.inode)));
        assertNull("replaced by another file", files.known(asset, stamp(3, s.modified, s.changed, 43)));
        assertNull("another file", files.known(new File(base, "assets/other"), s));
        files.forget(asset);
        assertNull(files.known(asset, s));
    }

    @Test
    public void unknownTimesAreNeverTrusted() {
        CheckedFiles files = CheckedFiles.empty(temp.getRoot(), DAY);
        File f = new File(temp.getRoot(), "lib.jar");
        files.put(f, stamp(10, 0, 5, 1), SHA);
        assertNull(files.known(f, stamp(10, 0, 5, 1)));
        files.put(f, stamp(10, 5, 0, 1), SHA);
        assertNull(files.known(f, stamp(10, 5, 0, 1)));
        files.put(f, stamp(10, 5, 6, 1), "not a hash");
        assertNull(files.known(f, stamp(10, 5, 6, 1)));
        assertEquals(0, files.size());
    }

    @Test
    public void theStoreKeepsWhatItKnowsAcrossStarts() throws IOException {
        File base = new File(temp.getRoot(), "game home");
        File store = new File(temp.getRoot(), "data/projects/checked-files.txt");
        CheckedFiles files = CheckedFiles.empty(base, DAY);
        File asset = new File(base, ".minecraft/assets/objects/a9/" + SHA);
        File outside = new File(temp.getRoot(), "elsewhere/client.jar");
        files.put(asset, stamp(3, 11, 12, 13), SHA);
        files.put(outside, stamp(5, 21, 22, 23), "0123456789abcdef0123456789abcdef01234567");
        assertTrue(files.save(store));
        String text = new String(AtomicFiles.read(store, 1 << 20), StandardCharsets.UTF_8);
        assertTrue(text.startsWith(CheckedFiles.HEADER + "\n"));
        assertTrue("paths under the game folder are short", text.contains(" ~" + File.separator + ".minecraft"));
        assertFalse(text.contains(base.getAbsolutePath()));

        CheckedFiles again = CheckedFiles.load(store, base, DAY + 1);
        assertEquals(SHA, again.known(asset, stamp(3, 11, 12, 13)));
        assertEquals("0123456789abcdef0123456789abcdef01234567", again.known(outside, stamp(5, 21, 22, 23)));
        assertNull(again.known(asset, stamp(3, 11, 99, 13)));
        // Nothing new: no write (the check above marked them seen today, so this one writes once).
        assertTrue(again.save(store));
        CheckedFiles third = CheckedFiles.load(store, base, DAY + 1);
        assertEquals(SHA, third.known(asset, stamp(3, 11, 12, 13)));
        assertFalse("unchanged since the last write", third.save(store));
    }

    @Test
    public void filesNotSeenForTwoMonthsAreForgotten() throws IOException {
        File base = temp.getRoot();
        File store = new File(base, "store.txt");
        CheckedFiles files = CheckedFiles.empty(base, DAY);
        File old = new File(base, "versions/1.21/1.21.jar");
        File used = new File(base, "versions/26.1.2/26.1.2.jar");
        files.put(old, stamp(1, 2, 3, 4), SHA);
        files.put(used, stamp(5, 6, 7, 8), SHA);
        files.save(store);
        CheckedFiles later = CheckedFiles.load(store, base, DAY + CheckedFiles.KEEP_DAYS + 1);
        assertEquals(SHA, later.known(used, stamp(5, 6, 7, 8)));
        later.save(store);
        CheckedFiles next = CheckedFiles.load(store, base, DAY + CheckedFiles.KEEP_DAYS + 1);
        assertNull(next.known(old, stamp(1, 2, 3, 4)));
        assertEquals(SHA, next.known(used, stamp(5, 6, 7, 8)));
    }

    @Test
    public void aBrokenStoreMeansHashingEverythingOnce() throws IOException {
        File base = temp.getRoot();
        File store = new File(base, "store.txt");
        File f = new File(base, "a.jar");
        AtomicFiles.write(store, "something else 9\n".getBytes(StandardCharsets.UTF_8));
        assertEquals(0, CheckedFiles.load(store, base, DAY).size());
        String good = SHA + " 1 2 3 4 " + DAY + " ~" + File.separator + "a.jar";
        String content = CheckedFiles.HEADER + "\n" + good + "\nnot a line\n" + SHA + " x 2 3 4 5 ~/b.jar\n"
                + "zz" + SHA.substring(2) + " 1 2 3 4 5 ~/c.jar\n" + SHA + " 1 2 3 4 5\n";
        AtomicFiles.write(store, content.getBytes(StandardCharsets.UTF_8));
        CheckedFiles files = CheckedFiles.load(store, base, DAY);
        assertEquals(1, files.size());
        assertEquals(SHA, files.known(f, stamp(1, 2, 3, 4)));
        assertEquals(0, CheckedFiles.load(new File(base, "missing.txt"), base, DAY).size());
    }

    @Test
    public void thePoolsThreadsCanRecordAtOnce() throws InterruptedException {
        File base = temp.getRoot();
        CheckedFiles files = CheckedFiles.empty(base, DAY);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 4; t++) {
            final int offset = t * 1000;
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 1000; i++) {
                    File f = new File(base, "assets/" + (offset + i));
                    files.put(f, stamp(i, 1, 2, offset + i), SHA);
                    files.known(f, stamp(i, 1, 2, offset + i));
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join();
        assertEquals(4000, files.size());
    }

    @Test
    public void hashesFilesThroughTheGivenBuffer() throws IOException {
        File f = new File(temp.getRoot(), "abc.txt");
        AtomicFiles.write(f, "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals(SHA, CheckedFiles.sha1(f, new byte[2]));
        File big = new File(temp.getRoot(), "big.bin");
        byte[] data = new byte[300_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31);
        AtomicFiles.write(big, data);
        assertEquals(CheckedFiles.sha1(big, new byte[64 * 1024]), CheckedFiles.sha1(big, new byte[7]));
        assertEquals(20_745, CheckedFiles.day(20_745L * 86_400_000L + 1));
    }
}
