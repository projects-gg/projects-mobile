package gg.projects.mobile.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The cloud contract's shared vectors (copies start with {@code //} lines naming their source) and small fixtures. */
final class Vectors {
    private Vectors() {}

    static String resource(String path) {
        try (InputStream in = Vectors.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing test resource " + path);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static JsonObject load(String path) {
        StringBuilder json = new StringBuilder();
        for (String line : resource(path).split("\n")) {
            if (!line.startsWith("//")) json.append(line).append('\n');
        }
        return JsonParser.parseString(json.toString()).getAsJsonObject();
    }

    static byte[] hex(String hex) {
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format("%02x", b & 0xFF));
        return out.toString();
    }

    static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }
}
