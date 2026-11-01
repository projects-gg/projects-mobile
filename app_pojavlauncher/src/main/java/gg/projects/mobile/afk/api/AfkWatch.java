package gg.projects.mobile.afk.api;

/** A screen's watch on the AFK accounts ({@link PhoneAfk#watch}); close it in {@code onStop}/{@code onDestroyView}. */
public interface AfkWatch {
    void close();
}
