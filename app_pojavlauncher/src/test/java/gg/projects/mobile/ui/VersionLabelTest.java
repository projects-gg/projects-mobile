package gg.projects.mobile.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** M14: profile version ids as players read them (VersionLabel). */
public class VersionLabelTest {
    @Test
    public void loadersNameTheGameVersionAndTheLoader() {
        assertEquals("Minecraft 26.1.2, Fabric", VersionLabel.describe("fabric-loader-0.19.5-26.1.2"));
        assertEquals("Minecraft 1.21-pre1, Fabric", VersionLabel.describe("fabric-loader-0.16.0-1.21-pre1"));
        assertEquals("Minecraft 24w14a, Fabric", VersionLabel.describe("fabric-loader-0.15.10-24w14a"));
        assertEquals("Minecraft 1.21.1, Quilt", VersionLabel.describe("quilt-loader-0.26.4-1.21.1"));
        assertEquals("Minecraft 1.20.1, Forge", VersionLabel.describe("1.20.1-forge-47.2.0"));
        assertEquals("Minecraft 1.7.10, Forge", VersionLabel.describe("1.7.10-Forge10.13.4.1614-1.7.10"));
        assertEquals("Minecraft 1.20.1, OptiFine", VersionLabel.describe("1.20.1-OptiFine_HD_U_I6"));
    }

    @Test
    public void neoForgeNumbersMapToTheGameUntilTheYearVersions() {
        assertEquals("Minecraft 1.21.1, NeoForge", VersionLabel.describe("neoforge-21.1.77"));
        assertEquals("Minecraft 1.21, NeoForge", VersionLabel.describe("neoforge-21.0.167"));
        assertEquals("Minecraft 1.20.4, NeoForge", VersionLabel.describe("neoforge-20.4.237"));
        assertEquals("NeoForge 26.1.0.12", VersionLabel.describe("neoforge-26.1.0.12"));
    }

    @Test
    public void plainVersionsAndUnknownIds() {
        assertEquals("Minecraft 26.1.2", VersionLabel.describe("26.1.2"));
        assertEquals("Minecraft 1.21.11", VersionLabel.describe("1.21.11"));
        assertEquals("Minecraft 25w14a", VersionLabel.describe("25w14a"));
        assertEquals("Minecraft 1.21.11-rc1", VersionLabel.describe("1.21.11-rc1"));
        assertEquals("ids it does not know stay as they are", "lwjgl3ify-3.0.0-1.7.10", VersionLabel.describe("lwjgl3ify-3.0.0-1.7.10"));
        assertEquals("b1.7.3", VersionLabel.describe("b1.7.3"));
        assertEquals("", VersionLabel.describe(null));
        assertEquals("", VersionLabel.describe("  "));
    }

    @Test
    public void onlyTheBundledDefaultProfileIsRenamed() {
        assertTrue(VersionLabel.isDefaultName("(Default)"));
        assertTrue(VersionLabel.isDefaultName("Default"));
        assertFalse(VersionLabel.isDefaultName("Projects"));
        assertFalse(VersionLabel.isDefaultName("My Default"));
        assertFalse(VersionLabel.isDefaultName(null));
    }
}
