package net.kdt.pojavlaunch.fragments;

import static net.kdt.pojavlaunch.Tools.dialogOnUiThread;
import static net.kdt.pojavlaunch.Tools.openPath;
import static net.kdt.pojavlaunch.Tools.runOnUiThread;
import static net.kdt.pojavlaunch.Tools.shareLog;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kdt.mcgui.mcVersionSpinner;

import net.kdt.pojavlaunch.CustomControlsActivity;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.modloaders.LWJGL3ifyUtils;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import java.io.File;
import java.util.List;

import gg.projects.mobile.ui.ProjectsHomeCard;

public class MainMenuFragment extends Fragment {
    public static final String TAG = "MainMenuFragment";

    private mcVersionSpinner mVersionSpinner;
    private ProjectsHomeCard mProjectsCard;
    // Projects fork: the event reminder's bell asks for the notification permission when it is tapped.
    private final ActivityResultLauncher<String> mNotificationPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                if (mProjectsCard != null) mProjectsCard.onNotificationPermission(granted);
            });

    public MainMenuFragment(){
        super(R.layout.fragment_launcher);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mNewsButton = view.findViewById(R.id.news_button);
        Button mDiscordButton = view.findViewById(R.id.discord_button);
        Button mCustomControlButton = view.findViewById(R.id.custom_control_button);
        Button mInstallJarButton = view.findViewById(R.id.install_jar_button);
        Button mShareLogsButton = view.findViewById(R.id.share_logs_button);
        Button mOpenDirectoryButton = view.findViewById(R.id.open_files_button);

        ImageButton mEditProfileButton = view.findViewById(R.id.edit_profile_button);
        Button mPlayButton = view.findViewById(R.id.play_button);
        mVersionSpinner = view.findViewById(R.id.mc_version_spinner);

        // Projects fork: our wiki; the Discord invite comes from the signed launcher config when it
        // has one, else the website's own link, which leads to the current invite.
        mNewsButton.setOnClickListener(v -> Tools.openURL(requireActivity(), Tools.URL_HOME));
        mDiscordButton.setOnClickListener(v -> Tools.openURL(requireActivity(), ProjectsHomeCard.discordUrl(getString(R.string.discord_invite))));
        mCustomControlButton.setOnClickListener(v -> startActivity(new Intent(requireContext(), CustomControlsActivity.class)));
        // Projects fork: every account may run installers, offline ones too (upstream: Microsoft only).
        mInstallJarButton.setOnClickListener(v -> runInstallerWithConfirmation(false));
        mInstallJarButton.setOnLongClickListener(v -> {
            runInstallerWithConfirmation(true);
            return true;
        });
        mEditProfileButton.setOnClickListener(v -> mVersionSpinner.openProfileEditor(requireActivity()));

        // Projects fork: Play starts the game. Upstream first asked about Sodium with a single
        // button that deleted every Sodium-looking jar (pack files included) before starting; Sodium
        // is now held off where it cannot run, and a start it breaks is caught afterwards
        // (gg.projects.mobile.launch.SodiumFallback).
        mPlayButton.setOnClickListener(v -> {
            // Projects fork (M16): the Projects profile always starts through its join, like "Projects'e
            // katıl": the pack is installed and in step first, so OYNA never starts it as plain vanilla
            // (a profile that had not joined yet showed the newest release and started without the
            // pack). This also covers REVIEW1 A2 (Sodium held off but its jars still in place).
            String current = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
            if (gg.projects.mobile.join.ProjectsProfile.KEY.equals(current)) {
                mProjectsCard.playThroughJoin(mPlayButton);
                return;
            }
            // Projects fork (M18): a loader profile of the player's own without mods (upstream's "Fabric"
            // profile) asks once whether Projects, which has the mods, was meant.
            if (mProjectsCard.offerProjectsInstead(mPlayButton)) return;
            ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
        });
        // Projects fork (M14): OYNA's gold face in its socket; the launch check confirms or rejects
        // the press (LauncherActivity). The tool rows are clipped to their rounded group.
        gg.projects.mobile.ui.PlayButtons.decoratePlay(mPlayButton);
        View menuGroup = view.findViewById(R.id.projects_menu_group);
        if (menuGroup != null) menuGroup.setClipToOutline(true);

        mShareLogsButton.setOnClickListener((v) -> shareLog(requireContext()));

        // Projects fork (M18): a new install plays Projects: its profile is there and chosen from the first
        // start (upstream's default profile, plain vanilla, was the only one and so Play's choice).
        if (gg.projects.mobile.join.ProjectsProfile.seedOnce(LauncherPreferences.DEFAULT_PREF,
                gg.projects.mobile.join.DefaultProfiles.recommended())) {
            mVersionSpinner.reloadProfiles();
            int projects = mVersionSpinner.getProfileAdapter().resolveProfileIndex(gg.projects.mobile.join.ProjectsProfile.KEY);
            if (projects >= 0) mVersionSpinner.setProfileSelection(projects);
        }

        mProjectsCard = new ProjectsHomeCard(this, view, mVersionSpinner, mPlayButton, mNotificationPermission);
        // Projects fork: "Tekrar başlat" after Sodium kept the game from starting.
        if (gg.projects.mobile.launch.ProjectsRelaunch.take(requireActivity())) view.postDelayed(() -> startAgain(40), 500);

        mOpenDirectoryButton.setOnClickListener((v)-> {
            if (Tools.isDemoProfile(v.getContext())){ // Say a different message when on demo profile since they might see the hidden demo folder
                dialogOnUiThread(requireActivity(), getString(R.string.demo_unsupported), getString(R.string.change_account));
            } else openPath(v.getContext(), getCurrentProfileDirectory(), false); // Projects fork: offline accounts too

        });


        mNewsButton.setOnLongClickListener((v)->{
            Tools.swapFragment(requireActivity(), GamepadMapperFragment.class, GamepadMapperFragment.TAG, null);
            return true;
        });
    }

    /**
     * Starts the profile that just failed again: the Projects profile through its join (the sync
     * takes Sodium out first). Waits for the start-up unpacking, which Play would refuse to overlap.
     */
    private void startAgain(int triesLeft) {
        if (!isAdded() || getView() == null) return;
        if (com.kdt.mcgui.ProgressLayout.hasProcesses() && triesLeft > 0) {
            getView().postDelayed(() -> startAgain(triesLeft - 1), 300);
            return;
        }
        String current = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, "");
        if (gg.projects.mobile.join.ProjectsProfile.KEY.equals(current)) mProjectsCard.joinNow();
        else ExtraCore.setValue(ExtraConstants.LAUNCH_GAME, true);
    }

    private File getCurrentProfileDirectory() {
        String currentProfile = LauncherPreferences.DEFAULT_PREF.getString(LauncherPreferences.PREF_KEY_CURRENT_PROFILE, null);
        if(!Tools.isValidString(currentProfile)) return new File(Tools.DIR_GAME_NEW);
        LauncherProfiles.load();
        MinecraftProfile profileObject = LauncherProfiles.mainProfileJson.profiles.get(currentProfile);
        if(profileObject == null) return new File(Tools.DIR_GAME_NEW);
        return Tools.getGameDirPath(profileObject);
    }

    @Override
    public void onResume() {
        super.onResume();
        mVersionSpinner.reloadProfiles();
        mProjectsCard.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        mProjectsCard.onPause();
    }

    private void runInstallerWithConfirmation(boolean isCustomArgs) {
        if (ProgressKeeper.getTaskCount() == 0)
            Tools.installMod(requireActivity(), isCustomArgs);
        else
            Toast.makeText(requireContext(), R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
    }
}
