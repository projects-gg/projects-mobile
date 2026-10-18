package com.kdt.mcgui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatSpinner;
import androidx.core.content.ContextCompat;


import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.listener.DoneListener;
import net.kdt.pojavlaunch.authenticator.listener.ErrorListener;
import net.kdt.pojavlaunch.authenticator.listener.ProgressListener;
import net.kdt.pojavlaunch.authenticator.microsoft.PresentedException;
import net.kdt.pojavlaunch.authenticator.microsoft.MicrosoftBackgroundLogin;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.extra.ExtraListener;
import net.kdt.pojavlaunch.value.MinecraftAccount;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import gg.projects.mobile.mods.Motion;
import gg.projects.mobile.ui.PixelHeadDrawable;
import gg.projects.mobile.ui.Press;
import gg.projects.mobile.ui.TopBarLine;

/**
 * The account picker. Projects fork (M14): drawn as the top bar's account chip (the desktop's
 * account switcher: crisp head, name, small caret; "Hesap ekle" in gold without an account) with
 * a branded drop-down list; the sign-in progress moved to the bar's line ({@link TopBarLine}).
 */
public class mcAccountSpinner extends AppCompatSpinner implements AdapterView.OnItemSelectedListener {
    public mcAccountSpinner(@NonNull Context context) {
        this(context, null);
    }
    public mcAccountSpinner(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private final List<String> mAccountList = new ArrayList<>(2);
    private MinecraftAccount mSelectecAccount = null;

    /* When a login is performed in the background, we need to know where we are */
    private final static int MAX_LOGIN_STEP = 5;
    private int mLoginStep = 0;

    /* Projects fork: the chip measured on the selected account alone (see onMeasure). */
    private View mMeasureView;

    /* Login listeners */
    private final ProgressListener mProgressListener = step -> {
        mLoginStep = step;
        TopBarLine line = loginLine();
        if (line != null) line.showProgress(step, MAX_LOGIN_STEP);
    };

    private final DoneListener mDoneListener = account -> {
        // Projects fork: an offline account was added, nobody signed in.
        Context toastContext = getContext();
        Toast.makeText(toastContext, account.isLocal()
                ? toastContext.getString(R.string.projects_offline_added, account.username)
                : toastContext.getString(R.string.main_login_done), Toast.LENGTH_SHORT).show();
        TopBarLine line = loginLine();
        if (line != null) line.finish();

        // Check if the account being added is not one that is already existing
        // Like login twice on the same mc account...
        for(String mcAccountName : mAccountList){
            if(mcAccountName.equals(account.username)) return;
        }

        mSelectecAccount = account;
        invalidate();
        mAccountList.add(account.username);
        reloadAccounts(false, mAccountList.size() -1);
    };

    private final ErrorListener mErrorListener = errorMessage -> {
        TopBarLine line = loginLine();
        if (line != null) line.fail();
        Context context = getContext();
        if(errorMessage instanceof PresentedException) {
            PresentedException exception = (PresentedException) errorMessage;
            Throwable cause = exception.getCause();
            if(cause == null) {
                Tools.dialog(context, context.getString(R.string.global_error), exception.toString(context));
            }else {
                Tools.showError(context, exception.toString(context), exception.getCause());
            }
        }else {
            Tools.showError(getContext(), errorMessage);
        }
        invalidate();
    };

    /* Triggered when we need to do microsoft login */
    private final ExtraListener<Uri> mMicrosoftLoginListener = (key, value) -> {
        TopBarLine line = loginLine();
        if (line != null) line.showProgress(0, MAX_LOGIN_STEP);
        new MicrosoftBackgroundLogin(false, value.getQueryParameter("code")).performLogin(
                mProgressListener, mDoneListener, mErrorListener);
        return false;
    };

    /* Triggered when we need to perform mojang login */
    private final ExtraListener<String[]> mMojangLoginListener = (key, value) -> {
        if(value[1].isEmpty()){ // Test mode
            // Projects fork: a name already in the list (ignoring case) switches to that account,
            // so no account file is ever overwritten, a Microsoft account's included.
            String existing = gg.projects.mobile.accounts.OfflineAccounts.findExisting(value[0],
                    mAccountList.subList(1, mAccountList.size()));
            if (existing != null) {
                pickAccount(mAccountList.indexOf(existing));
                Toast.makeText(getContext(), getContext().getString(R.string.projects_offline_switched, existing), Toast.LENGTH_SHORT).show();
                return false;
            }
            MinecraftAccount account = new MinecraftAccount();
            account.username = value[0];
            try {
                account.save();
            }catch (IOException e){
                Log.e("McAccountSpinner", "Failed to save the account : " + e);
            }

            mDoneListener.onLoginDone(account);
        }
        return false;
    };


    private void init(){
        // Projects fork (M14): a chip in the top bar, with a branded drop-down below it.
        Context context = getContext();
        setPopupBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.projects_popup_background));
        float density = getResources().getDisplayMetrics().density;
        setDropDownWidth(Math.round(300 * density));
        setDropDownVerticalOffset(Math.round(52 * density));
        Press.attach(this, Press.Style.BUTTON);

        // Set behavior
        reloadAccounts(true, 0);
        setOnItemSelectedListener(this);

        ExtraCore.addExtraListener(ExtraConstants.MOJANG_LOGIN_TODO, mMojangLoginListener);
        ExtraCore.addExtraListener(ExtraConstants.MICROSOFT_LOGIN_TODO, mMicrosoftLoginListener);
    }

    @Nullable
    private TopBarLine loginLine() {
        View root = getRootView();
        View line = root == null ? null : root.findViewById(R.id.projects_top_line);
        return line instanceof TopBarLine ? (TopBarLine) line : null;
    }


    @Override
    public final void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if(position == 0){  // Add account button
            if(mAccountList.size() > 1){
                ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
                // Projects fork: the chip keeps showing the account in use (upstream left "add
                // account" selected when the player backed out of adding one).
                int current = mSelectecAccount == null ? -1 : mAccountList.indexOf(mSelectecAccount.username);
                if (current > 0) post(() -> setSelection(current, false));
            }
            return;
        }

        pickAccount(position);
        if(mSelectecAccount != null)
            performLogin(mSelectecAccount);
    }

    @Override
    public final void onNothingSelected(AdapterView<?> parent) {}

    /**
     * Projects fork: with no account the chip is a button to the account screen (upstream used a
     * touch listener that never released the pressed state); otherwise the list opens and the
     * caret turns up until it closes.
     */
    @Override
    public boolean performClick() {
        if (mAccountList.size() <= 1) {
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
            playSoundEffect(android.view.SoundEffectConstants.CLICK);
            ExtraCore.setValue(ExtraConstants.SELECT_AUTH_METHOD, true);
            return true;
        }
        boolean opened = super.performClick();
        if (opened) turnCaret(180f);
        return opened;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        // The list is a popup window: the bar's window gets its focus back when it closes.
        if (hasWindowFocus) turnCaret(0f);
    }

    private void turnCaret(float degrees) {
        View selected = getSelectedView();
        View caret = selected == null ? null : selected.findViewById(R.id.projects_account_chip_caret);
        if (caret == null || caret.getRotation() == degrees) return;
        caret.animate().cancel();
        if (!Motion.enabled(getContext())) {
            caret.setRotation(degrees);
            return;
        }
        caret.animate().rotation(degrees).setDuration(200).setInterpolator(Motion.EASE_OUT).start();
    }

    /**
     * Projects fork: a chip as wide as the account it shows (the platform spinner takes the width
     * of its widest entry).
     */
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) return;
        SpinnerAdapter adapter = getAdapter();
        int position = getSelectedItemPosition();
        if (adapter == null || position < 0 || position >= adapter.getCount()) return;
        mMeasureView = adapter.getView(position, mMeasureView, this);
        if (mMeasureView.getLayoutParams() == null) {
            mMeasureView.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        mMeasureView.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.AT_MOST));
        int width = mMeasureView.getMeasuredWidth() + getPaddingLeft() + getPaddingRight();
        int max = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) width = Math.min(width, max);
        setMeasuredDimension(width, getMeasuredHeight());
    }

    public void removeCurrentAccount(){
        removeAccount(getSelectedItemPosition());
    }

    private void removeAccount(int position) {
        if(position == 0) return;
        File accountFile = new File(Tools.DIR_ACCOUNT_NEW, mAccountList.get(position)+".json");
        if(accountFile.exists()) accountFile.delete();
        mAccountList.remove(position);

        reloadAccounts(false, 0);
    }

    /** Allows checking whether we have an online account */
    public boolean isAccountOnline(){
        return mSelectecAccount != null && !mSelectecAccount.accessToken.equals("0");
    }

    public MinecraftAccount getSelectedAccount(){
        return mSelectecAccount;
    }

    public int getLoginState(){
        return mLoginStep;
    }

    public boolean isLoginDone(){
        return mLoginStep >= MAX_LOGIN_STEP;
    }

    /**
     * Reload the spinner, from memory or from scratch. A default account can be selected
     * @param fromFiles Whether we use files as the source of truth
     * @param overridePosition Force the spinner to be at this position, if not 0
     */
    private void reloadAccounts(boolean fromFiles, int overridePosition){
        if(fromFiles){
            mAccountList.clear();

            mAccountList.add(getContext().getString(R.string.main_add_account));
            File accountFolder = new File(Tools.DIR_ACCOUNT_NEW);
            if(accountFolder.exists()){
                for (String fileName : accountFolder.list()) {
                    mAccountList.add(fileName.substring(0, fileName.length() - 5));
                }
            }
        }

        String[] accountArray = mAccountList.toArray(new String[0]);
        AccountAdapter accountAdapter = new AccountAdapter(getContext(), R.layout.item_minecraft_account, accountArray);
        accountAdapter.setDropDownViewResource(R.layout.item_minecraft_account);
        setAdapter(accountAdapter);
        mMeasureView = null;

        // Pick what's available, might just be the the add account "button"
        pickAccount(overridePosition == 0 ? -1 : overridePosition);
        if(mSelectecAccount != null)
            performLogin(mSelectecAccount);

        updateChipBackground();
        requestLayout();
    }

    /** Projects fork: the gold "Hesap ekle" pill while there is no account, else the quiet one. */
    private void updateChipBackground() {
        boolean empty = mAccountList.size() <= 1;
        setBackgroundResource(empty ? R.drawable.projects_account_chip_add : R.drawable.projects_account_chip);
        setContentDescription(empty ? getContext().getString(R.string.main_add_account)
                : mSelectecAccount == null ? null
                : getContext().getString(R.string.projects_account_chip, mSelectecAccount.username));
    }

    private void performLogin(MinecraftAccount minecraftAccount){
        // Logging in when there's no internet is useless. This should really be turned into a network callback though.
        if(!Tools.isOnline(getContext())){
            return;
        }
        if(minecraftAccount.isLocal()) return;

        if(minecraftAccount.isMicrosoft){
            if(System.currentTimeMillis() > minecraftAccount.expiresAt){
                // Perform login only if needed
                TopBarLine line = loginLine();
                if (line != null) line.showProgress(0, MAX_LOGIN_STEP);
                new MicrosoftBackgroundLogin(true, minecraftAccount.msaRefreshToken)
                        .performLogin(mProgressListener, mDoneListener, mErrorListener);
            }
            return;
        }
    }

    /** Pick the selected account, the one in settings if 0 is passed */
    private void pickAccount(int position){
        MinecraftAccount selectedAccount;
        if(position != -1){
            PojavProfile.setCurrentProfile(getContext(), mAccountList.get(position));
            selectedAccount = PojavProfile.getCurrentProfileContent(getContext(), mAccountList.get(position));

            // WORKAROUND
            // Account file corrupted due to previous versions having improper encoding
            if (selectedAccount == null){
                Context ctx = Objects.requireNonNull(getContext());

                new AlertDialog.Builder(ctx)
                        .setCancelable(false)
                        .setTitle(R.string.account_corrupted)
                        .setMessage(R.string.login_again)
                        .setPositiveButton(R.string.delete_account_and_login, (dialog, which) -> {
                            removeCurrentAccount();
                            pickAccount(-1);
                            setSelection(0);
                        })
                        .show();


            }
            setSelection(position);
        }else {
            // Get the current profile, or the first available profile if the wanted one is unavailable
            selectedAccount = PojavProfile.getCurrentProfileContent(getContext(), null);
            int spinnerPosition = selectedAccount == null
                    ? mAccountList.size() <= 1 ? 0 : 1
                    : mAccountList.indexOf(selectedAccount.username);
            setSelection(spinnerPosition, false);
        }

        mSelectecAccount = selectedAccount;
        updateChipBackground();
        mMeasureView = null;
        requestLayout();
    }

    private class AccountAdapter extends ArrayAdapter<String> {

        private final HashMap<String, Drawable> mImageCache = new HashMap<>();
        public AccountAdapter(@NonNull Context context, int resource, @NonNull String[] objects) {
            super(context, resource, objects);
        }

        /** Projects fork: the crisp head of {@code username} at {@code sizeDp}, or null. */
        @Nullable
        private Drawable head(String username, int sizeDp) {
            String key = username + "@" + sizeDp;
            Drawable cached = mImageCache.get(key);
            if (cached != null) return cached;
            Bitmap face = MinecraftAccount.getSkinFace(username);
            if (face == null) return null;
            float density = getContext().getResources().getDisplayMetrics().density;
            Drawable drawable = new PixelHeadDrawable(face, Math.round(sizeDp * density), 3 * density);
            mImageCache.put(key, drawable);
            return drawable;
        }

        @Override
        public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if(convertView == null){
                convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_minecraft_account, parent, false);
                Press.attach(convertView, Press.Style.ROW);
            }

            TextView textview = convertView.findViewById(R.id.account_item);
            ImageView headView = convertView.findViewById(R.id.projects_account_row_head);
            ImageView deleteButton = convertView.findViewById(R.id.delete_account_button);
            textview.setText(super.getItem(position));

            // Handle the "Add account section"
            if(position == 0) {
                headView.setImageResource(R.drawable.projects_ic_user_plus);
                headView.setImageTintList(ContextCompat.getColorStateList(parent.getContext(), R.color.projects_gold_300));
                textview.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.projects_gold_200));
                deleteButton.setVisibility(View.GONE);
            }
            else {
                String username = super.getItem(position);
                headView.setImageTintList(null);
                headView.setImageDrawable(head(username, 32));
                textview.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.projects_bone_100));

                deleteButton.setVisibility(View.VISIBLE);
                Press.attach(deleteButton, Press.Style.ICON);
                deleteButton.setContentDescription(parent.getContext().getString(R.string.projects_account_remove, username));
                deleteButton.setOnClickListener(v -> {
                    showDeleteDialog(getContext(), position);
                });
            }
            return convertView;
        }



        /** Projects fork (M14): the chip itself (head, name, caret), or "Hesap ekle" in gold. */
        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            if (convertView == null || convertView.findViewById(R.id.projects_account_chip_name) == null) {
                convertView = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_projects_account_chip, parent, false);
            }
            ImageView headView = convertView.findViewById(R.id.projects_account_chip_head);
            TextView name = convertView.findViewById(R.id.projects_account_chip_name);
            View caret = convertView.findViewById(R.id.projects_account_chip_caret);
            Context context = parent.getContext();
            if (position == 0) {
                headView.setImageResource(R.drawable.projects_ic_user_plus);
                headView.setImageTintList(ContextCompat.getColorStateList(context, R.color.projects_gold_300));
                name.setText(R.string.main_add_account);
                name.setTextColor(ContextCompat.getColor(context, R.color.projects_gold_200));
                caret.setVisibility(View.GONE);
            } else {
                String username = super.getItem(position);
                Drawable head = head(username, 24);
                headView.setImageTintList(null);
                headView.setImageDrawable(head);
                headView.setVisibility(head == null ? View.GONE : View.VISIBLE);
                name.setText(username);
                name.setTextColor(ContextCompat.getColor(context, R.color.projects_bone_100));
                caret.setVisibility(View.VISIBLE);
            }
            return convertView;
        }

        private void showDeleteDialog(Context context, int position) {
            new AlertDialog.Builder(context)
                    .setMessage(R.string.warning_remove_account)
                    .setPositiveButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.global_delete, (dialog, which) -> {
                        onDetachedFromWindow();
                        removeAccount(position);
                    })
                    .show();
        }
    }



}
