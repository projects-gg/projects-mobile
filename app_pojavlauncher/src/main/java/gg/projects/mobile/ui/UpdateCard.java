package gg.projects.mobile.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;

import java.util.List;

import gg.projects.mobile.cloud.ui.CloudUi;
import gg.projects.mobile.language.LanguageManager;
import gg.projects.mobile.update.AppUpdates;
import gg.projects.mobile.update.UpdateException;
import gg.projects.mobile.update.UpdateManifest;

/**
 * APK-UPD: the home card's update block (the owner's card, 30 October 2026): "Güncelleme var", the version
 * and the download's size, the release notes, and three buttons: "Güncelle" (the block's one framed action),
 * "Kapat" (away for 2 hours) and "7 gün sonra hatırlat" (7 days). While "Güncelle" runs the block shows the
 * download's bar and "%42" with "Durdur", then that it checks and installs (the bar full and still: nothing
 * moves without end). A try that stopped says why in one sentence under the notes.
 */
public final class UpdateCard implements AppUpdates.Listener {
    /** Release note lines the card shows (each at most two lines, as the patch notes tile). */
    static final int NOTE_LINES = 6;

    private final Fragment mFragment;
    private final ViewGroup mSceneRoot;
    private final View mBlock;
    private final TextView mTitle;
    private final TextView mMeta;
    private final LinearLayout mNotes;
    private final ProgressBar mProgress;
    private final TextView mError;
    private final View mActions;
    private final Button mGo;
    private final Button mClose;
    private final Button mLater;
    private AppUpdates.Phase mPhase = AppUpdates.Phase.HIDDEN;
    /** The block's structure on screen (phase, version, sentence): a change animates the card's layout. */
    @Nullable
    private String mShown;
    @Nullable
    private String mNotesShown;

    public UpdateCard(Fragment fragment, View root, ViewGroup sceneRoot) {
        mFragment = fragment;
        mSceneRoot = sceneRoot;
        mBlock = root.findViewById(R.id.projects_update);
        mTitle = root.findViewById(R.id.projects_update_title);
        mMeta = root.findViewById(R.id.projects_update_meta);
        mNotes = root.findViewById(R.id.projects_update_notes);
        mProgress = root.findViewById(R.id.projects_update_progress);
        mError = root.findViewById(R.id.projects_update_error);
        mActions = root.findViewById(R.id.projects_update_actions);
        mGo = root.findViewById(R.id.projects_update_go);
        mClose = root.findViewById(R.id.projects_update_close);
        mLater = root.findViewById(R.id.projects_update_later);
        Context context = root.getContext();
        CloudUi.warnMark(mError, true);
        // The quiet words carry no start padding: the gap is the room after the frame and between the rows.
        if (mActions instanceof gg.projects.mobile.social.ui.WrapLayout) {
            ((gg.projects.mobile.social.ui.WrapLayout) mActions).setGap(Math.round(12 * context.getResources().getDisplayMetrics().density));
        }
        mGo.setBackground(ChamferDrawable.outlined(context));
        Press.attach(mGo, Press.Style.BUTTON);
        Press.attach(mClose, Press.Style.BUTTON);
        Press.attach(mLater, Press.Style.BUTTON);
        mGo.setOnClickListener(v -> {
            FragmentActivity activity = mFragment.getActivity();
            if (activity != null && mPhase == AppUpdates.Phase.OFFER) AppUpdates.update(activity);
        });
        // "Kapat" while offered, "Durdur" while it downloads.
        mClose.setOnClickListener(v -> {
            if (mPhase == AppUpdates.Phase.DOWNLOADING) AppUpdates.stop();
            else if (mPhase == AppUpdates.Phase.OFFER) AppUpdates.close(v.getContext());
        });
        mLater.setOnClickListener(v -> {
            if (mPhase == AppUpdates.Phase.OFFER) AppUpdates.remindLater(v.getContext());
        });
    }

    public void onResume() {
        FragmentActivity activity = mFragment.getActivity();
        if (activity == null) return;
        AppUpdates.addListener(this);
        AppUpdates.onHomeResumed(activity);
    }

    public void onPause() {
        AppUpdates.removeListener(this);
        FragmentActivity activity = mFragment.getActivity();
        if (activity != null) AppUpdates.onHomePaused(activity);
    }

    @Override
    public void onUpdateState(AppUpdates.State state) {
        if (!mFragment.isAdded()) return;
        Context context = mBlock.getContext();
        UpdateManifest manifest = state.manifest;
        boolean visible = state.phase != AppUpdates.Phase.HIDDEN && manifest != null;
        String shown = visible ? state.phase + "|" + manifest.versionCode + "|" + state.error : null;
        if (mShown == null ? shown != null : !mShown.equals(shown)) CardMotion.beginLayoutChange(mSceneRoot);
        mShown = shown;
        mPhase = visible ? state.phase : AppUpdates.Phase.HIDDEN;
        if (!visible) {
            show(mBlock, false);
            return;
        }
        long megabytes = Math.max(1, Math.round(manifest.size / 1_000_000.0));
        switch (state.phase) {
            case DOWNLOADING:
                setText(mTitle, context.getString(R.string.projects_update_downloading));
                setText(mMeta, context.getString(R.string.projects_update_percent, state.percent()));
                mProgress.setProgress(state.percent());
                break;
            case VERIFYING:
                setText(mTitle, context.getString(R.string.projects_update_checking));
                setText(mMeta, context.getString(R.string.projects_update_meta, manifest.versionName, megabytes));
                mProgress.setProgress(100);
                break;
            case INSTALLING:
                setText(mTitle, context.getString(R.string.projects_update_installing));
                setText(mMeta, context.getString(R.string.projects_update_installing_body));
                mProgress.setProgress(100);
                break;
            default:
                setText(mTitle, context.getString(R.string.projects_update_title));
                setText(mMeta, context.getString(R.string.projects_update_meta, manifest.versionName, megabytes));
                break;
        }
        bindNotes(context, manifest);
        boolean offer = state.phase == AppUpdates.Phase.OFFER;
        boolean downloading = state.phase == AppUpdates.Phase.DOWNLOADING;
        show(mProgress, !offer);
        int error = offer && state.error != null ? errorText(state.error) : 0;
        if (error != 0) setText(mError, context.getString(error));
        show(mError, error != 0);
        show(mActions, offer || downloading);
        show(mGo, offer);
        show(mLater, offer);
        setText(mClose, context.getString(downloading ? R.string.projects_update_stop : R.string.projects_update_close));
        show(mBlock, true);
    }

    private void bindNotes(Context context, UpdateManifest manifest) {
        boolean turkish = LanguageManager.isTurkish(context);
        List<String> lines = manifest.notes(turkish);
        String key = manifest.versionCode + "|" + turkish;
        if (key.equals(mNotesShown)) return;
        mNotesShown = key;
        mNotes.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(context);
        for (int i = 0; i < lines.size() && i < NOTE_LINES; i++) {
            View row = inflater.inflate(R.layout.item_projects_patch_line, mNotes, false);
            TextView text = row.findViewById(R.id.projects_patch_line_text);
            // A release note wraps whole (TASTE-2: never cut mid-word at big text); the channel bounds its length.
            text.setMaxLines(Integer.MAX_VALUE);
            text.setEllipsize(null);
            text.setText(lines.get(i));
            mNotes.addView(row);
        }
        show(mNotes, !lines.isEmpty());
    }

    /** The one sentence for a try that stopped (none for one the player stopped). */
    public static int errorText(UpdateException.Kind kind) {
        switch (kind) {
            case NETWORK:
                return R.string.projects_update_error_network;
            case SPACE:
                return R.string.projects_update_error_space;
            case CORRUPT:
                return R.string.projects_update_error_corrupt;
            case FOREIGN:
                return R.string.projects_update_error_foreign;
            case GAME_RUNNING:
                return R.string.projects_update_error_game;
            case BUSY:
                return R.string.tasks_ongoing;
            case BLOCKED:
                return R.string.projects_update_error_blocked;
            case INCOMPATIBLE:
                return R.string.projects_update_error_incompatible;
            case INSTALL_FAILED:
                return R.string.projects_update_error_failed;
            case CANCELLED:
                return 0;
            default:
                // The channel or the file is not right now (gone, malformed, wrongly signed): later.
                return R.string.projects_update_error_later;
        }
    }

    private static void setText(TextView view, CharSequence text) {
        if (!android.text.TextUtils.equals(view.getText(), text)) view.setText(text);
    }

    private static void show(View view, boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (view.getVisibility() != visibility) view.setVisibility(visibility);
    }
}
