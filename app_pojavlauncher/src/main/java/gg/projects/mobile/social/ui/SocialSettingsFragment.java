package gg.projects.mobile.social.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.google.gson.JsonObject;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import gg.projects.mobile.social.IdentityDays;
import gg.projects.mobile.social.PushMessage;
import gg.projects.mobile.social.SignInFlow;
import gg.projects.mobile.social.SocialGate;
import gg.projects.mobile.social.SocialModel.Identity;
import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialNotifications;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.AppDates;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * "Arkadaş ayarları" (M19): the desktop's privacy switches (the account's, the same on every
 * device), which notifications this phone gets ("Davetler", "Ekip çağrıları", "Arkadaşlık istekleri";
 * they also decide what the service sends it), the blocked players with "Engeli kaldır", SOC-ID's
 * "Bağlı hesaplar" (the account's ways in: link one more, remove one), and the account: "Çıkış yap" and
 * "Verilerimi sil" (the desktop's question and words).
 */
public class SocialSettingsFragment extends Fragment implements SocialService.Listener {
    public static final String TAG = "ProjectsSocialSettingsFragment";
    /** The notification switches while Android keeps the app's notifications off. */
    private static final float DIMMED = 0.5f;

    private LinearLayout mList;
    private String mShown = "";
    /** BADGES: "Rozetler". */
    private final OwnMarks mOwnMarks = new OwnMarks();

    public SocialSettingsFragment() {
        super(R.layout.fragment_projects_social_settings);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mList = view.findViewById(R.id.projects_social_settings_list);
    }

    @Override
    public void onResume() {
        super.onResume();
        SocialService service = SocialService.get(requireContext());
        if (!service.signedIn()) {
            SocialUi.leave(this);
            return;
        }
        service.addListener(this);
        // SOC-ID: whether Microsoft can be linked now ("Bağlı hesaplar").
        service.readSignInOptions(false);
        mShown = "";
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        SocialService.get(requireContext()).removeListener(this);
    }

    @Override
    public void onSocialChanged() {
        if (!SocialService.get(requireContext()).signedIn()) {
            // Signed out or deleted: back to the friends screen's consent.
            if (isResumed()) Tools.backToMainMenu(requireActivity());
            return;
        }
        render();
    }

    private void render() {
        if (!isAdded() || getView() == null) return;
        Context context = requireContext();
        SocialService service = SocialService.get(context);
        Me me = service.state().me();
        SocialGate gate = service.gate();
        StringBuilder key = new StringBuilder();
        // BADGES: changes only when "Rozetler" must be drawn again (an answer agreeing with its switches is not).
        key.append(mOwnMarks.key(service, me)).append('|');
        key.append(me == null ? "" : me.shareLastSeen + "|" + me.shareServer + "|" + me.shareRead + "|" + me.shareTyping + "|"
                + me.chatPrivacy).append('|')
                .append(SocialNotifications.canNotify(context)).append('|').append(gate.teams).append(gate.calls).append(gate.mute)
                .append('|').append(service.state().teamNotifications().calls).append(service.prefs().wantsInvites())
                .append(service.prefs().wantsRequests()).append(service.prefs().wantsCallsLocally())
                .append(gate.chat).append(service.prefs().wantsMessages()).append(gate.plans)
                .append(service.prefs().wantsPlanReminders()).append('|');
        for (Profile p : service.state().blocked()) key.append(p.id).append(',');
        // SOC-ID: the linked accounts, a link or a removal on its way, and whether Microsoft is offered.
        if (me != null) {
            key.append('|').append(me.identitiesListed).append(me.signInVia).append(me.discordNumber());
            for (Identity i : me.identities) key.append(i.provider).append(i.linkedOn).append(i.isNew).append(i.hint).append(',');
        }
        key.append('|').append(service.identityStatus()).append(service.identityProvider()).append(service.identityPurpose())
                .append(service.signInOptions().encode());
        if (key.toString().equals(mShown)) return;
        mShown = key.toString();
        mList.removeAllViews();

        // BADGES: "Rozetler" before "Gizlilik", the launcher's order (it follows "Profil fotoğrafı" there, which the
        // phone has not), while the player holds a badge and the service keeps the choice.
        if (mOwnMarks.shows(service, me)) {
            section(R.string.projects_marks_title);
            mOwnMarks.addRows(mList, service, () -> {
                mShown = "";
                render();
            });
        }

        section(R.string.projects_social_privacy);
        if (me != null) {
            toggle(R.string.projects_social_share_last_seen, me.shareLastSeen, on -> service.setPrivacy("shareLastSeen", on, privacyResult()));
            toggle(R.string.projects_social_share_server, me.shareServer, on -> service.setPrivacy("shareServer", on, privacyResult()));
            // CHAT2: "Görüldü" and "yazıyor…" while the service offers them; both work both ways.
            if (me.chatPrivacy && gate.chat) {
                toggle(R.string.projects_privacy_seen, me.shareRead, on -> service.setPrivacy("shareRead", on, privacyResult()));
                toggle(R.string.projects_privacy_typing, me.shareTyping, on -> service.setPrivacy("shareTyping", on, privacyResult()));
                mList.addView(text(getString(R.string.projects_privacy_both), R.color.projects_ash_400));
            }
        }

        section(R.string.projects_social_notifications);
        // MOB-POLISH: while Android keeps the app's notifications off, one line says so with "Aç" (the 7/24 Bulut
        // screen's notice), and the switches under it are dimmed instead of gold. They stay usable: they also decide
        // the app's own lines and the in-game ones, which come anyway.
        boolean osOn = SocialNotifications.canNotify(context);
        if (!osOn) mList.addView(notificationsOff(context));
        int firstSwitch = mList.getChildCount();
        toggle(R.string.projects_social_channel_invites, service.prefs().wantsInvites(), on -> {
            service.prefs().setWants(PushMessage.INVITE, on);
            service.syncPush();
        });
        if (gate.teams && gate.calls) {
            boolean calls = gate.mute ? service.state().teamNotifications().calls : service.prefs().wantsCallsLocally();
            toggle(R.string.projects_social_channel_calls, calls, on -> service.setCrewCalls(on, new SocialService.Result<gg.projects.mobile.social.SocialModel.TeamNotifications>() {
                @Override
                public void ok(gg.projects.mobile.social.SocialModel.TeamNotifications value) {
                }

                @Override
                public void failed(SocialProblem problem) {
                    mShown = "";
                    render();
                    SocialUi.toastProblem(getContext(), problem, null);
                }
            }));
        }
        if (gate.chat) {
            // M20: a friend's direct message ("Ayşe sana mesaj gönderdi"; never its text).
            toggle(R.string.projects_chat_channel, service.prefs().wantsMessages(), on -> {
                service.prefs().setWants(PushMessage.MESSAGE, on);
                service.syncPush();
            });
        }
        if (gate.plans) {
            // M21 (TEAM5): 10 minutes before a plan the player is coming to; set on this phone only.
            toggle(R.string.projects_plans_channel, service.prefs().wantsPlanReminders(), service::setPlanReminders);
        }
        toggle(R.string.projects_social_channel_requests, service.prefs().wantsRequests(), on -> {
            service.prefs().setWants(PushMessage.REQUEST, on);
            service.syncPush();
        });
        if (!osOn) {
            for (int i = firstSwitch; i < mList.getChildCount(); i++) mList.getChildAt(i).setAlpha(DIMMED);
        }

        // MOB-VOICE: "Sesli sohbet" (the desktop's settings, the rows a phone has: no device, key or sensitivity choice;
        // each person's volume is in the room).
        final gg.projects.mobile.voice.VoiceHub voice = service.voice();
        if (voice.available()) {
            section(R.string.projects_voice_title);
            toggle(R.string.projects_voice_settings_enabled, voice.prefs().enabled(), on -> {
                voice.setEnabled(on);
                mShown = "";
                render();
            });
            mList.addView(text(getString(R.string.projects_voice_settings_enabled_help), R.color.projects_ash_400));
            if (voice.prefs().enabled()) {
                toggle(R.string.projects_voice_ask_first, voice.prefs().askBeforeCall(), voice.prefs()::setAskBeforeCall);
                toggle(R.string.projects_voice_sounds, voice.prefs().soundsSaved(), on -> {
                    voice.prefs().setSoundsSaved(on);
                    gg.projects.mobile.voice.VoiceLink.get(requireContext()).setSounds(on);
                });
            }
        }

        section(R.string.projects_social_blocked_title);
        if (service.state().blocked().isEmpty()) {
            mList.addView(text(getString(R.string.projects_social_blocked_empty), R.color.projects_ash_400));
        }
        for (final Profile blocked : service.state().blocked()) mList.addView(blockedRow(blocked));

        // SOC-ID: "Bağlı hesaplar", while the service lists them (it links and removes then).
        if (me != null && me.identitiesListed) identities(context, service, me);

        section(R.string.projects_social_account);
        mList.addView(CrewViews.actionRow(context, R.drawable.projects_ic_sign_out, getString(R.string.projects_social_sign_out),
                R.color.projects_bone_100, v -> service.signOut()));
        mList.addView(CrewViews.actionRow(context, R.drawable.projects_ic_trash, getString(R.string.projects_social_delete_data),
                R.color.projects_flare_400, v -> deleteData()));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * "Bu telefonda bildirimler kapalı." with "Aç" at its end (Android's notification settings for the app), in the
     * 7/24 Bulut screen's notice: a quiet panel whose small bell carries the colour.
     */
    private View notificationsOff(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable surface = new GradientDrawable();
        surface.setCornerRadius(dp(10));
        surface.setColor(ContextCompat.getColor(context, R.color.projects_obsidian_800));
        surface.setStroke(Math.max(1, dp(1)), ContextCompat.getColor(context, R.color.projects_obsidian_700));
        panel.setBackground(surface);
        panel.setPaddingRelative(dp(14), dp(2), dp(2), dp(2));
        ImageView bell = new ImageView(context);
        bell.setImageResource(R.drawable.projects_ic_bell_slash);
        bell.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_gold_300));
        bell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        panel.addView(bell, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView words = new TextView(context);
        words.setText(R.string.projects_social_notifications_off);
        words.setTextSize(14);
        words.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordsLp.setMarginStart(dp(12));
        panel.addView(words, wordsLp);
        Button open = new Button(context, null, android.R.attr.borderlessButtonStyle);
        open.setText(R.string.projects_social_notifications_turn_on);
        open.setContentDescription(getString(R.string.projects_social_notifications_turn_on_label));
        open.setAllCaps(false);
        open.setTextSize(14);
        open.setTextColor(ContextCompat.getColor(context, R.color.projects_gold_300));
        try {
            open.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        open.setMinWidth(0);
        open.setMinimumWidth(0);
        open.setMinHeight(dp(48));
        open.setMinimumHeight(dp(48));
        open.setPadding(dp(14), 0, dp(14), 0);
        open.setBackgroundResource(R.drawable.projects_row_ripple);
        Press.attach(open, Press.Style.BUTTON);
        open.setOnClickListener(v -> openNotificationSettings());
        panel.addView(open, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        panel.setLayoutParams(lp);
        return panel;
    }

    private void section(int title) {
        TextView header = new TextView(requireContext());
        header.setText(title);
        header.setTextSize(13);
        header.setTextColor(ContextCompat.getColor(requireContext(), R.color.projects_ash_300));
        if (Build.VERSION.SDK_INT >= 28) header.setAccessibilityHeading(true);
        try {
            header.setTypeface(ResourcesCompat.getFont(requireContext(), R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        header.setPadding(dp(8), dp(20), dp(8), dp(4));
        mList.addView(header);
    }

    private TextView text(String value, int color) {
        TextView text = new TextView(requireContext());
        text.setText(value);
        text.setTextSize(14);
        text.setTextColor(ContextCompat.getColor(requireContext(), color));
        text.setPadding(dp(8), dp(6), dp(8), dp(6));
        return text;
    }

    private interface Toggle {
        void changed(boolean on);
    }

    private void toggle(int label, boolean checked, final Toggle toggle) {
        SwitchCompat row = new SwitchCompat(requireContext());
        row.setText(label);
        row.setTextSize(16);
        row.setTextColor(ContextCompat.getColor(requireContext(), R.color.projects_bone_100));
        row.setMinHeight(dp(56));
        row.setPadding(dp(8), 0, dp(8), 0);
        row.setChecked(checked);
        row.setOnCheckedChangeListener((button, on) -> {
            Haptics.toggle(button, on);
            toggle.changed(on);
        });
        mList.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private SocialService.Result<JsonObject> privacyResult() {
        return new SocialService.Result<JsonObject>() {
            @Override
            public void ok(JsonObject value) {
            }

            @Override
            public void failed(SocialProblem problem) {
                mShown = "";
                render();
                SocialUi.toastProblem(getContext(), problem, null);
            }
        };
    }

    private View blockedRow(final Profile blocked) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(8), dp(4), dp(4), dp(4));
        AvatarView avatar = new AvatarView(context);
        avatar.setProfile(blocked);
        avatar.setDimmed(true);
        row.addView(avatar, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView name = new TextView(context);
        name.setText(blocked.name());
        name.setTextSize(15);
        name.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMarginStart(dp(12));
        row.addView(name, lp);
        final Button unblock = new Button(context, null, android.R.attr.borderlessButtonStyle);
        unblock.setText(R.string.projects_social_unblock);
        unblock.setAllCaps(false);
        unblock.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        unblock.setBackground(ChamferDrawable.outlined(context));
        Press.attach(unblock, Press.Style.BUTTON);
        final FragmentActivity activity = requireActivity();
        final String unblocked = getString(R.string.projects_social_unblocked, blocked.name());
        unblock.setOnClickListener(v -> {
            unblock.setEnabled(false);
            SocialService.get(activity).unblock(blocked.id, new SocialService.Result<Void>() {
                @Override
                public void ok(Void value) {
                    SocialUi.toast(activity, unblocked);
                }

                @Override
                public void failed(SocialProblem problem) {
                    // Still blocked: the button works again.
                    unblock.setEnabled(true);
                    SocialUi.toastProblem(activity, problem, unblock);
                }
            });
        });
        row.addView(unblock);
        return row;
    }

    // ---- SOC-ID: "Bağlı hesaplar" ----

    /**
     * The account's ways in: each with its mark, its name, "Bu cihaz" on the one this phone signed in with, "Yeni" for 7
     * days after a link, the Discord username or the day it was linked, and "Kaldır" while another one stays; then a
     * link or a removal on its way (the browser step), or "… hesabını bağla" for each way in the service offers that is
     * not linked. One link or removal at a time.
     */
    private void identities(Context context, final SocialService service, Me me) {
        section(R.string.projects_identity_title);
        SocialService.IdentityStatus status = service.identityStatus();
        boolean busy = status != SocialService.IdentityStatus.IDLE;
        boolean removable = me.identities.size() > 1;
        for (Identity identity : me.identities) mList.addView(identityRow(context, service, me, identity, removable, busy));
        if (busy) {
            mList.addView(identityWaiting(context, service, status));
            return;
        }
        for (String provider : new String[]{SignInFlow.DISCORD, SignInFlow.MICROSOFT}) {
            if (me.identity(provider) != null || !service.signInOptions().offers(provider)) continue;
            final String way = provider;
            TextView link = CrewViews.actionRow(context, R.drawable.projects_ic_user_plus,
                    getString(SignInFlow.MICROSOFT.equals(provider) ? R.string.projects_identity_link_microsoft
                            : R.string.projects_identity_link_discord), R.color.projects_bone_100,
                    v -> SocialService.get(requireContext()).linkIdentity(requireActivity(), way));
            // The way in's own mark, untinted, as wide as the other rows' icons.
            Drawable mark = providerMark(context, provider, 22);
            if (mark != null) link.setCompoundDrawablesRelative(mark, null, null, null);
            mList.addView(link);
        }
    }

    /** A way in's own mark: Microsoft's four squares, Discord's symbol in Blurple; {@code widthDp} wide. */
    @Nullable
    private static Drawable providerMark(Context context, String provider, int widthDp) {
        if (SignInFlow.MICROSOFT.equals(provider)) return SocialFragment.microsoftMark(context, Math.round(widthDp * 0.82f));
        return SocialChips.discordMark(context, widthDp / SocialChips.DISCORD_RATIO);
    }

    private View identityRow(Context context, final SocialService service, final Me me, final Identity identity,
                             boolean removable, boolean busy) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(60));
        row.setPadding(dp(8), dp(6), dp(4), dp(6));
        ImageView mark = new ImageView(context);
        mark.setImageDrawable(providerMark(context, identity.provider, 22));
        mark.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark, new LinearLayout.LayoutParams(dp(24), dp(24)));

        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout title = new LinearLayout(context);
        title.setOrientation(LinearLayout.HORIZONTAL);
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(context);
        name.setText(SocialService.providerName(identity.provider));
        name.setTextSize(15);
        name.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        try {
            name.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        title.addView(name);
        // Discord's handle beside its name, quiet; the day it was linked under them (SOC-ID-C, the launcher's row: no
        // "Bu cihaz" or "Yeni" chips, projects-taste; the day says when, and a link the player did not make shows on
        // every device all the same).
        if (identity.hint != null && SignInFlow.DISCORD.equals(identity.provider)) {
            TextView handle = new TextView(context);
            handle.setText("@" + identity.hint);
            handle.setTextSize(13);
            handle.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
            handle.setSingleLine(true);
            handle.setEllipsize(TextUtils.TruncateAt.END);
            handle.setTextIsSelectable(false);
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            handleLp.setMarginStart(dp(8));
            title.addView(handle, handleLp);
        }
        words.addView(title);
        String day = IdentityDays.linkedDay(AppDates.turkish(context), identity.linkedOn, System.currentTimeMillis());
        String second = day == null ? null : getString(R.string.projects_identity_linked_on, day);
        if (second != null) {
            TextView line = new TextView(context);
            line.setText(second);
            line.setTextSize(13);
            line.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
            words.addView(line);
        }
        LinearLayout.LayoutParams wordsLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        wordsLp.setMarginStart(dp(14));
        row.addView(words, wordsLp);

        if (removable) {
            final Button remove = new Button(context, null, android.R.attr.borderlessButtonStyle);
            remove.setText(R.string.projects_identity_remove);
            remove.setAllCaps(false);
            remove.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
            remove.setBackground(ChamferDrawable.outlined(context));
            remove.setContentDescription(getString(R.string.projects_identity_remove_title, SocialService.providerName(identity.provider)));
            remove.setEnabled(!busy);
            remove.setAlpha(busy ? 0.5f : 1f);
            Press.attach(remove, Press.Style.BUTTON);
            remove.setOnClickListener(v -> askRemove(me, identity.provider));
            row.addView(remove);
        }
        return row;
    }

    /** "Kaldır": "Microsoft bağlantısı kaldırılsın mı?" (Discord's says what goes with it), then the removal. */
    private void askRemove(Me me, final String provider) {
        final FragmentActivity activity = requireActivity();
        String message = null;
        if (SignInFlow.DISCORD.equals(provider)) {
            // The account's 7/24 Bulut and Projects skin need its own Discord; the forum's crew tag and the Discord photo
            // need a Discord.
            message = getString(me.discordNumber() ? R.string.projects_identity_remove_discord_number
                    : R.string.projects_identity_remove_discord);
        }
        SocialUi.confirmDanger(activity, getString(R.string.projects_identity_remove_title, SocialService.providerName(provider)),
                message, getString(R.string.projects_identity_remove),
                () -> SocialService.get(activity).unlinkIdentity(activity, provider));
    }

    /**
     * A link or a removal on its way: what to do in the browser (a link signs in to the way in it adds; a removal
     * proves a way in that stays: with two ways in, the other one), "Tarayıcıyı yeniden aç" and "Vazgeç"; while the
     * service answers, only that it is on its way.
     */
    private View identityWaiting(Context context, final SocialService service, SocialService.IdentityStatus status) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setBackgroundResource(R.drawable.projects_social_section_background);
        panel.setPadding(dp(16), dp(14), dp(16), dp(10));
        android.widget.ProgressBar progress = new android.widget.ProgressBar(context);
        progress.setIndeterminateTintList(ContextCompat.getColorStateList(context, R.color.projects_gold_400));
        panel.addView(progress, new LinearLayout.LayoutParams(dp(26), dp(26)));
        boolean link = service.identityPurpose() == SignInFlow.Purpose.LINK;
        String provider = service.identityProvider();
        String browser = link ? provider : SignInFlow.DISCORD.equals(provider) ? SignInFlow.MICROSOFT : SignInFlow.DISCORD;
        boolean microsoft = SignInFlow.MICROSOFT.equals(browser);
        boolean inBrowser = status == SocialService.IdentityStatus.WAITING;
        TextView title = new TextView(context);
        title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        title.setGravity(Gravity.CENTER);
        title.setTextSize(15);
        title.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
        try {
            title.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        title.setText(inBrowser ? (microsoft ? R.string.projects_sign_in_waiting_microsoft : R.string.projects_social_waiting)
                : link ? R.string.projects_identity_linking : R.string.projects_identity_removing);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(8);
        panel.addView(title, titleLp);
        if (inBrowser) {
            TextView note = new TextView(context);
            note.setGravity(Gravity.CENTER);
            note.setTextSize(13);
            note.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
            note.setText(microsoft ? R.string.projects_sign_in_waiting_microsoft_note : R.string.projects_social_waiting_note);
            panel.addView(note);
            if (microsoft) {
                TextView safety = new TextView(context);
                safety.setGravity(Gravity.CENTER);
                safety.setTextSize(13);
                safety.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_300));
                safety.setText(R.string.projects_sign_in_microsoft_safety);
                LinearLayout.LayoutParams safetyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                safetyLp.topMargin = dp(6);
                panel.addView(safety, safetyLp);
            }
        }
        if (status != SocialService.IdentityStatus.CLAIMING) {
            LinearLayout buttons = new LinearLayout(context);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            if (inBrowser) {
                buttons.addView(panelButton(context, R.string.projects_social_reopen, R.color.projects_bone_100,
                        v -> SocialService.get(requireContext()).reopenSignInPage(requireActivity())));
            }
            buttons.addView(panelButton(context, R.string.projects_social_cancel, R.color.projects_ash_300,
                    v -> SocialService.get(requireContext()).cancelIdentity()));
            LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            buttonsLp.topMargin = dp(6);
            panel.addView(buttons, buttonsLp);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(4);
        panel.setLayoutParams(lp);
        return panel;
    }

    private Button panelButton(Context context, int text, int color, View.OnClickListener listener) {
        Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(ContextCompat.getColor(context, color));
        try {
            button.setTypeface(ResourcesCompat.getFont(context, R.font.figtree_semibold));
        } catch (RuntimeException ignored) {
            // The theme's font.
        }
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        Press.attach(button, Press.Style.BUTTON);
        button.setOnClickListener(listener);
        return button;
    }

    private void deleteData() {
        final FragmentActivity activity = requireActivity();
        new AlertDialog.Builder(activity)
                .setTitle(R.string.projects_social_delete_title)
                .setMessage(R.string.projects_social_delete_body)
                .setPositiveButton(R.string.projects_social_delete_confirm, (d, w) -> SocialService.get(activity).deleteAccount(
                        new SocialService.Result<Void>() {
                            @Override
                            public void ok(Void value) {
                                SocialUi.toast(activity, activity.getString(R.string.projects_social_deleted));
                            }

                            @Override
                            public void failed(SocialProblem problem) {
                                SocialUi.toastProblem(activity, problem, null);
                            }
                        }))
                .setNegativeButton(R.string.projects_social_cancel, null)
                .show();
    }

    private void openNotificationSettings() {
        FragmentActivity activity = requireActivity();
        Intent intent = Build.VERSION.SDK_INT >= 26
                ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName())
                : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.getPackageName(), null));
        try {
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            SocialUi.toast(activity, getString(R.string.projects_link_no_app));
        }
    }
}
