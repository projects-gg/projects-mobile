package gg.projects.mobile.ui;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.mods.Motion;

/**
 * OYNA and "Projects'e katıl" (M14; the owner: make them feel satisfying). Both press like every
 * button ({@link Press}). When the start goes ahead the press confirms: a sheen crosses the gold
 * face and the phone plays its confirm effect; when it is refused (something still running, no
 * account yet) it rejects: a short shake and the reject effect, next to the toast that says why.
 */
public final class PlayButtons {
    private PlayButtons() {}

    /** OYNA: the socketed gold face, Tektur with the desktop's letter spacing. */
    public static void decoratePlay(TextView button) {
        button.setBackground(new PlayButtonDrawable(button.getContext(), true));
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, button.getResources().getDimension(R.dimen.projects_text_play));
        button.setLetterSpacing(0.08f);
        Press.attach(button, Press.Style.BUTTON);
    }

    /** "Projects'e katıl": the same gold face without the socket (OYNA stays the loudest). */
    public static void decorateJoin(TextView button) {
        button.setBackground(new PlayButtonDrawable(button.getContext(), false));
        Press.attach(button, Press.Style.BUTTON);
    }

    /** The accepted press: the confirm effect and the sheen. Returns true when the sheen plays. */
    public static boolean confirm(@Nullable View button) {
        if (button == null) return false;
        Haptics.confirm(button);
        if (button.getBackground() instanceof PlayButtonDrawable && button.isShown()) {
            return ((PlayButtonDrawable) button.getBackground()).sheen(button.getContext());
        }
        return false;
    }

    public static void reject(@Nullable View button) {
        if (button == null) return;
        Haptics.reject(button);
        if (!button.isShown() || !Motion.enabled(button.getContext())) return;
        float d = 4 * button.getResources().getDisplayMetrics().density;
        ObjectAnimator shake = ObjectAnimator.ofFloat(button, View.TRANSLATION_X, 0f, -d, d, -0.6f * d, 0.6f * d, -0.25f * d, 0f);
        shake.setDuration(340);
        shake.start();
    }

    /** OYNA of the main menu when it is on screen, else the window (haptics still play). */
    public static View playButtonOf(Activity activity) {
        View play = activity.findViewById(R.id.play_button);
        return play != null && play.isShown() ? play : activity.getWindow().getDecorView();
    }
}
