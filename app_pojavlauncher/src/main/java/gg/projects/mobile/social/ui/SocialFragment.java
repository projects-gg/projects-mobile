package gg.projects.mobile.social.ui;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.SocialGate;
import gg.projects.mobile.social.SocialModel;
import gg.projects.mobile.social.ChatBook;
import gg.projects.mobile.social.ChatConv;
import gg.projects.mobile.social.SocialModel.Call;
import gg.projects.mobile.social.SocialModel.Crew;
import gg.projects.mobile.social.SocialModel.Friend;
import gg.projects.mobile.social.SocialModel.Invite;
import gg.projects.mobile.social.SocialModel.Me;
import gg.projects.mobile.social.SocialModel.Member;
import gg.projects.mobile.social.SocialModel.Presence;
import gg.projects.mobile.social.SocialModel.Profile;
import gg.projects.mobile.social.SocialModel.Request;
import gg.projects.mobile.social.SocialModel.Team;
import gg.projects.mobile.social.SocialModel.TeamInvite;
import gg.projects.mobile.social.SocialNotifications;
import gg.projects.mobile.social.SocialProblem;
import gg.projects.mobile.social.SocialService;
import gg.projects.mobile.social.SocialState;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.ContentTiles;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.PixelSprites;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.ui.ProjectsHomeCard;
import gg.projects.mobile.ui.ProjectsUi;
import gg.projects.mobile.ui.ScreenHeader;

/**
 * The friends screen (M19). Signed out: the desktop's consent screen (what is shared, what never
 * reaches the service, "Gizlilik") and "Discord ile giriş yap". Signed in: the player and their
 * status, the crew, "Birlikte oyna" invites, crew invites and friend requests, then friends by where
 * they are (in game, online, offline; favourites first) and the requests the player sent. Live while
 * it is on screen; rows that come, go or move glide like the news list.
 *
 * <p>FIX2: every person and crew row holds the same menu as anywhere else ({@link SocialMenuSheet}): a
 * friend's, a request's (its answers first), a crew's; an invite's row its own answers, then who sent
 * it. An invite to another server offers "Bağlan" ({@link ConnectFlow}) instead of copying the address.
 */
public class SocialFragment extends Fragment implements SocialService.Listener {
    public static final String TAG = "ProjectsSocialFragment";
    public static final String ARG_INVITE = "invite";

    private static final int T_ME = 0;
    private static final int T_BANNER = 1;
    private static final int T_CREW = 2;
    private static final int T_HEADER = 3;
    private static final int T_PERSON = 4;
    private static final int T_EMPTY = 5;
    /** M20: "Mesajlar" (CHAT1). */
    private static final int T_MESSAGES = 6;

    private final Adapter mAdapter = new Adapter();
    private RecyclerView mList;
    private View mConsent;
    private TextView mConsentMessage;
    private Button mSignIn;
    private View mWaiting;
    private TextView mWaitingTitle;
    private View mWaitingNote;
    private View mReopen;
    private View mConsentRules;
    private ImageButton mAddAction;
    private ImageButton mSettingsAction;
    private boolean mRemoteSignOutShown;
    /** The invite a notification tap came for: asked about once it is in the list. */
    @Nullable
    private String mPendingInvite;
    private final Runnable mTick = this::render;

    /** Android's notification question from the list's offer. */
    private final ActivityResultLauncher<String> mPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (!isAdded()) return;
                SocialService service = SocialService.get(requireContext());
                if (granted) service.syncPush();
                render();
            });

    public SocialFragment() {
        super(R.layout.fragment_projects_social);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ScreenHeader header = view.findViewById(R.id.projects_social_header);
        mAddAction = header.addAction(R.drawable.projects_ic_user_plus, R.string.projects_social_add,
                v -> Tools.swapFragment(requireActivity(), AddFriendFragment.class, AddFriendFragment.TAG, null));
        mSettingsAction = header.addAction(R.drawable.projects_ic_gear_six, R.string.projects_social_settings,
                v -> Tools.swapFragment(requireActivity(), SocialSettingsFragment.class, SocialSettingsFragment.TAG, null));
        mList = view.findViewById(R.id.projects_social_list);
        mList.setLayoutManager(new LinearLayoutManager(view.getContext()));
        mList.setAdapter(mAdapter);
        if (Motion.enabled(view.getContext())) {
            DefaultItemAnimator animator = new DefaultItemAnimator();
            animator.setSupportsChangeAnimations(false);
            animator.setAddDuration(Motion.ENTER_MS);
            animator.setMoveDuration(Motion.ENTER_MS);
            animator.setRemoveDuration(Motion.EXIT_MS);
            mList.setItemAnimator(animator);
        } else {
            mList.setItemAnimator(null);
        }
        mConsent = view.findViewById(R.id.projects_social_consent);
        ContentTiles.show(view.findViewById(R.id.projects_consent_icon), PixelSprites.Sprite.FRIENDS, false);
        mConsentMessage = view.findViewById(R.id.projects_consent_message);
        mSignIn = view.findViewById(R.id.projects_consent_sign_in);
        mWaiting = view.findViewById(R.id.projects_consent_waiting);
        mWaitingTitle = view.findViewById(R.id.projects_consent_waiting_title);
        mWaitingNote = view.findViewById(R.id.projects_consent_waiting_note);
        mReopen = view.findViewById(R.id.projects_consent_reopen);
        mConsentRules = view.findViewById(R.id.projects_consent_rules);
        Press.attach(mConsentRules, Press.Style.BUTTON);
        mConsentRules.setOnClickListener(v -> SocialUi.openRules(requireActivity()));
        View cancel = view.findViewById(R.id.projects_consent_cancel);
        View privacy = view.findViewById(R.id.projects_consent_privacy);
        Drawable glyph = ContextCompat.getDrawable(view.getContext(), R.drawable.projects_ic_discord_glyph);
        if (glyph != null) {
            float dp = getResources().getDisplayMetrics().density;
            glyph.setBounds(0, 0, Math.round(22 * dp), Math.round(16.7f * dp));
            mSignIn.setCompoundDrawablesRelative(glyph, null, null, null);
        }
        Press.attach(mSignIn, Press.Style.BUTTON);
        Press.attach(mReopen, Press.Style.BUTTON);
        Press.attach(cancel, Press.Style.BUTTON);
        Press.attach(privacy, Press.Style.BUTTON);
        mSignIn.setOnClickListener(v -> SocialService.get(requireContext()).beginSignIn(requireActivity()));
        mReopen.setOnClickListener(v -> SocialService.get(requireContext()).reopenSignInPage(requireActivity()));
        cancel.setOnClickListener(v -> SocialService.get(requireContext()).cancelSignIn());
        privacy.setOnClickListener(v -> ProjectsUi.openUrl(requireActivity(), getString(R.string.projects_social_privacy_url)));
    }

    /** Slide up and fade in; leave a little faster (as the news and mod screens). */
    @Nullable
    @Override
    public Animator onCreateAnimator(int transit, boolean enter, int nextAnim) {
        View view = getView();
        if (view == null || !Motion.enabled(view.getContext())) return null;
        float distance = 28 * view.getResources().getDisplayMetrics().density;
        AnimatorSet set = new AnimatorSet();
        if (enter) {
            set.playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f), ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, distance, 0f));
            set.setDuration(Motion.ENTER_MS);
            set.setInterpolator(Motion.EASE_OUT);
        } else {
            set.playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f), ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, distance * 0.6f));
            set.setDuration(Motion.EXIT_MS);
            set.setInterpolator(Motion.EASE_IN);
        }
        return set;
    }

    @Override
    public void onResume() {
        super.onResume();
        SocialService service = SocialService.get(requireContext());
        service.addListener(this);
        service.surfaceShown(SocialService.Surface.FRIENDS, true);
        service.refresh(false);
        if (service.takeSignedOutRemotely()) mRemoteSignOutShown = true;
        // A notification's invite: its question comes as soon as the invite is known (once).
        Bundle args = getArguments();
        if (args != null && args.getString(ARG_INVITE) != null) {
            mPendingInvite = args.getString(ARG_INVITE);
            args.remove(ARG_INVITE);
        }
        render();
    }

    @Override
    public void onPause() {
        super.onPause();
        SocialService service = SocialService.get(requireContext());
        service.removeListener(this);
        service.surfaceShown(SocialService.Surface.FRIENDS, false);
        if (mList != null) mList.removeCallbacks(mTick);
    }

    @Override
    public void onDestroyView() {
        // The adapter is the fragment's and outlives this view (back stack): let go of the old list.
        if (mList != null) {
            mList.removeCallbacks(mTick);
            mList.setAdapter(null);
        }
        super.onDestroyView();
    }

    @Override
    public void onSocialChanged() {
        render();
    }

    private void render() {
        if (!isAdded() || getView() == null) return;
        Context context = requireContext();
        SocialService service = SocialService.get(context);
        if (!service.enabled()) {
            // Friends were turned off while the screen was open.
            SocialUi.leave(this);
            return;
        }
        boolean signedIn = service.signedIn();
        mAddAction.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        mSettingsAction.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        mConsent.setVisibility(signedIn ? View.GONE : View.VISIBLE);
        mList.setVisibility(signedIn ? View.VISIBLE : View.GONE);
        if (!signedIn) {
            renderConsent(service);
            return;
        }
        mRemoteSignOutShown = false;
        // A friend link opened before the sign-in: "Arkadaş ekle" with its code, now.
        String pendingCode = isResumed() ? service.takePendingFriendCode() : null;
        if (pendingCode != null) {
            Bundle add = new Bundle();
            add.putString(AddFriendFragment.ARG_CODE, pendingCode);
            SocialUi.open(this, AddFriendFragment.class, AddFriendFragment.TAG, add);
        }
        // M21: a crew invite link opened before the sign-in: the crew page looks it up, now.
        String pendingLink = pendingCode == null && isResumed() ? service.takePendingCrewLink() : null;
        if (pendingLink != null) {
            Bundle crew = new Bundle();
            crew.putString(CrewFragment.ARG_LINK, pendingLink);
            SocialUi.open(this, CrewFragment.class, CrewFragment.TAG, crew);
        }
        answerPendingInvite(service);
        mAdapter.submit(buildRows(service));
        // MOD1: a warning or a reset picture, once, on the first screen that sees it.
        if (isResumed()) AccountNotes.showNotices(requireActivity());
        // Invites and calls end at their time without a message: the screen looks again then.
        mList.removeCallbacks(mTick);
        long now = SocialService.nowSeconds();
        long next = Long.MAX_VALUE;
        for (Invite invite : service.state().invites(now)) next = Math.min(next, invite.expires - now);
        for (Call call : service.state().calls(now)) next = Math.min(next, service.state().secondsLeft(call, now));
        // M21: a plan starts or ends with no message (the crew rows say "Plan · Başladı").
        long hub = service.state().hubNow(now);
        for (SocialModel.Plan plan : service.state().plans(now)) next = Math.min(next, plan.at > hub ? plan.at - hub : plan.until - hub);
        if (next != Long.MAX_VALUE && next > 0) mList.postDelayed(mTick, Math.min(next, 3600) * 1000L + 500);
    }

    private void renderConsent(SocialService service) {
        // CHAT1: with chat on, the consent says how long the server keeps messages (the desktop's line).
        View body = getView() == null ? null : getView().findViewById(R.id.projects_consent_body);
        if (body instanceof TextView) {
            String text = getString(R.string.projects_social_consent_body);
            if (service.chatEnabled()) text += " " + getString(R.string.projects_chat_consent);
            ((TextView) body).setText(text);
        }
        SocialService.SignInStatus status = service.signInStatus();
        boolean waiting = status == SocialService.SignInStatus.WAITING || status == SocialService.SignInStatus.CLAIMING;
        mWaiting.setVisibility(waiting ? View.VISIBLE : View.GONE);
        mSignIn.setVisibility(waiting ? View.GONE : View.VISIBLE);
        mSignIn.setEnabled(status != SocialService.SignInStatus.CHECKING);
        mSignIn.setAlpha(status == SocialService.SignInStatus.CHECKING ? 0.6f : 1f);
        boolean claiming = status == SocialService.SignInStatus.CLAIMING;
        mWaitingTitle.setText(claiming ? R.string.projects_social_signing_in : R.string.projects_social_waiting);
        mWaitingNote.setVisibility(claiming ? View.GONE : View.VISIBLE);
        mReopen.setVisibility(claiming ? View.GONE : View.VISIBLE);
        String message = null;
        boolean restricted = status == SocialService.SignInStatus.RESTRICTED;
        if (status == SocialService.SignInStatus.FAILED) message = getString(R.string.projects_social_sign_in_failed);
        else if (status == SocialService.SignInStatus.UNAVAILABLE) message = getString(R.string.projects_social_unavailable);
        else if (restricted) {
            Long until = service.restrictedUntil();
            message = SocialUi.restrictedText(requireContext(), until == null ? 0 : until);
        } else if (status == SocialService.SignInStatus.SIGNUPS_CLOSED) message = getString(R.string.projects_social_signups_closed);
        else if (mRemoteSignOutShown && status == SocialService.SignInStatus.IDLE) message = getString(R.string.projects_social_signed_out_remote);
        mConsentMessage.setText(message);
        mConsentMessage.setVisibility(message == null ? View.GONE : View.VISIBLE);
        // MOD1: under "Hesabın … kısıtlandı.", the community rules; nothing technical.
        mConsentRules.setVisibility(restricted ? View.VISIBLE : View.GONE);
        mConsentMessage.setTextColor(ContextCompat.getColor(requireContext(), restricted
                || status == SocialService.SignInStatus.SIGNUPS_CLOSED ? R.color.projects_ash_300 : R.color.projects_flare_400));
    }

    // ---- rows ----

    /** One row of the list. */
    private static final class Row {
        final int type;
        final String id;
        /** What it shows: a row re-binds only when this changes. */
        final String content;
        @Nullable
        final Object data;

        Row(int type, String id, String content, @Nullable Object data) {
            this.type = type;
            this.id = id;
            this.content = content;
            this.data = data;
        }
    }

    private static final class Banner {
        final int text;
        final int action;
        final boolean closable;
        final String kind;

        Banner(String kind, int text, int action, boolean closable) {
            this.kind = kind;
            this.text = text;
            this.action = action;
            this.closable = closable;
        }
    }

    /** A person row's data: who, what the line says, which actions. */
    private static final class Person {
        final String kind;
        @Nullable
        final Profile profile;
        @Nullable
        final Object item;

        Person(String kind, @Nullable Profile profile, @Nullable Object item) {
            this.kind = kind;
            this.profile = profile;
            this.item = item;
        }
    }

    private List<Row> buildRows(SocialService service) {
        Context context = requireContext();
        SocialState state = service.state();
        SocialGate gate = service.gate();
        long now = SocialService.nowSeconds();
        Set<String> favourites = service.prefs().favourites();
        List<Row> rows = new ArrayList<>();
        Me me = state.me();
        if (me != null) {
            rows.add(new Row(T_ME, "me", me.profile.name() + "|" + me.status + "|" + me.profile.faceKey() + "|"
                    + (gate.badges && me.profile.badge != null ? me.profile.badge.key() : ""), me));
        }

        SocialService.Connection connection = service.connection();
        if (connection == SocialService.Connection.REPLACED) {
            rows.add(new Row(T_BANNER, "banner", "replaced", new Banner("replaced", R.string.projects_social_replaced, R.string.projects_social_reconnect, false)));
        } else if (connection == SocialService.Connection.UNAVAILABLE) {
            rows.add(new Row(T_BANNER, "banner", "unavailable", new Banner("unavailable", R.string.projects_social_service_unavailable, R.string.projects_retry, false)));
        } else if (gate.push && !SocialNotifications.canNotify(context) && !service.prefs().offerDone()) {
            rows.add(new Row(T_BANNER, "banner", "notify", new Banner("notify", R.string.projects_social_notify_offer, R.string.projects_notify_offer_allow, true)));
        }

        if (service.chat().available()) {
            // CHAT1: "Mesajlar" with how many are unread and the newest of them.
            int unread = service.chat().unreadTotal();
            List<ChatBook.Summary> summaries = service.chat().visibleSummaries();
            ChatBook.Summary newest = summaries.isEmpty() ? null : summaries.get(0);
            rows.add(new Row(T_MESSAGES, "messages", unread + "|" + (newest == null || newest.last == null ? ""
                    : newest.conv + newest.last.key() + newest.last.text() + newest.last.sticker()), newest));
        }

        if (gate.teams) {
            List<Crew> crews = state.crews();
            if (crews.size() > 1) rows.add(header("h-crews", getString(R.string.projects_crews_title)));
            if (crews.isEmpty()) {
                rows.add(new Row(T_CREW, "crew", "none|" + state.teamInvites().size(), null));
            }
            for (Crew crew : crews) {
                Team team = crew.team;
                Call call = unanswered(state, crew, now);
                Call own = own(state, crew, now);
                SocialModel.Plan plan = gate.plans ? nextPlan(state, crew, now) : null;
                String content = team.id + "|" + team.name + "|" + team.tag + "|" + team.emblem + team.color + team.frame + "|"
                        + state.onlineInCrew(team.id) + "|" + (call == null ? "" : call.id + call.place() + call.crews) + "|"
                        + (own == null ? "" : own.id) + "|" + crew.mute.mutedAt(state.hubNow(now))
                        + "|" + (gate.requests ? state.requests(team.id, now).size() : 0)
                        + "|" + (plan == null ? "" : plan.id + plan.at + plan.underWay(state.hubNow(now)));
                rows.add(new Row(T_CREW, "crew-" + team.id, content, crew));
            }
        }

        List<Invite> invites = state.invites(now);
        if (!invites.isEmpty()) {
            rows.add(header("h-invites", getString(R.string.projects_social_invites)));
            for (Invite invite : invites) {
                Profile from = state.profile(invite.fromId);
                if (from == null) continue;
                rows.add(new Row(T_PERSON, "invite-" + invite.id, "invite|" + from.name() + from.faceKey() + "|" + invite.where + invite.host,
                        new Person("invite", from, invite)));
            }
        }
        // TEAM4: with several crews the invites stay listed in a crew too (at the limit they can be declined).
        if (gate.teams && (state.team() == null || gate.multi) && !state.teamInvites().isEmpty()) {
            rows.add(header("h-crew-invites", getString(R.string.projects_crew_invites)));
            for (TeamInvite invite : state.teamInvites()) {
                rows.add(new Row(T_PERSON, "team-invite-" + invite.team.id, "teaminvite|" + invite.team.name + invite.team.emblem
                        + invite.team.color + invite.team.frame + "|" + invite.from.name(), new Person("teaminvite", invite.from, invite)));
            }
        }
        List<Request> incoming = state.incoming();
        if (!incoming.isEmpty()) {
            rows.add(header("h-requests", getString(R.string.projects_social_requests)));
            for (Request request : incoming) {
                rows.add(new Row(T_PERSON, "in-" + request.profile.id, "in|" + request.profile.name() + request.profile.faceKey(),
                        new Person("in", request.profile, request)));
            }
        }
        SocialState.Sections sections = state.sections(favourites);
        addFriends(rows, "playing", R.string.projects_social_playing, sections.playing, state, favourites);
        addFriends(rows, "online", R.string.projects_social_online, sections.online, state, favourites);
        addFriends(rows, "offline", R.string.projects_social_offline, sections.offline, state, favourites);
        List<Request> outgoing = state.outgoing();
        if (!outgoing.isEmpty()) {
            rows.add(header("h-sent", getString(R.string.projects_social_sent)));
            for (Request request : outgoing) {
                rows.add(new Row(T_PERSON, "out-" + request.profile.id, "out|" + request.profile.name() + request.profile.faceKey(),
                        new Person("out", request.profile, request)));
            }
        }
        if (state.loaded() && state.friends().isEmpty() && incoming.isEmpty() && outgoing.isEmpty()) {
            rows.add(new Row(T_EMPTY, "empty", "empty", null));
        }
        return rows;
    }

    /** M21: the crew's next plan, when it runs now or starts within a day (the crew row says it). */
    @Nullable
    private static SocialModel.Plan nextPlan(SocialState state, Crew crew, long now) {
        long hub = state.hubNow(now);
        for (SocialModel.Plan p : state.plans(now, crew.id())) {
            if (p.underWay(hub) || p.at - hub < 86_400) return p;
        }
        return null;
    }

    /** A crewmate's call naming this crew that the player has not answered yet. */
    @Nullable
    private static Call unanswered(SocialState state, Crew crew, long now) {
        String me = state.myId();
        for (Call call : state.calls(now, crew.id())) {
            if (!call.from.equals(me) && !call.answers.containsKey(me)) return call;
        }
        return null;
    }

    @Nullable
    private static Call own(SocialState state, Crew crew, long now) {
        for (Call call : state.calls(now, crew.id())) if (call.from.equals(state.myId())) return call;
        return null;
    }

    private Row header(String id, String title) {
        return new Row(T_HEADER, id, title, title);
    }

    private void addFriends(List<Row> rows, String id, int title, List<Friend> friends, SocialState state, Set<String> favourites) {
        if (friends.isEmpty()) return;
        rows.add(header("h-" + id, getString(title) + " · " + friends.size()));
        boolean tags = SocialService.get(requireContext()).gate().badges;
        for (Friend f : friends) {
            Presence p = state.presence(f.profile.id);
            rows.add(new Row(T_PERSON, "f-" + f.profile.id, "f|" + f.profile.name() + f.profile.faceKey() + "|" + p.state + p.dnd + p.where
                    + p.host + "|" + f.lastSeen + "|" + favourites.contains(f.profile.id) + "|"
                    + (tags && f.profile.badge != null ? f.profile.badge.key() : ""), new Person("friend", f.profile, f)));
        }
    }

    // ---- actions ----

    private void onBanner(Banner banner) {
        SocialService service = SocialService.get(requireContext());
        switch (banner.kind) {
            case "replaced":
                service.reconnect();
                break;
            case "unavailable":
                service.refresh(true);
                service.reconnect();
                break;
            case "notify":
                askNotifications();
                break;
            default:
                break;
        }
    }

    /**
     * The list's notification question (once): Android's own question while it may still ask, else
     * the app's notification settings. Marks the home card's question answered too (M18: nothing asks
     * after an answer).
     */
    private void askNotifications() {
        FragmentActivity activity = requireActivity();
        SocialService service = SocialService.get(activity);
        service.prefs().setOfferDone();
        boolean neverAsked = !ProjectsHomeCard.notificationsAskedBefore();
        ProjectsHomeCard.markNotificationsAnswered();
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
                && (neverAsked || ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS))) {
            try {
                mPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
                return;
            } catch (RuntimeException ignored) {
                // Settings below.
            }
        }
        Intent intent = Build.VERSION.SDK_INT >= 26
                ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName())
                : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.getPackageName(), null));
        try {
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            render();
        }
    }

    /** "Birlikte oyna" from a notification: the join question at once (or the other server's address). */
    private void answerPendingInvite(SocialService service) {
        if (mPendingInvite == null || !isResumed()) return;
        long now = SocialService.nowSeconds();
        for (Invite invite : service.state().invites(now)) {
            if (!mPendingInvite.equals(invite.id)) continue;
            mPendingInvite = null;
            connect(invite);
            return;
        }
        // Not there once the socket told the open invites (expired or taken back): nothing to ask.
        if (service.connection() == SocialService.Connection.LIVE && service.state().loaded()) mPendingInvite = null;
    }

    private void closeBanner(Banner banner) {
        if ("notify".equals(banner.kind)) {
            SocialService.get(requireContext()).prefs().setOfferDone();
            render();
        }
    }

    private void onPerson(Person person, View row) {
        FragmentActivity activity = requireActivity();
        switch (person.kind) {
            case "friend":
                if (person.profile != null) FriendCard.show(activity, person.profile.id);
                break;
            case "teaminvite":
                if (person.item instanceof TeamInvite) InviteCard.show(activity, (TeamInvite) person.item);
                break;
            case "in":
                if (person.item instanceof Request) InviteCard.request(activity, (Request) person.item);
                break;
            default:
                if (person.profile != null && SocialService.get(activity).state().isFriend(person.profile.id)) {
                    FriendCard.show(activity, person.profile.id);
                }
                break;
        }
    }

    private void onPrimary(Person person, final View button) {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        switch (person.kind) {
            case "friend":
            case "invite": {
                Invite invite = person.item instanceof Invite ? (Invite) person.item : null;
                if (invite != null) {
                    connect(invite);
                    return;
                }
                SocialUi.joinProjects(activity);
                break;
            }
            case "in": {
                final Profile from = person.profile;
                if (from == null) return;
                button.setEnabled(false);
                service.accept(from.id, new SocialService.Result<com.google.gson.JsonObject>() {
                    @Override
                    public void ok(com.google.gson.JsonObject value) {
                        Haptics.confirm(button);
                        SocialUi.toast(activity, activity.getString(R.string.projects_social_friend_added, from.name()));
                    }

                    @Override
                    public void failed(SocialProblem problem) {
                        button.setEnabled(true);
                        SocialUi.toastProblem(activity, problem, button);
                    }
                });
                break;
            }
            case "teaminvite": {
                final TeamInvite invite = (TeamInvite) person.item;
                if (invite == null) return;
                button.setEnabled(false);
                service.acceptTeamInvite(invite.team.id, new SocialService.Result<com.google.gson.JsonObject>() {
                    @Override
                    public void ok(com.google.gson.JsonObject value) {
                        Haptics.confirm(button);
                        BadgeActions.joined(activity, activity.getString(R.string.projects_crew_joined, invite.team.name), invite.team.id);
                        // TEAM4: the crew screen opens on the crew just joined, not the primary one.
                        Bundle args = new Bundle();
                        args.putString(CrewFragment.ARG_CREW, invite.team.id);
                        SocialUi.open(SocialFragment.this, CrewFragment.class, CrewFragment.TAG, args);
                    }

                    @Override
                    public void failed(SocialProblem problem) {
                        button.setEnabled(true);
                        SocialUi.toastProblem(activity, problem, button);
                    }
                });
                break;
            }
            default:
                break;
        }
    }

    /** FIX2: "Bağlan" for an invite: Projects, or the server the inviter shares. */
    private void connect(Invite invite) {
        FragmentActivity activity = requireActivity();
        if (invite.onProjects()) {
            ConnectFlow.projects(activity);
        } else if (invite.host != null) {
            ConnectFlow.server(activity, invite.host);
        }
    }

    /** FIX2: the menu a person row holds (what it is about decides its first rows). */
    private void personMenu(final Person person, View row) {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        switch (person.kind) {
            case "friend":
                if (person.profile != null) SocialMenuSheet.person(activity, person.profile, null, SocialMenuSheet.Place.FRIENDS,
                        gg.projects.mobile.social.SocialMenus.Pending.NONE, row);
                break;
            case "in":
                if (person.profile != null) SocialMenuSheet.person(activity, person.profile, null, SocialMenuSheet.Place.FRIENDS,
                        gg.projects.mobile.social.SocialMenus.Pending.FRIEND_REQUEST, row);
                break;
            case "out":
                if (person.profile != null) SocialMenuSheet.person(activity, person.profile, null, SocialMenuSheet.Place.FRIENDS,
                        gg.projects.mobile.social.SocialMenus.Pending.OUTGOING, row);
                break;
            case "invite": {
                final Invite invite = (Invite) person.item;
                if (invite == null) break;
                List<SocialMenuSheet.Own> own = new ArrayList<>();
                if (invite.onProjects()) {
                    own.add(new SocialMenuSheet.Own(R.drawable.projects_join_glyph, getString(R.string.projects_social_join),
                            R.color.projects_bone_100, () -> connect(invite)));
                } else if (invite.host != null) {
                    own.add(new SocialMenuSheet.Own(R.drawable.projects_join_glyph, getString(R.string.projects_connect),
                            R.color.projects_bone_100, () -> connect(invite)));
                }
                own.add(new SocialMenuSheet.Own(R.drawable.projects_ic_x, getString(R.string.projects_social_dismiss),
                        R.color.projects_bone_100, () -> {
                            service.dismissInvite(invite.id);
                            SocialNotifications.cancelInvite(activity, invite.fromId);
                        }));
                SocialMenuSheet.people(activity, null, own, java.util.Collections.singletonList(invite.fromId), null, row);
                break;
            }
            case "teaminvite": {
                final TeamInvite invite = (TeamInvite) person.item;
                if (invite == null) break;
                List<SocialMenuSheet.Own> own = new ArrayList<>();
                final View primary = row.findViewById(R.id.projects_person_action);
                final View secondary = row.findViewById(R.id.projects_person_secondary);
                own.add(new SocialMenuSheet.Own(R.drawable.projects_ic_check, getString(R.string.projects_social_accept),
                        R.color.projects_bone_100, primary::performClick));
                own.add(new SocialMenuSheet.Own(R.drawable.projects_ic_x, getString(R.string.projects_social_decline),
                        R.color.projects_bone_100, secondary::performClick));
                if (service.gate().reports) {
                    own.add(new SocialMenuSheet.Own(R.drawable.projects_ic_flag, getString(R.string.projects_report_crew),
                            R.color.projects_ash_300, () -> ReportDialog.crew(activity, invite.team, null)));
                }
                SocialMenuSheet.people(activity, invite.team.name, own, java.util.Collections.singletonList(invite.from.id), null, row);
                break;
            }
            default:
                break;
        }
    }

    private void onSecondary(Person person, final View button) {
        final FragmentActivity activity = requireActivity();
        final SocialService service = SocialService.get(activity);
        SocialService.Result<Void> quiet = new SocialService.Result<Void>() {
            @Override
            public void ok(Void value) {
            }

            @Override
            public void failed(SocialProblem problem) {
                button.setEnabled(true);
                SocialUi.toastProblem(activity, problem, button);
            }
        };
        switch (person.kind) {
            case "invite":
                if (person.item instanceof Invite) {
                    service.dismissInvite(((Invite) person.item).id);
                    SocialNotifications.cancelInvite(activity, ((Invite) person.item).fromId);
                }
                break;
            case "in":
                if (person.profile != null) {
                    button.setEnabled(false);
                    service.decline(person.profile.id, quiet);
                }
                break;
            case "out":
                if (person.profile != null) {
                    button.setEnabled(false);
                    service.cancelRequest(person.profile.id, quiet);
                }
                break;
            case "teaminvite":
                if (person.item instanceof TeamInvite) {
                    button.setEnabled(false);
                    service.declineTeamInvite(((TeamInvite) person.item).team.id, quiet);
                }
                break;
            default:
                break;
        }
    }

    // ---- adapter ----

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<Row> mRows = new ArrayList<>();
        private final Map<String, Long> mIds = new HashMap<>();

        /** Stable ids once, here: the adapter outlives the fragment's view (back stack), and RecyclerView refuses the
         *  change while a list is attached. */
        Adapter() {
            setHasStableIds(true);
        }

        void submit(final List<Row> rows) {
            final List<Row> old = mRows;
            mRows = rows;
            if (old.isEmpty()) {
                notifyDataSetChanged();
                return;
            }
            DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return old.size();
                }

                @Override
                public int getNewListSize() {
                    return rows.size();
                }

                @Override
                public boolean areItemsTheSame(int o, int n) {
                    return old.get(o).id.equals(rows.get(n).id);
                }

                @Override
                public boolean areContentsTheSame(int o, int n) {
                    return old.get(o).content.equals(rows.get(n).content);
                }
            }).dispatchUpdatesTo(this);
        }

        @Override
        public long getItemId(int position) {
            String id = mRows.get(position).id;
            Long stable = mIds.get(id);
            if (stable == null) {
                stable = (long) mIds.size();
                mIds.put(id, stable);
            }
            return stable;
        }

        @Override
        public int getItemViewType(int position) {
            return mRows.get(position).type;
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            View view;
            switch (viewType) {
                case T_ME:
                    view = inflater.inflate(R.layout.item_projects_social_me, parent, false);
                    Press.attach(view, Press.Style.CARD);
                    break;
                case T_BANNER:
                    view = inflater.inflate(R.layout.item_projects_social_banner, parent, false);
                    Button action = view.findViewById(R.id.projects_banner_action);
                    action.setBackground(ChamferDrawable.outlined(parent.getContext()));
                    Press.attach(action, Press.Style.BUTTON);
                    Press.attach(view.findViewById(R.id.projects_banner_close), Press.Style.ICON);
                    break;
                case T_CREW:
                case T_MESSAGES:
                    view = inflater.inflate(R.layout.item_projects_social_crew, parent, false);
                    Press.attach(view, Press.Style.CARD);
                    break;
                case T_HEADER:
                    view = inflater.inflate(R.layout.item_projects_social_header, parent, false);
                    break;
                case T_EMPTY:
                    view = inflater.inflate(R.layout.item_projects_social_empty, parent, false);
                    ContentTiles.show(view.findViewById(R.id.projects_social_empty_icon), PixelSprites.Sprite.FRIENDS, false);
                    break;
                case T_PERSON:
                default:
                    view = inflater.inflate(R.layout.item_projects_social_person, parent, false);
                    Press.attach(view, Press.Style.ROW);
                    Button primary = view.findViewById(R.id.projects_person_action);
                    primary.setBackground(ChamferDrawable.outlined(parent.getContext()));
                    Press.attach(primary, Press.Style.BUTTON);
                    Press.attach(view.findViewById(R.id.projects_person_secondary), Press.Style.ICON);
                    break;
            }
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Row row = mRows.get(position);
            View view = holder.itemView;
            Context context = view.getContext();
            switch (row.type) {
                case T_ME:
                    bindMe(view, (Me) row.data);
                    break;
                case T_BANNER:
                    bindBanner(view, (Banner) row.data);
                    break;
                case T_CREW:
                    bindCrew(view, (Crew) row.data);
                    break;
                case T_MESSAGES:
                    bindMessages(view, (ChatBook.Summary) row.data);
                    break;
                case T_HEADER:
                    ((TextView) view).setText((String) row.data);
                    break;
                case T_EMPTY:
                    View add = view.findViewById(R.id.projects_social_empty_add);
                    add.setOnClickListener(v -> Tools.swapFragment(requireActivity(), AddFriendFragment.class, AddFriendFragment.TAG, null));
                    break;
                case T_PERSON:
                default:
                    bindPerson(view, (Person) row.data, context);
                    break;
            }
        }
    }

    private void bindMe(View view, Me me) {
        AvatarView avatar = view.findViewById(R.id.projects_me_avatar);
        avatar.setProfile(me.profile);
        avatar.setSurfaceColor(ContextCompat.getColor(view.getContext(), R.color.projects_obsidian_900));
        avatar.setDot(SocialUi.statusDot(me.status));
        ((TextView) view.findViewById(R.id.projects_me_name)).setText(me.profile.name());
        // TEAM6: the player's own tag, a button to its card (where it comes off).
        CrewTagChip ownTag = view.findViewById(R.id.projects_me_tag);
        SocialModel.Badge own = gg.projects.mobile.social.BadgeRules.shown(SocialService.get(view.getContext()).gate(), me.profile.badge);
        ownTag.setBadgeOf(me.profile.id, own, CrewTagChip.Size.MD);
        if (own != null) ownTag.makeButton(own, v -> BadgeCard.show(requireActivity(), me.profile.id));
        ((TextView) view.findViewById(R.id.projects_me_status)).setText(SocialUi.statusLabel(me.status));
        view.setContentDescription(me.profile.name() + ", " + getString(SocialUi.statusLabel(me.status)) + ". "
                + getString(R.string.projects_social_status_change));
        view.setOnClickListener(v -> StatusSheet.show(requireActivity()));
        // SPAM1: only the player sees it; a tap says what is limited and until when.
        TextView limited = view.findViewById(R.id.projects_me_limited);
        final SocialModel.Limited limits = me.limited;
        boolean shown = limits != null && limits.active(SocialService.nowSeconds());
        limited.setVisibility(shown ? View.VISIBLE : View.GONE);
        if (shown) {
            Drawable lock = ContextCompat.getDrawable(view.getContext(), R.drawable.projects_ic_lock);
            if (lock != null) {
                lock = lock.mutate();
                lock.setTint(ContextCompat.getColor(view.getContext(), R.color.projects_gold_300));
                int size = Math.round(13 * getResources().getDisplayMetrics().density);
                lock.setBounds(0, 0, size, size);
                limited.setCompoundDrawablesRelative(lock, null, null, null);
            }
            // MOB-POLISH: a quiet chamfered chip, as every action (it was a pill).
            limited.setBackground(SocialChips.quiet(view.getContext(), 5));
            Press.attach(limited, Press.Style.BUTTON);
            limited.setOnClickListener(v -> AccountNotes.limited(requireActivity(), limits));
        }
    }

    /** "Mesajlar": the unread count in gold and the newest message; a tap opens the list. */
    private void bindMessages(View view, @Nullable ChatBook.Summary newest) {
        Context context = view.getContext();
        SocialService service = SocialService.get(context);
        view.findViewById(R.id.projects_crew_row_mark).setVisibility(View.GONE);
        ImageView icon = view.findViewById(R.id.projects_crew_row_icon);
        icon.setVisibility(View.VISIBLE);
        icon.setImageResource(R.drawable.projects_ic_chats);
        ((TextView) view.findViewById(R.id.projects_crew_row_name)).setText(R.string.projects_chat_title);
        TextView tag = view.findViewById(R.id.projects_crew_row_tag);
        TextView line = view.findViewById(R.id.projects_crew_row_line);
        int unread = service.chat().unreadTotal();
        if (unread > 0) {
            tag.setVisibility(View.VISIBLE);
            tag.setText(unread > 99 ? "99+" : String.valueOf(unread));
            tag.setBackgroundResource(R.drawable.projects_chat_unread_badge);
            tag.setTextColor(ContextCompat.getColor(context, R.color.projects_on_gold));
            tag.setContentDescription(context.getResources().getQuantityString(R.plurals.projects_chat_unread, unread, unread));
        } else {
            tag.setVisibility(View.GONE);
        }
        String me = service.state().myId();
        if (newest == null || newest.last == null || me == null) {
            line.setVisibility(View.GONE);
        } else {
            line.setVisibility(View.VISIBLE);
            // FIX2: a deleted newest message says so; CHAT2: a sticker says which.
            line.setText(ChatPreview.line(context, service.state(), newest.last, me, true));
            line.setTextColor(ContextCompat.getColor(context, unread > 0 ? R.color.projects_bone_100 : R.color.projects_ash_400));
        }
        view.setOnClickListener(v -> Tools.swapFragment(requireActivity(), ChatListFragment.class, ChatListFragment.TAG, null));
    }

    private void bindBanner(View view, final Banner banner) {
        ((TextView) view.findViewById(R.id.projects_banner_text)).setText(banner.text);
        ImageView icon = view.findViewById(R.id.projects_banner_icon);
        icon.setImageResource("notify".equals(banner.kind) ? R.drawable.projects_ic_bell : R.drawable.projects_ic_warning);
        Button action = view.findViewById(R.id.projects_banner_action);
        action.setText(banner.action);
        action.setOnClickListener(v -> onBanner(banner));
        View close = view.findViewById(R.id.projects_banner_close);
        close.setVisibility(banner.closable ? View.VISIBLE : View.GONE);
        close.setOnClickListener(v -> closeBanner(banner));
    }

    private void bindCrew(View view, @Nullable Crew crew) {
        final Team team = crew == null ? null : crew.team;
        Context context = view.getContext();
        CrewMarkView mark = view.findViewById(R.id.projects_crew_row_mark);
        View icon = view.findViewById(R.id.projects_crew_row_icon);
        TextView name = view.findViewById(R.id.projects_crew_row_name);
        TextView tag = view.findViewById(R.id.projects_crew_row_tag);
        TextView line = view.findViewById(R.id.projects_crew_row_line);
        SocialService service = SocialService.get(context);
        if (team == null) {
            mark.setVisibility(View.GONE);
            icon.setVisibility(View.VISIBLE);
            name.setText(R.string.projects_crew_row_none);
            tag.setVisibility(View.GONE);
            int invites = service.state().teamInvites().size();
            line.setVisibility(invites > 0 ? View.VISIBLE : View.GONE);
            line.setText(invites > 0 ? getString(R.string.projects_crew_invites) + " · " + invites : "");
        } else {
            mark.setVisibility(View.VISIBLE);
            icon.setVisibility(View.GONE);
            mark.setLook(team.emblem, team.color, team.frame);
            name.setText(team.name);
            tag.setVisibility(team.tag.isEmpty() ? View.GONE : View.VISIBLE);
            tag.setText(team.tag);
            CrewViews.tintTag(tag, team.color);
            long now = SocialService.nowSeconds();
            Call call = unanswered(service.state(), crew, now);
            Call own = own(service.state(), crew, now);
            if (call != null) {
                Profile caller = service.state().profile(call.from);
                line.setText(getString(R.string.projects_crew_call_theirs, caller == null ? getString(R.string.projects_crew_someone) : caller.name())
                        + " · " + gg.projects.mobile.social.SocialNotifications.callPlace(context, call.place(), call.serverHost));
                line.setTextColor(ContextCompat.getColor(context, R.color.projects_gold_300));
            } else if (own != null) {
                line.setText(R.string.projects_crew_call_mine);
                line.setTextColor(ContextCompat.getColor(context, R.color.projects_gold_300));
            } else if (service.gate().requests && !service.state().requests(team.id, now).isEmpty()) {
                // M21: join requests wait for the player's answer.
                int n = service.state().requests(team.id, now).size();
                line.setText(context.getResources().getQuantityString(R.plurals.projects_requests_count, n, n));
                line.setTextColor(ContextCompat.getColor(context, R.color.projects_gold_300));
            } else if (service.gate().plans && nextPlan(service.state(), crew, now) != null) {
                // M21: the crew's next plan today or tomorrow ("Plan · Bugün 22:00"; "Plan · Başladı" while it runs).
                SocialModel.Plan plan = nextPlan(service.state(), crew, now);
                boolean under = plan.underWay(service.state().hubNow(now));
                line.setText(getString(R.string.projects_plans_row, under ? getString(R.string.projects_plans_started_line)
                        : PlanViews.when(context, plan.at, now)));
                line.setTextColor(ContextCompat.getColor(context, under ? R.color.projects_jade_400 : R.color.projects_ash_300));
            } else {
                int online = service.state().onlineInCrew(team.id);
                String text = context.getResources().getQuantityString(R.plurals.projects_crew_online, online, online);
                // TEAM4: a crew the player silenced says so here too (the rail shows it with a bell).
                if (crew != null && crew.mute.mutedAt(service.state().hubNow(now))) text += " · " + getString(R.string.projects_crew_muted);
                line.setText(text);
                line.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
            }
            line.setVisibility(View.VISIBLE);
        }
        view.setOnClickListener(v -> {
            Bundle args = new Bundle();
            if (team != null) args.putString(CrewFragment.ARG_CREW, team.id);
            Tools.swapFragment(requireActivity(), CrewFragment.class, CrewFragment.TAG, args);
        });
        // FIX2: a crew's row holds the crew's menu.
        if (team != null) {
            final String crewId = team.id;
            SocialMenuSheet.attach(view, team.name, v -> {
                SocialMenuSheet.crew(requireActivity(), crewId, false, false, v);
                return true;
            });
        } else {
            SocialMenuSheet.detach(view);
        }
    }

    private void bindPerson(final View view, final Person person, Context context) {
        AvatarView avatar = view.findViewById(R.id.projects_person_avatar);
        CrewMarkView mark = view.findViewById(R.id.projects_person_mark);
        TextView name = view.findViewById(R.id.projects_person_name);
        TextView line = view.findViewById(R.id.projects_person_line);
        View star = view.findViewById(R.id.projects_person_star);
        View badge = view.findViewById(R.id.projects_person_badge);
        final Button primary = view.findViewById(R.id.projects_person_action);
        final ImageButton secondary = view.findViewById(R.id.projects_person_secondary);
        SocialService service = SocialService.get(context);
        SocialState state = service.state();
        badge.setVisibility(View.GONE);
        if (badge instanceof TextView) {
            badge.setBackgroundResource(R.drawable.projects_social_badge);
            ((TextView) badge).setTextColor(ContextCompat.getColor(context, R.color.projects_gold_300));
        }
        star.setVisibility(View.GONE);
        primary.setVisibility(View.GONE);
        primary.setEnabled(true);
        secondary.setVisibility(View.GONE);
        secondary.setEnabled(true);
        line.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
        avatar.setSurfaceColor(ContextCompat.getColor(context, R.color.projects_obsidian_950));
        boolean teamInvite = "teaminvite".equals(person.kind);
        avatar.setVisibility(teamInvite ? View.GONE : View.VISIBLE);
        mark.setVisibility(teamInvite ? View.VISIBLE : View.GONE);
        if (!teamInvite) avatar.setProfile(person.profile);
        avatar.setDot(AvatarView.Dot.NONE);
        avatar.setDimmed(false);
        // TEAM6: a friend's tag before their name (the other rows show none).
        CrewTagChip tagChip = view.findViewById(R.id.projects_person_tag);
        if (!"friend".equals(person.kind)) {
            tagChip.setBadgeOf(null, null, CrewTagChip.Size.MD);
            name.setContentDescription(null);
        }
        switch (person.kind) {
            case "friend": {
                Friend friend = (Friend) person.item;
                Presence presence = state.presence(friend.profile.id);
                name.setText(friend.profile.name());
                SocialModel.Badge worn = gg.projects.mobile.social.BadgeRules.shown(service.gate(), friend.profile.badge);
                tagChip.setBadgeOf(friend.profile.id, worn, CrewTagChip.Size.MD);
                CrewTagChip.labelName(name, worn);
                line.setText(SocialUi.presenceLine(context, presence));
                if (presence.onProjects()) line.setTextColor(ContextCompat.getColor(context, R.color.projects_jade_400));
                avatar.setDot(SocialUi.dot(presence));
                avatar.setDimmed(!presence.online());
                star.setVisibility(service.prefs().favourites().contains(friend.profile.id) ? View.VISIBLE : View.GONE);
                ChatBook book = service.chat().available() ? service.chat().book() : null;
                String me = state.myId();
                int unread = book == null || me == null ? 0 : book.unread(ChatConv.direct(me, friend.profile.id));
                if (unread > 0) {
                    TextView count = (TextView) badge;
                    count.setVisibility(View.VISIBLE);
                    count.setText(unread > 99 ? "99+" : String.valueOf(unread));
                    count.setBackgroundResource(R.drawable.projects_chat_unread_badge);
                    count.setTextColor(ContextCompat.getColor(context, R.color.projects_on_gold));
                    count.setContentDescription(context.getResources().getQuantityString(R.plurals.projects_chat_unread, unread, unread));
                }
                if (presence.onProjects()) {
                    primary.setVisibility(View.VISIBLE);
                    primary.setText(R.string.projects_social_join);
                }
                break;
            }
            case "invite": {
                Invite invite = (Invite) person.item;
                name.setText(person.profile == null ? "" : person.profile.name());
                String where = invite.onProjects() ? getString(R.string.projects_social_server_projects)
                        : invite.host != null ? invite.host : null;
                line.setText(where == null ? getString(R.string.projects_social_invited_you)
                        : getString(R.string.projects_social_invited_you) + " · " + where);
                line.setTextColor(ContextCompat.getColor(context, R.color.projects_gold_300));
                avatar.setDot(person.profile == null ? AvatarView.Dot.NONE : SocialUi.dot(state.presence(person.profile.id)));
                if (invite.onProjects()) {
                    primary.setVisibility(View.VISIBLE);
                    primary.setText(R.string.projects_social_join);
                } else if (invite.host != null) {
                    // FIX2: straight into the inviter's server ("Bağlan"); the address can still be copied there.
                    primary.setVisibility(View.VISIBLE);
                    primary.setText(R.string.projects_connect);
                }
                secondary.setVisibility(View.VISIBLE);
                secondary.setImageResource(R.drawable.projects_ic_x);
                secondary.setContentDescription(getString(R.string.projects_social_dismiss));
                break;
            }
            case "teaminvite": {
                TeamInvite invite = (TeamInvite) person.item;
                mark.setLook(invite.team.emblem, invite.team.color, invite.team.frame);
                name.setText(invite.team.name);
                line.setText(getString(R.string.projects_crew_invited_by, invite.from.name()));
                primary.setVisibility(View.VISIBLE);
                primary.setText(R.string.projects_social_accept);
                secondary.setVisibility(View.VISIBLE);
                secondary.setImageResource(R.drawable.projects_ic_x);
                secondary.setContentDescription(getString(R.string.projects_social_decline));
                break;
            }
            case "in":
                name.setText(person.profile == null ? "" : person.profile.name());
                line.setText(R.string.projects_social_wants_to_add);
                primary.setVisibility(View.VISIBLE);
                primary.setText(R.string.projects_social_accept);
                secondary.setVisibility(View.VISIBLE);
                secondary.setImageResource(R.drawable.projects_ic_x);
                secondary.setContentDescription(getString(R.string.projects_social_decline));
                break;
            case "out":
                name.setText(person.profile == null ? "" : person.profile.name());
                line.setText(R.string.projects_social_waiting_reply);
                secondary.setVisibility(View.VISIBLE);
                secondary.setImageResource(R.drawable.projects_ic_x);
                secondary.setContentDescription(getString(R.string.projects_social_cancel_request));
                break;
            default:
                break;
        }
        primary.setOnClickListener(v -> onPrimary(person, primary));
        secondary.setOnClickListener(v -> onSecondary(person, secondary));
        view.setOnClickListener(v -> onPerson(person, view));
        // FIX2: every person row holds the shared menu.
        CharSequence label = name.getText();
        SocialMenuSheet.attach(view, label, v -> {
            personMenu(person, view);
            return true;
        });
    }
}
