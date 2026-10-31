package gg.projects.mobile.mods;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import net.kdt.pojavlaunch.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import gg.projects.mobile.content.ContentException;
import gg.projects.mobile.content.ItemResolver;
import gg.projects.mobile.content.ModsSelection;
import gg.projects.mobile.content.PackManifest;
import gg.projects.mobile.content.PackSelection;
import gg.projects.mobile.content.PackState;
import gg.projects.mobile.content.PackSyncApplier;
import gg.projects.mobile.content.PackSyncPlanner;
import gg.projects.mobile.content.SodiumSupport;
import gg.projects.mobile.ui.ChamferDrawable;
import gg.projects.mobile.ui.ProjectsUi;

/**
 * The mod screen ("Modlar"): the official pack's recommended mods by category with a switch
 * each, "Yeni" for mods added since the player last looked, why a mod is on although it was not
 * picked ("X için gerekli"), and an apply bar with what will be downloaded and removed, then
 * progress. Mirrors the desktop mod center's "Önerilenler".
 */
public class ProjectsModsFragment extends Fragment implements ModsApply.Listener {
    public static final String TAG = "ProjectsModsFragment";

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final long PREVIEW_DELAY_MS = 250;
    private static final int MAX_COLUMN_DP = 720;
    private static final String STATE_CHOICES = "choices";
    private static final String[] CATEGORY_ORDER = {
            PackManifest.CATEGORY_PERFORMANCE, PackManifest.CATEGORY_VISUAL, PackManifest.CATEGORY_INTERFACE,
            PackManifest.CATEGORY_GAMEPLAY, PackManifest.CATEGORY_OTHER};

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Adapter mAdapter = new Adapter();

    private RecyclerView mList;
    private View mSkeleton;
    private View mMessage;
    private TextView mMessageTitle;
    private TextView mMessageBody;
    private Button mRetry;
    private TextView mCaption;
    private View mBar;
    private TextView mBarText;
    private ProgressBar mBarProgress;
    private Button mDiscard;
    private Button mApply;
    private OnBackPressedCallback mBackCallback;

    private ModsModel mModel;
    /** Pending choices (item id to selected), starting from the recorded ones. */
    private final Map<String, Boolean> mChoices = new HashMap<>();
    private final Set<String> mRevert = new HashSet<>();
    private Set<String> mEnabled = new HashSet<>();
    /**
     * The player switched on a Sodium item this phone holds off: the family shows (and applying
     * installs) the recorded choices again, and the hold is lifted on apply.
     */
    private boolean mHoldLifted;
    @Nullable private PackSelection mSelection;
    /**
     * TABLET-M: the switches the player changed before a rotation or a resize (the launcher is made again): put back
     * over the pack's own choices when the list is read again, so a change waiting for "Uygula" is not lost.
     */
    @Nullable private Bundle mRestoredChoices;
    @Nullable private PackSyncPlanner.Summary mPreview;
    private int mPreviewToken;
    private boolean mBarShown;
    private boolean mApplying;
    private PackSyncApplier.Phase mApplyPhase;
    private float mApplyFraction = -1;
    private boolean mAnimateRows;
    private ObjectAnimator mProgressAnimator;

    public ProjectsModsFragment() {
        super(R.layout.fragment_projects_mods);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Context context = view.getContext();
        mList = view.findViewById(R.id.projects_mods_list);
        mSkeleton = view.findViewById(R.id.projects_mods_skeleton);
        mMessage = view.findViewById(R.id.projects_mods_message);
        mMessageTitle = view.findViewById(R.id.projects_mods_message_title);
        mMessageBody = view.findViewById(R.id.projects_mods_message_body);
        mRetry = view.findViewById(R.id.projects_mods_retry);
        mCaption = view.findViewById(R.id.projects_mods_caption);
        mBar = view.findViewById(R.id.projects_mods_bar);
        mBarText = view.findViewById(R.id.projects_mods_bar_text);
        mBarProgress = view.findViewById(R.id.projects_mods_bar_progress);
        mDiscard = view.findViewById(R.id.projects_mods_discard);
        mApply = view.findViewById(R.id.projects_mods_apply);

        // Wide screens (landscape, tablets): a centered column keeps names, notes and switches together.
        if (getResources().getConfiguration().screenWidthDp > MAX_COLUMN_DP) {
            View column = view.findViewById(R.id.projects_mods_column);
            column.getLayoutParams().width = dp(context, MAX_COLUMN_DP);
            column.requestLayout();
        }

        mList.setLayoutManager(new LinearLayoutManager(context));
        mList.setAdapter(mAdapter);
        DefaultItemAnimator animator = new DefaultItemAnimator();
        // Rows change in place (switch, notes); only inserted and removed banners animate.
        animator.setSupportsChangeAnimations(false);
        mList.setItemAnimator(animator);
        mList.setPadding(mList.getPaddingLeft(), mList.getPaddingTop(), mList.getPaddingRight(), dp(context, 104));

        mApply.setBackground(ChamferDrawable.primary(context));
        mRetry.setBackground(ChamferDrawable.secondary(context));
        view.findViewById(R.id.projects_mods_back).setOnClickListener(v -> requireActivity().getOnBackPressedDispatcher().onBackPressed());
        mRetry.setOnClickListener(v -> load(false));
        mDiscard.setOnClickListener(v -> discard());
        mApply.setOnClickListener(v -> {
            if (mSelection != null) apply(mSelection);
        });

        mBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                confirmLeave();
            }
        };
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), mBackCallback);
        load(false);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) mRestoredChoices = savedInstanceState.getBundle(STATE_CHOICES);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mModel == null || mSelection == null) {
            // Not read yet: what came back before is still waiting for the list.
            if (mRestoredChoices != null) outState.putBundle(STATE_CHOICES, mRestoredChoices);
            return;
        }
        Bundle choices = new Bundle();
        ArrayList<String> on = new ArrayList<>();
        ArrayList<String> off = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : mChoices.entrySet()) (Boolean.TRUE.equals(e.getValue()) ? on : off).add(e.getKey());
        choices.putStringArrayList("on", on);
        choices.putStringArrayList("off", off);
        choices.putStringArrayList("revert", new ArrayList<>(mRevert));
        choices.putBoolean("held", mHoldLifted);
        outState.putBundle(STATE_CHOICES, choices);
    }

    @Override
    public void onResume() {
        super.onResume();
        ModsApply.setListener(this);
        if (!ModsApply.isRunning() && mApplying) {
            // Finished while the screen was away: show the result of it.
            mApplying = false;
            load(true);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        ModsApply.setListener(null);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mPreviewToken++;
        mMain.removeCallbacksAndMessages(null);
    }

    /** Slide up and fade in; leave a little faster. Animators follow the system's animation scale. */
    @Nullable
    @Override
    public Animator onCreateAnimator(int transit, boolean enter, int nextAnim) {
        View view = getView();
        if (view == null || !Motion.enabled(view.getContext())) return null;
        float distance = dp(view.getContext(), 28);
        AnimatorSet set = new AnimatorSet();
        if (enter) {
            set.playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, 0f, 1f),
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, distance, 0f));
            set.setDuration(Motion.ENTER_MS);
            set.setInterpolator(Motion.EASE_OUT);
        } else {
            set.playTogether(ObjectAnimator.ofFloat(view, View.ALPHA, 1f, 0f),
                    ObjectAnimator.ofFloat(view, View.TRANSLATION_Y, 0f, distance * 0.6f));
            set.setDuration(Motion.EXIT_MS);
            set.setInterpolator(Motion.EASE_IN);
        }
        return set;
    }

    // ---- loading ----

    private void load(final boolean silent) {
        if (!silent) {
            mMessage.setVisibility(View.GONE);
            mSkeleton.setVisibility(View.VISIBLE);
            mSkeleton.setAlpha(1f);
            mList.setVisibility(View.INVISIBLE);
        }
        final Context app = requireContext().getApplicationContext();
        WORKER.execute(() -> {
            final ModsModel model = ModsModel.load(app);
            mMain.post(() -> onModel(model, silent));
        });
    }

    private void onModel(ModsModel model, boolean silent) {
        if (!isAdded() || getView() == null) return;
        mModel = model;
        mChoices.clear();
        mRevert.clear();
        mPreview = null;
        mPreviewToken++;
        mHoldLifted = false;
        if (model.plan != null) mChoices.putAll(ModsSelection.choicesOf(model.plan.items));
        if (mRestoredChoices != null && model.plan != null) {
            // TABLET-M: the player's changes from before the screen was made again, over the pack's choices.
            Bundle kept = mRestoredChoices;
            ArrayList<String> on = kept.getStringArrayList("on");
            ArrayList<String> off = kept.getStringArrayList("off");
            ArrayList<String> revert = kept.getStringArrayList("revert");
            if (on != null) for (String id : on) mChoices.put(id, true);
            if (off != null) for (String id : off) mChoices.put(id, false);
            if (revert != null) mRevert.addAll(revert);
            mHoldLifted = kept.getBoolean("held", false);
        }
        mRestoredChoices = null;

        if (model.status == ModsModel.Status.READY && model.manifest != null) {
            PackManifest manifest = model.manifest;
            mCaption.setText(getString(R.string.projects_mods_pack_caption, displayName(manifest), manifest.versionId, manifest.minecraftVersion()));
            fadeIn(mCaption);
            mMessage.setVisibility(View.GONE);
            mAnimateRows = !silent && Motion.enabled(requireContext());
            recompute();
            showList();
        } else {
            mCaption.setVisibility(View.INVISIBLE);
            mAdapter.submit(new ArrayList<Row>());
            showMessage(model);
            recompute();
        }
    }

    private static String displayName(PackManifest manifest) {
        return manifest.name == null || manifest.name.trim().isEmpty() ? "Projects" : manifest.name.trim();
    }

    private void showList() {
        mList.setVisibility(View.VISIBLE);
        if (mSkeleton.getVisibility() == View.VISIBLE) {
            mSkeleton.animate().cancel();
            if (Motion.enabled(requireContext())) {
                mSkeleton.animate().alpha(0f).setDuration(140).setInterpolator(Motion.EASE_IN)
                        .withEndAction(() -> mSkeleton.setVisibility(View.GONE)).start();
            } else {
                mSkeleton.setVisibility(View.GONE);
            }
        }
    }

    private void showMessage(ModsModel model) {
        mSkeleton.animate().cancel();
        mSkeleton.setVisibility(View.GONE);
        mList.setVisibility(View.INVISIBLE);
        mRetry.setVisibility(View.GONE);
        mMessageBody.setVisibility(View.VISIBLE);
        switch (model.status) {
            case UNAVAILABLE:
                mMessageTitle.setText(R.string.projects_mods_unavailable_title);
                mMessageBody.setText(R.string.projects_mods_unavailable_body);
                mRetry.setVisibility(View.VISIBLE);
                break;
            case DROPPED:
                mMessageTitle.setText(R.string.projects_mods_dropped_title);
                String body = getString(R.string.projects_mods_dropped_body);
                if (model.recommendedMinecraft != null) {
                    body += " " + getString(R.string.projects_mods_dropped_recommended, model.recommendedMinecraft);
                }
                mMessageBody.setText(body);
                break;
            default:
                mMessageTitle.setText(R.string.projects_mods_empty_title);
                mMessageBody.setVisibility(View.GONE);
                break;
        }
        fadeIn(mMessage);
    }

    private void fadeIn(View view) {
        boolean wasVisible = view.getVisibility() == View.VISIBLE;
        view.setVisibility(View.VISIBLE);
        if (wasVisible || !Motion.enabled(view.getContext())) {
            view.setAlpha(1f);
            return;
        }
        view.setAlpha(0f);
        view.animate().alpha(1f).setDuration(Motion.ENTER_MS).setInterpolator(Motion.EASE_OUT).start();
    }

    // ---- selection ----

    private boolean locked() {
        return mApplying || ModsApply.isRunning();
    }

    private void toggle(String id, boolean on) {
        if (locked() || mModel == null || mModel.plan == null) return;
        if (on && !mHoldLifted && heldOff(id)) {
            // Switching a held-off Sodium item on lifts the hold: the family shows its recorded
            // choices again (what applying will install), with this one on.
            mHoldLifted = true;
            for (ItemResolver.ItemStatus status : mModel.plan.items) {
                if (status.heldOff && !status.library) mChoices.put(status.id, status.selected);
            }
        }
        mChoices.put(id, on);
        changed();
    }

    private boolean heldOff(String id) {
        if (mModel == null || mModel.plan == null) return false;
        for (ItemResolver.ItemStatus status : mModel.plan.items) if (status.id.equals(id)) return status.heldOff;
        return false;
    }

    /** "Sodium artık telefonunda da çalışıyor" → "Aç": the recommended family on, applied at once when nothing else waits. */
    private void acceptSodiumOffer() {
        if (locked() || mModel == null || mModel.plan == null || mModel.manifest == null) return;
        boolean otherChanges = mSelection != null;
        for (String id : SodiumSupport.recommendedFamily(mModel.manifest)) mChoices.put(id, true);
        SodiumState.answerOffer(requireContext());
        changed();
        if (!otherChanges && mSelection != null) apply(mSelection);
    }

    private void declineSodiumOffer() {
        if (mModel == null) return;
        SodiumState.answerOffer(requireContext());
        load(true);
    }

    private void toggleRevert(String id) {
        if (locked()) return;
        if (!mRevert.remove(id)) mRevert.add(id);
        changed();
    }

    private void discard() {
        if (locked() || mModel == null || mModel.plan == null) return;
        mChoices.clear();
        mHoldLifted = false;
        mChoices.putAll(ModsSelection.choicesOf(mModel.plan.items));
        mRevert.clear();
        changed();
    }

    private void changed() {
        mPreview = null;
        recompute();
        schedulePreview();
    }

    private void recompute() {
        if (mModel != null && mModel.plan != null && mModel.manifest != null) {
            mEnabled = ModsSelection.enabledSet(mModel.manifest, mChoices);
            mSelection = ModsSelection.pending(mModel.plan.items, mChoices, mRevert, mHoldLifted);
            // Lifting the hold is a change of its own, even when every choice stays as recorded.
            if (mSelection == null && mHoldLifted) mSelection = new PackSelection();
            mAdapter.submit(buildRows());
        } else {
            mEnabled = new HashSet<>();
            mSelection = null;
        }
        mBackCallback.setEnabled(mSelection != null && !locked());
        updateBar();
    }

    private void schedulePreview() {
        final int token = ++mPreviewToken;
        final PackSelection selection = mSelection;
        final ModsModel model = mModel;
        final boolean holdLifted = mHoldLifted;
        final Context app = requireContext().getApplicationContext();
        if (selection == null || model == null) return;
        mMain.postDelayed(() -> {
            if (token != mPreviewToken) return;
            WORKER.execute(() -> {
                PackSyncPlanner.Summary summary = null;
                try {
                    summary = model.preview(app, selection, holdLifted).summary();
                } catch (ContentException | RuntimeException ignored) {
                    // The bar keeps saying it is calculating; applying plans again anyway.
                }
                final PackSyncPlanner.Summary result = summary;
                mMain.post(() -> {
                    if (token != mPreviewToken || !isAdded()) return;
                    mPreview = result;
                    updateBar();
                });
            });
        }, PREVIEW_DELAY_MS);
    }

    // ---- apply ----

    private void apply(PackSelection selection) {
        if (mModel == null || mModel.manifest == null || locked()) return;
        Context context = requireContext();
        // The player wants Sodium although this phone held it off: their choice from now on.
        if (mHoldLifted || (mModel.sodiumBlock != SodiumSupport.Block.NONE
                && ModsSelection.switchesOn(selection, mModel.sodiumFamily))) {
            SodiumState.setOverride(context, true);
        }
        // Choosing about the family answers the offer to sets from before 0.1.5 as well.
        if (ModsSelection.touches(selection, mModel.sodiumFamily)) SodiumState.answerOffer(context);
        if (!ModsApply.start(context, mModel.manifest, mModel.gameDir, selection)) {
            Toast.makeText(requireContext(), R.string.projects_join_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        mApplying = true;
        mApplyPhase = null;
        mApplyFraction = -1;
        mBarProgress.setProgress(0);
        recompute();
    }

    @Override
    public void onProgress(PackSyncApplier.Phase phase, float fraction) {
        if (!isAdded() || getView() == null) return;
        if (!mApplying) {
            mApplying = true;
            recompute();
        }
        mApplyPhase = phase;
        mApplyFraction = fraction;
        updateBar();
    }

    @Override
    public void onFinished(ModsApply.Result result) {
        if (!isAdded() || getView() == null) return;
        mApplying = false;
        FragmentActivity activity = requireActivity();
        switch (result.outcome) {
            case DONE:
                Toast.makeText(activity, result.issues > 0 ? R.string.projects_mods_applied_issues : R.string.projects_mods_applied,
                        Toast.LENGTH_SHORT).show();
                load(true);
                break;
            default:
                recompute();
                ProjectsUi.showError(activity, R.string.projects_mods_apply_failed_title,
                        result.network ? R.string.projects_mods_apply_failed_network : R.string.projects_mods_apply_failed_generic,
                        result.details);
                break;
        }
    }

    private void markSeen() {
        final ModsModel model = mModel;
        if (model == null || locked()) return;
        WORKER.execute(() -> {
            try {
                model.markSeen();
            } catch (java.io.IOException ignored) {
                // The banner comes back next time.
            }
            mMain.post(() -> {
                if (isAdded() && getView() != null) load(true);
            });
        });
    }

    private void confirmLeave() {
        if (mSelection == null || locked()) {
            mBackCallback.setEnabled(false);
            requireActivity().getOnBackPressedDispatcher().onBackPressed();
            return;
        }
        final PackSelection selection = mSelection;
        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.projects_mods_leave_title)
                .setPositiveButton(R.string.projects_mods_apply, (d, w) -> apply(selection))
                .setNegativeButton(R.string.projects_mods_discard, (d, w) -> {
                    mBackCallback.setEnabled(false);
                    if (isAdded()) requireActivity().getOnBackPressedDispatcher().onBackPressed();
                })
                .show();
    }

    // ---- apply bar ----

    private void updateBar() {
        if (getView() == null) return;
        Context context = requireContext();
        if (mApplying) {
            mDiscard.setVisibility(View.GONE);
            mApply.setVisibility(View.GONE);
            mBarProgress.setVisibility(View.VISIBLE);
            String stage = getString(stageText(mApplyPhase));
            if (mApplyFraction >= 0 && mApplyPhase == PackSyncApplier.Phase.DOWNLOADING) {
                int percent = Math.round(mApplyFraction * 100);
                mBarText.setText(getString(R.string.projects_mods_percent, stage, percent));
                mBarProgress.setIndeterminate(false);
                animateProgress(Math.round(mApplyFraction * 1000));
            } else {
                mBarText.setText(stage);
                if (mApplyPhase == PackSyncApplier.Phase.FINISHED) {
                    mBarProgress.setIndeterminate(false);
                    animateProgress(1000);
                } else if (mApplyPhase == null) {
                    mBarProgress.setIndeterminate(true);
                }
            }
            setBarVisible(true);
            return;
        }
        mBarProgress.setVisibility(View.GONE);
        if (mSelection == null) {
            setBarVisible(false);
            return;
        }
        mDiscard.setVisibility(View.VISIBLE);
        mApply.setVisibility(View.VISIBLE);
        mBarText.setText(mPreview == null ? getString(R.string.projects_mods_calculating) : summaryText(context, mPreview));
        setBarVisible(true);
    }

    private static int stageText(@Nullable PackSyncApplier.Phase phase) {
        if (phase == null) return R.string.projects_mods_stage_preparing;
        switch (phase) {
            case DOWNLOADING: return R.string.projects_mods_stage_downloading;
            case REMOVING: return R.string.projects_mods_stage_removing;
            default: return R.string.projects_mods_stage_finishing;
        }
    }

    private void animateProgress(int target) {
        if (mProgressAnimator != null) mProgressAnimator.cancel();
        if (!Motion.enabled(requireContext())) {
            mBarProgress.setProgress(target);
            return;
        }
        mProgressAnimator = ObjectAnimator.ofInt(mBarProgress, "progress", mBarProgress.getProgress(), target);
        mProgressAnimator.setDuration(220);
        mProgressAnimator.setInterpolator(Motion.EASE_OUT);
        mProgressAnimator.start();
    }

    private static final int[] MOD_PLURALS = {
            R.plurals.projects_mods_added, R.plurals.projects_mods_updated, R.plurals.projects_mods_removed_count};
    private static final int[] FILE_PLURALS = {
            R.plurals.projects_mods_files_added, R.plurals.projects_mods_files_updated, R.plurals.projects_mods_files_removed};

    private String summaryText(Context context, PackSyncPlanner.Summary summary) {
        int[][] counts = ModsSelection.barCounts(summary);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            int mods = counts[i][0];
            int files = counts[i][1];
            if (mods > 0) parts.add(context.getResources().getQuantityString(MOD_PLURALS[i], mods, mods));
            else if (files > 0) parts.add(context.getResources().getQuantityString(FILE_PLURALS[i], files, files));
        }
        if (parts.isEmpty()) return getString(R.string.projects_mods_no_files);
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (text.length() > 0) text.append(", ");
            text.append(part);
        }
        text.append('.');
        if (summary.downloadBytes > 0) {
            text.append(' ').append(getString(R.string.projects_mods_download_size, Formatter.formatShortFileSize(context, summary.downloadBytes)));
        }
        return text.toString();
    }

    private void setBarVisible(boolean visible) {
        if (visible == mBarShown) return;
        mBarShown = visible;
        mBar.animate().cancel();
        boolean motion = Motion.enabled(requireContext());
        float offscreen = dp(requireContext(), 96);
        if (visible) {
            if (mBar.getVisibility() != View.VISIBLE) {
                mBar.setVisibility(View.VISIBLE);
                mBar.setTranslationY(motion ? offscreen : 0f);
                mBar.setAlpha(motion ? 0f : 1f);
            }
            if (!motion) {
                mBar.setTranslationY(0f);
                mBar.setAlpha(1f);
                return;
            }
            mBar.animate().translationY(0f).alpha(1f).setDuration(Motion.ENTER_MS).setInterpolator(Motion.EASE_OUT).start();
        } else {
            if (!motion) {
                mBar.setVisibility(View.GONE);
                return;
            }
            mBar.animate().translationY(offscreen).alpha(0f).setDuration(Motion.EXIT_MS).setInterpolator(Motion.EASE_IN)
                    .withEndAction(() -> mBar.setVisibility(View.GONE)).start();
        }
    }

    // ---- rows ----

    private static final int TYPE_BANNER = 0;
    private static final int TYPE_HEADER = 1;
    private static final int TYPE_ITEM = 2;
    private static final int TYPE_LIBRARY = 3;
    /** The offer to sets from before 0.1.5 ("Sodium artık telefonunda da çalışıyor"): text above two buttons. */
    private static final int TYPE_OFFER = 4;

    private static final int BANNER_NEW = 0;
    private static final int BANNER_UPDATES = 1;
    private static final int BANNER_RESET = 2;
    private static final int BANNER_ISSUES = 3;

    /** One list row; {@link #content} holds everything it shows, so unchanged rows are not rebound. */
    private static final class Row {
        final int type;
        final String key;
        String content = "";
        int banner;
        String title;
        String body;
        String action;
        PackManifest.PackItem item;
        boolean on;
        boolean enabled;
        boolean changed;
        boolean isNew;
        /** Why a Sodium item may not work here ("Seçili işleyiciyle çalışmaz."), or null. */
        String warning;
        /** A second, quiet action ("Kapalı kalsın"), or null. */
        String dismiss;
        boolean reverting;
        boolean modified;
        String badge;
        String note;
        String status;
        /** "Mod sayfası": the item's https page, or null (no link). */
        String page;

        Row(int type, String key) {
            this.type = type;
            this.key = key;
        }

        void seal() {
            content = type + "|" + title + "|" + body + "|" + action + "|" + dismiss + "|" + on + enabled + changed + isNew
                    + reverting + modified + "|" + badge + "|" + note + "|" + status + "|" + page + "|" + warning;
        }
    }

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        ModsModel model = mModel;
        if (model == null || model.plan == null || model.manifest == null) return rows;
        Context context = requireContext();
        PackManifest manifest = model.manifest;
        PackSyncPlanner.Plan plan = model.plan;
        boolean locked = locked();

        int newCount = 0;
        int updates = 0;
        for (ItemResolver.ItemStatus status : plan.items) {
            if (status.isNew && !status.library) newCount++;
            if (status.updateAvailable && mEnabled.contains(status.id)) updates++;
        }
        if (model.sodiumOffer) {
            Row row = new Row(TYPE_OFFER, "offer-sodium");
            row.title = getString(R.string.projects_mods_sodium_offer);
            row.body = getString(R.string.projects_mods_sodium_offer_body);
            row.action = getString(R.string.projects_mods_sodium_offer_on);
            row.dismiss = getString(R.string.projects_mods_sodium_offer_keep);
            row.enabled = !locked;
            rows.add(row);
        }
        if (newCount > 0) {
            Row row = new Row(TYPE_BANNER, "banner-new");
            row.banner = BANNER_NEW;
            row.title = context.getResources().getQuantityString(R.plurals.projects_mods_new_items, newCount, newCount);
            row.action = getString(R.string.projects_mods_new_items_ok);
            row.enabled = !locked;
            rows.add(row);
        }
        if (updates > 0 && mSelection == null) {
            Row row = new Row(TYPE_BANNER, "banner-updates");
            row.banner = BANNER_UPDATES;
            row.title = context.getResources().getQuantityString(R.plurals.projects_mods_updates, updates, updates);
            row.action = getString(R.string.projects_mods_update_now);
            row.enabled = !locked;
            rows.add(row);
        }
        if (plan.reset) {
            Row row = new Row(TYPE_BANNER, "banner-reset");
            row.banner = BANNER_RESET;
            row.title = getString(R.string.projects_mods_reset);
            rows.add(row);
        }
        if (!plan.issues.isEmpty()) {
            Row row = new Row(TYPE_BANNER, "banner-issues");
            row.banner = BANNER_ISSUES;
            row.title = context.getResources().getQuantityString(R.plurals.projects_mods_issues_title, plan.issues.size(), plan.issues.size());
            row.body = getString(R.string.projects_mods_issues_body);
            row.action = getString(R.string.projects_copy_details);
            row.enabled = true;
            rows.add(row);
        }

        Map<String, ItemResolver.ItemStatus> statuses = new HashMap<>();
        for (ItemResolver.ItemStatus status : plan.items) statuses.put(status.id, status);
        for (String category : CATEGORY_ORDER) {
            List<Row> section = new ArrayList<>();
            for (PackManifest.PackItem item : manifest.projects.items) {
                if (item.library || !category.equals(sectionOf(item))) continue;
                ItemResolver.ItemStatus status = statuses.get(item.id);
                if (status != null) section.add(itemRow(context, manifest, item, status, locked));
            }
            if (section.isEmpty()) continue;
            Row header = new Row(TYPE_HEADER, "header-" + category);
            header.title = getString(categoryText(category));
            rows.add(header);
            rows.addAll(section);
        }
        List<Row> libraries = new ArrayList<>();
        for (PackManifest.PackItem item : manifest.projects.items) {
            if (!item.library) continue;
            Row row = new Row(TYPE_LIBRARY, "library-" + item.id);
            row.item = item;
            row.title = item.title;
            row.on = mEnabled.contains(item.id);
            List<PackManifest.PackItem> needed = ModsSelection.neededBy(manifest, mEnabled, item.id);
            row.note = row.on && !needed.isEmpty() ? getString(R.string.projects_mods_library_for, titles(needed)) : null;
            row.status = getString(row.on ? R.string.projects_mods_library_on : R.string.projects_mods_library_off);
            libraries.add(row);
        }
        if (!libraries.isEmpty()) {
            Row header = new Row(TYPE_HEADER, "header-library");
            header.title = getString(R.string.projects_mods_category_library);
            rows.add(header);
            rows.addAll(libraries);
        }
        for (Row row : rows) row.seal();
        return rows;
    }

    /** Non-library items in the "library" category are listed under "other". */
    private static String sectionOf(PackManifest.PackItem item) {
        String category = item.categoryOrDefault();
        return PackManifest.CATEGORY_LIBRARY.equals(category) ? PackManifest.CATEGORY_OTHER : category;
    }

    private Row itemRow(Context context, PackManifest manifest, PackManifest.PackItem item, ItemResolver.ItemStatus status, boolean locked) {
        Row row = new Row(TYPE_ITEM, "item-" + item.id);
        row.item = item;
        row.title = item.title;
        row.body = item.summary == null || item.summary.trim().isEmpty() ? null : item.summary.trim();
        row.page = item.page();
        row.on = mEnabled.contains(item.id);
        List<PackManifest.PackItem> needed = ModsSelection.neededBy(manifest, mEnabled, item.id);
        boolean forced = !needed.isEmpty();
        row.enabled = !locked && !(forced && row.on);
        Boolean choice = mChoices.get(item.id);
        row.reverting = mRevert.contains(item.id);
        row.changed = (choice != null && choice != ModsSelection.baseline(status, mHoldLifted)) || row.reverting;
        row.isNew = status.isNew;
        if (mModel.sodiumFamily.contains(item.id)) {
            if (mModel.rendererBlocksSodium) row.warning = getString(R.string.projects_mods_sodium_renderer);
            else if (mModel.sodiumCrashedHere) row.warning = getString(R.string.projects_mods_sodium_crashed);
        }
        row.modified = status.modified;
        if (status.modified) row.badge = getString(R.string.projects_mods_badge_modified);
        else if (status.updateAvailable && row.on) row.badge = getString(R.string.projects_mods_badge_update);

        List<String> notes = new ArrayList<>();
        String names = titles(needed);
        if (PackState.MANUAL_REMOVED.equals(status.manual)) {
            notes.add(row.on && forced ? getString(R.string.projects_mods_removed_back, names) : getString(R.string.projects_mods_removed));
        } else if (PackState.MANUAL_DISABLED.equals(status.manual)) {
            notes.add(row.on && forced ? getString(R.string.projects_mods_disabled_back, names) : getString(R.string.projects_mods_disabled));
        } else if (PackState.MANUAL_ENABLED.equals(status.manual)) {
            notes.add(getString(R.string.projects_mods_enabled));
        }
        if (forced && !PackState.MANUAL_REMOVED.equals(status.manual) && !PackState.MANUAL_DISABLED.equals(status.manual)) {
            notes.add(getString(R.string.projects_mods_required_by, names));
        }
        if (!row.on) {
            List<PackManifest.PackItem> brings = ModsSelection.bringsAlong(manifest, item.id, mEnabled);
            if (!brings.isEmpty()) notes.add(getString(R.string.projects_mods_brings_along, titles(brings)));
        }
        if (status.modified) notes.add(getString(R.string.projects_mods_modified));
        StringBuilder note = new StringBuilder();
        for (String text : notes) {
            if (note.length() > 0) note.append('\n');
            note.append(text);
        }
        row.note = note.length() == 0 ? null : note.toString();
        return row;
    }

    private static String titles(List<PackManifest.PackItem> items) {
        StringBuilder out = new StringBuilder();
        for (PackManifest.PackItem item : items) {
            if (out.length() > 0) out.append(", ");
            out.append(item.title);
        }
        return out.toString();
    }

    private static int categoryText(String category) {
        switch (category) {
            case PackManifest.CATEGORY_PERFORMANCE: return R.string.projects_mods_category_performance;
            case PackManifest.CATEGORY_VISUAL: return R.string.projects_mods_category_visual;
            case PackManifest.CATEGORY_INTERFACE: return R.string.projects_mods_category_interface;
            case PackManifest.CATEGORY_GAMEPLAY: return R.string.projects_mods_category_gameplay;
            default: return R.string.projects_mods_category_other;
        }
    }

    private void onBannerAction(Row row) {
        switch (row.banner) {
            case BANNER_NEW:
                markSeen();
                break;
            case BANNER_UPDATES:
                // Confirming without changes installs the updates now (and marks everything seen).
                apply(new PackSelection());
                break;
            case BANNER_ISSUES:
                copyIssueDetails();
                break;
            default:
                break;
        }
    }

    private void copyIssueDetails() {
        if (mModel == null || mModel.plan == null) return;
        StringBuilder details = new StringBuilder();
        for (PackSyncPlanner.Issue issue : mModel.plan.issues) {
            details.append(issue.path).append(": ").append(issue.kind.name().toLowerCase(Locale.ROOT))
                    .append(" (").append(issue.detail).append(")\n");
        }
        ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Projects Launcher", details.toString().trim()));
            Toast.makeText(requireContext(), R.string.projects_details_copied, Toast.LENGTH_SHORT).show();
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private List<Row> mRows = new ArrayList<>();
        private final Set<String> mShown = new HashSet<>();

        void submit(final List<Row> rows) {
            final List<Row> old = mRows;
            if (old.isEmpty()) {
                // First fill: rows enter with their own staggered animation, not the item animator's.
                mRows = rows;
                notifyDataSetChanged();
                if (mAnimateRows) mList.postDelayed(() -> mAnimateRows = false, 600);
                return;
            }
            DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return old.size();
                }

                @Override
                public int getNewListSize() {
                    return rows.size();
                }

                @Override
                public boolean areItemsTheSame(int oldPosition, int newPosition) {
                    return old.get(oldPosition).key.equals(rows.get(newPosition).key);
                }

                @Override
                public boolean areContentsTheSame(int oldPosition, int newPosition) {
                    return old.get(oldPosition).content.equals(rows.get(newPosition).content);
                }

                @Override
                public Object getChangePayload(int oldPosition, int newPosition) {
                    return Boolean.TRUE;
                }
            });
            mRows = rows;
            diff.dispatchUpdatesTo(this);
        }

        @Override
        public int getItemViewType(int position) {
            return mRows.get(position).type;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            int layout;
            switch (viewType) {
                case TYPE_BANNER: layout = R.layout.item_projects_mod_banner; break;
                case TYPE_OFFER: layout = R.layout.item_projects_mod_offer; break;
                case TYPE_HEADER: layout = R.layout.item_projects_mod_header; break;
                case TYPE_LIBRARY: layout = R.layout.item_projects_mod_library; break;
                default: layout = R.layout.item_projects_mod; break;
            }
            return new Holder(inflater.inflate(layout, parent, false), viewType);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Row row = mRows.get(position);
            holder.bind(row, false);
            if (mAnimateRows && !mShown.contains(row.key) && position < 12) {
                View view = holder.itemView;
                view.setAlpha(0f);
                view.setTranslationY(dp(view.getContext(), 14));
                view.animate().alpha(1f).translationY(0f).setStartDelay(40L + position * 26L)
                        .setDuration(Motion.ENTER_MS).setInterpolator(Motion.EASE_OUT).start();
            } else {
                holder.itemView.setAlpha(1f);
                holder.itemView.setTranslationY(0f);
            }
            mShown.add(row.key);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position, @NonNull List<Object> payloads) {
            if (payloads.isEmpty()) {
                onBindViewHolder(holder, position);
            } else {
                holder.bind(mRows.get(position), true);
            }
        }

        @Override
        public void onViewRecycled(@NonNull Holder holder) {
            holder.itemView.animate().cancel();
            holder.itemView.setAlpha(1f);
            holder.itemView.setTranslationY(0f);
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }
    }

    private final class Holder extends RecyclerView.ViewHolder {
        private final int mType;
        private Row mRow;

        Holder(View view, int type) {
            super(view);
            mType = type;
            if (type == TYPE_ITEM) {
                // The whole row is the touch target; the switch only shows the state (and animates).
                SwitchCompat toggle = view.findViewById(R.id.projects_mod_switch);
                toggle.setClickable(false);
                toggle.setFocusable(false);
                toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                View rowView = view.findViewById(R.id.projects_mod_row);
                // M14: the row presses (light tick, barely moves); a flip plays the switch's toggle,
                // a locked row (needed by another mod) a reject.
                gg.projects.mobile.ui.Press.attach(rowView, gg.projects.mobile.ui.Press.Style.ROW);
                rowView.setOnClickListener(v -> {
                    if (mRow == null) return;
                    if (!mRow.enabled) {
                        gg.projects.mobile.ui.Haptics.reject(v);
                        return;
                    }
                    gg.projects.mobile.ui.Haptics.toggle(v, !mRow.on);
                    toggle(mRow.item.id, !mRow.on);
                });
                ViewCompat.setAccessibilityDelegate(rowView, new AccessibilityDelegateCompat() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        info.setClassName(Switch.class.getName());
                        info.setCheckable(true);
                        info.setChecked(mRow != null && mRow.on);
                    }
                });
                View revert = view.findViewById(R.id.projects_mod_revert);
                gg.projects.mobile.ui.Press.attach(revert, gg.projects.mobile.ui.Press.Style.BUTTON);
                revert.setOnClickListener(v -> {
                    if (mRow != null) toggleRevert(mRow.item.id);
                });
                view.findViewById(R.id.projects_mod_icon_frame).setClipToOutline(true);
                setUpPageLink(view.findViewById(R.id.projects_mod_page));
            } else if (type == TYPE_LIBRARY) {
                view.findViewById(R.id.projects_mod_icon_frame).setClipToOutline(true);
            } else if (type == TYPE_BANNER) {
                view.findViewById(R.id.projects_banner_action).setOnClickListener(v -> {
                    if (mRow != null && mRow.enabled) onBannerAction(mRow);
                });
            } else if (type == TYPE_OFFER) {
                Button on = view.findViewById(R.id.projects_offer_on);
                on.setBackground(ChamferDrawable.primary(view.getContext()));
                on.setOnClickListener(v -> {
                    if (mRow != null && mRow.enabled) acceptSodiumOffer();
                });
                view.findViewById(R.id.projects_offer_keep).setOnClickListener(v -> {
                    if (mRow != null && mRow.enabled) declineSodiumOffer();
                });
            }
        }

        /**
         * "Mod sayfası" with the external-link mark in the text's size and colour (gold while
         * pressed); opens the page in the browser, announced as a button named after the mod.
         */
        private void setUpPageLink(TextView page) {
            Context context = page.getContext();
            Drawable icon = ContextCompat.getDrawable(context, R.drawable.projects_ic_arrow_square_out);
            if (icon != null) {
                icon = DrawableCompat.wrap(icon.mutate());
                int size = Math.round(page.getTextSize());
                icon.setBounds(0, 0, size, size);
                DrawableCompat.setTintList(icon, ContextCompat.getColorStateList(context, R.color.projects_link_text));
                page.setCompoundDrawablesRelative(null, null, icon, null);
            }
            gg.projects.mobile.ui.Press.attach(page, gg.projects.mobile.ui.Press.Style.BUTTON);
            page.setOnClickListener(v -> {
                if (mRow != null && mRow.page != null) ProjectsUi.openUrl(requireActivity(), mRow.page);
            });
            ViewCompat.setAccessibilityDelegate(page, new AccessibilityDelegateCompat() {
                @Override
                public void onInitializeAccessibilityNodeInfo(@NonNull View host, @NonNull AccessibilityNodeInfoCompat info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(Button.class.getName());
                }
            });
        }

        void bind(Row row, boolean partial) {
            mRow = row;
            Context context = itemView.getContext();
            switch (mType) {
                case TYPE_HEADER:
                    ((TextView) itemView).setText(row.title);
                    break;
                case TYPE_BANNER:
                    bindBanner(row);
                    break;
                case TYPE_OFFER:
                    bindOffer(row);
                    break;
                case TYPE_LIBRARY:
                    bindLibrary(context, row, partial);
                    break;
                default:
                    bindItem(context, row, partial);
                    break;
            }
        }

        private void bindBanner(Row row) {
            ImageView icon = itemView.findViewById(R.id.projects_banner_icon);
            int drawable;
            switch (row.banner) {
                case BANNER_UPDATES: drawable = R.drawable.projects_ic_arrows_clockwise; break;
                case BANNER_RESET:
                case BANNER_ISSUES: drawable = R.drawable.projects_ic_warning; break;
                default: drawable = R.drawable.projects_ic_sparkle; break;
            }
            icon.setImageResource(drawable);
            ((TextView) itemView.findViewById(R.id.projects_banner_text)).setText(row.title);
            TextView body = itemView.findViewById(R.id.projects_banner_body);
            body.setText(row.body);
            body.setVisibility(row.body == null ? View.GONE : View.VISIBLE);
            Button action = itemView.findViewById(R.id.projects_banner_action);
            if (row.action == null) {
                action.setVisibility(View.GONE);
            } else {
                action.setText(row.action);
                action.setEnabled(row.enabled);
                action.setAlpha(row.enabled ? 1f : 0.5f);
                if (action.getBackground() == null || !(action.getTag() instanceof Boolean)) {
                    action.setBackground(ChamferDrawable.secondary(itemView.getContext()));
                    action.setTag(Boolean.TRUE);
                }
                action.setVisibility(View.VISIBLE);
            }
        }

        private void bindOffer(Row row) {
            ((TextView) itemView.findViewById(R.id.projects_offer_title)).setText(row.title);
            ((TextView) itemView.findViewById(R.id.projects_offer_body)).setText(row.body);
            Button on = itemView.findViewById(R.id.projects_offer_on);
            on.setText(row.action);
            on.setEnabled(row.enabled);
            on.setAlpha(row.enabled ? 1f : 0.5f);
            Button keep = itemView.findViewById(R.id.projects_offer_keep);
            keep.setText(row.dismiss);
            keep.setEnabled(row.enabled);
            keep.setAlpha(row.enabled ? 1f : 0.5f);
        }

        private void bindLibrary(Context context, Row row, boolean partial) {
            ((TextView) itemView.findViewById(R.id.projects_mod_title)).setText(row.title);
            TextView note = itemView.findViewById(R.id.projects_mod_note);
            note.setText(row.note);
            note.setVisibility(row.note == null ? View.GONE : View.VISIBLE);
            ((TextView) itemView.findViewById(R.id.projects_mod_status)).setText(row.status);
            float dim = row.on ? 1f : 0.55f;
            itemView.findViewById(R.id.projects_mod_icon_frame).setAlpha(dim);
            if (!partial) {
                TextView monogram = itemView.findViewById(R.id.projects_mod_monogram);
                monogram.setText(monogram(row.title));
                ModIcons.load(context, row.item.iconUrl, itemView.findViewById(R.id.projects_mod_icon), monogram, dp(context, 32), true);
            }
        }

        private void bindItem(Context context, Row row, boolean partial) {
            ((TextView) itemView.findViewById(R.id.projects_mod_title)).setText(row.title);
            TextView summary = itemView.findViewById(R.id.projects_mod_summary);
            summary.setText(row.body);
            summary.setVisibility(row.body == null ? View.GONE : View.VISIBLE);
            TextView page = itemView.findViewById(R.id.projects_mod_page);
            page.setVisibility(row.page == null ? View.GONE : View.VISIBLE);
            page.setContentDescription(row.page == null ? null : getString(R.string.projects_mods_page_for, row.title));
            itemView.findViewById(R.id.projects_mod_badge_new).setVisibility(row.isNew ? View.VISIBLE : View.GONE);
            TextView badge = itemView.findViewById(R.id.projects_mod_badge);
            badge.setText(row.badge);
            badge.setVisibility(row.badge == null ? View.GONE : View.VISIBLE);
            TextView note = itemView.findViewById(R.id.projects_mod_note);
            note.setText(row.note);
            note.setVisibility(row.note == null ? View.GONE : View.VISIBLE);
            TextView warning = itemView.findViewById(R.id.projects_mod_warning);
            warning.setText(row.warning);
            warning.setVisibility(row.warning == null ? View.GONE : View.VISIBLE);
            Button revert = itemView.findViewById(R.id.projects_mod_revert);
            revert.setVisibility(row.modified ? View.VISIBLE : View.GONE);
            revert.setText(row.reverting ? R.string.projects_mods_keep_mine : R.string.projects_mods_revert);
            revert.setEnabled(!locked());
            if (!(revert.getTag() instanceof Boolean)) {
                revert.setBackground(ChamferDrawable.secondary(context));
                revert.setTag(Boolean.TRUE);
            }

            SwitchCompat toggle = itemView.findViewById(R.id.projects_mod_switch);
            if (toggle.isChecked() != row.on) {
                // Animates the knob on a partial update; jumps on a fresh bind.
                toggle.setChecked(row.on);
                if (!partial) toggle.jumpDrawablesToCurrentState();
            }
            toggle.setEnabled(row.enabled);
            toggle.setContentDescription(getString(R.string.projects_mods_toggle, row.title));
            View rowView = itemView.findViewById(R.id.projects_mod_row);
            rowView.setEnabled(row.enabled);
            View highlight = itemView.findViewById(R.id.projects_mod_highlight);
            float target = row.changed ? 1f : 0f;
            if (partial && Motion.enabled(context)) {
                highlight.animate().cancel();
                highlight.animate().alpha(target).setDuration(160).setInterpolator(Motion.EASE_OUT).start();
            } else {
                highlight.animate().cancel();
                highlight.setAlpha(target);
            }
            View iconFrame = itemView.findViewById(R.id.projects_mod_icon_frame);
            float dim = row.on ? 1f : 0.6f;
            if (partial && Motion.enabled(context)) iconFrame.animate().alpha(dim).setDuration(160).start();
            else iconFrame.setAlpha(dim);
            if (!partial) {
                TextView monogram = itemView.findViewById(R.id.projects_mod_monogram);
                monogram.setText(monogram(row.title));
                ModIcons.load(context, row.item.iconUrl, itemView.findViewById(R.id.projects_mod_icon), monogram, dp(context, 44), true);
            }
        }
    }

    private static String monogram(String title) {
        String trimmed = title == null ? "" : title.trim();
        if (trimmed.isEmpty()) return "";
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).toUpperCase(Locale.getDefault());
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
