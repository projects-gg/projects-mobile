package gg.projects.mobile.strings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Texts formatted with arguments must never crash the launcher. getString(id, args) gets the
 * arguments the default (English) text asks for: a translation that wants another argument, or
 * another type at the same place, throws at run time, and so does a call that passes fewer
 * arguments than its text needs. That is how "Projects'e katıl" crashed in 0.1.2: Fabric's first
 * progress message came without the loader name its text needs (ProgressLayout now also keeps the
 * previous text instead of crashing).
 */
public class FormatStringsTest {
    private static final File RES = new File("src/main/res");
    private static final File JAVA = new File("src/main/java");
    /** java.util.Formatter's specifier syntax. */
    private static final Pattern SPEC = Pattern.compile("%(\\d+\\$)?([-#+ 0,(<]*)?(\\d+)?(\\.\\d+)?([tT])?([a-zA-Z%])");
    private static final Pattern CALL = Pattern.compile("\\b(?:ProgressLayout\\.setProgress|ProgressKeeper\\.submitProgress)\\s*\\(");
    private static final Pattern STRING_ID = Pattern.compile("R\\.string\\.(\\w+)");

    /** Conversions java.util.Formatter knows (besides the date ones, %t and %T). */
    private static final String CONVERSIONS = "bBhHsScCdoxXeEfgGaA%n";

    /**
     * The arguments a format string uses, index (from 1) to conversion, e.g. {1=s, 2=d}; null for a
     * text that is no format string, with a "%" the formatter would refuse ("100% means ...").
     */
    static Map<Integer, String> arguments(String text) {
        Map<Integer, String> used = new TreeMap<>();
        Matcher m = SPEC.matcher(text);
        int ordinal = 0;
        int last = 0;
        int covered = 0;
        while (m.find()) {
            if (text.substring(covered, m.start()).indexOf('%') >= 0) return null;
            covered = m.end();
            String conversion = m.group(6);
            boolean date = m.group(5) != null;
            if (!date && CONVERSIONS.indexOf(conversion.charAt(0)) < 0) return null;
            if (!date && (conversion.equals("%") || conversion.equals("n"))) continue;
            String flags = m.group(2) == null ? "" : m.group(2);
            int index;
            if (m.group(1) != null) index = Integer.parseInt(m.group(1).substring(0, m.group(1).length() - 1));
            else if (flags.contains("<")) index = last;
            else index = ++ordinal;
            last = index;
            used.put(index, date ? "t" : conversion.toLowerCase());
        }
        return text.indexOf('%', covered) >= 0 ? null : used;
    }

    /** Conversions that accept any argument. */
    private static boolean acceptsAnything(String conversion) {
        return conversion.equals("s") || conversion.equals("b") || conversion.equals("h");
    }

    @Test
    public void specifiersAreParsedLikeTheFormatter() {
        assertEquals(Collections.singletonMap(1, "s"), arguments("Downloading %s loader metadata"));
        Map<Integer, String> two = new TreeMap<>();
        two.put(1, "f");
        two.put(2, "f");
        assertEquals(two, arguments("Projects paketi indiriliyor (%1$.1f MB / %2$.1f MB)"));
        assertEquals(Collections.emptyMap(), arguments("100%% done%n"));
        Map<Integer, String> reuse = new TreeMap<>();
        reuse.put(1, "d");
        reuse.put(2, "s");
        assertEquals(reuse, arguments("%2$s: %1$d (%<d)"));
        assertEquals(Collections.singletonMap(1, "t"), arguments("%1$tY"));
        // Plain texts with a percent sign: the formatter would refuse them, nobody formats them.
        assertNull(arguments("100% means full resolution"));
        assertNull(arguments("70 %"));
        assertNull(arguments("%s is 50%"));
    }

    @Test
    public void translationsUseOnlyTheDefaultArguments() throws Exception {
        assertTrue("run from the app module: " + RES.getAbsolutePath(), RES.isDirectory());
        Map<String, Map<Integer, String>> defaults = texts(new File(RES, "values"), true);
        assertTrue("default strings found", defaults.size() > 100);
        List<String> problems = new ArrayList<>();
        File[] folders = RES.listFiles((dir, name) -> name.startsWith("values-"));
        if (folders == null) folders = new File[0];
        Arrays.sort(folders);
        for (File folder : folders) {
            for (Map.Entry<String, Map<Integer, String>> text : texts(folder, false).entrySet()) {
                Map<Integer, String> allowed = defaults.get(text.getKey());
                // Not in the default (removed upstream, untranslatable), or a plain text there.
                if (allowed == null) continue;
                if (text.getValue() == null) {
                    if (!allowed.isEmpty()) problems.add(folder.getName() + "/" + text.getKey() + ": a % the formatter refuses");
                    continue;
                }
                for (Map.Entry<Integer, String> use : text.getValue().entrySet()) {
                    String given = allowed.get(use.getKey());
                    if (given == null) {
                        problems.add(folder.getName() + "/" + text.getKey() + ": argument " + use.getKey() + " is not passed");
                    } else if (!acceptsAnything(use.getValue()) && !use.getValue().equals(given)) {
                        problems.add(folder.getName() + "/" + text.getKey() + ": argument " + use.getKey() + " is %" + use.getValue() + " but the default has %" + given);
                    }
                }
            }
        }
        assertTrue("translations that would crash: " + problems, problems.isEmpty());
    }

    @Test
    public void progressCallsPassEveryArgumentTheirTextNeeds() throws Exception {
        assertTrue("run from the app module: " + JAVA.getAbsolutePath(), JAVA.isDirectory());
        Map<String, Map<Integer, String>> defaults = texts(new File(RES, "values"), true);
        List<String> problems = new ArrayList<>();
        int checked = scan(JAVA, defaults, problems);
        assertTrue("progress calls found: " + checked, checked > 10);
        assertTrue("progress calls that would crash: " + problems, problems.isEmpty());
    }

    private static int scan(File dir, Map<String, Map<Integer, String>> defaults, List<String> problems) throws IOException {
        int checked = 0;
        File[] children = dir.listFiles();
        if (children == null) return 0;
        for (File child : children) {
            if (child.isDirectory()) {
                checked += scan(child, defaults, problems);
                continue;
            }
            if (!child.getName().endsWith(".java")) continue;
            String source = new String(Files.readAllBytes(child.toPath()), StandardCharsets.UTF_8);
            Matcher call = CALL.matcher(source);
            while (call.find()) {
                List<String> args = callArguments(source, call.end());
                for (int i = 0; i < args.size(); i++) {
                    Matcher id = STRING_ID.matcher(args.get(i).trim());
                    if (!id.matches()) continue;
                    Map<Integer, String> needs = defaults.get(id.group(1));
                    if (needs == null) continue;
                    checked++;
                    int needed = needs.isEmpty() ? 0 : Collections.max(needs.keySet());
                    int given = args.size() - i - 1;
                    if (given < needed) {
                        int line = 1;
                        for (int c = 0; c < call.start(); c++) if (source.charAt(c) == '\n') line++;
                        problems.add(child.getName() + ":" + line + " " + id.group(1) + " needs " + needed + ", gets " + given);
                    }
                    break;
                }
            }
        }
        return checked;
    }

    /** The top-level arguments of the call whose "(" ends right before {@code start}. */
    private static List<String> callArguments(String source, int start) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < source.length() && source.charAt(end) != c) end += source.charAt(end) == '\\' ? 2 : 1;
                current.append(source, i, Math.min(end + 1, source.length()));
                i = end;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    if (current.toString().trim().length() > 0) args.add(current.toString());
                    return args;
                }
                depth--;
            }
            if (c == ',' && depth == 0) {
                args.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        return args;
    }

    /** Every formatted text in a values folder: plain strings and plural items (merged per name). */
    private static Map<String, Map<Integer, String>> texts(File folder, boolean isDefault) throws Exception {
        Map<String, Map<Integer, String>> texts = new LinkedHashMap<>();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".xml"));
        if (files == null) return texts;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        for (File file : files) {
            Document document = factory.newDocumentBuilder().parse(file);
            NodeList strings = document.getElementsByTagName("string");
            for (int i = 0; i < strings.getLength(); i++) {
                Element string = (Element) strings.item(i);
                if (isDefault && "false".equals(string.getAttribute("formatted"))) continue;
                texts.put(string.getAttribute("name"), arguments(string.getTextContent()));
            }
            NodeList plurals = document.getElementsByTagName("plurals");
            for (int i = 0; i < plurals.getLength(); i++) {
                Element plural = (Element) plurals.item(i);
                Map<Integer, String> merged = new TreeMap<>();
                NodeList items = plural.getChildNodes();
                for (int j = 0; j < items.getLength() && merged != null; j++) {
                    Node item = items.item(j);
                    if (item.getNodeType() != Node.ELEMENT_NODE) continue;
                    Map<Integer, String> used = arguments(item.getTextContent());
                    if (used == null) merged = null;
                    else merged.putAll(used);
                }
                texts.put(plural.getAttribute("name"), merged);
            }
        }
        return texts;
    }
}
