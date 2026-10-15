package gg.projects.mobile.content;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** JSON reading for content documents: strict UTF-8, unknown fields ignored. */
public final class ContentJson {
    private ContentJson() {}

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public static String utf8(byte[] bytes, String what) throws ContentException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new ContentException(ContentException.Kind.INVALID, what + " is not valid UTF-8", e);
        }
    }

    public static <T> T parse(byte[] bytes, Class<T> type, String what) throws ContentException {
        String text = utf8(bytes, what);
        T value;
        try {
            value = GSON.fromJson(text, type);
        } catch (JsonParseException e) {
            throw new ContentException(ContentException.Kind.INVALID, what + " is not valid JSON: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // Gson reports some shape mismatches (wrong types, overflowing numbers) this way.
            throw new ContentException(ContentException.Kind.INVALID, what + " has an unexpected shape: " + e, e);
        }
        if (value == null) throw new ContentException(ContentException.Kind.INVALID, what + " is empty");
        return value;
    }

    /** Rejects schemas newer than {@code supported} (and 0, which no writer emits). */
    public static void checkSchema(String what, int found, int supported) throws ContentException {
        if (found > supported || found <= 0) {
            throw new ContentException(ContentException.Kind.UNSUPPORTED_SCHEMA,
                    what + " schema " + found + " is not supported (this build reads up to " + supported + ")");
        }
    }
}
