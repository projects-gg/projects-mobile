package gg.projects.mobile.social;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * {@code projects://} links (M19): {@code projects://auth/done?c=<code>}, where the browser comes back
 * after the sign-in (SOC-ID: Discord's or Microsoft's, or a link or removal of a way in, which comes back to
 * the friends' settings), and {@code projects://friend/<code>}, which the QR landing page and
 * shared friend links open; M21: {@code projects://crew/<link>}, which a crew invite link's page
 * opens. No screen of its own: it hands the link on and leaves. A sign-in link counts only while this
 * app waits for a sign-in it started ({@link SignInFlow}).
 */
public final class SocialLinkActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handle(getIntent());
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
        finish();
    }

    private void handle(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        if (data == null || !"projects".equalsIgnoreCase(data.getScheme())) return;
        String link = data.toString();
        SocialService service = SocialService.get(this);
        if (!service.enabled()) return;
        if ("auth".equalsIgnoreCase(data.getHost())) {
            // Only the app's own waiting sign-in brings the launcher up (any web page can open this link).
            // SOC-ID: a link or a removal of the signed-in account comes back to "Bağlı hesaplar".
            boolean identity = service.signedIn() && service.identityStatus() == SocialService.IdentityStatus.WAITING;
            if (!service.onAuthLink(SocialCodes.authCodeFromDeepLink(link))) return;
            SocialRoutes.set(identity ? SocialRoutes.SETTINGS : SocialRoutes.FRIENDS, null);
            SocialRoutes.bringUp(this);
        } else if ("friend".equalsIgnoreCase(data.getHost())) {
            String code = SocialCodes.friendCodeFromDeepLink(link);
            if (code == null) return;
            if (service.signedIn()) {
                SocialRoutes.set(SocialRoutes.ADD, code);
            } else {
                // Sign in first, on the friends screen; the code waits and opens "Arkadaş ekle" after.
                service.setPendingFriendCode(code);
                SocialRoutes.set(SocialRoutes.FRIENDS, null);
            }
            SocialRoutes.bringUp(this);
        } else if ("crew".equalsIgnoreCase(data.getHost()) || "ekip".equalsIgnoreCase(data.getHost())) {
            // M21 (TEAM5): a crew's invite link from its page: the crew shows with "Katıl" (or "Katılma isteği
            // gönder"); nothing is sent until the player confirms.
            String crewLink = SocialCodes.inviteLinkFromDeepLink(link);
            if (crewLink == null) return;
            if (service.signedIn()) {
                SocialRoutes.set(SocialRoutes.CREW_LINK, crewLink);
            } else {
                service.setPendingCrewLink(crewLink);
                SocialRoutes.set(SocialRoutes.FRIENDS, null);
            }
            SocialRoutes.bringUp(this);
        }
    }
}
