package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.SocialModel.Friend;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.ui.CardMotion;
import gg.projects.mobile.ui.Press;

/**
 * FIX2 (the owner: "discord bağlama tuşunu daha üstte uygun bir yere alırsak iyi olur, zor gözüküyor"):
 * the friends entry in the top bar, where the desktop has its friends pill. Before sign-in it is
 * Discord's own button, Blurple with its logo and "Bağlan", seen first on the home screen; signed in it
 * is a quiet pill with the faces of friends who are on and how many, and a gold dot while something
 * waits for the player (a request, an invite, a call, an unread message). A tap opens Friends (the
 * sign-in page with what is shared first). Shown on the home screen only; hidden while Friends are off.
 */
public final class FriendsPill extends FrameLayout implements SocialService.Listener {
    private final LinearLayout mPill;
    private final LinearLayout mFaces;
    private final ImageView mIcon;
    private final TextView mLabel;
    private final TextView mCount;
    private final View mDot;
    private boolean mHere = true;
    private String mShown = "";

    public FriendsPill(Context context) {
        this(context, null);
    }

    public FriendsPill(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float dp = getResources().getDisplayMetrics().density;
        mPill = new LinearLayout(context);
        mPill.setOrientation(LinearLayout.HORIZONTAL);
        mPill.setGravity(Gravity.CENTER_VERTICAL);
        mPill.setMinimumHeight(Math.round(48 * dp));
        mPill.setPaddingRelative(Math.round(12 * dp), 0, Math.round(14 * dp), 0);
        mPill.setClickable(true);
        mPill.setFocusable(true);
        addView(mPill, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(48 * dp), Gravity.CENTER_VERTICAL));

        mFaces = new LinearLayout(context);
        mFaces.setOrientation(LinearLayout.HORIZONTAL);
        mFaces.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        mPill.addView(mFaces, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mIcon = new ImageView(context);
        mIcon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        mPill.addView(mIcon, new LinearLayout.LayoutParams(Math.round(20 * dp), Math.round(20 * dp)));

        mLabel = text(context, 14);
        try {
            mLabel.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            mLabel.setTypeface(Typeface.DEFAULT_BOLD);
        }
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.setMarginStart(Math.round(8 * dp));
        mPill.addView(mLabel, labelLp);

        mCount = text(context, 14);
        try {
            mCount.setTypeface(ResourcesCompat.getFont(context, R.font.tektur_semibold));
        } catch (RuntimeException ignored) {
            mCount.setTypeface(Typeface.DEFAULT_BOLD);
        }
        mCount.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        countLp.setMarginStart(Math.round(7 * dp));
        mPill.addView(mCount, countLp);

        mDot = new View(context);
        mDot.setBackgroundResource(R.drawable.projects_news_dot);
        mDot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams dotLp = new LayoutParams(Math.round(9 * dp), Math.round(9 * dp), Gravity.TOP | Gravity.END);
        dotLp.topMargin = Math.round(7 * dp);
        dotLp.setMarginEnd(Math.round(1 * dp));
        addView(mDot, dotLp);

        Press.attach(mPill, Press.Style.BUTTON);
        mPill.setOnClickListener(v -> open());
        setVisibility(GONE);
    }

    private static TextView text(Context context, int sp) {
        TextView t = new TextView(context);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setMaxLines(1);
        t.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }

    /** The launcher's screen changed: the pill shows on the home screen only. */
    public void setShownHere(boolean here) {
        if (here == mHere) return;
        mHere = here;
        render();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        SocialService.get(getContext()).addListener(this);
        render();
    }

    @Override
    protected void onDetachedFromWindow() {
        SocialService.get(getContext()).removeListener(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void onSocialChanged() {
        render();
    }

    private void open() {
        Context context = getContext();
        while (context instanceof android.content.ContextWrapper && !(context instanceof FragmentActivity)) {
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        if (!(context instanceof FragmentActivity)) return;
        FragmentActivity activity = (FragmentActivity) context;
        if (activity.getSupportFragmentManager().isStateSaved()) return;
        Tools.swapFragment(activity, SocialFragment.class, SocialFragment.TAG, null);
    }

    private void render() {
        Context context = getContext();
        SocialService service = SocialService.get(context);
        boolean visible = mHere && service.enabled();
        if (!visible) {
            setVisibility(GONE);
            mShown = "";
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        boolean signedIn = service.signedIn();
        String shown;
        if (!signedIn) {
            shown = "out";
        } else {
            SocialState state = service.state();
            List<Profile> faces = onlineFaces(service);
            int online = state.onlineCount();
            boolean waiting = waiting(service);
            StringBuilder key = new StringBuilder("in|").append(online).append('|').append(waiting).append('|');
            for (Profile p : faces) key.append(p.id).append(p.faceKey()).append(',');
            shown = key.toString();
        }
        if (shown.equals(mShown) && getVisibility() == VISIBLE) return;
        boolean kindChanged = !mShown.isEmpty() && !mShown.startsWith(shown.substring(0, Math.min(3, shown.length())));
        if (kindChanged && Motion.enabled(context)) CardMotion.crossfade(mPill);
        mShown = shown;
        setVisibility(VISIBLE);
        if (!signedIn) {
            // Discord's own button: its Blurple, its white logo, "Bağlan".
            background(R.drawable.projects_friends_pill_discord);
            mFaces.setVisibility(GONE);
            mFaces.removeAllViews();
            mIcon.setVisibility(VISIBLE);
            mIcon.setImageResource(R.drawable.projects_ic_discord_glyph);
            mIcon.setImageTintList(null);
            mLabel.setVisibility(VISIBLE);
            mLabel.setText(R.string.projects_social_pill_connect);
            mLabel.setTextColor(0xFFFFFFFF);
            mCount.setVisibility(GONE);
            mDot.setVisibility(GONE);
            mPill.setContentDescription(context.getString(R.string.projects_social_pill_connect_label));
            return;
        }
        SocialState state = service.state();
        List<Profile> faces = onlineFaces(service);
        int online = state.onlineCount();
        boolean waiting = waiting(service);
        background(R.drawable.projects_friends_pill);
        mLabel.setVisibility(GONE);
        mFaces.removeAllViews();
        mFaces.setVisibility(faces.isEmpty() ? GONE : VISIBLE);
        int size = Math.round(22 * dp);
        for (int i = 0; i < faces.size(); i++) {
            AvatarView avatar = new AvatarView(context);
            avatar.setProfile(faces.get(i));
            avatar.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_800));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            if (i > 0) lp.setMarginStart(-Math.round(7 * dp));
            mFaces.addView(avatar, lp);
        }
        mIcon.setVisibility(faces.isEmpty() ? VISIBLE : GONE);
        mIcon.setImageResource(R.drawable.projects_ic_users);
        mIcon.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_ash_300));
        mCount.setVisibility(online > 0 ? VISIBLE : GONE);
        mCount.setText(String.valueOf(online));
        mCount.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        mDot.setVisibility(waiting ? VISIBLE : GONE);
        String label = context.getResources().getQuantityString(R.plurals.projects_social_pill_online, online, online);
        if (waiting) label += ". " + context.getString(R.string.projects_social_pending);
        mPill.setContentDescription(label);
    }

    /** A background with insets resets the padding to them: the pill's own padding goes back on. */
    private void background(int drawable) {
        float dp = getResources().getDisplayMetrics().density;
        mPill.setBackgroundResource(drawable);
        mPill.setPaddingRelative(Math.round(12 * dp), 0, Math.round(14 * dp), 0);
    }

    /** Friends who are on, those in game first (favourites first within), at most three. */
    private static List<Profile> onlineFaces(SocialService service) {
        Set<String> favourites = service.prefs().favourites();
        SocialState.Sections sections = service.state().sections(favourites);
        List<Profile> out = new ArrayList<>();
        for (Friend f : sections.playing) if (out.size() < 3) out.add(f.profile);
        for (Friend f : sections.online) if (out.size() < 3) out.add(f.profile);
        return out;
    }

    /** Something waits for the player: a request, an invite, a crewmate's call, a join request, an unread message. */
    private static boolean waiting(SocialService service) {
        SocialState state = service.state();
        long now = SocialService.nowSeconds();
        if (!state.incoming().isEmpty() || !state.invites(now).isEmpty()) return true;
        if (service.gate().teams && (!state.teamInvites().isEmpty() || state.unansweredCall(now) != null || state.requestCount(now) > 0)) {
            return true;
        }
        return service.chat().unreadTotal() > 0;
    }
}
