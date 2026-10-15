package gg.projects.mobile.content;

import androidx.annotation.Keep;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code config/launcher.<sha256>.json}, reduced to what the mobile app uses. Every field is
 * optional ({@code {}} is a valid config).
 */
@Keep
public final class LauncherConfig {
    public int schema = 1;
    public Maintenance maintenance;
    public List<ServerEntry> servers;
    public Links links;
    public String recommendedPack;

    @Keep
    public static final class Maintenance {
        public boolean enabled;
        public String title;
        public String message;
        public String until;

        /** Active while enabled and ({@code until} missing or in the future). */
        public boolean isActive(long now) {
            if (!enabled) return false;
            if (until == null) return true;
            Long end = Rfc3339.parse(until);
            return end == null || now < end;
        }
    }

    @Keep
    public static final class ServerEntry {
        public String id;
        public String name;
        public String address;
        public Integer port;
        public boolean featured;
    }

    @Keep
    public static final class Links {
        public String website;
        public String discord;
        public String store;
        public String support;
    }

    public static LauncherConfig parse(byte[] bytes) throws ContentException {
        LauncherConfig config = ContentJson.parse(bytes, LauncherConfig.class, "launcher config");
        ContentJson.checkSchema("launcher config", config.schema, ContentConfig.LAUNCHER_CONFIG_SCHEMA);
        if (config.servers == null) config.servers = new ArrayList<>();
        return config;
    }

    /** The Discord invite to open, or null when the config has no usable one. */
    public String discordUrl() {
        return links != null && ContentPaths.isHttpsUrl(links.discord) ? links.discord : null;
    }
}
