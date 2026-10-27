package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.RTSpinnerAdapter;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.profiles.ProfileIconCache;
import net.kdt.pojavlaunch.profiles.VersionSelectorDialog;
import net.kdt.pojavlaunch.utils.CropperUtils;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ProfileEditorFragment extends Fragment implements CropperUtils.CropperListener{
    public static final String TAG = "ProfileEditorFragment";
    public static final String DELETED_PROFILE = "deleted_profile";

    private String mProfileKey;
    private MinecraftProfile mTempProfile = null;
    private String mValueToConsume = "";
    private Button mSaveButton, mDeleteButton, mControlSelectButton, mGameDirButton, mVersionSelectButton;
    private Spinner mDefaultRuntime, mDefaultRenderer;
    private CompoundButton mUseANGLE;
    // MOB-POLISH: the engine's settings under "Gelişmiş" (closed unless one of them was changed); the
    // fragment keeps it open across a picker's round trip (the view is made again on return).
    private View mAdvanced, mAdvancedGroup;
    private ImageView mAdvancedCaret;
    private android.widget.ScrollView mScroll;
    private boolean mAdvancedOpen;
    private EditText mDefaultName, mDefaultJvmArgument;
    private TextView mDefaultPath, mDefaultVersion, mDefaultControl;
    private ImageView mProfileIcon;
    private final ActivityResultLauncher<?> mCropperLauncher = CropperUtils.registerCropper(this, this);

    private List<String> mRenderNames;

    public ProfileEditorFragment(){
        super(R.layout.fragment_profile_editor);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Paths, which can be changed
        String value = (String) ExtraCore.consumeValue(ExtraConstants.FILE_SELECTOR);
        if(value != null){
            if(mValueToConsume.equals(FileSelectorFragment.BUNDLE_SELECT_FOLDER)){
                mTempProfile.gameDir = value;
            }else{
                mTempProfile.controlFile = value;
            }
        }
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        bindViews(view);

        Tools.RenderersList renderersList = Tools.getCompatibleRenderers(view.getContext());
        mRenderNames = renderersList.rendererIds;
        List<String> renderList = new ArrayList<>(renderersList.rendererDisplayNames.length + 1);
        renderList.addAll(Arrays.asList(renderersList.rendererDisplayNames));
        renderList.add(view.getContext().getString(R.string.global_default));
        mDefaultRenderer.setAdapter(new ArrayAdapter<>(getContext(), R.layout.item_simple_list_1, renderList));

        // Set up behaviors
        mSaveButton.setOnClickListener(v -> {
            ProfileIconCache.dropIcon(mProfileKey);
            save();
            Tools.backToMainMenu(requireActivity());
        });

        mDeleteButton.setOnClickListener(v -> onDelete());


        View.OnClickListener gameDirListener = getGameDirListener();
        mGameDirButton.setOnClickListener(gameDirListener);
        mDefaultPath.setOnClickListener(gameDirListener);

        View.OnClickListener controlSelectListener = getControlSelectListener();
        mControlSelectButton.setOnClickListener(controlSelectListener);
        mDefaultControl.setOnClickListener(controlSelectListener);

        // Setup the expendable list behavior
        View.OnClickListener versionSelectListener = getVersionSelectListener();
        mVersionSelectButton.setOnClickListener(versionSelectListener);
        mDefaultVersion.setOnClickListener(versionSelectListener);

        mUseANGLE.setOnClickListener(v -> mTempProfile.useANGLE = mUseANGLE.isChecked());

        // Set up the icon change click listener
        mProfileIcon.setOnClickListener(v -> CropperUtils.startCropper(mCropperLauncher));

        loadValues(LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, ""), view.getContext());
    }

    /**
     * MOB2: "Sil" moves the profile to the trash (30 days) and leaves its game folder (worlds, mods)
     * where it is; the home screen then offers "Geri al" and Play's choice moves to the list's first
     * profile. The Projects profile asks first, in one sentence. The only profile stays (one must
     * remain), and a profile not saved yet has nothing to delete: the editor just closes.
     */
    private void onDelete() {
        if (!gg.projects.mobile.join.ProfileRemoval.canDelete(mProfileKey)) {
            Tools.removeCurrentFragment(requireActivity());
            return;
        }
        if (!gg.projects.mobile.join.ProjectsProfile.KEY.equals(mProfileKey)) {
            delete();
            return;
        }
        AlertDialog question = new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.mob_profile_delete_title, storedName()))
                .setMessage(R.string.mob_profile_delete_projects)
                .setPositiveButton(R.string.global_delete, (dialog, which) -> {
                    if (isAdded()) delete();
                })
                .setNegativeButton(R.string.mob_profile_delete_cancel, null)
                .show();
        // MOB-POLISH: the deleting choice in the error colour, "Vazgeç" in the dialog's own (as removing an account).
        android.widget.Button remove = question.getButton(AlertDialog.BUTTON_POSITIVE);
        if (remove != null) remove.setTextColor(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.projects_flare_400));
    }

    private void delete() {
        FragmentActivity activity = requireActivity();
        String name = storedName();
        String chosen;
        try {
            chosen = gg.projects.mobile.join.ProfileRemoval.delete(mProfileKey);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "profile not deleted", e);
            Toast.makeText(activity, R.string.mob_profile_delete_failed, Toast.LENGTH_LONG).show();
            return;
        }
        if (chosen != null) {
            ProfileIconCache.dropIcon(mProfileKey);
            // The home screen's play bar shows Play's new choice; its undo bar offers "Geri al".
            ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, chosen);
            gg.projects.mobile.join.ProfileRemoval.offerUndo(mProfileKey, name);
        }
        Tools.removeCurrentFragment(activity);
    }

    /** The stored profile's name as the player reads it ("Varsayılan"; "Profil" when it has none). */
    private String storedName() {
        MinecraftProfile stored = LauncherProfiles.mainProfileJson == null ? null
                : LauncherProfiles.mainProfileJson.profiles.get(mProfileKey);
        String name = stored != null ? stored.name : mTempProfile != null ? mTempProfile.name : null;
        return gg.projects.mobile.join.ProfileNames.shown(name,
                getString(R.string.projects_profile_default_name), getString(R.string.mob_profile_unnamed));
    }

    private View.OnClickListener getGameDirListener() {
        return v -> {
            Bundle bundle = new Bundle(2);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SELECT_FOLDER, true);
            bundle.putString(FileSelectorFragment.BUNDLE_ROOT_PATH, Tools.DIR_GAME_HOME);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SHOW_FILE, false);
            mValueToConsume = FileSelectorFragment.BUNDLE_SELECT_FOLDER;

            Tools.swapFragment(requireActivity(),
                    FileSelectorFragment.class, FileSelectorFragment.TAG, bundle);
        };
    }

    private View.OnClickListener getControlSelectListener() {
        return v -> {
            Bundle bundle = new Bundle(3);
            bundle.putBoolean(FileSelectorFragment.BUNDLE_SELECT_FOLDER, false);
            bundle.putString(FileSelectorFragment.BUNDLE_ROOT_PATH, Tools.CTRLMAP_PATH);
            mValueToConsume = FileSelectorFragment.BUNDLE_SELECT_FILE;

            Tools.swapFragment(requireActivity(),
                    FileSelectorFragment.class, FileSelectorFragment.TAG, bundle);
        };
    }

    private View.OnClickListener getVersionSelectListener() {
        return v -> VersionSelectorDialog.open(v.getContext(), false, (id, snapshot)-> {
            mTempProfile.lastVersionId = id;
            mDefaultVersion.setText(id);
        });
    }

    private void loadValues(@NonNull String profile, @NonNull Context context){
        if(mTempProfile == null){
            mTempProfile = getProfile(profile);
        }
        // TODO: Remove this jank when it's not relevant anymore
        // Shitty hack to make OSMZink smoothly transition into kopper
        if ("vulkan_zink".equals(mTempProfile.pojavRendererName)) mTempProfile.pojavRendererName = "opengles3_desktopgl_zink_kopper";
        mProfileIcon.setImageDrawable(
                ProfileIconCache.fetchIcon(getResources(), mProfileKey, mTempProfile.icon)
        );

        // Runtime spinner
        List<Runtime> runtimes = MultiRTUtils.getInstalledRuntimes();
        int jvmIndex = runtimes.indexOf(new Runtime("<Default>"));
        if (mTempProfile.javaDir != null) {
            String selectedRuntime = mTempProfile.javaDir.substring(Tools.LAUNCHERPROFILES_RTPREFIX.length());
            int nindex = runtimes.indexOf(new Runtime(selectedRuntime));
            if (nindex != -1) jvmIndex = nindex;
        }
        mDefaultRuntime.setAdapter(new RTSpinnerAdapter(context, runtimes));
        if(jvmIndex == -1) jvmIndex = runtimes.size() - 1;
        mDefaultRuntime.setSelection(jvmIndex);

        // Renderer spinner
        int rendererIndex = mDefaultRenderer.getAdapter().getCount() - 1;
        if(mTempProfile.pojavRendererName != null) {
            int nindex = mRenderNames.indexOf(mTempProfile.pojavRendererName);
            if(nindex != -1) rendererIndex = nindex;
        }
        mDefaultRenderer.setSelection(rendererIndex);

        mDefaultVersion.setText(mTempProfile.lastVersionId);
        mDefaultJvmArgument.setText(mTempProfile.javaArgs == null ? "" : mTempProfile.javaArgs);
        // MOB2: Amethyst's "(Default)" profile by the name the profile list gives it ("Varsayılan").
        mDefaultName.setText(gg.projects.mobile.join.ProfileNames.editable(mTempProfile.name,
                context.getString(R.string.projects_profile_default_name)));
        mDefaultPath.setText(mTempProfile.gameDir == null ? "" : mTempProfile.gameDir);
        mDefaultControl.setText(mTempProfile.controlFile == null ? "" : mTempProfile.controlFile);
        mUseANGLE.setChecked(mTempProfile.useANGLE);
        // A changed engine setting shows itself; the Projects profile's own folder and renderer are the pack's.
        boolean projects = gg.projects.mobile.join.ProjectsProfile.KEY.equals(mProfileKey);
        boolean changed = (mTempProfile.javaArgs != null && !mTempProfile.javaArgs.trim().isEmpty())
                || mTempProfile.useANGLE || mTempProfile.javaDir != null
                || (!projects && (mTempProfile.pojavRendererName != null
                        || (mTempProfile.gameDir != null && !mTempProfile.gameDir.isEmpty())));
        showAdvanced(mAdvancedOpen || changed, false);
    }

    /** Opens or closes "Gelişmiş"; a tap turns the caret and brings the section into view. */
    private void showAdvanced(boolean open, boolean tapped) {
        mAdvancedOpen = open;
        mAdvancedGroup.setVisibility(open ? View.VISIBLE : View.GONE);
        mAdvanced.setActivated(open);
        float turn = open ? 180f : 0f;
        boolean motion = tapped && gg.projects.mobile.mods.Motion.enabled(requireContext());
        mAdvancedCaret.animate().cancel();
        if (motion) mAdvancedCaret.animate().rotation(turn).setDuration(180).setInterpolator(gg.projects.mobile.mods.Motion.EASE_OUT).start();
        else mAdvancedCaret.setRotation(turn);
        if (open && tapped) {
            mScroll.post(() -> {
                int y = Math.max(0, mAdvanced.getTop() - Math.round(8 * getResources().getDisplayMetrics().density));
                if (motion) mScroll.smoothScrollTo(0, y);
                else mScroll.scrollTo(0, y);
            });
        }
    }

    private MinecraftProfile getProfile(@NonNull String profile){
        MinecraftProfile minecraftProfile;
        if(getArguments() == null) {
            // EDGE CASE: User leaves Pojav in background. Pojav gets terminated in the background.
            // Current selected fragment and its arguments are saved.
            // User returns to Pojav. Android restarts process and reinitializes fragment without
            // going to the main screen. mainProfileJson and profiles left uninitialized, which
            // results in a crash.
            // Reload the profiles to avoid this edge case.
            LauncherProfiles.load();
            MinecraftProfile originalProfile = LauncherProfiles.mainProfileJson.profiles.get(profile);
            // EDGE CASE: User edits the JSON, so the profile that was edited no longer exists.
            // Create a brand new profile as a fallback for this case.
            if(originalProfile != null) minecraftProfile = new MinecraftProfile(originalProfile);
            else minecraftProfile = MinecraftProfile.createTemplate();
            mProfileKey = profile;
        }else{
            minecraftProfile = MinecraftProfile.createTemplate();
            // Projects: a new profile starts on the recommended Minecraft version (as on the desktop).
            minecraftProfile.lastVersionId = gg.projects.mobile.join.DefaultProfiles.recommended();
            mProfileKey = LauncherProfiles.getFreeProfileKey();
        }
        return minecraftProfile;
    }


    private void bindViews(@NonNull View view){
        mDefaultControl = view.findViewById(R.id.vprof_editor_ctrl_spinner);
        mDefaultRuntime = view.findViewById(R.id.vprof_editor_spinner_runtime);
        mDefaultRenderer = view.findViewById(R.id.vprof_editor_profile_renderer);
        mUseANGLE = view.findViewById(R.id.vprof_editor_profile_angle);
        mDefaultVersion = view.findViewById(R.id.vprof_editor_version_spinner);

        mDefaultPath = view.findViewById(R.id.vprof_editor_path);
        mDefaultName = view.findViewById(R.id.vprof_editor_profile_name);
        mDefaultJvmArgument = view.findViewById(R.id.vprof_editor_jre_args);

        mSaveButton = view.findViewById(R.id.vprof_editor_save_button);
        mDeleteButton = view.findViewById(R.id.vprof_editor_delete_button);
        // MOB-POLISH: "Profili sil" as the sheets' "Komutu sil": red words on the left (deleting is not the goal, and
        // a second look read the same-size "Sil" beside "Kaydet" as its twin).
        mDeleteButton.setBackgroundResource(R.drawable.projects_row_ripple);
        mDeleteButton.setTextColor(androidx.core.content.ContextCompat.getColor(view.getContext(), R.color.projects_flare_400));
        mDeleteButton.setTypeface(androidx.core.content.res.ResourcesCompat.getFont(view.getContext(), R.font.figtree_semibold));
        mDeleteButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        mDeleteButton.setAllCaps(false);
        int side = Math.round(12 * view.getResources().getDisplayMetrics().density);
        mDeleteButton.setPadding(side, 0, side, 0);
        mDeleteButton.setMinHeight(Math.round(48 * view.getResources().getDisplayMetrics().density));
        mDeleteButton.setMinimumHeight(mDeleteButton.getMinHeight());
        mControlSelectButton = view.findViewById(R.id.vprof_editor_ctrl_button);
        mVersionSelectButton = view.findViewById(R.id.vprof_editor_version_button);
        mGameDirButton = view.findViewById(R.id.vprof_editor_path_button);
        mProfileIcon = view.findViewById(R.id.vprof_editor_profile_icon);
        // MOB-POLISH: "Seç" beside a field is the app's secondary action (chamfered, bone), not a grey slab.
        for (Button pick : new Button[]{mControlSelectButton, mVersionSelectButton, mGameDirButton}) {
            pick.setBackground(gg.projects.mobile.ui.ChamferDrawable.secondary(view.getContext()));
            gg.projects.mobile.ui.Press.attach(pick, gg.projects.mobile.ui.Press.Style.BUTTON);
        }
        mScroll = view.findViewById(R.id.projects_editor_scroll);
        mAdvanced = view.findViewById(R.id.projects_editor_advanced);
        mAdvancedGroup = view.findViewById(R.id.projects_editor_advanced_group);
        mAdvancedCaret = view.findViewById(R.id.projects_editor_advanced_caret);
        mAdvanced.setOnClickListener(v -> showAdvanced(!mAdvancedOpen, true));
        gg.projects.mobile.ui.Press.attach(mAdvanced, gg.projects.mobile.ui.Press.Style.ROW);
    }

    private void save(){
        //First, check for potential issues in the inputs
        mTempProfile.lastVersionId = mDefaultVersion.getText().toString();
        mTempProfile.controlFile = mDefaultControl.getText().toString();
        // MOB2: the localized default name left as it was keeps the stored one (the app knows the
        // default profile by it); a name the player typed is the new name.
        mTempProfile.name = gg.projects.mobile.join.ProfileNames.saved(mTempProfile.name,
                mDefaultName.getText().toString(), getString(R.string.projects_profile_default_name));
        mTempProfile.javaArgs = mDefaultJvmArgument.getText().toString()
                .replaceAll("[\r\n]+", " ")
                .trim();
        mTempProfile.gameDir = mDefaultPath.getText().toString();

        if(mTempProfile.controlFile.isEmpty()) mTempProfile.controlFile = null;
        if(mTempProfile.javaArgs.isEmpty()) mTempProfile.javaArgs = null;
        if(mTempProfile.gameDir.isEmpty()) mTempProfile.gameDir = null;

        Runtime selectedRuntime = (Runtime) mDefaultRuntime.getSelectedItem();
        mTempProfile.javaDir = (selectedRuntime.name.equals("<Default>") || selectedRuntime.versionString == null)
                ? null : Tools.LAUNCHERPROFILES_RTPREFIX + selectedRuntime.name;

        if(mDefaultRenderer.getSelectedItemPosition() == mRenderNames.size()) mTempProfile.pojavRendererName = null;
        else mTempProfile.pojavRendererName = mRenderNames.get(mDefaultRenderer.getSelectedItemPosition());


        LauncherProfiles.mainProfileJson.profiles.put(mProfileKey, mTempProfile);
        LauncherProfiles.write();
        ExtraCore.setValue(ExtraConstants.REFRESH_VERSION_SPINNER, mProfileKey);
    }

    @Override
    public void onCropped(Bitmap contentBitmap) {
        mProfileIcon.setImageBitmap(contentBitmap);
        Log.i("bitmap", "w="+contentBitmap.getWidth() +" h="+contentBitmap.getHeight());
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try (Base64OutputStream base64OutputStream = new Base64OutputStream(byteArrayOutputStream, Base64.NO_WRAP)) {
            contentBitmap.compress(
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R ?
                    // On Android < 30, there was no distinction between "lossy" and "lossless",
                    // and the type is picked by the quality parameter. We set the quality to 60.
                    // so it should be lossy,
                    Bitmap.CompressFormat.WEBP:
                    // On Android >= 30, we can explicitly specify that we want lossy compression
                    // with the visual quality of 60.
                    Bitmap.CompressFormat.WEBP_LOSSY,
                60,
                base64OutputStream
            );
            base64OutputStream.flush();
            byteArrayOutputStream.flush();
        }catch (IOException e) {
            Tools.showErrorRemote(e);
            return;
        }
        String iconLine = new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
        mTempProfile.icon = "data:image/webp;base64," + iconLine;
    }

    @Override
    public void onFailed(Exception exception) {
        Tools.showErrorRemote(exception);
    }
}
