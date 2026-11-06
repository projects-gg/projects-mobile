package gg.projects.mobile.content;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/** PHONE-AFK: {@code features.phoneAfk} of the signed config (content-format.md): off unless it says {@code true}. */
public class PhoneAfkFeatureTest {
    private static LauncherConfig parse(String json) throws ContentException {
        return LauncherConfig.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void offUnlessTheConfigSaysTrue() throws ContentException {
        assertFalse(parse("{\"schema\":1}").phoneAfkFeature);
        assertFalse(parse("{\"schema\":1,\"features\":{\"friends\":true}}").phoneAfkFeature);
        assertTrue(parse("{\"schema\":1,\"features\":{\"phoneAfk\":true}}").phoneAfkFeature);
    }

    @Test
    public void independentOfFriends() throws ContentException {
        LauncherConfig alone = parse("{\"schema\":1,\"features\":{\"phoneAfk\":true,\"friends\":false}}");
        assertTrue(alone.phoneAfkFeature);
        assertFalse(alone.friendsFeature);
    }

    @Test
    public void aBrokenValueIsOff() throws ContentException {
        assertFalse(parse("{\"schema\":1,\"features\":{\"phoneAfk\":\"true\"}}").phoneAfkFeature);
        assertFalse(parse("{\"schema\":1,\"features\":{\"phoneAfk\":1}}").phoneAfkFeature);
        assertFalse(parse("{\"schema\":1,\"features\":[true]}").phoneAfkFeature);
    }
}
