package gg.projects.mobile.voice.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.ui.AvatarView;
import gg.projects.mobile.voice.VoiceState;

/**
 * A face in voice chat with its speaking ring: a thin jade rounded square in the gap around the face, the face's own
 * shape with the face in its middle (it never grows outside the face's box, so neighbouring rows never merge), fading in
 * and out on opacity alone (the desktop's ring, 120 ms in, 220 ms out). Nothing animates while nobody speaks.
 */
public final class VoiceFace extends FrameLayout {
    private final AvatarView mAvatar;
    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF mTile = new android.graphics.RectF();
    private final float mStroke;
    /** The gap between the face and its ring. */
    private final float mGap;
    private float mRingAlpha;
    private boolean mSpeaking;
    @Nullable private android.animation.ValueAnimator mFade;

    public VoiceFace(Context context, int sizeDp) {
        super(context);
        float dp = context.getResources().getDisplayMetrics().density;
        mStroke = Math.max(2f, 2 * dp);
        mGap = 2 * dp;
        int inset = Math.round(4 * dp);
        mAvatar = new AvatarView(context);
        mAvatar.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_800));
        // No dot here: the face sits in the middle of its box, the ring round it (the dot's room put it off centre).
        mAvatar.setCentered(true);
        LayoutParams lp = new LayoutParams(Math.round(sizeDp * dp) - 2 * inset, Math.round(sizeDp * dp) - 2 * inset, Gravity.CENTER);
        addView(mAvatar, lp);
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setStrokeWidth(mStroke);
        mRing.setColor(ContextCompat.getColor(context, R.color.projects_jade_400));
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public AvatarView avatar() {
        return mAvatar;
    }

    /** The face of {@code id}: the friends list's profile, else the room's card (a friend's friend). */
    public void setPerson(Context context, String id, @Nullable VoiceState.Person person) {
        mAvatar.setProfile(profileOf(context, id, person));
    }

    public void setSpeaking(boolean speaking) {
        if (mSpeaking == speaking) return;
        mSpeaking = speaking;
        if (mFade != null) mFade.cancel();
        float to = speaking ? 1f : 0f;
        if (!gg.projects.mobile.mods.Motion.enabled(getContext())) {
            mRingAlpha = to;
            invalidate();
            return;
        }
        mFade = android.animation.ValueAnimator.ofFloat(mRingAlpha, to);
        mFade.setDuration(speaking ? 120 : 220);
        mFade.addUpdateListener(a -> {
            mRingAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        mFade.start();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (mRingAlpha <= 0f) return;
        mRing.setAlpha(Math.round(255 * mRingAlpha));
        // Round the face itself: its rounded square, a gap wider, the stroke's middle on that line.
        mAvatar.tileRect(mTile);
        if (mTile.isEmpty()) return;
        float faceRadius = mAvatar.tileRadius(mTile);
        mTile.offset(mAvatar.getLeft(), mAvatar.getTop());
        float out = mGap + mStroke / 2f;
        mTile.inset(-out, -out);
        canvas.drawRoundRect(mTile, faceRadius + out, faceRadius + out, mRing);
    }

    /** Someone's profile for a face: the friends feature's, else built from the room's card. */
    @Nullable
    public static SocialModel.Profile profileOf(Context context, String id, @Nullable VoiceState.Person person) {
        SocialModel.Profile profile = SocialService.exists() ? SocialService.get(context).state().profile(id) : null;
        if (profile != null || person == null || person.cardName == null) return profile;
        JsonObject card = new JsonObject();
        card.addProperty("id", id);
        card.addProperty("username", person.cardName.toLowerCase(java.util.Locale.ROOT));
        card.addProperty("displayName", person.cardName);
        if (person.cardAvatar != null) card.addProperty("avatar", person.cardAvatar);
        if (person.cardAvatarHash != null) card.addProperty("avatarHash", person.cardAvatarHash);
        if (person.cardDiscordId != null) card.addProperty("discordId", person.cardDiscordId);
        return SocialModel.Profile.read(card);
    }
}
