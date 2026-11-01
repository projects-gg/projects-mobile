package gg.projects.mobile.afk.service;

import android.content.Context;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.afk.api.AfkReason;
import gg.projects.mobile.afk.api.AfkTexts;
import gg.projects.mobile.afk.core.AfkHost;
import gg.projects.mobile.language.LanguageManager;

/** The engine's console notices in the app's language (taken when the service starts). */
final class AndroidWords implements AfkHost.Words {
    private final Context c;
    private final boolean turkish;

    AndroidWords(Context context) {
        this.c = LanguageManager.wrap(context);
        this.turkish = LanguageManager.isTurkish(context);
    }

    @Override
    public String connecting(String address) {
        return c.getString(R.string.projects_afk_line_connecting, address);
    }

    @Override
    public String joined() {
        return c.getString(R.string.projects_afk_line_joined);
    }

    @Override
    public String dropped(AfkReason reason, long waitMs) {
        return c.getString(R.string.projects_afk_line_dropped, AfkTexts.reason(c, reason), AfkTexts.duration(c, waitMs));
    }

    @Override
    public String stopped(AfkReason reason) {
        return c.getString(R.string.projects_afk_line_stopped, AfkTexts.reason(c, reason));
    }

    @Override
    public String stoppedByPlayer() {
        return c.getString(R.string.projects_afk_line_stopped_by_player);
    }

    @Override
    public String transfer(String address) {
        return c.getString(R.string.projects_afk_line_transfer, address);
    }

    @Override
    public String window(String title) {
        return title == null || title.isEmpty() ? c.getString(R.string.projects_afk_line_window_untitled)
                : c.getString(R.string.projects_afk_line_window, title);
    }

    @Override
    public String respawned() {
        return c.getString(R.string.projects_afk_line_respawn);
    }

    @Override
    public String loginCommandsSent() {
        return c.getString(R.string.projects_afk_line_login_commands);
    }

    @Override
    public boolean turkish() {
        return turkish;
    }
}
