package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.text.style.MetricAffectingSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.PojavProfile;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;

import java.util.List;

import gg.projects.mobile.accounts.OfflineAccounts;

/**
 * The offline account: a player name, nothing else. Projects fork: no Microsoft account is needed
 * first (the owner's decision of 17 October 2026; upstream sent players without one back to the
 * main menu). The name is checked as it is typed with the desktop's rules ({@link OfflineAccounts});
 * a name already in the list switches to that account instead of adding it twice.
 */
public class LocalLoginFragment extends Fragment {
    public static final String TAG = "LOCAL_LOGIN_FRAGMENT";

    private EditText mUsernameEditText;
    private TextView mMessageView;
    private Button mLoginButton;
    private List<String> mAccounts;
    /** "Empty" and "too short" wait until the player submits, as on the desktop. */
    private boolean mSubmitted;

    public LocalLoginFragment(){
        super(R.layout.fragment_local_login);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mAccounts = PojavProfile.getAllProfilesList();
        mUsernameEditText = view.findViewById(R.id.login_edit_email);
        mMessageView = view.findViewById(R.id.login_message);
        mLoginButton = view.findViewById(R.id.login_button);
        mMessageView.setMovementMethod(LinkMovementMethod.getInstance());

        mUsernameEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                mSubmitted = false;
                render();
            }
        });
        mUsernameEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (event == null) {
                // The on-screen keyboard's Done.
                if (actionId != EditorInfo.IME_ACTION_DONE) return false;
                submit();
                return true;
            }
            int key = event.getKeyCode();
            if (key != KeyEvent.KEYCODE_ENTER && key != KeyEvent.KEYCODE_NUMPAD_ENTER) return false;
            // A hardware keyboard's Enter: the press is taken here and the account is added on the
            // release, so the release cannot reach the main menu that opens next (it opened the
            // account list there). Holding the key repeats the press, never the add.
            if (event.getAction() == KeyEvent.ACTION_UP) submit();
            return true;
        });
        mLoginButton.setOnClickListener(v -> submit());
        render();

        if (savedInstanceState == null) focusWhenShown(view);
    }

    @Override
    public void onPause() {
        super.onPause();
        hideKeyboard();
    }

    /**
     * Focuses the field and opens the keyboard once the screen is on display: the fragment swap
     * keeps the new view hidden for a frame or two (alpha 0, then invisible). Nothing in this
     * layout may animate its bounds (a LayoutTransition did): a view that moves farther than its
     * own height under such an animation passes through a negative size, and Android drops its
     * focus, which left the keyboard open with nowhere to type.
     */
    private void focusWhenShown(View root) {
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (!mUsernameEditText.hasFocus() && (!mUsernameEditText.isShown() || root.getAlpha() < 1f)) return true;
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                if (mUsernameEditText.hasFocus() || !mUsernameEditText.requestFocus()) return true;
                InputMethodManager keyboard = (InputMethodManager) mUsernameEditText.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (keyboard != null) keyboard.showSoftInput(mUsernameEditText, InputMethodManager.SHOW_IMPLICIT);
                return true;
            }
        });
    }

    private void submit() {
        mSubmitted = true;
        OfflineAccounts.Check check = render();
        if (check.problem != null) return;
        hideKeyboard();
        // The account list adds the account, or switches to the one that has this name.
        String name = check.existing != null ? check.existing : check.name;
        ExtraCore.setValue(ExtraConstants.MOJANG_LOGIN_TODO, new String[]{name, ""});
        Tools.backToMainMenu(requireActivity());
    }

    /** Shows what the current text would do: a problem (flare), a name already in the list, or nothing. */
    private OfflineAccounts.Check render() {
        OfflineAccounts.Check check = OfflineAccounts.check(mUsernameEditText.getText().toString(), mAccounts, mSubmitted);
        Context context = requireContext();
        boolean problem = check.problem != null;
        mUsernameEditText.setActivated(problem);
        mLoginButton.setText(!problem && check.existing != null ? R.string.projects_offline_switch : R.string.projects_offline_add);
        if (problem) {
            SpannableStringBuilder text = new SpannableStringBuilder(context.getString(problemText(check.problem)));
            if (check.suggestion != null) {
                text.append(' ');
                appendSuggestion(context, text, check.suggestion);
            }
            mMessageView.setTextColor(ContextCompat.getColor(context, R.color.projects_flare_400));
            mMessageView.setText(text);
        } else if (check.existing != null) {
            mMessageView.setTextColor(ContextCompat.getColor(context, R.color.projects_ash_400));
            mMessageView.setText(R.string.projects_offline_exists);
        } else {
            mMessageView.setText(null);
        }
        return check;
    }

    @StringRes
    private static int problemText(OfflineAccounts.Problem problem) {
        switch (problem) {
            case EMPTY: return R.string.projects_offline_name_empty;
            case TOO_SHORT: return R.string.projects_offline_name_too_short;
            case TOO_LONG: return R.string.projects_offline_name_too_long;
            case TURKISH: return R.string.projects_offline_name_turkish;
            case SPACES: return R.string.projects_offline_name_spaces;
            default: return R.string.projects_offline_name_characters;
        }
    }

    /** "Golge_Avci kullan" in gold, kept on one line: puts the suggestion into the field. */
    private void appendSuggestion(Context context, SpannableStringBuilder text, final String suggestion) {
        int start = text.length();
        text.append(context.getString(R.string.projects_offline_use_suggestion, suggestion).replace(' ', (char) 0xA0));
        int end = text.length();
        final int gold = ContextCompat.getColor(context, R.color.projects_gold_300);
        text.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                mUsernameEditText.setText(suggestion);
                mUsernameEditText.setSelection(suggestion.length());
            }

            @Override
            public void updateDrawState(@NonNull TextPaint paint) {
                paint.setColor(gold);
                paint.setUnderlineText(false);
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Typeface semibold = ResourcesCompat.getFont(context, R.font.figtree_semibold);
        if (semibold != null) text.setSpan(new FontSpan(semibold), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void hideKeyboard() {
        if (mUsernameEditText == null) return;
        InputMethodManager keyboard = (InputMethodManager) mUsernameEditText.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(mUsernameEditText.getWindowToken(), 0);
    }

    /** A font for part of a text (TypefaceSpan takes a Typeface only from Android 9). */
    private static final class FontSpan extends MetricAffectingSpan {
        private final Typeface mTypeface;

        FontSpan(Typeface typeface) {
            mTypeface = typeface;
        }

        @Override
        public void updateMeasureState(@NonNull TextPaint paint) {
            paint.setTypeface(mTypeface);
        }

        @Override
        public void updateDrawState(TextPaint paint) {
            paint.setTypeface(mTypeface);
        }
    }
}
