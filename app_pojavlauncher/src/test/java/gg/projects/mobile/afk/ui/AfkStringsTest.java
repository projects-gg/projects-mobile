package gg.projects.mobile.afk.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The AFK screens' words (projects_afk_strings.xml) in both languages: every text in English and Turkish, the same
 * arguments in both, no Turkish letters left in English, a Turkish plural with its "other" form, and none of the
 * marks the copy rules ban (design.md §7: no long dash, arrow, middle dot or exclamation mark).
 */
public final class AfkStringsTest {
    private static final File RES = new File("src/main/res");
    private static final Pattern ARG = Pattern.compile("%(\\d+)\\$([sd])");
    private static final Pattern TURKISH = Pattern.compile("[çğıöşüÇĞİÖŞÜ]");

    private static Map<String, String> texts(File file) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
        Map<String, String> out = new TreeMap<>();
        NodeList nodes = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            String name = e.getAttribute("name");
            if ("string".equals(e.getTagName())) {
                out.put(name, e.getTextContent());
            } else if ("plurals".equals(e.getTagName())) {
                NodeList items = e.getElementsByTagName("item");
                for (int j = 0; j < items.getLength(); j++) {
                    Element item = (Element) items.item(j);
                    out.put(name + "#" + item.getAttribute("quantity"), item.getTextContent());
                }
            }
        }
        return out;
    }

    private static TreeSet<String> args(String text) {
        TreeSet<String> out = new TreeSet<>();
        Matcher m = ARG.matcher(text);
        while (m.find()) out.add(m.group(1) + m.group(2));
        return out;
    }

    @Test
    public void everyTextIsInBothLanguagesWithTheSameArguments() throws Exception {
        Map<String, String> english = texts(new File(RES, "values/projects_afk_strings.xml"));
        Map<String, String> turkish = texts(new File(RES, "values-tr/projects_afk_strings.xml"));
        assertTrue("texts found", english.size() > 60);
        TreeSet<String> englishNames = new TreeSet<>();
        for (String key : english.keySet()) englishNames.add(key.replaceAll("#.*", ""));
        TreeSet<String> turkishNames = new TreeSet<>();
        for (String key : turkish.keySet()) turkishNames.add(key.replaceAll("#.*", ""));
        assertEquals(englishNames, turkishNames);
        for (Map.Entry<String, String> e : english.entrySet()) {
            String key = e.getKey();
            assertFalse(key + " is empty", e.getValue().trim().isEmpty());
            assertFalse(key + " has Turkish letters in English: " + e.getValue(), TURKISH.matcher(e.getValue()).find());
            String base = key.replaceAll("#.*", "");
            String tr = turkish.containsKey(key) ? turkish.get(key) : turkish.get(base + "#other");
            assertTrue(key + " in Turkish", tr != null && !tr.trim().isEmpty());
            if (key.contains("#")) assertTrue(base + " needs an \"other\" form in Turkish", turkish.containsKey(base + "#other"));
            assertEquals(key + " takes the same arguments", args(e.getValue()), args(tr));
        }
    }

    @Test
    public void theCopyRulesHold() throws Exception {
        List<String> bad = new ArrayList<>();
        for (String file : new String[]{"values/projects_afk_strings.xml", "values-tr/projects_afk_strings.xml"}) {
            for (Map.Entry<String, String> e : texts(new File(RES, file)).entrySet()) {
                String t = e.getValue();
                if (t.contains("—") || t.contains("–") || t.contains("→") || t.contains("·") || t.contains("!")
                        || t.contains(";")) {
                    bad.add(file + ": " + e.getKey() + " = " + t);
                }
            }
        }
        assertEquals(new ArrayList<String>(), bad);
    }
}
