package gg.projects.mobile.cloud.console;

import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import gg.projects.mobile.cloud.CloudModel;

/**
 * A downloaded console (services/cloud-afk README "Console kinds" → Logs and downloads): the masked log's lines of the
 * last hour, today (the Turkey day) or the last three days, as a UTF-8 text file with a BOM and CRLF line ends, one line
 * per console line, {@code [HH:MM:SS] [<kind>] <text>} in Turkey time and {@code --- DD.MM.YYYY ---} before each day.
 * The text is the masked log's: no secret, no password. The phone shares it or saves it as a file.
 */
public final class ConsoleExport {
    private ConsoleExport() {}

    public static final TimeZone TURKEY = TimeZone.getTimeZone("Europe/Istanbul");
    /** The pages read for one file at most (500 lines each): a file never holds more than this many lines. */
    public static final int MAX_PAGES = 40;

    public enum Range { HOUR, TODAY, DAYS3 }

    /** The span's start in Unix seconds, for {@code nowSeconds}. */
    public static long from(Range range, long nowSeconds) {
        switch (range) {
            case HOUR:
                return nowSeconds - 3600;
            case DAYS3:
                return nowSeconds - 3 * 86_400;
            case TODAY:
            default: {
                Calendar day = Calendar.getInstance(TURKEY, Locale.ROOT);
                day.setTimeInMillis(nowSeconds * 1000L);
                day.set(Calendar.HOUR_OF_DAY, 0);
                day.set(Calendar.MINUTE, 0);
                day.set(Calendar.SECOND, 0);
                day.set(Calendar.MILLISECOND, 0);
                return day.getTimeInMillis() / 1000L;
            }
        }
    }

    /** The kinds' names in the file's language (the app's strings: Sohbet, Özel, Sunucu, Başlık, Komut, Bağlantı, Hata, Konsol). */
    public interface Names {
        String kind(ConsoleKinds.FileKind kind);
    }

    /** The file's text (without the BOM: {@link #bytes} adds it). */
    public static String text(List<CloudModel.ConsoleLine> lines, Names names) {
        StringBuilder out = new StringBuilder();
        Calendar at = Calendar.getInstance(TURKEY, Locale.ROOT);
        int lastDay = -1;
        for (CloudModel.ConsoleLine line : lines) {
            at.setTimeInMillis(line.at * 1000L);
            int day = at.get(Calendar.YEAR) * 1000 + at.get(Calendar.DAY_OF_YEAR);
            if (day != lastDay) {
                lastDay = day;
                out.append(String.format(Locale.ROOT, "--- %02d.%02d.%04d ---", at.get(Calendar.DAY_OF_MONTH), at.get(Calendar.MONTH) + 1,
                        at.get(Calendar.YEAR))).append("\r\n");
            }
            out.append(String.format(Locale.ROOT, "[%02d:%02d:%02d] [", at.get(Calendar.HOUR_OF_DAY), at.get(Calendar.MINUTE),
                    at.get(Calendar.SECOND)));
            out.append(names.kind(ConsoleKinds.fileKind(line))).append("] ");
            // A line is one line in the file: the machine already made it so; a stray break would start a fake one.
            out.append(line.text.replace("\r", " ").replace("\n", " ")).append("\r\n");
        }
        return out.toString();
    }

    /** UTF-8 with a BOM (Notepad and phones open Turkish letters right). */
    public static byte[] bytes(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[body.length + 3];
        out[0] = (byte) 0xEF;
        out[1] = (byte) 0xBB;
        out[2] = (byte) 0xBF;
        System.arraycopy(body, 0, out, 3, body.length);
        return out;
    }

    /** {@code <name>-konsol-<YYYY-MM-DD>.txt} / {@code <name>-console-<YYYY-MM-DD>.txt}, the day in Turkey. */
    public static String fileName(String account, String lang, long nowSeconds) {
        Calendar day = Calendar.getInstance(TURKEY, Locale.ROOT);
        day.setTimeInMillis(nowSeconds * 1000L);
        String safe = account.replaceAll("[^A-Za-z0-9_]", "");
        if (safe.isEmpty()) safe = "hesap";
        return String.format(Locale.ROOT, "%s-%s-%04d-%02d-%02d.txt", safe, "en".equals(lang) ? "console" : "konsol", day.get(Calendar.YEAR),
                day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
    }
}
