package gg.projects.mobile.cloud.ui;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * TABLET-M: a settings screen's unsaved edits through a rotation, a split screen or a resized window. The launcher is
 * made again then (its layouts follow the window), the process stays: what the screen puts here when its state is
 * saved, its next copy takes when it is created. The values go with the screen (back, saved, left); a process that
 * Android ended keeps nothing, as before.
 */
public final class Kept extends ViewModel {
    private final Map<String, Object> mValues = new HashMap<>();

    static Kept of(Fragment fragment) {
        return new ViewModelProvider(fragment).get(Kept.class);
    }

    void put(String key, @Nullable Object value) {
        mValues.put(key, value);
    }

    boolean has(String key) {
        return mValues.containsKey(key);
    }

    /** The value kept under {@code key}, taken away (a later copy of the screen starts from its own state). */
    @Nullable
    @SuppressWarnings("unchecked")
    <T> T take(String key) {
        return (T) mValues.remove(key);
    }

    void clear() {
        mValues.clear();
    }
}
