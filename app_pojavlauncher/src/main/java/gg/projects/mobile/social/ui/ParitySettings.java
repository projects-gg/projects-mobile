package gg.projects.mobile.social.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import gg.projects.mobile.social.Json;
import gg.projects.mobile.social.OwnPhoto;
import gg.projects.mobile.social.SessionDays;
import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.AppDates;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * MOB-PARITY: the two blocks of the desktop's friends settings the phone lacked, in its order: "Profil fotoğrafı" (the
 * picture friends see: the Discord picture, the Minecraft head, the 24 avatars; chosen at once) and "Oturumlar" (where
 * the account is signed in, "Çıkış yap" for one, "Diğer cihazlardan çıkış yap"; read when the settings open, kept
 * nowhere). The settings screen owns one instance.
 */
final class ParitySettings {
    interface Redraw {
        void redraw();
    }

    private final Redraw mRedraw;
    /** {@code sessions} as read; null until read or after a failure. */
    @Nullable
    private List<JsonObject> mSessions;
    private boolean mSessionsFailed;
    private boolean mSessionsBusy;
    @Nullable
    private String mPhotoBusy;

    ParitySettings(Redraw redraw) {
        mRedraw = redraw;
    }

    /** What the blocks show: a change draws the settings again. */
    String key(@Nullable Me me) {
        StringBuilder b = new StringBuilder("p|");
        if (me != null) b.append(me.profile.avatar).append('|').append(me.discordPhoto).append('|');
        b.append(mPhotoBusy).append('|').append(mSessionsFailed).append(mSessionsBusy).append('|');
        if (mSessions != null) {
            for (JsonObject s : mSessions) b.append(Json.str(s, "id")).append(Json.str(s, "lastUsedOn")).append(',');
        }
        return b.toString();
    }

    // ---- "Profil fotoğrafı" ----

    void addPhoto(FragmentActivity activity, LinearLayout list, Me me) {
        list.addView(header(activity, R.string.projects_parity_photo_title));
        WrapLayout grid = new WrapLayout(activity);
        int dp4 = dp(activity, 4);
        grid.setGap(dp4);
        grid.setPadding(dp(activity, 4), dp4, dp(activity, 4), dp4);
        List<OwnPhoto.Choice> choices = OwnPhoto.choices(me.discordPhoto, minecraftTexture(activity));
        String chosen = OwnPhoto.chosen(me.profile.avatar, choices);
        for (OwnPhoto.Choice choice : choices) grid.addView(tile(activity, me, choice, choice.value.equals(chosen)));
        list.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private View tile(final FragmentActivity activity, Me me, final OwnPhoto.Choice choice, boolean selected) {
        FrameLayout tile = new FrameLayout(activity);
        int size = dp(activity, 48);
        tile.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setSelected(selected);
        tile.setContentDescription(label(activity, choice));
        if (selected) {
            // The chosen one: the bone ring (the crew colour swatch's), never gold.
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(activity, 12));
            ring.setStroke(dp(activity, 2), ContextCompat.getColor(activity, R.color.projects_bone_100));
            tile.setBackground(ring);
        } else {
            tile.setBackgroundResource(R.drawable.projects_row_ripple);
        }
        AvatarView face = new AvatarView(activity);
        face.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // The face keeps room for a presence dot on its right and foot: the padding centres it in the tile.
        int pad = dp(activity, 4);
        face.setPadding(pad, pad, 0, 0);
        face.setProfile(OwnPhoto.preview(me.profile, choice));
        int inner = dp(activity, 42);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER);
        tile.addView(face, lp);
        boolean busy = choice.value.equals(mPhotoBusy);
        tile.setAlpha(mPhotoBusy != null && !busy ? 0.6f : 1f);
        Press.attach(tile, Press.Style.ICON);
        tile.setOnClickListener(v -> {
            if (mPhotoBusy != null || v.isSelected()) return;
            mPhotoBusy = choice.value;
            Haptics.perform(v, gg.projects.mobile.ui.HapticMap.Kind.TOGGLE_ON);
            mRedraw.redraw();
            SocialService.get(activity).setAvatar(choice.value, new SocialService.Result<JsonObject>() {
                @Override
                public void ok(JsonObject value) {
                    mPhotoBusy = null;
                    mRedraw.redraw();
                    SocialUi.toast(activity, activity.getString(R.string.projects_parity_photo_changed));
                }

                @Override
                public void failed(SocialProblem problem) {
                    mPhotoBusy = null;
                    mRedraw.redraw();
                    SocialUi.toastProblem(activity, problem, null);
                }
            });
        });
        return tile;
    }

    private static String label(Context context, OwnPhoto.Choice choice) {
        if (choice.kind == OwnPhoto.Kind.DISCORD) return context.getString(R.string.projects_parity_photo_discord);
        if (choice.kind == OwnPhoto.Kind.MINECRAFT) return context.getString(R.string.projects_parity_photo_minecraft);
        int id = context.getResources().getIdentifier("projects_parity_avatar_" + choice.slug.replace('-', '_'), "string",
                context.getPackageName());
        return id == 0 ? choice.slug : context.getString(id);
    }

    /**
     * The Minecraft head's skin: the chosen account's when it is a Microsoft account with a known skin, else the first
     * other Microsoft account's (the desktop's order). Offline accounts have no skin of their own on Mojang.
     */
    @Nullable
    private static String minecraftTexture(Context context) {
        try {
            net.kdt.pojavlaunch.value.MinecraftAccount current = net.kdt.pojavlaunch.PojavProfile.getCurrentProfileContent(context, null);
            if (current != null && current.isMicrosoft && current.skinTexture != null) return current.skinTexture;
            File[] files = new File(net.kdt.pojavlaunch.Tools.DIR_ACCOUNT_NEW).listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) return null;
            List<String> names = new ArrayList<>();
            for (File f : files) names.add(f.getName().substring(0, f.getName().length() - 5));
            Collections.sort(names);
            for (String name : names) {
                net.kdt.pojavlaunch.value.MinecraftAccount account = net.kdt.pojavlaunch.value.MinecraftAccount.load(name);
                if (account != null && account.isMicrosoft && account.skinTexture != null) return account.skinTexture;
            }
        } catch (RuntimeException ignored) {
            // No accounts readable: no head offered.
        }
        return null;
    }

    // ---- "Oturumlar" ----

    /** Reads the sessions once per opening of the settings (and after a sign-out from one). */
    void readSessions(Context context) {
        if (mSessionsBusy) return;
        mSessionsBusy = true;
        SocialService.get(context).readSessions(new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
                mSessionsBusy = false;
                mSessionsFailed = false;
                mSessions = Json.objects(value, "sessions");
                mRedraw.redraw();
            }

            @Override
            public void failed(SocialProblem problem) {
                mSessionsBusy = false;
                mSessionsFailed = true;
                mRedraw.redraw();
            }
        });
    }

    void addSessions(final FragmentActivity activity, LinearLayout list) {
        list.addView(header(activity, R.string.projects_parity_sessions_title));
        if (mSessions == null) {
            if (mSessionsFailed) {
                list.addView(note(activity, activity.getString(R.string.projects_parity_sessions_failed)));
                list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_arrows_clockwise, activity.getString(R.string.projects_retry),
                        R.color.projects_bone_100, v -> readSessions(activity)));
            }
            // While reading: nothing yet (the block fills in a moment; no spinner for a short read).
            return;
        }
        boolean others = false;
        for (JsonObject session : mSessions) {
            boolean current = Json.bool(session, "current", false);
            others |= !current;
            list.addView(sessionRow(activity, session, current));
        }
        if (others) {
            list.addView(CrewViews.actionRow(activity, R.drawable.projects_ic_sign_out,
                    activity.getString(R.string.projects_parity_session_revoke_others), R.color.projects_flare_400, v -> revokeOthers(activity, v)));
        } else {
            list.addView(note(activity, activity.getString(R.string.projects_parity_session_none_other)));
        }
    }

    private View sessionRow(final FragmentActivity activity, final JsonObject session, boolean current) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(activity, 60));
        row.setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 4), dp(activity, 6));
        LinearLayout words = new LinearLayout(activity);
        words.setOrientation(LinearLayout.VERTICAL);
        String device = Json.str(session, "device");
        if (device == null || device.trim().isEmpty()) device = activity.getString(R.string.projects_parity_session_unknown_device);
        TextView name = new TextView(activity);
        name.setText(device);
        name.setTextSize(15);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setTextColor(ContextCompat.getColor(activity, R.color.projects_bone_100));
        try {
            name.setTypeface(ResourcesCompat.getFont(activity, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        words.addView(name);
        TextView line = new TextView(activity);
        line.setText(sessionLine(activity, session, current));
        line.setTextSize(13);
        line.setTextColor(ContextCompat.getColor(activity, R.color.projects_ash_400));
        words.addView(line);
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!current) {
            final Button out = new Button(activity, null, android.R.attr.borderlessButtonStyle);
            out.setText(R.string.projects_parity_session_revoke);
            out.setContentDescription(activity.getString(R.string.projects_parity_session_revoke_label, device));
            out.setAllCaps(false);
            out.setTextColor(ContextCompat.getColor(activity, R.color.projects_bone_100));
            out.setBackground(ChamferDrawable.outlined(activity));
            Press.attach(out, Press.Style.BUTTON);
            final String id = Json.str(session, "id");
            out.setOnClickListener(v -> {
                if (id == null) return;
                out.setEnabled(false);
                SocialService.get(activity).revokeSession(id, new SocialService.Result<Void>() {
                    @Override
                    public void ok(Void value) {
                        Haptics.confirm(out);
                        if (mSessions != null) {
                            List<JsonObject> left = new ArrayList<>();
                            for (JsonObject s : mSessions) if (!id.equals(Json.str(s, "id"))) left.add(s);
                            mSessions = left;
                        }
                        mRedraw.redraw();
                    }

                    @Override
                    public void failed(SocialProblem problem) {
                        out.setEnabled(true);
                        // Gone already (signed out there meanwhile): the list is read again.
                        if (problem.status == 404) readSessions(activity);
                        else SocialUi.toastProblem(activity, problem, out);
                    }
                });
            });
            row.addView(out);
        }
        return row;
    }

    /** "Bu cihaz", else "Son kullanım dün"; ", Microsoft ile girildi" after a Microsoft sign-in. */
    private static String sessionLine(Context context, JsonObject session, boolean current) {
        String text = null;
        if (current) {
            text = context.getString(R.string.projects_parity_session_this_device);
        } else {
            long now = System.currentTimeMillis();
            SessionDays.When when = SessionDays.lastUsed(Json.str(session, "lastUsedOn"), now);
            if (when != null) {
                String day = when.kind == SessionDays.Kind.TODAY ? context.getString(R.string.projects_parity_session_today)
                        : when.kind == SessionDays.Kind.YESTERDAY ? context.getString(R.string.projects_parity_session_yesterday)
                        : AppDates.day(AppDates.turkish(context), when.millis, now, AppDates.UTC);
                text = context.getString(R.string.projects_parity_session_last_used, day);
            }
        }
        if (text == null) return "";
        return "microsoft".equals(Json.str(session, "via")) ? context.getString(R.string.projects_parity_session_via_microsoft, text) : text;
    }

    private void revokeOthers(final FragmentActivity activity, final View feedback) {
        if (mSessionsBusy) return;
        mSessionsBusy = true;
        SocialService.get(activity).revokeOtherSessions(new SocialService.Result<Integer>() {
            @Override
            public void ok(Integer revoked) {
                mSessionsBusy = false;
                Haptics.confirm(feedback);
                if (mSessions != null) {
                    List<JsonObject> left = new ArrayList<>();
                    for (JsonObject s : mSessions) if (Json.bool(s, "current", false)) left.add(s);
                    mSessions = left;
                }
                mRedraw.redraw();
                int n = revoked == null ? 0 : revoked;
                SocialUi.toast(activity, activity.getResources().getQuantityString(R.plurals.projects_parity_session_revoked_others, n, n));
            }

            @Override
            public void failed(SocialProblem problem) {
                mSessionsBusy = false;
                mRedraw.redraw();
                SocialUi.toastProblem(activity, problem, feedback);
            }
        });
    }

    // ---- shared ----

    private static TextView header(Context context, int title) {
        TextView header = new TextView(context);
        header.setText(title);
        header.setTextSize(13);
        header.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_300));
        if (android.os.Build.VERSION.SDK_INT >= 28) header.setAccessibilityHeading(true);
        try {
            header.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        header.setPadding(dp(context, 8), dp(context, 20), dp(context, 8), dp(context, 4));
        return header;
    }

    private static TextView note(Context context, String value) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(14);
        text.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
        text.setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6));
        return text;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
