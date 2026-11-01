package gg.projects.mobile.afk.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.List;

import gg.projects.mobile.afk.api.AfkAlert;
import gg.projects.mobile.afk.api.AfkLine;
import gg.projects.mobile.afk.api.AfkListener;
import gg.projects.mobile.afk.api.AfkStatus;
import gg.projects.mobile.afk.api.AfkWatch;
import gg.projects.mobile.afk.api.PhoneAfk;
import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.social.ui.BottomPanel;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.ui.ScreenHeader;

/**
 * What the phone AFK screens share (7/24 Bulut's {@code CloudScreen}, without its service): the header, the scrolling
 * content and the bar under it, the accounts watched while the screen is on screen (statuses, lines and alerts on the
 * main thread; nothing is watched while it is away), leaving by itself when the feature goes, and back held while
 * there are unsaved changes ({@link #holdBack}).
 */
public abstract class AfkScreen extends Fragment implements AfkListener {
    protected PhoneAfk mAfk;
    protected ScreenHeader mHeader;
    @Nullable
    protected ScrollView mScroll;
    @Nullable
    protected LinearLayout mContent;
    @Nullable
    protected FrameLayout mBottom;
    @Nullable
    private AfkWatch mWatch;
    private OnBackPressedCallback mBack;
    /** The sheets this screen opened: they close with its view. */
    private final List<BottomPanel> mPanels = new ArrayList<>();
    /** The notification permission, asked at the first start on Android 13+ (the battery's question follows). */
    private final ActivityResultLauncher<String> mNotifyAsk = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (alive()) AfkBattery.afterNotifications(this);
            });

    protected AfkScreen() {
        super(R.layout.fragment_projects_afk_page);
    }

    protected AfkScreen(@LayoutRes int layout) {
        super(layout);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mAfk = PhoneAfk.get(requireContext());
        mHeader = view.findViewById(R.id.projects_afk_header);
        mScroll = view.findViewById(R.id.projects_afk_scroll);
        mContent = view.findViewById(R.id.projects_afk_content);
        mBottom = view.findViewById(R.id.projects_afk_bottom);
        mBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                onBack();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), mBack);
        created(view, savedInstanceState);
    }

    /** The screen's own views (after the shared ones are found). */
    protected void created(View view, @Nullable Bundle savedInstanceState) {}

    @Override
    public void onResume() {
        super.onResume();
        if (!AfkUi.available(requireContext())) {
            // Studio turned the feature off (its running accounts stopped): nothing to show here any more.
            leaveNow();
            return;
        }
        if (mWatch == null) mWatch = mAfk.watch(this);
        AfkBattery.onResume(this);
    }

    /** A start the player made went ahead: the phone's questions not asked yet (notifications, battery), once. */
    protected void afterStart() {
        AfkBattery.afterStart(this, mNotifyAsk);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mWatch != null) {
            mWatch.close();
            mWatch = null;
        }
    }

    @Override
    public void onDestroyView() {
        for (BottomPanel panel : mPanels) panel.dialog().dismiss();
        mPanels.clear();
        super.onDestroyView();
    }

    // ---- AfkListener: screens override what they need ----

    @Override
    public void onStatuses(List<AfkStatus> all) {}

    @Override
    public void onStatus(AfkStatus status) {}

    @Override
    public void onBacklog(String accountId, List<AfkLine> lines) {}

    @Override
    public void onLine(AfkLine line) {}

    @Override
    public void onAlert(AfkAlert alert) {
        AfkSeen.note(alert);
    }

    // ---- shared ----

    /** Keeps a sheet to close it with this screen's view; returns it. */
    protected BottomPanel keep(BottomPanel panel) {
        for (java.util.Iterator<BottomPanel> it = mPanels.iterator(); it.hasNext(); ) if (!it.next().showing()) it.remove();
        mPanels.add(panel);
        return panel;
    }

    /** Back is the screen's own while it holds it (unsaved changes ask first). */
    protected void holdBack(boolean hold) {
        if (mBack != null) mBack.setEnabled(hold);
    }

    protected void onBack() {
        leaveNow();
    }

    protected void leaveNow() {
        if (mBack != null) mBack.setEnabled(false);
        SocialUi.leave(this);
    }

    protected void open(Class<? extends Fragment> screen, @Nullable Bundle args) {
        SocialUi.open(this, screen, screen.getSimpleName(), args);
    }

    /** A short line at the bottom of the screen (the app's own toast). */
    protected void toast(CharSequence text) {
        SocialUi.toast(getContext(), text.toString());
    }

    /** Whether an answer that comes late still has a screen to land on. */
    protected boolean alive() {
        return isAdded() && getView() != null;
    }

    protected Context ctx() {
        return requireContext();
    }

    protected LinearLayout clear() {
        LinearLayout content = mContent;
        if (content != null) content.removeAllViews();
        return content;
    }

    /**
     * A block arriving on screen the first time: it fades in and rises 10 dp (the desktop's cards, 350 ms on the
     * entrance curve), 40 ms after the one before it, at most 8 in a row. With animations removed it is simply there.
     */
    protected static void enter(View view, int index) {
        if (!Motion.enabled(view.getContext())) return;
        view.setAlpha(0f);
        view.setTranslationY(AfkUi.dp(view.getContext(), 10));
        view.animate().alpha(1f).translationY(0f).setStartDelay(Math.min(index, 8) * 40L).setDuration(350)
                .setInterpolator(Motion.EASE_OUT).start();
    }
}
