package gg.projects.mobile.accounts;

import androidx.annotation.MainThread;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** M053 (SKIN1): the presence socket's {@code mc} frames for whoever listens (the Görünüm screen while it is open). */
public final class ProjectsSkinHub {
    private ProjectsSkinHub() {}

    public interface Listener {
        void onMcFrame(JsonObject frame);
    }

    private static final List<Listener> sListeners = new ArrayList<>();

    @MainThread
    public static void add(Listener listener) {
        if (!sListeners.contains(listener)) sListeners.add(listener);
    }

    @MainThread
    public static void remove(Listener listener) {
        sListeners.remove(listener);
    }

    @MainThread
    public static void onFrame(JsonObject frame) {
        for (Listener l : new ArrayList<>(sListeners)) l.onMcFrame(frame);
    }
}
