package gg.projects.mobile.content;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

/** The patch notes tile's lines (the desktop's lib/news.ts {@code highlights} and {@code newsHighlights}). */
public class NewsTextTest {
    @Test
    public void bulletsBecomePlainLines() {
        String body = "## Yenilikler\n\nGiriş metni.\n\n- Yeni zindan: **Kül Mağarası**\n* [Kılıç](https://projects.gg) hasarı dengelendi\n"
                + "+ `market` hızlandı\n1. Arena_düzeltmesi\n2) beşinci\n- \n- Yeni zindan: Kül Mağarası\n";
        assertEquals(Arrays.asList("Yeni zindan: Kül Mağarası", "Kılıç hasarı dengelendi", "market hızlandı", "Arenadüzeltmesi"),
                NewsText.highlights(body, 4));
        // Repeated bullets show once; the count is taken before (as on the desktop).
        assertEquals(Collections.singletonList("Aynı"), NewsText.highlights("- Aynı\n- Aynı\n- Başka", 2));
        assertTrue(NewsText.highlights(null, 4).isEmpty());
    }

    @Test
    public void newsFallsBackToTheSummary() {
        assertEquals(Arrays.asList("Bir", "İki"), NewsText.newsHighlights("- Bir\n- İki\n- Üç", "Özet", 2));
        assertEquals(Collections.singletonList("Yeni zindan ve düzeltmeler."),
                NewsText.newsHighlights("Madde yok.", " Yeni zindan ve düzeltmeler. ", 2));
        assertTrue(NewsText.newsHighlights("", " ", 2).isEmpty());
    }
}
