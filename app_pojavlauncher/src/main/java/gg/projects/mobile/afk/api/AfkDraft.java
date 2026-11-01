package gg.projects.mobile.afk.api;

import java.util.ArrayList;
import java.util.List;

/**
 * An account as the add/edit screen fills it in. {@link PhoneAfk#check} lists its problems with the same rules
 * {@link PhoneAfk#add} and {@link PhoneAfk#update} apply; both trim and normalise what they keep. Login commands are
 * secrets: never log a draft (its {@link #toString()} leaves them out).
 */
public final class AfkDraft {
    /** The offline name as typed (spaces around it are dropped). */
    public String name = "";
    /** {@code host}, {@code host:port} or {@code [ipv6]:port} as typed. */
    public String address = "";
    /** Lines typed after joining (a {@code /login} with the password); blank lines are dropped. */
    public List<String> loginCommands = new ArrayList<>();
    public AfkLoginMode loginMode = AfkLoginMode.EVERY_JOIN;
    public boolean sneak = true;
    public boolean mobileData = false;
    /**
     * Set by {@link PhoneAfk#edit} when the saved login commands could not be read (the phone's key was lost): the
     * draft has none; the screen may say they need to be written again. Ignored by add and update.
     */
    public boolean loginCommandsUnreadable = false;

    public AfkDraft() {}

    /** A draft of {@code account} with these commands (the edit screen's start). */
    public static AfkDraft of(AfkAccount account, List<String> loginCommands) {
        AfkDraft d = new AfkDraft();
        d.name = account.name;
        d.address = account.address();
        d.loginCommands = new ArrayList<>(loginCommands == null ? new ArrayList<String>() : loginCommands);
        d.loginMode = account.loginMode;
        d.sneak = account.sneak;
        d.mobileData = account.mobileData;
        return d;
    }

    @Override
    public String toString() {
        return "AfkDraft{" + name + " @ " + address + ", " + loginCommands.size() + " login commands}";
    }
}
