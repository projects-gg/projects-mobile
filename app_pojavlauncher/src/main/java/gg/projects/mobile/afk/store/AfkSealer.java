package gg.projects.mobile.afk.store;

import java.util.List;

/**
 * Seals an account's login commands for {@code accounts.json} (they carry passwords: never stored in clear, never
 * logged, never sent to any service). The app's is {@link KeystoreSealer}; the JVM tests use a stand-in.
 */
public interface AfkSealer {
    /** The commands sealed ("" for none). */
    String seal(List<String> commands) throws Exception;

    /** The commands of a sealed text ("" gives none); throws when they cannot be read (the key is gone). */
    List<String> unseal(String sealed) throws Exception;

    /** "Verilerimi sil": the key goes, so nothing sealed with it can be read again. */
    void forget();
}
