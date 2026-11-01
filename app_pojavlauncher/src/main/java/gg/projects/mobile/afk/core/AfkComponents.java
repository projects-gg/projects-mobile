package gg.projects.mobile.afk.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import gg.projects.mobile.afk.api.AfkLine;

/**
 * The game's text components (chat, kick reasons, titles) as the console shows them, without Adventure: a tree read
 * from NBT ({@code AfkNbt}) or JSON ({@link #fromJson}) of {@code String}, {@code Map<String, Object>},
 * {@code List<Object>}, numbers and booleans. Colours (16 named + hex), bold/italic/lines and legacy § codes inside
 * literal text become {@link AfkLine.Span}s (§k plain, never scrambled). Vanilla's translation keys come from a small
 * built-in table (Turkish and English: chat kinds, joins, kicks, common deaths); an unknown key shows its fallback or
 * the key, as the game does. Pure Java.
 */
public final class AfkComponents {
    private AfkComponents() {}

    /** Longest text rendered from one component. */
    static final int MAX_CHARS = 4096;
    private static final int MAX_DEPTH = 64;

    private static final String[] NAMED = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};
    private static final int[] NAMED_RGB = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00,
            0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    /** Vanilla keys: {English, Turkish}. */
    private static final Map<String, String[]> KEYS = new HashMap<>();

    private static void key(String key, String en, String tr) {
        KEYS.put(key, new String[]{en, tr});
    }

    static {
        key("chat.type.text", "<%s> %s", "<%s> %s");
        key("chat.type.emote", "* %s %s", "* %s %s");
        key("chat.type.announcement", "[%s] %s", "[%s] %s");
        key("chat.type.admin", "[%s: %s]", "[%s: %s]");
        key("chat.type.team.text", "%s <%s> %s", "%s <%s> %s");
        key("chat.type.team.sent", "-> %s <%s> %s", "-> %s <%s> %s");
        key("commands.message.display.incoming", "%s whispers to you: %s", "%s sana fısıldıyor: %s");
        key("commands.message.display.outgoing", "You whisper to %s: %s", "%s adlı oyuncuya fısıldıyorsun: %s");
        key("multiplayer.player.joined", "%s joined the game", "%s oyuna katıldı");
        key("multiplayer.player.joined.renamed", "%s (formerly known as %s) joined the game", "%s (eski adıyla %s) oyuna katıldı");
        key("multiplayer.player.left", "%s left the game", "%s oyundan ayrıldı");
        key("multiplayer.disconnect.banned", "You are banned from this server", "Bu sunucudan yasaklandın");
        key("multiplayer.disconnect.banned.reason", "You are banned from this server.\nReason: %s", "Bu sunucudan yasaklandın.\nSebep: %s");
        key("multiplayer.disconnect.banned.expiration", "\nYour ban will be removed on %s", "\nYasağın %s tarihinde kalkacak");
        key("multiplayer.disconnect.banned_ip.reason", "Your IP address is banned from this server.\nReason: %s",
                "IP adresin bu sunucudan yasaklandı.\nSebep: %s");
        key("multiplayer.disconnect.banned_ip.expiration", "\nYour ban will be removed on %s", "\nYasağın %s tarihinde kalkacak");
        key("multiplayer.disconnect.not_whitelisted", "You are not white-listed on this server!", "Bu sunucunun beyaz listesinde değilsin!");
        key("multiplayer.disconnect.duplicate_login", "You logged in from another location", "Başka bir yerden giriş yapıldı");
        key("multiplayer.disconnect.server_full", "The server is full!", "Sunucu dolu!");
        key("multiplayer.disconnect.server_shutdown", "Server closed", "Sunucu kapatıldı");
        key("multiplayer.disconnect.kicked", "Kicked by an operator", "Bir yönetici tarafından atıldın");
        key("multiplayer.disconnect.idling", "You have been idle for too long!", "Çok uzun süre hareketsiz kaldın!");
        key("multiplayer.disconnect.outdated_client", "Incompatible client! Please use %s", "Uyumsuz istemci! Lütfen %s kullan");
        key("multiplayer.disconnect.outdated_server", "Incompatible client! Please use %s", "Uyumsuz istemci! Lütfen %s kullan");
        key("multiplayer.disconnect.incompatible", "Incompatible client! Please use %s", "Uyumsuz istemci! Lütfen %s kullan");
        key("multiplayer.disconnect.unverified_username", "Failed to verify username!", "Kullanıcı adı doğrulanamadı!");
        key("multiplayer.disconnect.name_taken", "That name is already taken", "Bu ad zaten alınmış");
        key("multiplayer.disconnect.transfers_disabled", "Server does not accept transfers", "Sunucu aktarma kabul etmiyor");
        key("multiplayer.disconnect.generic", "Disconnected", "Bağlantı kesildi");
        key("multiplayer.disconnect.flying", "Flying is not enabled on this server", "Bu sunucuda uçmak kapalı");
        key("disconnect.timeout", "Timed out", "Zaman aşımı");
        key("disconnect.endOfStream", "End of stream", "Bağlantı kapandı");
        key("disconnect.closed", "Connection closed", "Bağlantı kapandı");
        key("disconnect.disconnected", "Disconnected by Server", "Sunucu bağlantıyı kesti");
        key("disconnect.genericReason", "%s", "%s");
        key("disconnect.transfer", "Transferred to another server", "Başka bir sunucuya aktarıldı");
        key("disconnect.loginFailedInfo", "Failed to log in: %s", "Giriş yapılamadı: %s");
        key("death.attack.mob", "%1$s was slain by %2$s", "%1$s, %2$s tarafından öldürüldü");
        key("death.attack.player", "%1$s was slain by %2$s", "%1$s, %2$s tarafından öldürüldü");
        key("death.attack.drown", "%1$s drowned", "%1$s boğuldu");
        key("death.attack.lava", "%1$s tried to swim in lava", "%1$s lavda yüzmeye çalıştı");
        key("death.attack.inFire", "%1$s went up in flames", "%1$s alevler içinde kaldı");
        key("death.attack.onFire", "%1$s burned to death", "%1$s yanarak öldü");
        key("death.attack.starve", "%1$s starved to death", "%1$s açlıktan öldü");
        key("death.attack.outOfWorld", "%1$s fell out of the world", "%1$s dünyanın dışına düştü");
        key("death.attack.generic", "%1$s died", "%1$s öldü");
        key("death.fell.accident.generic", "%1$s fell from a high place", "%1$s yüksek bir yerden düştü");
        key("death.attack.fall", "%1$s hit the ground too hard", "%1$s yere çok sert çarptı");
        key("death.attack.explosion", "%1$s blew up", "%1$s havaya uçtu");
        key("death.attack.explosion.player", "%1$s was blown up by %2$s", "%1$s, %2$s tarafından havaya uçuruldu");
        key("death.attack.arrow", "%1$s was shot by %2$s", "%1$s, %2$s tarafından vuruldu");
        key("death.attack.cactus", "%1$s was pricked to death", "%1$s kaktüse batarak öldü");
        key("death.attack.inWall", "%1$s suffocated in a wall", "%1$s bir duvarın içinde boğuldu");
    }

    /** Collects the translation keys of a component (for telling kick reasons apart). */
    public static List<String> keys(Object component) {
        List<String> out = new ArrayList<>();
        collectKeys(component, out, 0);
        return out;
    }

    private static void collectKeys(Object c, List<String> out, int depth) {
        if (depth > MAX_DEPTH || c == null) return;
        if (c instanceof List) {
            for (Object o : (List<?>) c) collectKeys(o, out, depth + 1);
        } else if (c instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) c;
            if (m.size() == 1 && m.containsKey("")) {
                collectKeys(m.get(""), out, depth + 1);
                return;
            }
            Object key = m.get("translate");
            if (key instanceof String) out.add((String) key);
            Object with = m.get("with");
            if (with instanceof List) {
                for (Object o : (List<?>) with) collectKeys(o, out, depth + 1);
            }
            Object extra = m.get("extra");
            if (extra instanceof List) {
                for (Object o : (List<?>) extra) collectKeys(o, out, depth + 1);
            }
        }
    }

    /** The component in Turkish ({@code turkish}) or English, with its style runs. */
    public static AfkText render(Object component, boolean turkish) {
        AfkText.Builder b = new AfkText.Builder();
        new Renderer(b, turkish ? 1 : 0).node(component, AfkLine.NO_COLOR, 0, 0);
        return b.build();
    }

    /** The component's plain English text (kick reasons are matched in English, like the desktop's). */
    public static String english(Object component) {
        return render(component, false).text;
    }

    private static final class Renderer {
        private final AfkText.Builder out;
        private final int lang;

        Renderer(AfkText.Builder out, int lang) {
            this.out = out;
            this.lang = lang;
        }

        void node(Object c, int color, int style, int depth) {
            if (c == null || depth > MAX_DEPTH || out.length() >= MAX_CHARS) return;
            if (c instanceof String) {
                literal((String) c, color, style);
            } else if (c instanceof Number || c instanceof Boolean) {
                literal(String.valueOf(c), color, style);
            } else if (c instanceof List) {
                List<?> list = (List<?>) c;
                if (list.isEmpty()) return;
                // The first element is the parent, the rest are its children (they take its style).
                Object first = list.get(0);
                int[] own = styleOf(first, color, style);
                node(first, color, style, depth + 1);
                for (int i = 1; i < list.size(); i++) node(list.get(i), own[0], own[1], depth + 1);
            } else if (c instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) c;
                if (m.size() == 1 && m.containsKey("")) {
                    node(m.get(""), color, style, depth + 1);
                    return;
                }
                int[] own = styleOf(m, color, style);
                content(m, own[0], own[1], depth);
                Object extra = m.get("extra");
                if (extra instanceof List) {
                    for (Object child : (List<?>) extra) node(child, own[0], own[1], depth + 1);
                }
            }
        }

        private void content(Map<?, ?> m, int color, int style, int depth) {
            Object text = m.get("text");
            if (text != null) {
                node(text instanceof String ? text : String.valueOf(text), color, style, depth + 1);
                return;
            }
            Object translate = m.get("translate");
            if (translate instanceof String) {
                translate((String) translate, m.get("with"), m.get("fallback"), color, style, depth);
                return;
            }
            Object selector = m.get("selector");
            if (selector instanceof String) {
                literal((String) selector, color, style);
                return;
            }
            Object keybind = m.get("keybind");
            if (keybind instanceof String) literal((String) keybind, color, style);
            // score, nbt and object (sprites, heads) parts have no text of their own here.
        }

        private void translate(String key, Object with, Object fallback, int color, int style, int depth) {
            String[] known = KEYS.get(key);
            String format;
            if (known != null) {
                format = known[lang];
            } else if (fallback instanceof String) {
                format = (String) fallback;
            } else if (key.startsWith("death.")) {
                format = KEYS.get("death.attack.generic")[lang];
            } else {
                format = key;
            }
            List<?> args = with instanceof List ? (List<?>) with : Collections.emptyList();
            int next = 0;
            int i = 0;
            int n = format.length();
            StringBuilder plain = new StringBuilder();
            while (i < n) {
                char ch = format.charAt(i);
                if (ch == '%' && i + 1 < n) {
                    char c1 = format.charAt(i + 1);
                    if (c1 == '%') {
                        plain.append('%');
                        i += 2;
                        continue;
                    }
                    if (c1 == 's') {
                        literal(plain.toString(), color, style);
                        plain.setLength(0);
                        if (next < args.size()) node(args.get(next), color, style, depth + 1);
                        next++;
                        i += 2;
                        continue;
                    }
                    int j = i + 1;
                    int index = 0;
                    while (j < n && Character.isDigit(format.charAt(j))) {
                        index = index * 10 + (format.charAt(j) - '0');
                        j++;
                    }
                    if (j > i + 1 && j + 1 < n && format.charAt(j) == '$' && format.charAt(j + 1) == 's') {
                        literal(plain.toString(), color, style);
                        plain.setLength(0);
                        if (index >= 1 && index <= args.size()) node(args.get(index - 1), color, style, depth + 1);
                        i = j + 2;
                        continue;
                    }
                }
                plain.append(ch);
                i++;
            }
            literal(plain.toString(), color, style);
        }

        /** Literal text with legacy § codes: they change the style for the rest of this text only. */
        private void literal(String s, int color, int style) {
            if (s.isEmpty()) return;
            if (s.indexOf('§') < 0) {
                append(s, color, style);
                return;
            }
            int baseColor = color;
            int baseStyle = style;
            StringBuilder part = new StringBuilder();
            int i = 0;
            int n = s.length();
            while (i < n) {
                char ch = s.charAt(i);
                if (ch != '§' || i + 1 >= n) {
                    if (ch != '§') part.append(ch);
                    i++;
                    continue;
                }
                char code = Character.toLowerCase(s.charAt(i + 1));
                append(part.toString(), color, style);
                part.setLength(0);
                // BungeeCord's hex: §x§R§R§G§G§B§B.
                if (code == 'x' && hexRun(s, i + 2)) {
                    int rgb = 0;
                    for (int k = 0; k < 6; k++) rgb = (rgb << 4) | Character.digit(s.charAt(i + 3 + k * 2), 16);
                    color = rgb;
                    style = 0;
                    i += 14;
                    continue;
                }
                int digit = Character.digit(code, 16);
                if (digit >= 0) {
                    color = NAMED_RGB[digit];
                    style = 0;
                } else if (code == 'k') {
                    style |= AfkLine.OBFUSCATED;
                } else if (code == 'l') {
                    style |= AfkLine.BOLD;
                } else if (code == 'm') {
                    style |= AfkLine.STRIKETHROUGH;
                } else if (code == 'n') {
                    style |= AfkLine.UNDERLINED;
                } else if (code == 'o') {
                    style |= AfkLine.ITALIC;
                } else if (code == 'r') {
                    color = baseColor;
                    style = baseStyle;
                }
                i += 2;
            }
            append(part.toString(), color, style);
        }

        private boolean hexRun(String s, int from) {
            if (from + 12 > s.length()) return false;
            for (int k = 0; k < 6; k++) {
                if (s.charAt(from + k * 2) != '§' || Character.digit(s.charAt(from + k * 2 + 1), 16) < 0) return false;
            }
            return true;
        }

        private void append(String s, int color, int style) {
            if (s.isEmpty()) return;
            int room = MAX_CHARS - out.length();
            if (room <= 0) return;
            out.append(s.length() > room ? s.substring(0, room) : s, color, style);
        }
    }

    /** The colour and style bits a component gives its text and children ({@code {color, style}}). */
    static int[] styleOf(Object c, int color, int style) {
        if (!(c instanceof Map)) return new int[]{color, style};
        Map<?, ?> m = (Map<?, ?>) c;
        if (m.size() == 1 && m.containsKey("")) return styleOf(m.get(""), color, style);
        Object col = m.get("color");
        if (col instanceof String) {
            int parsed = parseColor((String) col);
            if (parsed != Integer.MIN_VALUE) color = parsed;
        }
        style = bit(m.get("bold"), style, AfkLine.BOLD);
        style = bit(m.get("italic"), style, AfkLine.ITALIC);
        style = bit(m.get("underlined"), style, AfkLine.UNDERLINED);
        style = bit(m.get("strikethrough"), style, AfkLine.STRIKETHROUGH);
        style = bit(m.get("obfuscated"), style, AfkLine.OBFUSCATED);
        return new int[]{color, style};
    }

    private static int bit(Object value, int style, int bit) {
        Boolean b = null;
        if (value instanceof Boolean) b = (Boolean) value;
        else if (value instanceof Number) b = ((Number) value).intValue() != 0;
        else if (value instanceof String) b = Boolean.parseBoolean((String) value);
        if (b == null) return style;
        return b ? style | bit : style & ~bit;
    }

    /** {@code red}, {@code #RRGGBB}; {@link Integer#MIN_VALUE} for anything else ("reset" included). */
    static int parseColor(String s) {
        String c = s.trim().toLowerCase(Locale.ROOT);
        if (c.length() == 7 && c.charAt(0) == '#') {
            try {
                return Integer.parseInt(c.substring(1), 16);
            } catch (NumberFormatException e) {
                return Integer.MIN_VALUE;
            }
        }
        for (int i = 0; i < NAMED.length; i++) {
            if (NAMED[i].equals(c)) return NAMED_RGB[i];
        }
        return Integer.MIN_VALUE;
    }

    /**
     * A JSON text component (the login stage's kick reason; Gson tree) as the same tree NBT gives: objects become
     * maps, arrays lists, strings strings, numbers numbers, booleans booleans.
     */
    public static Object fromJson(com.google.gson.JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsNumber();
            return p.getAsString();
        }
        if (e.isJsonArray()) {
            List<Object> out = new ArrayList<>();
            for (com.google.gson.JsonElement x : e.getAsJsonArray()) out.add(fromJson(x));
            return out;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> x : e.getAsJsonObject().entrySet()) {
            out.put(x.getKey(), fromJson(x.getValue()));
        }
        return out;
    }
}
