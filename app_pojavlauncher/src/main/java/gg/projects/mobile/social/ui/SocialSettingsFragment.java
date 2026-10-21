package gg.projects.mobile.social.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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

import gg.projects.mobile.social.PushMessage;
import gg.projects.mobile.social.SocialGate;
import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialNotifications;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.Press;

/**
 * "Arkadaş ayarları" (M19): the desktop's privacy switches (the account's, the same on every
 * device), which notifications this phone gets ("Davetler", "Ekip çağrıları", "Arkadaşlık istekleri";
 * they also decide what the service sends it), the blocked players with "Engeli kaldır", and the
 * account: "Çıkış yap" and "Verilerimi sil" (the desktop's question and words).
 */
public class SocialSettingsFragment extends Fragment implements SocialService.Listener {
    public static final String TAG = "ProjectsSocialSettingsFragment";

    private LinearLayout mList;
    private String mShown = "";

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
        key.append(me == null ? "" : me.shareLastSeen + "|" + me.shareServer).append('|')
                .append(SocialNotifications.canNotify(context)).append('|').append(gate.teams).append(gate.calls).append(gate.mute)
                .append('|').append(service.state().teamNotifications().calls).append(service.prefs().wantsInvites())
                .append(service.prefs().wantsRequests()).append(service.prefs().wantsCallsLocally())
                .append(gate.chat).append(service.prefs().wantsMessages()).append('|');
        for (Profile p : service.state().blocked()) key.append(p.id).append(',');
        if (key.toString().equals(mShown)) return;
        mShown = key.toString();
        mList.removeAllViews();

        section(R.string.projects_social_privacy);
        if (me != null) {
            toggle(R.string.projects_social_share_last_seen, me.shareLastSeen, on -> service.setPrivacy("shareLastSeen", on, privacyResult()));
            toggle(R.string.projects_social_share_server, me.shareServer, on -> service.setPrivacy("shareServer", on, privacyResult()));
        }

        section(R.string.projects_social_notifications);
        if (!SocialNotifications.canNotify(context)) {
            TextView off = text(getString(R.string.projects_social_notifications_off), R.color.projects_ash_300);
            mList.addView(off);
            Button open = new Button(context, null, android.R.attr.borderlessButtonStyle);
            open.setText(R.string.projects_open_settings);
            open.setAllCaps(false);
            open.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
            open.setBackground(ChamferDrawable.outlined(context));
            Press.attach(open, Press.Style.BUTTON);
            open.setOnClickListener(v -> openNotificationSettings());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(8));
            lp.topMargin = dp(4);
            mList.addView(open, lp);
        }
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
        toggle(R.string.projects_social_channel_requests, service.prefs().wantsRequests(), on -> {
            service.prefs().setWants(PushMessage.REQUEST, on);
            service.syncPush();
        });

        section(R.string.projects_social_blocked_title);
        if (service.state().blocked().isEmpty()) {
            mList.addView(text(getString(R.string.projects_social_blocked_empty), R.color.projects_ash_400));
        }
        for (final Profile blocked : service.state().blocked()) mList.addView(blockedRow(blocked));

        section(R.string.projects_social_account);
        mList.addView(CrewViews.actionRow(context, R.drawable.projects_ic_sign_out, getString(R.string.projects_social_sign_out),
                R.color.projects_bone_100, v -> service.signOut()));
        mList.addView(CrewViews.actionRow(context, R.drawable.projects_ic_trash, getString(R.string.projects_social_delete_data),
                R.color.projects_flare_400, v -> deleteData()));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
