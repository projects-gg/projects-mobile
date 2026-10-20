package gg.projects.mobile.social;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * {@code projects://} links (M19): {@code projects://auth/done?c=<code>}, where the browser comes back
 * after the Discord sign-in, and {@code projects://friend/<code>}, which the QR landing page and
 * shared friend links open. No screen of its own: it hands the link on and leaves. A sign-in link
 * counts only while this app waits for a sign-in it started ({@link SignInFlow}).
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
            if (!service.onAuthLink(SocialCodes.authCodeFromDeepLink(link))) return;
            SocialRoutes.set(SocialRoutes.FRIENDS, null);
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
        }
    }
}
