package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CHAT2: how a link and its card are drawn (the desktop's link-ui.tsx rules, pure): the domain the
 * one-line warning names ("Bu bağlantı ornek.com sitesine gidiyor."), the shape a card holds before
 * it comes (a wide place for a video, a clip or a projects.gg page, else one row) and once it came
 * (by its picture's own proportions), whether a card has anything beyond the link (MOB-POLISH: one
 * without is not drawn), the address the desktop's plain card shows, and which refusal has words of
 * its own. Pure Java.
 */
public final class ChatLinkUi {
    private ChatLinkUi() {}

    /** A card's shape: a wide picture over the text, or one row (a small picture beside, or none). */
    public enum Shape { BANNER, ROW }

    /** A picture this much wider than tall is a banner. */
    private static final float WIDE = 1.3f;

    /** Second-level labels under a country's domain that are nobody's name ("example.com.tr"). */
    private static final Set<String> GENERIC_SECOND = new HashSet<>(Arrays.asList(
            "com net org gov edu co ac gen info biz name web bel tv av dr pol k12 tel nom ltd plc me sch mil or ne go".split(" ")));

    /** The refusals whose words are their own (the rest: "Bu bağlantı gönderilemez."). */
    private static final Set<String> WORDED = new HashSet<>(Arrays.asList(
            "off", "form", "formHint", "count", "http", "long", "shortener", "lookalike", "file", "invite"));

    private static final Pattern WATCH = Pattern.compile("^/(?:watch|shorts/|live/)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLIP = Pattern.compile("^/(?:videos/|[^/]+/clip/)", Pattern.CASE_INSENSITIVE);

    /**
     * The registrable part of a host, as the warning names it: "www.youtube.com" → "youtube.com",
     * "forum.example.com.tr" → "example.com.tr" (whose site it is, not the subdomain in front).
     */
    public static String domainOf(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        String[] labels = h.split("\\.", -1);
        if (labels.length <= 2) return h;
        String top = labels[labels.length - 1];
        String second = labels[labels.length - 2];
        int keep = top.length() == 2 && (GENERIC_SECOND.contains(second) || second.length() <= 2) ? 3 : 2;
        StringBuilder out = new StringBuilder();
        for (int i = labels.length - keep; i < labels.length; i++) {
            if (out.length() > 0) out.append('.');
            out.append(labels[i]);
        }
        return out.toString();
    }

    /** The shape a card holds before it comes, guessed from the address (no jump when it does). */
    public static Shape shapeFor(String url, String host) {
        String h = host.toLowerCase(Locale.ROOT);
        String path = pathOf(url);
        if (h.equals("youtu.be")) return Shape.BANNER;
        if (under(h, "youtube.com")) return WATCH.matcher(path).find() ? Shape.BANNER : Shape.ROW;
        if (h.equals("clips.twitch.tv")) return Shape.BANNER;
        if (under(h, "twitch.tv")) return CLIP.matcher(path).find() ? Shape.BANNER : Shape.ROW;
        if (h.equals("projects.gg") || h.equals("www.projects.gg")) return Shape.BANNER;
        return Shape.ROW;
    }

    /**
     * MOB-POLISH: whether a card that came has anything beyond the link: a title, a picture or a description. One
     * without (the plain card: the icon, the domain and the address, which the message's text already shows) is not
     * drawn at all; one with a picture but no title shows the picture and the domain once.
     */
    public static boolean showsCard(ChatCards.Card card) {
        return card.state == ChatCards.State.READY && (card.title != null || card.image != null || card.desc != null);
    }

    /** The shape a card that came takes: by its picture's own proportions. */
    public static Shape shapeOf(ChatCards.Card card) {
        if (card.state != ChatCards.State.READY || card.image == null) return Shape.ROW;
        if (card.w > 0 && card.h > 0) return (float) card.w / card.h >= WIDE ? Shape.BANNER : Shape.ROW;
        return card.kind.equals("video") || card.kind.equals("stream") ? Shape.BANNER : Shape.ROW;
    }

    /** The address as a card shows it without a title: "ornek.com/adalar". */
    public static String addressOf(String url, String host) {
        String path = pathOf(url);
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        String h = host.startsWith("www.") ? host.substring(4) : host;
        return h + path;
    }

    /** A refusal's words key: its own, or "other" ("Bu bağlantı gönderilemez."). */
    public static String ruleWords(@Nullable String rule) {
        return rule != null && WORDED.contains(rule) ? rule : "other";
    }

    /** The path of an https address ("" when it has none). */
    static String pathOf(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) return "";
        int start = scheme + 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        int slash = url.indexOf('/', start);
        if (slash < 0 || slash >= end) return "/";
        return url.substring(slash, end);
    }

    private static boolean under(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /** The pieces of a text: plain runs and links (offsets into the text), for drawing. */
    public static int[][] pieces(String text, List<int[]> links) {
        int[][] out = new int[links.size() * 2 + 1][];
        int at = 0;
        int n = 0;
        for (int[] link : links) {
            if (link[0] > at) out[n++] = new int[]{at, link[0], 0};
            out[n++] = new int[]{link[0], link[1], 1};
            at = link[1];
        }
        if (at < text.length()) out[n++] = new int[]{at, text.length(), 0};
        return Arrays.copyOf(out, n);
    }
}
