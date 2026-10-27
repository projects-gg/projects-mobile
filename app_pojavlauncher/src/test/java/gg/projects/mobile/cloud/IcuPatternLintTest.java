package gg.projects.mobile.cloud;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The app's own regular expressions must compile on Android, whose engine is ICU, not only on the JVM these tests run
 * on: ICU refuses a bare closing brace that the JVM takes as a literal (27 Oct 2026: CloudEdit's pattern checks threw on
 * the phone for every regex rule, and no JVM test could see it). This reads the literals given to Pattern.compile,
 * matches, replaceAll, replaceFirst and split in the app's code and flags a "}" that closes no "{".
 */
public class IcuPatternLintTest {
    private static final Pattern CALL = Pattern.compile(
            "(?:Pattern\\.compile|\\.matches|\\.replaceAll|\\.replaceFirst|\\.split)\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** Where a "}" closes no "{" (outside a character class), or -1. */
    static int bareBrace(String regex) {
        boolean open = false;
        int depth = 0;
        for (int i = 0; i < regex.length(); i++) {
            char c = regex.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (depth > 0) {
                if (c == '[') depth++;
                else if (c == ']') depth--;
            } else if (c == '[') {
                depth = 1;
            } else if (c == '{') {
                open = true;
            } else if (c == '}') {
                if (!open) return i;
                open = false;
            }
        }
        return -1;
    }

    /** The Java literal's text as the regex engine sees it. */
    static String unescapeJava(String literal) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (c == '\\' && i + 1 < literal.length()) {
                char next = literal.charAt(++i);
                if (next == '\\' || next == '"') out.append(next);
                else out.append('\\').append(next);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    @Test
    public void theCheckKnowsABareBrace() {
        assertTrue(bareBrace(unescapeJava("\\\\{(\\\\d+)(?:,(\\\\d*))?}")) >= 0);
        assertEquals(-1, bareBrace(unescapeJava("\\\\{(\\\\d+)(?:,(\\\\d*))?\\\\}")));
        assertEquals(-1, bareBrace("^[A-Za-z0-9_]{3,16}$"));
        assertEquals(-1, bareBrace("[}]"));
    }

    @Test
    public void noLiteralHasABareClosingBrace() throws IOException {
        Path root = null;
        for (String candidate : new String[]{"src/main/java/gg/projects/mobile", "app_pojavlauncher/src/main/java/gg/projects/mobile"}) {
            Path p = Paths.get(candidate);
            if (Files.isDirectory(p)) {
                root = p;
                break;
            }
        }
        // Run from somewhere else (a javac-only run): nothing to read.
        Assume.assumeTrue(root != null);
        List<String> found = new ArrayList<>();
        int literals = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                Matcher m = CALL.matcher(source);
                while (m.find()) {
                    literals++;
                    String regex = unescapeJava(m.group(1));
                    int at = bareBrace(regex);
                    if (at >= 0) found.add(root.relativize(file) + ": \"" + regex + "\" at " + at);
                }
            }
        }
        assertTrue("no regex literals found under " + root, literals > 50);
        assertEquals("bare \"}\" in a regex (ICU refuses it on Android): " + found, 0, found.size());
    }
}
