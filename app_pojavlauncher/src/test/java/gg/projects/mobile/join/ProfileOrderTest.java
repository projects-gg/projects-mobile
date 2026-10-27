package gg.projects.mobile.join;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * MOB2: the profile list has an order of its own, so the profile chosen after a delete is the
 * list's first, never a HashMap's first entry (practically random).
 */
public class ProfileOrderTest {
    private static final Locale TR = new Locale("tr", "TR");

    private static MinecraftProfile named(String name) {
        MinecraftProfile profile = new MinecraftProfile();
        profile.name = name;
        profile.lastVersionId = "26.1.2";
        return profile;
    }

    @Test
    public void projectsThenTheDefaultThenTheirOwnByName() {
        Map<String, MinecraftProfile> profiles = new HashMap<>();
        profiles.put("c1", named("Zombi"));
        profiles.put("c2", named("Çiftlik"));
        profiles.put("c3", named("cadı"));
        profiles.put("c4", named("(Default)"));
        profiles.put(ProjectsProfile.KEY, named("Projects"));
        profiles.put("c5", named("Dağ"));
        profiles.put("c6", named(""));
        profiles.put("c7", named("New"));
        profiles.put("c8", named(null));
        assertEquals(Arrays.asList(ProjectsProfile.KEY, "c4", "c3", "c2", "c5", "c1", "c6", "c7", "c8"),
                ProfileOrder.keys(profiles, TR));
        // The player renamed Projects: it is still the official pack's profile, first.
        profiles.put(ProjectsProfile.KEY, named("Zzz"));
        assertEquals(ProjectsProfile.KEY, ProfileOrder.keys(profiles, TR).get(0));
    }

    @Test
    public void equalNamesKeepOneOrder() {
        Map<String, MinecraftProfile> profiles = new HashMap<>();
        profiles.put("k2", named("Fabric"));
        profiles.put("k1", named("fabric"));
        profiles.put("k3", named(" Fabric "));
        assertEquals(Arrays.asList("k1", "k2", "k3"), ProfileOrder.keys(profiles, Locale.ENGLISH));
        assertEquals(Arrays.asList("k1", "k2", "k3"), ProfileOrder.keys(profiles, TR));
    }

    @Test
    public void theFirstProfileIsPlaysChoiceAfterADelete() {
        Map<String, MinecraftProfile> profiles = new HashMap<>();
        assertNull(ProfileOrder.first(profiles, TR));
        profiles.put("c1", named("Zombi"));
        profiles.put("c2", named("Fabric"));
        assertEquals("c2", ProfileOrder.first(profiles, TR));
        profiles.put("c4", named("Default"));
        assertEquals("the default profile before the player's own", "c4", ProfileOrder.first(profiles, TR));
        profiles.put(ProjectsProfile.KEY, named("Projects"));
        assertEquals(ProjectsProfile.KEY, ProfileOrder.first(profiles, TR));
    }
}
