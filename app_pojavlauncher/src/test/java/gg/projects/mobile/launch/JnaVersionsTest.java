package gg.projects.mobile.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** M8: the JNA a game version runs on the phone, 16 KB page ready from 5.14 on (JnaVersions). */
public class JnaVersionsTest {
    @Test
    public void fiveFourteenToSixteenRunAsFiveSeventeen() {
        assertEquals("5.17.0", JnaVersions.replacement("5.14.0"));
        assertEquals("5.17.0", JnaVersions.replacement("5.15.0"));
        assertEquals("5.17.0", JnaVersions.replacement("5.16.0"));
        assertNull("already ready", JnaVersions.replacement("5.17.0"));
        assertNull(JnaVersions.replacement("5.18.1"));
        assertNull("the next major is kept", JnaVersions.replacement("6.0.0"));
    }

    @Test
    public void upstreamsRuleStaysForTheOldOnes() {
        assertEquals("5.13.0", JnaVersions.replacement("4.4.0"));
        assertEquals("5.13.0", JnaVersions.replacement("3.4.0"));
        assertEquals("5.13.0", JnaVersions.replacement("5.12.1"));
        assertNull("5.13 stays itself", JnaVersions.replacement("5.13.0"));
    }

    @Test
    public void anOddVersionIsKeptInsteadOfCrashingTheStart() {
        assertNull(JnaVersions.replacement("five"));
        assertNull(JnaVersions.replacement("5"));
        assertNull(JnaVersions.replacement(""));
        assertNull(JnaVersions.replacement(null));
        assertEquals("a suffix after the minor", "5.17.0", JnaVersions.replacement("5.15-jpms"));
    }

    @Test
    public void theReplacementsJarIsTheOneAtMavenCentral() {
        assertEquals("net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar", JnaVersions.path("5.17.0"));
        assertEquals("https://repo1.maven.org/maven2/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar", JnaVersions.url("5.17.0"));
        assertEquals("33d12735bef894440780fce64f9758d420c7bae2", JnaVersions.sha1("5.17.0"));
        assertEquals("upstream's", "1200e7ebeedbe0d10062093f32925a912020e747", JnaVersions.sha1("5.13.0"));
        assertEquals("", JnaVersions.sha1("5.15.0"));
    }
}
