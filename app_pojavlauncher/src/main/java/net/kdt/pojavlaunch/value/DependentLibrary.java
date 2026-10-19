package net.kdt.pojavlaunch.value;

import androidx.annotation.Keep;
import net.kdt.pojavlaunch.JMinecraftVersionList.Arguments.ArgValue.ArgRules;

@Keep
public class DependentLibrary {
    public ArgRules[] rules;
    public String name;
    public LibraryDownloads downloads;
    public String url;
    /**
     * Projects fork (M16): Fabric's and Quilt's Maven-style entries (no {@code downloads}) name the
     * file's SHA-1 and size beside its {@code url}; 0/null when an entry has none.
     */
    public String sha1;
    public long size;

    @Keep
	public static class LibraryDownloads {
		public final MinecraftLibraryArtifact artifact;
		public LibraryDownloads(MinecraftLibraryArtifact artifact) {
			this.artifact = artifact;
		}
	}
}

