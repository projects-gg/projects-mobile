package net.kdt.pojavlaunch.fragments;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

public class SelectAuthFragment extends Fragment {
    public static final String TAG = "AUTH_SELECT_FRAGMENT";

    public SelectAuthFragment(){
        super(R.layout.fragment_select_auth_method);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Button mMicrosoftButton = view.findViewById(R.id.button_microsoft_authentication);
        Button mLocalButton = view.findViewById(R.id.button_local_authentication);
        // Projects: an offline account needs no Microsoft account (the owner's decision of
        // 17 October 2026; upstream asked for one that owns the game first). As on the desktop's
        // "Hesap ekle", Microsoft stays the main action and the offline account the quiet
        // secondary one (design.md: one loud thing per screen).
        mLocalButton.setBackground(gg.projects.mobile.ui.ChamferDrawable.secondary(view.getContext()));
        mLocalButton.setTextColor(androidx.core.content.ContextCompat.getColor(view.getContext(), R.color.projects_bone_100));

        mMicrosoftButton.setOnClickListener(v -> Tools.swapFragment(requireActivity(), MicrosoftLoginFragment.class, MicrosoftLoginFragment.TAG, null));
        mLocalButton.setOnClickListener(v -> Tools.swapFragment(requireActivity(), LocalLoginFragment.class, LocalLoginFragment.TAG, null));
    }
}
