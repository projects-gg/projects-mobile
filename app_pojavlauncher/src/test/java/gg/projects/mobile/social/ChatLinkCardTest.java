package gg.projects.mobile.social;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** MOB-POLISH: a link's card shows only when it has something beyond the link (ChatLinkUi.showsCard). */
public class ChatLinkCardTest {
    private static ChatCards.Card ready(String title, String desc, String image) {
        return new ChatCards.Card(ChatCards.State.READY, "https://ornek.com/adalar", "ornek.com", "globe", "page",
                title, null, desc, image, 0, 0, null);
    }

    @Test
    public void aCardShowsOnlyWhatTheMessageDoesNotSayAlready() {
        assertTrue(ChatLinkUi.showsCard(ready("Adalar", null, null)));
        assertTrue("a picture without a title", ChatLinkUi.showsCard(ready(null, null, "data:image/png;base64,AAAA")));
        assertTrue("a description without a title", ChatLinkUi.showsCard(ready(null, "Haritadaki adalar", null)));
        assertFalse("nothing beyond the link", ChatLinkUi.showsCard(ready(null, null, null)));
        assertFalse("the plain card", ChatLinkUi.showsCard(ChatCards.Card.plain("https://ornek.com/adalar", "ornek.com")));
        assertFalse(ChatLinkUi.showsCard(ChatCards.Card.refused("https://ornek.com/adalar", "ornek.com", "other")));
    }
}
