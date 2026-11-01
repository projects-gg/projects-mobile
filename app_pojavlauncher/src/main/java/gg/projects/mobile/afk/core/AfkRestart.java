package gg.projects.mobile.afk.core;

import java.util.List;

import gg.projects.mobile.afk.api.AfkReason;

/**
 * What happens after a connection ends on its own: pl-afk's {@code restart.rs} in Java (the shared vectors
 * {@code afk-rules.json} keep the two the same). Reconnects back off: the shortest delay doubles up to a minute, then
 * steps to four minutes (5, 10, 20, 40, 60, 90, 120, 180, 240 s with the phone's fixed settings, spread ±20 %); two
 * minutes in game start the count over unless a kick for the login ended it. Kicks reconnecting cannot fix stop the
 * account with a {@link KickCause} (at once, or after {@link #REPEATED_KICKS} in a row for "already connected" and
 * login timeouts), unless the message is one of the default "reconnect on these" lines. Pure Java.
 */
public final class AfkRestart {
    private AfkRestart() {}

    /** {@code RestartSchedule::default()}: floor and ceiling of any delay, the ramp, the back-off steps. */
    static final double FLOOR_S = 5.0;
    static final double CEILING_S = 3600.0;
    static final int RAMP = 4;
    static final double[] BACKOFF_STEPS_S = {60, 90, 120, 180, 240};
    public static final long STABLE_AFTER_MS = 120_000L;
    /** The phone's AutoRelog delays (the desktop's defaults: 5–10 s). */
    public static final double DELAY_MIN_S = 5.0;
    public static final double DELAY_MAX_S = 10.0;
    /** Kicks in a row with a cause that can also be passing before the account stops. */
    public static final int REPEATED_KICKS = 5;

    public enum KickCause {
        BANNED("banned", AfkReason.BANNED),
        NOT_WHITELISTED("notWhitelisted", AfkReason.NOT_WHITELISTED),
        WRONG_PASSWORD("wrongPassword", AfkReason.WRONG_PASSWORD),
        PREMIUM_ONLY("premiumOnly", AfkReason.PREMIUM_ONLY),
        LOGGED_IN_ELSEWHERE("loggedInElsewhere", AfkReason.LOGGED_IN_ELSEWHERE),
        ALREADY_CONNECTED("alreadyConnected", AfkReason.ALREADY_CONNECTED),
        LOGIN_TIMEOUT("loginTimeout", AfkReason.LOGIN_TIMEOUT);

        /** The desktop's serde name (shared vectors). */
        public final String key;
        public final AfkReason reason;

        KickCause(String key, AfkReason reason) {
            this.key = key;
            this.reason = reason;
        }

        public int kicksToStop() {
            return this == ALREADY_CONNECTED || this == LOGIN_TIMEOUT ? REPEATED_KICKS : 1;
        }

        /** A kick for the login itself: however long the account sat at the login, it never got in. */
        public boolean isLogin() {
            return this == LOGIN_TIMEOUT || this == WRONG_PASSWORD || this == ALREADY_CONNECTED || this == PREMIUM_ONLY;
        }

        public static KickCause ofKey(String key) {
            for (KickCause c : values()) {
                if (c.key.equals(key)) return c;
            }
            return null;
        }
    }

    private static final String[] PASSING = {"MAINTENANCE", "BAKIM", "SERVER IS RESTARTING", "SERVER RESTART",
            "RESTARTING SERVER", "YENIDEN BASLATILIYOR", "GELISTIRME MODU"};

    private static final Object[][] CAUSES = {
            {KickCause.BANNED, new String[]{"YOU ARE BANNED", "YOU'RE BANNED", "YOU HAVE BEEN BANNED", "YOU WERE BANNED",
                    "BANNED FROM", "BANNED BY", "PERMANENTLY BANNED", "TEMPORARILY BANNED", "IP BANNED", "IP IS BANNED",
                    "IP ADDRESS IS BANNED", "ACCOUNT IS BANNED", "YASAKLANDIN", "YASAKLANMISSIN", "BANLANDIN",
                    "BANLANMISSIN", "UZAKLASTIRILDIN", "HESABINIZ KULLANIMA KAPATILMIS", "BOLGENIZ YASAKLI"}},
            {KickCause.NOT_WHITELISTED, new String[]{"WHITELIST", "WHITE-LIST", "BEYAZ LISTE", "YONETICI HESABINA BU ADRESTEN"}},
            {KickCause.WRONG_PASSWORD, new String[]{"WRONG PASSWORD", "INCORRECT PASSWORD", "INVALID PASSWORD",
                    "PASSWORD IS WRONG", "PASSWORD IS INCORRECT", "YANLIS SIFRE", "HATALI SIFRE", "SIFRE YANLIS",
                    "SIFREN YANLIS", "SIFRENIZ YANLIS", "YANLIS PAROLA", "HATALI PAROLA", "PAROLA YANLIS"}},
            {KickCause.PREMIUM_ONLY, new String[]{"FAILED TO VERIFY USERNAME", "INVALID SESSION",
                    "NOT AUTHENTICATED WITH MINECRAFT"}},
            {KickCause.LOGGED_IN_ELSEWHERE, new String[]{"LOGGED IN FROM ANOTHER LOCATION", "BASKA BIR YERDEN GIRIS",
                    "BASKA BIR KONUMDAN GIRIS"}},
            {KickCause.ALREADY_CONNECTED, new String[]{"ALREADY CONNECTED", "ALREADY ONLINE", "ALREADY LOGGED IN",
                    "ALREADY PLAYING", "ZATEN BAGLI", "ZATEN CEVRIMICI", "ZATEN OYUNDA", "ZATEN SUNUCUDA", "ZATEN BIR OYUNCU",
                    "ADINIZDA BIR OYUNCU"}},
            {KickCause.LOGIN_TIMEOUT, new String[]{"LOGIN TIMEOUT", "LOGIN TIMED OUT", "TOOK TOO LONG TO LOG",
                    "GIRIS ZAMAN ASIMI", "GIRIS SURESI", "GIRIS YAPMAK ICIN SURE", "SIFRE GIRILMEDIGI ICIN"}},
    };

    /** The default "reconnect on these" kick messages of both languages (model.rs). */
    static final String[] DEFAULT_KICK_MESSAGES = {"Bağlantı koptu", "Sunucu yeniden başlatılıyor", "Sunucu dolu",
            "Çok fazla oyuncu", "Connection has been lost", "Server is restarting", "Server is full", "Too Many people"};

    /**
     * {@code RestartSchedule::delay}: seconds before restart number {@code attempt} (1-based, since the last lasting
     * connection) with AutoRelog delays {@code min}..{@code max}. Inverted ranges are swapped; non-finite values fall
     * back to the defaults.
     */
    public static double delaySeconds(double min, double max, int attempt) {
        double floor = FLOOR_S;
        double ceiling = Math.max(CEILING_S, floor);
        double lo = bound(min, DELAY_MIN_S, floor, ceiling);
        double hi = bound(max, DELAY_MAX_S, floor, ceiling);
        double shortest = Math.min(lo, hi);
        double longest = Math.max(lo, hi);
        int ramp = Math.max(RAMP, 1);
        double fraction = ramp == 1 ? 1.0 : (double) (clamp(attempt, 1, ramp) - 1) / (double) (ramp - 1);
        double ramped = shortest + (longest - shortest) * fraction;
        double backoff = backoff(shortest, attempt);
        return Math.min(Math.max(Math.max(ramped, backoff), floor), ceiling);
    }

    /** The phone's delay (its fixed 5–10 s settings). */
    public static double delaySeconds(int attempt) {
        return delaySeconds(DELAY_MIN_S, DELAY_MAX_S, attempt);
    }

    private static double bound(double seconds, double fallback, double floor, double ceiling) {
        double s = Double.isNaN(seconds) || Double.isInfinite(seconds) ? fallback : seconds;
        return Math.min(Math.max(s, floor), ceiling);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static double backoff(double shortest, int attemptIn) {
        double first = BACKOFF_STEPS_S[0];
        int attempt = Math.max(attemptIn, 1);
        int reached = 1;
        double doubled = shortest;
        while (doubled < first && reached < 32) {
            doubled *= 2.0;
            reached++;
        }
        if (attempt <= reached) return Math.min(shortest * Math.pow(2.0, attempt - 1), first);
        int step = Math.min(attempt - reached, BACKOFF_STEPS_S.length - 1);
        return BACKOFF_STEPS_S[step];
    }

    /** {@code spread_delay}: the delay times 0.8 + 0.4 × {@code spread} ({@code 0..1}, normally random), in ms. */
    public static long spreadDelayMs(double min, double max, int attempt, double spread) {
        double s = Double.isNaN(spread) || Double.isInfinite(spread) ? 0.5 : Math.min(Math.max(spread, 0.0), 1.0);
        return Math.round(delaySeconds(min, max, attempt) * (0.8 + 0.4 * s) * 1000.0);
    }

    public static long spreadDelayMs(int attempt, double spread) {
        return spreadDelayMs(DELAY_MIN_S, DELAY_MAX_S, attempt, spread);
    }

    /** The connection worked: in game for two minutes and not ended by a kick for the login. */
    public static boolean lasting(long inGameMs, KickCause cause) {
        return inGameMs >= STABLE_AFTER_MS && (cause == null || !cause.isLogin());
    }

    /** Upper case with the Turkish letters spelled in ASCII ({@code Yanlış şifre} → {@code YANLIS SIFRE}). */
    public static String plainUpper(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            String upper = new String(Character.toChars(cp)).toUpperCase(java.util.Locale.ROOT);
            for (int j = 0; j < upper.length(); ) {
                int u = upper.codePointAt(j);
                j += Character.charCount(u);
                switch (u) {
                    case 'İ': out.append('I'); break;
                    case 'Ş': out.append('S'); break;
                    case 'Ç': out.append('C'); break;
                    case 'Ğ': out.append('G'); break;
                    case 'Ö': out.append('O'); break;
                    case 'Ü': out.append('U'); break;
                    default: out.appendCodePoint(u);
                }
            }
        }
        return out.toString();
    }

    /**
     * The cause behind a server's kick or login refusal ({@code kickOrRefusal}), if its message names one; connection
     * losses never have one.
     */
    public static KickCause kickCause(boolean kickOrRefusal, String message) {
        if (!kickOrRefusal || message == null) return null;
        String text = plainUpper(message);
        for (String p : PASSING) {
            if (text.contains(p)) return null;
        }
        for (Object[] entry : CAUSES) {
            for (String phrase : (String[]) entry[1]) {
                if (text.contains(phrase)) return (KickCause) entry[0];
            }
        }
        return null;
    }

    /** The message contains one of the default "reconnect on these" lines (either language, Turkish letters folded). */
    public static boolean listedKickMessage(String message) {
        String text = plainUpper(message == null ? "" : message);
        for (String line : DEFAULT_KICK_MESSAGES) {
            String l = plainUpper(line.trim());
            if (!l.isEmpty() && text.contains(l)) return true;
        }
        return false;
    }

    /** A decision: stop for {@link #stop} (a refusal), or restart as attempt {@link #attempt}. */
    public static final class Decision {
        public final AfkReason stop;
        public final KickCause cause;
        public final int attempt;

        private Decision(AfkReason stop, KickCause cause, int attempt) {
            this.stop = stop;
            this.cause = cause;
            this.attempt = attempt;
        }

        public boolean stops() {
            return stop != null;
        }

        @Override
        public String toString() {
            return stop != null ? "stop " + stop : "restart " + attempt;
        }
    }

    /** {@code decide_failure} with the phone's settings (AutoRelog on, unlimited retries, kick filter off). */
    public static Decision decide(AfkDrop drop, int attempts, int repeats) {
        if (drop.kind == AfkDrop.Kind.UNSUPPORTED_VERSION) return new Decision(AfkReason.UNSUPPORTED_VERSION, null, 0);
        KickCause cause = causeOf(drop);
        if (cause != null && !listedKickMessage(drop.message) && !listedKickMessage(drop.english)
                && repeats + 1 >= cause.kicksToStop()) {
            return new Decision(cause.reason, cause, 0);
        }
        return new Decision(null, null, attempts + 1);
    }

    /**
     * The kick cause of a drop: its English text, then its text as shown, then vanilla's keys (a premium-only server's
     * encryption request is one at once).
     */
    public static KickCause causeOf(AfkDrop drop) {
        if (drop.kind == AfkDrop.Kind.PREMIUM_ONLY) return KickCause.PREMIUM_ONLY;
        boolean kick = drop.kind == AfkDrop.Kind.KICKED || drop.kind == AfkDrop.Kind.LOGIN_REJECTED;
        if (!kick) return null;
        KickCause cause = kickCause(true, drop.english);
        if (cause == null) cause = kickCause(true, drop.message);
        if (cause == null) cause = causeOfKeys(drop.keys);
        return cause;
    }

    static KickCause causeOfKeys(List<String> keys) {
        if (keys == null) return null;
        for (String k : keys) {
            if (k.startsWith("multiplayer.disconnect.banned")) return KickCause.BANNED;
            if (k.equals("multiplayer.disconnect.not_whitelisted")) return KickCause.NOT_WHITELISTED;
            if (k.equals("multiplayer.disconnect.duplicate_login")) return KickCause.LOGGED_IN_ELSEWHERE;
            if (k.equals("multiplayer.disconnect.unverified_username")) return KickCause.PREMIUM_ONLY;
        }
        return null;
    }
}
