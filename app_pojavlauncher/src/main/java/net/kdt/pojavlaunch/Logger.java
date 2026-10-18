package net.kdt.pojavlaunch;

import androidx.annotation.Keep;

import java.util.concurrent.CopyOnWriteArrayList;

/** Singleton class made to log on one file
 * The singleton part can be removed but will require more implementation from the end-dev
 */
@Keep
public class Logger {
    private static final CopyOnWriteArrayList<eventLogListener> logListeners = new CopyOnWriteArrayList<>();
    /** Projects fork (M14): the one native listener, set once and never replaced or unset. */
    private static final eventLogListener DISPATCHER = Logger::onEventLogged;
    private static boolean sNativeListenerSet;

    /** Print the text to the log file if not censored */
    public static native void appendToLog(String text);

    /** Reset the log file, effectively erasing any previous logs */
    public static native void begin(String logFilePath);

    /**
     * Add a listener for the logfile. Projects fork (M14): the native side gets one listener the
     * first time and keeps it. Unsetting or replacing it deletes the global reference the logger
     * thread may be calling through at that very moment (it checks the field, then calls), which
     * could abort the game when the loading screen stopped listening while Minecraft logged its
     * start; upstream unset it whenever the last listener left.
     */
    public static void addLogListener(eventLogListener logListeners) {
        Logger.logListeners.add(logListeners);
        synchronized (Logger.class) {
            if (sNativeListenerSet) return;
            sNativeListenerSet = true;
        }
        setLogListener(DISPATCHER);
    }

    /** Remove a listener for the logfile; the native listener stays (it then finds nobody). */
    public static void removeLogListener(eventLogListener logListener) {
        Logger.logListeners.remove(logListener);
    }

    private static void onEventLogged(String text) {
        for (eventLogListener logListener: Logger.logListeners) {
            logListener.onEventLogged(text);
        }
    }

    /** Small listener for anything listening to the log
     *  Performs double duty as being the interface for java listeners and the native callback
     */
    @Keep
    public interface eventLogListener {
        void onEventLogged(String text);
    }

    /** Link a log listener to the logger */
    private static native void setLogListener(eventLogListener logListener);
}
