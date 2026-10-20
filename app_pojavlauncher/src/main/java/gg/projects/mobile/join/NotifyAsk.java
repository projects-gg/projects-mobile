package gg.projects.mobile.join;

/**
 * M18: when the app asks Android whether it may notify ("Projects hazır" when the player left during a
 * download). Android 13+ hides notifications until they are allowed. Plain Java, JVM-tested.
 *
 * <ul>
 * <li>{@link #offerDue}: the home card's quiet question (0.1.9), on the first home visit where it applies,
 * until it is answered or set aside. A tester who updated with everything downloaded opened the app
 * looking for the question and found none: 0.1.8 asked only at the first Projects start.</li>
 * <li>{@link #startAsks}: Android's question right before the first Projects start (0.1.8), once; it is
 * the fallback when the card was set aside or never answered.</li>
 * </ul>
 * Nothing asks again after an answer: a "no" stays a no, and when Android itself stopped asking only a
 * notification control the player taps later (the event bell) offers the app's settings.
 */
public final class NotifyAsk {
    private NotifyAsk() {}

    /** Android 13, where notifications became a runtime permission. */
    public static final int TIRAMISU = 33;

    /**
     * @param sdk        the phone's API level
     * @param allowed    notifications are allowed
     * @param offerDone  the card was answered or set aside, or Android asked another way meanwhile
     * @param startAsked a Projects start asked already
     */
    public static boolean offerDue(int sdk, boolean allowed, boolean offerDone, boolean startAsked) {
        return sdk >= TIRAMISU && !allowed && !offerDone && !startAsked;
    }

    /** Whether a Projects start shows Android's question before it begins. */
    public static boolean startAsks(int sdk, boolean allowed, boolean startAsked) {
        return sdk >= TIRAMISU && !allowed && !startAsked;
    }
}
