package gg.projects.mobile.voice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** After 0.5.4 (the lead, 13 Nov): a friend call of two shows no roles ("Lider"); from three people on, and in crew rooms always, they show. */
public class VoiceRolesTest {
    @Test
    public void rolesShowFromThreePeopleOnAndAlwaysInCrewRooms() {
        VoiceState s = new VoiceState();
        s.crewRoom = false;
        assertFalse(s.showsRoles());
        s.people.add(new VoiceState.Person());
        assertFalse(s.showsRoles());
        s.people.add(new VoiceState.Person());
        assertTrue(s.showsRoles());
        VoiceState crew = new VoiceState();
        crew.crewRoom = true;
        assertTrue(crew.showsRoles());
    }
}
