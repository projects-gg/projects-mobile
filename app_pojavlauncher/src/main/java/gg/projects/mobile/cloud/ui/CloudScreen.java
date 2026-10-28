package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;

import gg.projects.mobile.cloud.CloudModel;
import gg.projects.mobile.cloud.CloudProblem;
import gg.projects.mobile.cloud.CloudService;
import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.social.ui.SocialUi;
import gg.projects.mobile.ui.Haptics;
import gg.projects.mobile.ui.ScreenHeader;

/**
 * What every 7/24 Bulut screen shares: the header, the scrolling content and the optional rows above and below it,
 * the service's changes while on screen, the account the screen is about, and leaving by itself when that account or
 * the session goes (the list then says why). A screen with unsaved changes takes back over with {@link #holdBack}.
 */
public abstract class CloudScreen extends Fragment implements CloudService.Listener {
    public static final String ARG_ACCOUNT = "gg.projects.mobile.cloud.ACCOUNT";

    protected CloudService mService;
    protected ScreenHeader mHeader;
    @Nullable
    protected FrameLayout mTop;
    @Nullable
    protected ScrollView mScroll;
    @Nullable
    protected LinearLayout mContent;
    @Nullable
    protected FrameLayout mBottom;
    private OnBackPressedCallback mBack;
    private boolean mStarted;

    protected CloudScreen() {
        super(R.layout.fragment_projects_cloud_page);
    }

    protected CloudScreen(@LayoutRes int layout) {
        super(layout);
    }

    public static Bundle args(String account) {
        Bundle args = new Bundle();
        args.putString(ARG_ACCOUNT, account);
        return args;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mService = CloudService.get(requireContext());
        mHeader = view.findViewById(R.id.projects_cloud_header);
        mTop = view.findViewById(R.id.projects_cloud_top);
        mScroll = view.findViewById(R.id.projects_cloud_scroll);
        mContent = view.findViewById(R.id.projects_cloud_content);
        mBottom = view.findViewById(R.id.projects_cloud_bottom);
        mBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                onBack();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), mBack);
        created(view);
    }

    /** The screen's own views (after the shared ones are found). */
    protected void created(View view) {}

    @Override
    public void onResume() {
        super.onResume();
        mService.addListener(this);
        mStarted = false;
        if (!stillValid()) return;
        render();
        maybeStart();
    }

    @Override
    public void onPause() {
        super.onPause();
        mService.removeListener(this);
    }

    @Override
    public void onCloudChanged() {
        if (!isResumed() || getView() == null) return;
        if (!stillValid()) return;
        render();
        maybeStart();
    }

    /** Once per visit, as soon as the account is known (a screen opened from a notice may come before the list). */
    private void maybeStart() {
        if (mStarted) return;
        if (needsAccount() && account() == null) {
            mService.refresh(false);
            return;
        }
        mStarted = true;
        started();
    }

    /** What a visit starts: reads, the live watch (called once per visit, with the account known). */
    protected void started() {}

    private boolean mNamesAsked;

    /**
     * Reads, once a visit, what item names need: the AFK+ account's settings (its custom items' own names and its
     * server's Minecraft version) and that version's item data (the game's own names in the player's language, from our
     * CDN or the phone's copy); {@link #namesArrived()} redraws.
     */
    protected void ensureItemNames() {
        final CloudModel.Account a = account();
        if (mNamesAsked || a == null || !a.plus()) return;
        mNamesAsked = true;
        CloudModel.PlusDoc known = mService.lastPlus(a.id);
        if (known != null && mService.cachedItemData(known.server.mc) != null) return;
        mService.plus(a.id, new CloudService.Result<CloudModel.PlusDoc>() {
            @Override
            public void ok(CloudModel.PlusDoc doc) {
                if (alive()) namesArrived();
                mService.itemData(doc.server.mc, new CloudService.Result<gg.projects.mobile.cloud.items.ItemData>() {
                    @Override
                    public void ok(gg.projects.mobile.cloud.items.ItemData value) {
                        if (alive()) namesArrived();
                    }

                    @Override
                    public void failed(CloudProblem problem) {}
                });
            }

            @Override
            public void failed(CloudProblem problem) {
                mNamesAsked = false;
            }
        });
    }

    protected void namesArrived() {}

    /** An item's name in the player's language for this screen's account ({@code custom:<k>} by the player's own name). */
    protected String itemName(String ref) {
        return mService.itemName(accountId(), ref, lang());
    }

    /** Draws the screen from the service's state (called often: a screen keeps what did not change). */
    protected abstract void render();

    /** Whether the screen is about one account (it leaves when that account goes). */
    protected boolean needsAccount() {
        return true;
    }

    private boolean stillValid() {
        if (!needsAccount()) return true;
        if (!mService.signedIn() || !mService.available()) {
            leaveNow();
            return false;
        }
        if (mService.load() == CloudService.Load.LOADED && account() == null) {
            leaveNow();
            return false;
        }
        return true;
    }

    @Nullable
    protected String accountId() {
        Bundle args = getArguments();
        return args == null ? null : args.getString(ARG_ACCOUNT);
    }

    @Nullable
    protected CloudModel.Account account() {
        String id = accountId();
        return id == null ? null : mService.account(id);
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

    protected void openFor(Class<? extends Fragment> screen, String account) {
        open(screen, args(account));
    }

    /** A short line at the bottom of the screen. */
    protected void toast(CharSequence text) {
        SocialUi.toast(getContext(), text.toString());
    }

    /** What went wrong, in the app's words (no codes), with the reject tick on the view that asked. */
    protected void problem(CloudProblem problem, @Nullable View from) {
        Context c = getContext();
        if (c == null) return;
        if (from != null) Haptics.reject(from);
        SocialUi.toastLong(c, CloudWords.problem(c, problem));
    }

    /** Whether an answer that comes late still has a screen to land on. */
    protected boolean alive() {
        return isAdded() && getView() != null;
    }

    protected Context ctx() {
        return requireContext();
    }

    protected String lang() {
        return CloudWords.lang(requireContext());
    }

    /** Clears the content for a full redraw. */
    protected LinearLayout clear() {
        LinearLayout content = mContent;
        if (content != null) content.removeAllViews();
        return content;
    }
}
