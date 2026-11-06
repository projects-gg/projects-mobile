package gg.projects.mobile.accounts;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.commons.codec.binary.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import gg.projects.mobile.ProjectsContent;
import gg.projects.mobile.content.AtomicFiles;

/**
 * M053 (SKIN1 on the phone): "Oyuncu adı", a premium player's skin for the Projects skin, the desktop's
 * {@code pl_auth::mojang_lookup} (skin1.md §4.2): the name's profile, its signed textures property (the service passes
 * {@code value} and {@code signature} on; Lobi checks the signature), and the texture for the preview. Answers (failures
 * too) are kept 10 minutes per name, at most 64.
 */
public final class MojangSkinLookup {
    private MojangSkinLookup() {}

    private static final String TAG = "ProjectsSkin";
    private static final long KEEP_MS = 10 * 60 * 1000L;
    private static final int KEEP_MAX = 64;
    private static final int JSON_MAX = 64 * 1024;
    private static final int TEXTURE_MAX = 2 * 1024 * 1024;

    public enum Kind { FOUND, NO_ACCOUNT, NO_SKIN, UNREACHABLE }

    public static final class Result {
        public final Kind kind;
        /** Mojang's spelling of the name. */
        @Nullable public final String name;
        @Nullable public final String id;
        @Nullable public final String texture;
        public final boolean slim;
        @Nullable public final String value;
        @Nullable public final String signature;
        /** The texture PNG (the preview). */
        @Nullable public final byte[] png;

        Result(Kind kind, @Nullable String name, @Nullable String id, @Nullable String texture, boolean slim, @Nullable String value,
               @Nullable String signature, @Nullable byte[] png) {
            this.kind = kind;
            this.name = name;
            this.id = id;
            this.texture = texture;
            this.slim = slim;
            this.value = value;
            this.signature = signature;
            this.png = png;
        }

        static Result of(Kind kind) {
            return new Result(kind, null, null, null, false, null, null, null);
        }
    }

    private static final Map<String, Object[]> KEPT = new LinkedHashMap<>();

    @WorkerThread
    public static Result lookUp(String name) {
        if (!ProjectsSkinState.premiumName(name)) return Result.of(Kind.NO_ACCOUNT);
        String key = name.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        synchronized (KEPT) {
            Object[] kept = KEPT.get(key);
            if (kept != null && now - (Long) kept[0] < KEEP_MS) return (Result) kept[1];
        }
        Result result = fetch(name);
        synchronized (KEPT) {
            KEPT.remove(key);
            KEPT.put(key, new Object[]{now, result});
            while (KEPT.size() > KEEP_MAX) KEPT.remove(KEPT.keySet().iterator().next());
        }
        return result;
    }

    private static Result fetch(String name) {
        try {
            Answer profile = get("https://api.minecraftservices.com/minecraft/profile/lookup/name/" + name);
            if (profile.status == 204 || profile.status == 400 || profile.status == 404) return Result.of(Kind.NO_ACCOUNT);
            if (profile.status != 200) {
                profile = get("https://api.mojang.com/users/profiles/minecraft/" + name);
                if (profile.status == 204 || profile.status == 404) return Result.of(Kind.NO_ACCOUNT);
                if (profile.status != 200) return Result.of(Kind.UNREACHABLE);
            }
            JsonObject p = object(profile.body);
            String id = p == null ? null : ProjectsSkinState.str(p, "id");
            String spelled = p == null ? null : ProjectsSkinState.str(p, "name");
            if (id == null || !id.matches("[0-9a-fA-F]{32}")) return Result.of(Kind.UNREACHABLE);
            Answer session = get("https://sessionserver.mojang.com/session/minecraft/profile/" + id + "?unsigned=false");
            if (session.status != 200) return Result.of(Kind.UNREACHABLE);
            Result read = fromSession(object(session.body), id, spelled == null ? name : spelled);
            if (read.kind != Kind.FOUND) return read;
            Answer texture = getBytes("https://textures.minecraft.net/texture/" + read.texture);
            if (texture.status != 200 || !SkinPng.looksLikePng(texture.bytes)) return Result.of(Kind.UNREACHABLE);
            return new Result(Kind.FOUND, read.name, read.id, read.texture, read.slim, read.value, read.signature, texture.bytes);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "skin lookup failed");
            return Result.of(Kind.UNREACHABLE);
        }
    }

    /** A texture on Mojang's host as a PNG (the Projects skin's preview), or null. Worker thread. */
    @Nullable
    @WorkerThread
    public static byte[] texturePng(@Nullable String texture) {
        if (texture == null || !texture.matches("[0-9a-fA-F]{32,64}")) return null;
        try {
            Answer a = getBytes("https://textures.minecraft.net/texture/" + texture.toLowerCase(Locale.ROOT));
            return a.status == 200 && SkinPng.looksLikePng(a.bytes) ? a.bytes : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The session profile's signed textures: the texture id on Mojang's host, the arm model, {@code value} and its signature. */
    static Result fromSession(@Nullable JsonObject session, String id, String name) {
        if (session == null) return Result.of(Kind.UNREACHABLE);
        JsonElement properties = session.get("properties");
        if (properties == null || !properties.isJsonArray()) return Result.of(Kind.NO_SKIN);
        String value = null;
        String signature = null;
        for (JsonElement e : properties.getAsJsonArray()) {
            if (!e.isJsonObject()) continue;
            JsonObject p = e.getAsJsonObject();
            if ("textures".equals(ProjectsSkinState.str(p, "name"))) {
                value = ProjectsSkinState.str(p, "value");
                signature = ProjectsSkinState.str(p, "signature");
            }
        }
        if (value == null) return Result.of(Kind.NO_SKIN);
        if (signature == null || signature.isEmpty()) return Result.of(Kind.UNREACHABLE);
        JsonObject decoded;
        try {
            decoded = object(new String(Base64.decodeBase64(value.getBytes(StandardCharsets.US_ASCII)), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return Result.of(Kind.UNREACHABLE);
        }
        if (decoded == null || !id.equalsIgnoreCase(ProjectsSkinState.str(decoded, "profileId"))) return Result.of(Kind.UNREACHABLE);
        JsonObject skin = ProjectsSkinState.obj(ProjectsSkinState.obj(decoded, "textures"), "SKIN");
        if (skin == null) return Result.of(Kind.NO_SKIN);
        String url = ProjectsSkinState.str(skin, "url");
        String texture = textureOf(url);
        if (texture == null) return Result.of(Kind.UNREACHABLE);
        boolean slim = "slim".equals(ProjectsSkinState.str(ProjectsSkinState.obj(skin, "metadata"), "model"));
        return new Result(Kind.FOUND, name, id.toLowerCase(Locale.ROOT), texture, slim, value, signature, null);
    }

    /** A texture address's id when it is on {@code textures.minecraft.net} (32–64 hex), else null. */
    @Nullable
    static String textureOf(@Nullable String url) {
        if (url == null) return null;
        String rest;
        if (url.startsWith("http://textures.minecraft.net/texture/")) rest = url.substring("http://textures.minecraft.net/texture/".length());
        else if (url.startsWith("https://textures.minecraft.net/texture/")) rest = url.substring("https://textures.minecraft.net/texture/".length());
        else return null;
        return rest.matches("[0-9a-fA-F]{32,64}") ? rest.toLowerCase(Locale.ROOT) : null;
    }

    @Nullable
    private static JsonObject object(@Nullable String text) {
        if (text == null) return null;
        try {
            JsonElement e = JsonParser.parseString(text);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Answer {
        final int status;
        @Nullable final String body;
        @Nullable final byte[] bytes;

        Answer(int status, @Nullable byte[] bytes) {
            this.status = status;
            this.bytes = bytes;
            this.body = bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static Answer get(String address) throws IOException {
        return request(address, JSON_MAX);
    }

    private static Answer getBytes(String address) throws IOException {
        return request(address, TEXTURE_MAX);
    }

    private static Answer request(String address, int max) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("User-Agent", ProjectsContent.userAgent());
            int status = connection.getResponseCode();
            if (status != 200) return new Answer(status, null);
            try (InputStream in = connection.getInputStream()) {
                return new Answer(status, AtomicFiles.readBounded(in, max));
            }
        } finally {
            connection.disconnect();
        }
    }
}
