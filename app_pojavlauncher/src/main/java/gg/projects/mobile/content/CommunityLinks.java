package gg.projects.mobile.content;

import androidx.annotation.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * HOME1 on the phone: the community and social links of the home card's bottom row, by pl-content's
 * rule ({@code Links::community}, docs/content-format.md "Community platforms"). They have no field of
 * their own: Studio publishes them as {@code links.custom} entries with a well-known {@code id} or
 * {@code icon}; Discord is {@code links.discord}. While {@code custom} names none of the platforms the
 * built-in addresses show; once it names one, the config decides and a platform it leaves out, or gives
 * an empty or unsafe address, is not shown. Discord is never hidden. Pure, with tests.
 */
public final class CommunityLinks {
    private CommunityLinks() {}

    /** A platform with its own mark, in the launcher's order: the community's places, then the social accounts. */
    public enum Platform {
        WIKI("wiki", "Wiki", false, "https://wiki.projects.gg"),
        DISCORD("discord", "Discord", false, "https://projects.gg/discord"),
        FORUM("forum", "Forum", false, "https://forum.projects.gg"),
        INSTAGRAM("instagram", "Instagram", true, "https://www.instagram.com/projects.oyun/"),
        TIKTOK("tiktok", "TikTok", true, "https://www.tiktok.com/@projects.oyun"),
        YOUTUBE("youtube", "YouTube", true, "https://www.youtube.com/@projectsoyun"),
        X("x", "X", true, null),
        TWITCH("twitch", "Twitch", true, null),
        KICK("kick", "Kick", true, null);

        /** The {@code id} and {@code icon} Studio writes for the platform's {@code custom} entry. */
        public final String key;
        /** The platform's name, the same in every language (the accessible name and the tooltip). */
        public final String label;
        /** A social account (the row's second group). */
        public final boolean social;
        /** The built-in address while the config gives none, or null. */
        @Nullable
        public final String defaultUrl;

        Platform(String key, String label, boolean social, @Nullable String defaultUrl) {
            this.key = key;
            this.label = label;
            this.social = social;
            this.defaultUrl = defaultUrl;
        }

        /** The platform a {@code custom} entry stands for (ASCII case and surrounding spaces do not matter; "twitter" is X); never Discord. */
        @Nullable
        static Platform of(@Nullable LauncherConfig.CustomLink link) {
            if (link == null) return null;
            Platform byId = known(link.id);
            return byId != null ? byId : known(link.icon);
        }

        @Nullable
        private static Platform known(@Nullable String text) {
            if (text == null) return null;
            String key = text.trim().toLowerCase(Locale.ROOT);
            if (key.equals("twitter")) return X;
            for (Platform platform : values()) {
                if (platform != DISCORD && platform.key.equals(key)) return platform;
            }
            return null;
        }
    }

    /** One link of the row. */
    public static final class Link {
        public final Platform platform;
        public final String url;

        Link(Platform platform, String url) {
            this.platform = platform;
            this.url = url;
        }
    }

    /** The links the row shows, in {@link Platform} order. */
    public static List<Link> of(@Nullable LauncherConfig.Links links) {
        List<LauncherConfig.CustomLink> custom = links == null || links.custom == null
                ? Collections.<LauncherConfig.CustomLink>emptyList() : links.custom;
        boolean named = false;
        for (LauncherConfig.CustomLink link : custom) {
            if (Platform.of(link) != null) {
                named = true;
                break;
            }
        }
        List<Link> out = new ArrayList<>();
        for (Platform platform : Platform.values()) {
            String url;
            if (platform == Platform.DISCORD) {
                // Never hidden: a missing or unusable address falls back to the built-in one.
                String configured = links == null ? null : links.discord;
                url = isCommunityUrl(configured) ? configured : platform.defaultUrl;
            } else if (named) {
                url = null;
                for (LauncherConfig.CustomLink link : custom) {
                    if (Platform.of(link) == platform) {
                        url = link.url;
                        break;
                    }
                }
            } else {
                url = platform.defaultUrl;
            }
            if (isCommunityUrl(url)) out.add(new Link(platform, url));
        }
        return out;
    }

    /**
     * pl-content's {@code is_community_url}: {@code https://} with a host, no credentials, no surrounding
     * space and no control characters.
     */
    static boolean isCommunityUrl(@Nullable String url) {
        if (url == null || url.isEmpty() || !url.startsWith("https://") || !url.equals(url.trim())) return false;
        for (int i = 0; i < url.length(); i++) {
            if (Character.isISOControl(url.charAt(i))) return false;
        }
        try {
            URI uri = new URI(url);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isEmpty()
                    && uri.getRawUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
