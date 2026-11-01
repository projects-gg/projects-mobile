package gg.projects.mobile.social;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

/**
 * SOC-ID: the ways into the friends service it offers now ({@code GET /v1/auth/providers}, no session):
 * {@code {"providers":[{"id":"discord","signIn":true,"signUp":true},{"id":"microsoft","signIn":…,"signUp":…}]}}.
 * Discord is always there; Microsoft comes switched off and shows only while its {@code signIn} is on. A service from
 * before SOC-ID has no such route (404): Discord only. {@code signUp} says whether a Microsoft account without a
 * Projects account opens one (otherwise it must be linked first: "Önce Discord ile gir, sonra Ayarlar'dan bağla").
 * Pure Java.
 */
public final class SignInOptions {
    public static final SignInOptions DISCORD_ONLY = new SignInOptions(false, false);

    public final boolean microsoftSignIn;
    public final boolean microsoftSignUp;

    public SignInOptions(boolean microsoftSignIn, boolean microsoftSignUp) {
        this.microsoftSignIn = microsoftSignIn;
        // A new account is opened only through a sign-in.
        this.microsoftSignUp = microsoftSignIn && microsoftSignUp;
    }

    /** The service's answer; anything unreadable is Discord only. */
    public static SignInOptions read(@Nullable JsonObject answer) {
        boolean signIn = false;
        boolean signUp = false;
        for (JsonObject way : Json.objects(answer, "providers")) {
            if (SignInFlow.MICROSOFT.equals(Json.str(way, "id"))) {
                signIn = Json.bool(way, "signIn", false);
                signUp = Json.bool(way, "signUp", false);
            }
        }
        return new SignInOptions(signIn, signUp);
    }

    /** Whether the service offers {@code provider} to sign in with (and to link). Discord always. */
    public boolean offers(@Nullable String provider) {
        if (SignInFlow.DISCORD.equals(provider)) return true;
        return SignInFlow.MICROSOFT.equals(provider) && microsoftSignIn;
    }

    /**
     * Every way opens an account ("Discord ya da Microsoft hesabınla giriş yaparsın"): the buttons are equals and the
     * friends' entry is no Discord button any more. Otherwise Discord opens accounts and Microsoft only signs in.
     */
    public boolean equalWays() {
        return microsoftSignUp;
    }

    /** As kept between starts ({@link #decode}). */
    public String encode() {
        return (microsoftSignIn ? "1" : "0") + (microsoftSignUp ? "1" : "0");
    }

    public static SignInOptions decode(@Nullable String kept) {
        if (kept == null || kept.length() != 2) return DISCORD_ONLY;
        return new SignInOptions(kept.charAt(0) == '1', kept.charAt(1) == '1');
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SignInOptions)) return false;
        SignInOptions o = (SignInOptions) other;
        return o.microsoftSignIn == microsoftSignIn && o.microsoftSignUp == microsoftSignUp;
    }

    @Override
    public int hashCode() {
        return (microsoftSignIn ? 2 : 0) + (microsoftSignUp ? 1 : 0);
    }

    @Override
    public String toString() {
        return "SignInOptions(" + encode() + ")";
    }
}
