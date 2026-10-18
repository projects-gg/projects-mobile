package gg.projects.mobile.accounts;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import net.kdt.pojavlaunch.Tools;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.AtomicFiles;

/**
 * The Android side of {@link SkinFace}: a head picture cut from a skin texture and kept as a small
 * PNG where upstream kept the one it downloaded from mc-heads.net.
 */
public final class AccountHeads {
    private AccountHeads() {}

    private static final String TAG = "AccountHeads";
    /** Skin textures are a few KiB; anything far bigger is not one. */
    private static final int MAX_TEXTURE_BYTES = 2 * 1024 * 1024;
    /** Client jars looked at for the default skins (newest first); any 1.19.3+ client has them all. */
    private static final int MAX_JARS = 12;
    /** The head is drawn this many pixels square (crisp pixels: nearest-neighbour). */
    private static final int HEAD_PIXELS = 96;

    /**
     * Downloads a Microsoft account's skin from Mojang's texture host and saves its head to
     * {@code out}; false when the address is not a Mojang texture or the download fails.
     */
    @WorkerThread
    public static boolean saveFromTexture(@Nullable String skinUrl, File out) {
        String url = SkinFace.mojangTextureUrl(skinUrl);
        if (url == null) return false;
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(15_000);
            connection.setRequestProperty("User-Agent", ProjectsContent.userAgent());
            if (connection.getResponseCode() != 200) return false;
            byte[] png;
            try (InputStream in = connection.getInputStream()) {
                png = AtomicFiles.readBounded(in, MAX_TEXTURE_BYTES);
            }
            return save(png, out);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "skin not downloaded", e);
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Saves the head of the default skin Minecraft gives {@code profile} (an offline account's
     * UUID), read from a client jar on the phone; false when no installed version has it yet.
     */
    public static boolean saveDefault(java.util.UUID profile, File out) {
        SkinFace.DefaultSkin skin = SkinFace.defaultSkin(profile);
        for (File jar : clientJars()) {
            try (ZipFile zip = new ZipFile(jar)) {
                ZipEntry entry = zip.getEntry(skin.jarEntry());
                if (entry == null) continue;
                byte[] png;
                try (InputStream in = zip.getInputStream(entry)) {
                    png = AtomicFiles.readBounded(in, MAX_TEXTURE_BYTES);
                }
                if (save(png, out)) return true;
            } catch (IOException | RuntimeException e) {
                // A broken or unfinished jar: try the next one.
            }
        }
        return false;
    }

    private static boolean save(byte[] png, File out) throws IOException {
        Bitmap texture = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (texture == null) return false;
        try {
            int width = texture.getWidth();
            int height = texture.getHeight();
            int[] argb = new int[width * height];
            texture.getPixels(argb, 0, width, 0, 0, width, height);
            int[] face = SkinFace.face(argb, width, height);
            if (face == null) return false;
            int size = SkinFace.faceSize(width);
            Bitmap head = Bitmap.createBitmap(face, size, size, Bitmap.Config.ARGB_8888);
            Bitmap scaled = Bitmap.createScaledBitmap(head, HEAD_PIXELS, HEAD_PIXELS, false);
            File parent = out.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
            File temp = new File(out.getPath() + ".tmp");
            try (OutputStream stream = new FileOutputStream(temp)) {
                scaled.compress(Bitmap.CompressFormat.PNG, 100, stream);
            }
            if (!temp.renameTo(out)) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                throw new IOException("cannot write " + out);
            }
            if (scaled != head) scaled.recycle();
            head.recycle();
            return true;
        } finally {
            texture.recycle();
        }
    }

    /** Installed client jars, newest first. */
    private static List<File> clientJars() {
        List<File> jars = new ArrayList<>();
        File[] versions = Tools.DIR_HOME_VERSION == null ? null : new File(Tools.DIR_HOME_VERSION).listFiles(File::isDirectory);
        if (versions == null) return jars;
        for (File version : versions) {
            File jar = new File(version, version.getName() + ".jar");
            if (jar.isFile()) jars.add(jar);
        }
        Collections.sort(jars, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return jars.size() > MAX_JARS ? jars.subList(0, MAX_JARS) : jars;
    }
}
