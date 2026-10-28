package gg.projects.mobile.strings;

import static org.junit.Assert.assertEquals;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import gg.projects.mobile.language.AppLanguage;

/**
 * The app speaks Turkish and English (gg.projects.mobile.language): every text of the Projects
 * screens exists in both, and the English interface holds no Turkish left behind in the default
 * texts or in Java code.
 */
public class LanguagePairsTest {
    private static final File RES = new File("src/main/res");
    private static final File JAVA = new File("src/main/java/gg/projects/mobile");
    private static final Pattern TURKISH_LETTER = Pattern.compile("[çğıöşüÇĞİÖŞÜ]");
    /** English texts that name Turkish letters or the language itself on purpose. */
    private static final List<String> TURKISH_ON_PURPOSE = Arrays.asList("projects_offline_name_turkish", "projects_language_turkish");

    /** Name to text of the translatable strings, plurals (their "other" form) and arrays (joined) of a file. */
    private static Map<String, String> texts(File file) throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
        Map<String, String> out = new LinkedHashMap<>();
        NodeList nodes = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element)) continue;
            Element element = (Element) node;
            if ("false".equals(element.getAttribute("translatable"))) continue;
            String name = element.getAttribute("name");
            switch (element.getTagName()) {
                case "string":
                    out.put(name, element.getTextContent());
                    break;
                case "plurals":
                case "string-array":
                    StringBuilder joined = new StringBuilder();
                    NodeList items = element.getElementsByTagName("item");
                    for (int j = 0; j < items.getLength(); j++) {
                        Element item = (Element) items.item(j);
                        String quantity = item.getAttribute("quantity");
                        joined.append(quantity.isEmpty() ? "" : quantity + "=").append(item.getTextContent()).append('\n');
                    }
                    out.put(name, joined.toString());
                    break;
                default:
                    break;
            }
        }
        return out;
    }

    @Test
    public void everyProjectsTextExistsInBothLanguages() throws Exception {
        Map<String, String> english = texts(new File(RES, "values/projects_strings.xml"));
        Map<String, String> turkish = texts(new File(RES, "values-tr/projects_strings.xml"));
        // M19: the friends and crews texts live in their own file pair.
        english.putAll(texts(new File(RES, "values/projects_social_strings.xml")));
        turkish.putAll(texts(new File(RES, "values-tr/projects_social_strings.xml")));
        // AFKPLUS-M: 7/24 Bulut and AFK+.
        english.putAll(texts(new File(RES, "values/projects_cloud_strings.xml")));
        turkish.putAll(texts(new File(RES, "values-tr/projects_cloud_strings.xml")));
        // FEAT-M: the cloud features (inventory, console kinds, placeholders, bulk, movement).
        english.putAll(texts(new File(RES, "values/strings_cloud_feat.xml")));
        turkish.putAll(texts(new File(RES, "values-tr/strings_cloud_feat.xml")));
        // FREE-M: catalog-free AFK+ (orders, the audit log, fine-tuning, receipts, talk and tag, bought credits).
        english.putAll(texts(new File(RES, "values/strings_cloud_plus.xml")));
        turkish.putAll(texts(new File(RES, "values-tr/strings_cloud_plus.xml")));
        TreeSet<String> missingTurkish = new TreeSet<>(english.keySet());
        missingTurkish.removeAll(turkish.keySet());
        TreeSet<String> missingEnglish = new TreeSet<>(turkish.keySet());
        missingEnglish.removeAll(english.keySet());
        assertEquals("English texts without Turkish", new TreeSet<String>(), missingTurkish);
        assertEquals("Turkish texts without English", new TreeSet<String>(), missingEnglish);
        for (Map.Entry<String, String> text : english.entrySet()) {
            assertTrue(text.getKey() + " is empty", !text.getValue().trim().isEmpty());
            assertTrue(text.getKey() + " is empty in Turkish", !turkish.get(text.getKey()).trim().isEmpty());
            if (text.getValue().contains("other=")) {
                assertTrue(text.getKey() + " needs an \"other\" form in Turkish", turkish.get(text.getKey()).contains("other="));
            }
        }
    }

    @Test
    public void turkishListsReplaceWholeEnglishLists() throws Exception {
        Map<String, String> turkish = texts(new File(RES, "values-tr/projects_arrays.xml"));
        Map<String, String> english = new LinkedHashMap<>();
        english.putAll(texts(new File(RES, "values/headings_array.xml")));
        english.putAll(texts(new File(RES, "values/projects_arrays.xml")));
        for (Map.Entry<String, String> list : turkish.entrySet()) {
            assertTrue(list.getKey(), english.containsKey(list.getKey()));
            assertEquals(list.getKey() + " has as many entries as the English list",
                    english.get(list.getKey()).split("\n").length, list.getValue().split("\n").length);
        }
    }

    @Test
    public void theEnglishInterfaceHasNoTurkishLeft() throws Exception {
        for (String file : new String[]{"values/projects_strings.xml", "values/projects_social_strings.xml", "values/projects_cloud_strings.xml",
                "values/strings_cloud_feat.xml", "values/strings_cloud_plus.xml", "values/strings.xml"}) {
            for (Map.Entry<String, String> text : texts(new File(RES, file)).entrySet()) {
                if (TURKISH_ON_PURPOSE.contains(text.getKey())) continue;
                assertTrue(file + ": " + text.getKey() + " = " + text.getValue(), !TURKISH_LETTER.matcher(text.getValue()).find());
            }
        }
        // Texts in Java code (the screens use resources; a log line is English anyway).
        List<String> found = new ArrayList<>();
        scan(JAVA, found);
        assertEquals(new ArrayList<String>(), found);
    }

    @Test
    public void theLanguageSettingOffersAutomaticTurkishAndEnglish() throws Exception {
        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(RES, "values/projects_arrays.xml"));
        NodeList items = document.getElementsByTagName("item");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < items.getLength(); i++) values.add(items.item(i).getTextContent());
        assertEquals(Arrays.asList(AppLanguage.AUTO, AppLanguage.TURKISH, AppLanguage.ENGLISH), values);
        String config = new String(Files.readAllBytes(new File(RES, "xml/locales_config.xml").toPath()), StandardCharsets.UTF_8);
        assertTrue(config.contains("android:name=\"tr\"") && config.contains("android:name=\"en\""));
    }

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");

    private static void scan(File dir, List<String> found) throws IOException {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scan(file, found);
                continue;
            }
            if (!file.getName().endsWith(".java")) continue;
            // The table of the Turkish default control labels and their English ones.
            if (file.getName().equals("ControlLabels.java")) continue;
            // AFKPLUS-M: the cloud contract's password command names ("/giriş", shared with the launcher and the
            // site) and the Turkish number and suffix tables; neither is interface text.
            if (file.getName().equals("CloudText.java") || file.getName().equals("TrGrammar.java")) continue;
            String code = withoutComments(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            Matcher literal = STRING_LITERAL.matcher(code);
            while (literal.find()) {
                String text = literal.group(1);
                // The offline name rules list the Turkish letters they refuse.
                if (text.equals("çÇğĞıİöÖşŞüÜ")) continue;
                if (TURKISH_LETTER.matcher(text).find()) found.add(file.getName() + ": \"" + text + "\"");
            }
        }
    }

    /** Java source without comments (string literals kept). */
    static String withoutComments(String code) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < code.length() && code.charAt(j) != c) j += code.charAt(j) == '\\' ? 2 : 1;
                out.append(code, i, Math.min(j + 1, code.length()));
                i = j + 1;
            } else if (code.startsWith("//", i)) {
                int end = code.indexOf('\n', i);
                i = end < 0 ? code.length() : end;
            } else if (code.startsWith("/*", i)) {
                int end = code.indexOf("*/", i + 2);
                i = end < 0 ? code.length() : end + 2;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
