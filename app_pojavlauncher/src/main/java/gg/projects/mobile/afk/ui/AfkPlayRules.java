package gg.projects.mobile.afk.ui;

/**
 * What OYNA asks while AFK runs on the phone (phone-afk.md §3 "Oyunla birlikte"; the desktop's afkGuard and PLAY-AGAIN):
 * the same name AFK asks first ("AFK'yı durdur ve oyna", "AFK açık kalsın", "Vazgeç"); another name plays without a
 * question; while any account stays AFK the game's memory is checked first and a phone that is short asks once more
 * ("Yine de oyna"). Plain Java, JVM-tested.
 */
public final class AfkPlayRules {
    private AfkPlayRules() {}

    /**
     * What the AFK process and Android need beside the game's heap (MB): the {@code :afk} process (~32 MB and a few
     * accounts), the launcher kept in memory, and room so the phone does not close the AFK service first.
     */
    public static final int MARGIN_MB = 512;

    /** The game's heap fits beside AFK in the phone's free memory. */
    public static boolean memoryFits(int freeMb, int gameHeapMb) {
        if (freeMb <= 0 || gameHeapMb <= 0) return true;
        return freeMb >= gameHeapMb + MARGIN_MB;
    }

    /** The questions OYNA asks, in order. */
    public enum Step {
        /** The chosen account's name is AFK on this phone. */
        SAME_NAME,
        /** AFK stays on and the memory is short. */
        MEMORY,
        /** Nothing to ask: play. */
        PLAY
    }

    /**
     * The first question: {@code sameName} the chosen name is AFK; {@code othersRunning} how many AFK accounts run besides
     * it; {@code fits} the memory check's answer.
     */
    public static Step first(boolean sameName, int othersRunning, boolean fits) {
        if (sameName) return Step.SAME_NAME;
        return othersRunning > 0 && !fits ? Step.MEMORY : Step.PLAY;
    }

    /**
     * After the same-name question: {@code sameNameStays} the player kept it AFK ("AFK açık kalsın"),
     * {@code othersRunning} the other AFK accounts.
     */
    public static Step afterSameName(boolean sameNameStays, int othersRunning, boolean fits) {
        boolean afkStays = sameNameStays || othersRunning > 0;
        return afkStays && !fits ? Step.MEMORY : Step.PLAY;
    }
}
