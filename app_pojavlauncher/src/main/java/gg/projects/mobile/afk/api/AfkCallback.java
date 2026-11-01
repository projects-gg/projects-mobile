package gg.projects.mobile.afk.api;

/** An answer that comes later, on the main thread. */
public interface AfkCallback<T> {
    void done(T result);
}
