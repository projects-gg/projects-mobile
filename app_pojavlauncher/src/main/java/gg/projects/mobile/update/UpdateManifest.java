package gg.projects.mobile.update;

import androidx.annotation.Keep;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import gg.projects.mobile.content.ContentException;
import gg.projects.mobile.content.ContentJson;
import gg.projects.mobile.content.ContentPaths;
import gg.projects.mobile.content.Hashes;
import gg.projects.mobile.content.Rfc3339;

/**
 * APK-UPD: one Android release channel file, {@code v1/android/<channel>.json} (docs/content-format.md,
 * "Android release channel"), read only after its signature verified ({@link #read}). Unknown fields are
 * ignored; a rule broken anywhere refuses the whole file (nothing is offered then). Release notes are
 * display text: readers trim them instead of refusing the file. Plain Java, JVM-tested.
 */
public final class UpdateManifest {
    /** The schema this build reads. */
    public static final int SCHEMA = 1;
    /** A channel file is small; anything bigger is not one. */
    public static final int MAX_BYTES = 16 * 1024;
    public static final long MAX_APK_BYTES = 512L * 1024 * 1024;
    /** Lines of notes kept per language, and characters per line. */
    static final int MAX_NOTE_LINES = 20;
    static final int MAX_NOTE_LINE = 300;

    private static final Pattern APPLICATION_ID = Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$");
    private static final Pattern VERSION_NAME = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._+-]{0,31}$");
    private static final Pattern CHANNEL = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");
    private static final Pattern TAG = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    public final String channel;
    public final String applicationId;
    public final String versionName;
    public final int versionCode;
    public final int minSdk;
    public final long pubDateMillis;
    /** Where the APK is, tried in this order. */
    public final List<String> urls;
    public final long size;
    /** Lowercase hex. */
    public final String sha256;
    /** SHA-256 of each certificate that signs the APK (lowercase hex). */
    public final List<String> signers;
    public final List<String> notesTr;
    public final List<String> notesEn;
    /** LGPL: the source of this exact version (may be null). */
    public final String sourceTag;
    public final String sourceUrl;

    @Keep
    static final class Doc {
        Integer schema;
        String channel;
        String applicationId;
        String versionName;
        Long versionCode;
        Integer minSdk;
        String pubDate;
        Apk apk;
        Notes notes;
        Source source;
    }

    @Keep
    static final class Apk {
        List<String> urls;
        Long size;
        String sha256;
        List<String> signerSha256;
    }

    @Keep
    static final class Notes {
        String tr;
        String en;
    }

    @Keep
    static final class Source {
        String tag;
        String url;
    }

    private UpdateManifest(Doc doc, List<String> urls, String sha256, List<String> signers, long pubDate) {
        channel = doc.channel;
        applicationId = doc.applicationId;
        versionName = doc.versionName;
        versionCode = (int) (long) doc.versionCode;
        minSdk = doc.minSdk;
        pubDateMillis = pubDate;
        this.urls = Collections.unmodifiableList(urls);
        size = doc.apk.size;
        this.sha256 = sha256;
        this.signers = Collections.unmodifiableList(signers);
        notesTr = noteLines(doc.notes == null ? null : doc.notes.tr);
        notesEn = noteLines(doc.notes == null ? null : doc.notes.en);
        boolean source = doc.source != null && doc.source.tag != null && TAG.matcher(doc.source.tag).matches();
        sourceTag = source ? doc.source.tag : null;
        sourceUrl = source && ContentPaths.isHttpsUrl(doc.source.url) ? doc.source.url : null;
    }

    /** The name the channel's signature must carry in its trusted comment ({@code file:}). */
    public static String signedFileName(String channel) {
        return "android-" + channel + ".json";
    }

    /**
     * Verifies {@code signature} over the exact bytes of {@code json} with {@code keys}, then reads the file for
     * {@code channel}. The trusted comment must name this channel's file and the file's version (so a signature
     * made for anything else, a launcher installer included, never passes).
     *
     * @param allowHttp debug builds' local test servers only
     */
    public static UpdateManifest read(byte[] json, byte[] signature, String channel, List<Minisign.PublicKey> keys,
                                      boolean allowHttp) throws UpdateException {
        if (json == null || json.length > MAX_BYTES) throw invalid("the channel file is missing or too large");
        Minisign.Verified verified = Minisign.verify(json, signature, keys);
        UpdateManifest manifest = parse(json, channel, allowHttp);
        Map<String, String> fields = verified.fields();
        if (!signedFileName(channel).equals(fields.get("file"))) {
            throw new UpdateException(UpdateException.Kind.BAD_SIGNATURE, "signed for another file: " + fields.get("file"));
        }
        if (!manifest.versionName.equals(fields.get("version"))) {
            throw new UpdateException(UpdateException.Kind.BAD_SIGNATURE, "signed for another version: " + fields.get("version"));
        }
        return manifest;
    }

    /** Reads and checks the file's fields (no signature check: {@link #read} does both). */
    static UpdateManifest parse(byte[] json, String channel, boolean allowHttp) throws UpdateException {
        Doc doc;
        try {
            doc = ContentJson.parse(json, Doc.class, "the Android channel");
        } catch (ContentException e) {
            throw new UpdateException(UpdateException.Kind.INVALID, e.getMessage(), e);
        }
        if (doc.schema == null) throw invalid("schema is missing");
        if (doc.schema > SCHEMA) throw new UpdateException(UpdateException.Kind.UNSUPPORTED, "schema " + doc.schema + " is newer than " + SCHEMA);
        if (doc.schema < 1) throw invalid("schema " + doc.schema + " is not valid");
        if (channel == null || !CHANNEL.matcher(channel).matches() || !channel.equals(doc.channel)) {
            throw invalid("the file is for channel " + doc.channel + ", not " + channel);
        }
        if (doc.applicationId == null || doc.applicationId.length() > 100 || !APPLICATION_ID.matcher(doc.applicationId).matches()) {
            throw invalid("applicationId is not valid");
        }
        if (doc.versionName == null || !VERSION_NAME.matcher(doc.versionName).matches()) throw invalid("versionName is not valid");
        if (doc.versionCode == null || doc.versionCode < 1 || doc.versionCode > 2_100_000_000L) throw invalid("versionCode is not valid");
        if (doc.minSdk == null || doc.minSdk < 1 || doc.minSdk > 1000) throw invalid("minSdk is not valid");
        Long pubDate = doc.pubDate == null ? null : Rfc3339.parse(doc.pubDate);
        if (pubDate == null) throw invalid("pubDate is not RFC 3339");
        if (doc.apk == null) throw invalid("apk is missing");
        if (doc.apk.size == null || doc.apk.size < 1 || doc.apk.size > MAX_APK_BYTES) throw invalid("apk.size is not valid");
        String sha256 = Hashes.normalizeHex(doc.apk.sha256, 64);
        if (sha256 == null) throw invalid("apk.sha256 is not 64 hex digits");
        if (doc.apk.urls == null || doc.apk.urls.isEmpty() || doc.apk.urls.size() > 4) throw invalid("apk.urls needs 1 to 4 addresses");
        List<String> urls = new ArrayList<>();
        for (String url : doc.apk.urls) {
            boolean ok = url != null && url.length() <= 2048
                    && (ContentPaths.isHttpsUrl(url) || (allowHttp && ContentPaths.isHttpUrl(url)));
            if (!ok) throw invalid("apk.urls has an address that is not https");
            urls.add(url);
        }
        if (doc.apk.signerSha256 == null || doc.apk.signerSha256.isEmpty() || doc.apk.signerSha256.size() > 4) {
            throw invalid("apk.signerSha256 needs 1 to 4 certificates");
        }
        List<String> signers = new ArrayList<>();
        for (String signer : doc.apk.signerSha256) {
            String hex = Hashes.normalizeHex(signer, 64);
            if (hex == null) throw invalid("apk.signerSha256 has an entry that is not 64 hex digits");
            if (!signers.contains(hex)) signers.add(hex);
        }
        return new UpdateManifest(doc, urls, sha256, signers, pubDate);
    }

    private static UpdateException invalid(String message) {
        return new UpdateException(UpdateException.Kind.INVALID, message);
    }

    /** The notes in the app's language, the other one when that is empty. */
    public List<String> notes(boolean turkish) {
        List<String> first = turkish ? notesTr : notesEn;
        return first.isEmpty() ? (turkish ? notesEn : notesTr) : first;
    }

    /**
     * Display lines: one per line of the text, a leading list mark ("- ", "* ", "• ") and control characters
     * dropped, blank lines skipped, at most {@link #MAX_NOTE_LINES} lines of {@link #MAX_NOTE_LINE} characters.
     */
    static List<String> noteLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return Collections.unmodifiableList(out);
        for (String raw : text.split("\n")) {
            StringBuilder clean = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (!Character.isISOControl(c)) clean.append(c);
            }
            String line = clean.toString().trim();
            if (line.startsWith("- ") || line.startsWith("* ") || line.startsWith("\u2022 ")) line = line.substring(2).trim();
            if (line.isEmpty()) continue;
            if (line.length() > MAX_NOTE_LINE) line = line.substring(0, MAX_NOTE_LINE).trim() + "\u2026";
            out.add(line);
            if (out.size() == MAX_NOTE_LINES) break;
        }
        return Collections.unmodifiableList(out);
    }
}
