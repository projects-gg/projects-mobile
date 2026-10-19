package gg.projects.mobile.controls;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The labels of the on-screen game controls.
 *
 * <p>Upstream upper-cases them with the phone's language, and Turkish turns the English labels
 * players bring into "HİDE GUI", "SHİFT" and "MİDDLE CLİCK". They are upper-cased with
 * {@link Locale#ROOT} instead. The Turkish labels of our default layout are written in capitals
 * ("EĞİL", "GİZLE"), so root upper-casing keeps them right too (it would turn a lower-case i into
 * I, not İ).
 *
 * <p>The default layout comes in the app's two languages ({@code assets/default.json} Turkish,
 * {@code assets/default_en.json} English, the same geometry): an untouched default takes the app's
 * language, and in a layout the player edited only the labels still equal to a default label of
 * the other language are translated.
 */
public final class ControlLabels {
    private ControlLabels() {}

    /** The Turkish default layout's asset. */
    public static final String TURKISH_ASSET = "default.json";
    /** The English default layout's asset. */
    public static final String ENGLISH_ASSET = "default_en.json";

    /**
     * Turkish default label to English (CTRL, SHIFT and F3 are the same in both): this version's
     * layout (M15) first, then the labels 0.1.4 and 0.1.5 used, so a layout the player edited from
     * one of those still follows the language.
     */
    static final Map<String, String> ENGLISH_LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        // 0.1.6 (M15): one line each; the glyph says the rest.
        labels.put("GİZLE", "HIDE");
        labels.put("MODLAR", "MODS");
        labels.put("KAMERA", "CAMERA");
        labels.put("OYUNCULAR", "PLAYERS");
        labels.put("EL DEĞİŞ", "SWAP HANDS");
        labels.put("BLOK SEÇ", "PICK BLOCK");
        labels.put("YAKINLAŞ", "ZOOM");
        labels.put("PARLAKLIK", "BRIGHTNESS");
        labels.put("MİKROFON", "MICROPHONE");
        labels.put("YOL NOKTASI", "WAYPOINT");
        labels.put("HARİTA", "MAP");
        labels.put("KONUŞ", "TALK");
        labels.put("SALDIR", "ATTACK");
        labels.put("KULLAN", "USE");
        labels.put("SAĞ TIK", "RIGHT CLICK");
        // 0.1.4 and 0.1.5 (several still in use).
        labels.put("ZIPLA", "JUMP");
        labels.put("EL\nDEĞİŞ", "OFF\nHAND");
        labels.put("MENÜ", "MENU");
        labels.put("EĞİL", "SNEAK");
        labels.put("SAĞ\nTIK", "RIGHT\nCLICK");
        labels.put("SOL\nTIK", "LEFT\nCLICK");
        labels.put("ORTA\nTIK", "MIDDLE\nCLICK");
        labels.put("ENV.", "INV.");
        labels.put("KOŞ", "SPRINT");
        labels.put("TUŞLARI\nGİZLE", "HIDE\nBUTTONS");
        labels.put("DURAKLAT", "PAUSE");
        labels.put("ENVANTER", "INVENTORY");
        labels.put("DÜZENLEYİCİYE HOŞ GELDİN.\nAYARLARI İÇİN BİR TUŞA DOKUN.\nDAHA FAZLASI İÇİN ÜSTTEKİ DİŞLİYE DOKUN.",
                "WELCOME TO THE EDITOR.\nTAP A BUTTON TO SEE ITS SETTINGS.\nTAP THE GEAR AT THE TOP FOR MORE.");
        labels.put("GERİ", "BACK");
        labels.put("DİĞER", "MORE");
        labels.put("GÖRÜŞ\nAÇISI", "THIRD\nPERSON");
        labels.put("FARE", "MOUSE");
        labels.put("SOHBET", "CHAT");
        labels.put("KLAVYE", "KEYBOARD");
        labels.put("ARAYÜZ", "HUD");
        labels.put("BIRAK", "DROP");
        labels.put("OYUNCU\nLİSTESİ", "PLAYER\nLIST");
        ENGLISH_LABELS = Collections.unmodifiableMap(labels);
    }

    /** A control label in capitals: "Hide\nGUI" reads "HIDE\nGUI" on a Turkish phone. */
    public static String upper(String label) {
        return label == null ? null : label.toUpperCase(Locale.ROOT);
    }

    /** The default layout asset of the app's language. */
    public static String assetFor(boolean turkish) {
        return turkish ? TURKISH_ASSET : ENGLISH_ASSET;
    }

    /**
     * SHA-256 of the default layouts earlier versions installed, line endings normalized to LF (the
     * APKs built on Windows carry CRLF, upstream's CI builds LF): upstream's English one of 0.1.0 to
     * 0.1.3, the Turkish one of 0.1.4 and 0.1.5 and the English one of 0.1.5 (the layout before the
     * M15 redesign). An untouched one of these is replaced by this version's layout.
     */
    private static final Set<String> OLD_DEFAULTS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "d92a182bde65a19c1c9edb1291f8dde2ff9efcbb95b9a7745c3e591b85f7516a",
            "bbc73ba76be9fd51e9cb938dc3373dea04458e119d10098f541f6264fbda700a",
            "cb6c4bef9d985c1a3adf31c4b41674c0f51611ed874762eb2dbcadb492802341")));

    /**
     * True when {@code installed} is an earlier version's default layout, byte for byte apart from
     * line endings: the player never edited it, so the new default may replace it. A layout the
     * player saved from the editor has other bytes and stays.
     */
    public static boolean isOldDefault(byte[] installed) {
        return installed != null && OLD_DEFAULTS.contains(sha256(normalizeLineEndings(installed)));
    }

    /**
     * True when {@code installed} is a default layout nobody edited: an earlier version's, or one of
     * {@code defaults} (this version's in either language), line endings aside.
     */
    public static boolean isUntouchedDefault(byte[] installed, byte[]... defaults) {
        if (installed == null) return false;
        if (isOldDefault(installed)) return true;
        byte[] normalized = normalizeLineEndings(installed);
        for (byte[] layout : defaults) {
            if (layout != null && Arrays.equals(normalized, normalizeLineEndings(layout))) return true;
        }
        return false;
    }

    /** True when the two layouts are the same apart from line endings. */
    public static boolean sameLayout(byte[] a, byte[] b) {
        return a != null && b != null && Arrays.equals(normalizeLineEndings(a), normalizeLineEndings(b));
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * A layout the player edited, with the labels that are still a default label of the other
     * language put into {@code toTurkish}'s language; null when no label changes (or the file is not
     * a layout). Positions, sizes, keys and every other label stay as the player left them.
     */
    public static String translate(String layoutJson, boolean toTurkish) {
        Map<String, String> map = toTurkish ? turkishLabels() : ENGLISH_LABELS;
        JsonElement root;
        try {
            root = JsonParser.parseString(layoutJson);
        } catch (RuntimeException e) {
            return null;
        }
        if (!root.isJsonObject()) return null;
        JsonObject layout = root.getAsJsonObject();
        boolean changed = false;
        changed |= translateList(layout.get("mControlDataList"), map);
        JsonElement drawers = layout.get("mDrawerDataList");
        if (drawers != null && drawers.isJsonArray()) {
            for (JsonElement drawer : drawers.getAsJsonArray()) {
                if (!drawer.isJsonObject()) continue;
                changed |= translateName(drawer.getAsJsonObject().get("properties"), map);
                changed |= translateList(drawer.getAsJsonObject().get("buttonProperties"), map);
            }
        }
        return changed ? GSON.toJson(root) : null;
    }

    private static boolean translateList(JsonElement list, Map<String, String> map) {
        if (list == null || !list.isJsonArray()) return false;
        boolean changed = false;
        for (JsonElement button : list.getAsJsonArray()) changed |= translateName(button, map);
        return changed;
    }

    private static boolean translateName(JsonElement button, Map<String, String> map) {
        if (button == null || !button.isJsonObject()) return false;
        JsonElement name = button.getAsJsonObject().get("name");
        if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) return false;
        String translated = map.get(name.getAsString());
        if (translated == null) return false;
        button.getAsJsonObject().add("name", new JsonPrimitive(translated));
        return true;
    }

    /** English default label to Turkish: ours, and upstream's from 0.1.0 to 0.1.3. */
    static Map<String, String> turkishLabels() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : ENGLISH_LABELS.entrySet()) out.put(entry.getValue(), entry.getKey());
        out.putAll(UPSTREAM_LABELS);
        return out;
    }

    /** Upstream's English labels as 0.1.0 to 0.1.3 shipped them, to Turkish. */
    static final Map<String, String> UPSTREAM_LABELS;

    static {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("Jump", "ZIPLA");
        labels.put("Off\nHand", "EL\nDEĞİŞ");
        labels.put("Offhand", "EL\nDEĞİŞ");
        labels.put("Menu", "MENÜ");
        labels.put("Crouch", "EĞİL");
        labels.put("Right\nClick", "SAĞ\nTIK");
        labels.put("Left\nClick", "SOL\nTIK");
        labels.put("Middle\nClick", "ORTA\nTIK");
        labels.put("Inv.", "ENV.");
        labels.put("Sprint", "KOŞ");
        labels.put("Hide\nGUI", "TUŞLARI\nGİZLE");
        labels.put("Pause", "DURAKLAT");
        labels.put("Inventory", "ENVANTER");
        labels.put("Welcome to the Editor.\nTap a button to see what you can do!\nTap the gear at the top to see more options.",
                "DÜZENLEYİCİYE HOŞ GELDİN.\nAYARLARI İÇİN BİR TUŞA DOKUN.\nDAHA FAZLASI İÇİN ÜSTTEKİ DİŞLİYE DOKUN.");
        labels.put("Exit\nMenu", "GERİ");
        labels.put("Shift", "SHIFT");
        labels.put("More", "DİĞER");
        labels.put("Third\nPerson", "GÖRÜŞ\nAÇISI");
        labels.put("Debug\nMenu", "F3");
        labels.put("Mouse\nToggle", "FARE");
        labels.put("Chat", "SOHBET");
        labels.put("Keyboard", "KLAVYE");
        labels.put("HUD\nToggle", "ARAYÜZ");
        labels.put("Drop\nItem", "BIRAK");
        labels.put("Player\nList", "OYUNCU\nLİSTESİ");
        UPSTREAM_LABELS = Collections.unmodifiableMap(labels);
    }

    static byte[] normalizeLineEndings(byte[] bytes) {
        byte[] out = new byte[bytes.length];
        int n = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\r' && i + 1 < bytes.length && bytes[i + 1] == '\n') continue;
            out[n++] = bytes[i];
        }
        return Arrays.copyOf(out, n);
    }

    static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@link #sha256} of UTF-8 text, for tests. */
    static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }
}
