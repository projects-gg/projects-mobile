package gg.projects.mobile.cloud.ui;

import android.content.Context;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;

import androidx.annotation.Nullable;

import gg.projects.mobile.cloud.CloudWords;
import gg.projects.mobile.cloud.PlusConfig;
import gg.projects.mobile.cloud.TrGrammar;

/**
 * An in-game amount field: "40k", "40 bin", "37.500" or digits; the words under it say what was typed ("kırk bin"),
 * so a missing zero shows before anything is saved (the launcher's and the site's amount field).
 */
final class PlusAmount {
    final CloudUi.Field field;
    private final Context mContext;
    @Nullable
    private Runnable mChanged;

    PlusAmount(Context c, String label, long value) {
        mContext = c;
        field = CloudUi.field(c, label, value > 0 ? CloudWords.money(c, value) : "", null,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        say(field.value());
        field.input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                field.setError(null);
                say(s.toString());
                if (mChanged != null) mChanged.run();
            }
        });
    }

    /** Runs after every change of the typed text. */
    PlusAmount onChange(Runnable changed) {
        mChanged = changed;
        return this;
    }

    private void say(String typed) {
        Long v = PlusConfig.parseAmount(typed);
        field.setHelp(v == null ? null : TrGrammar.words(v, CloudWords.lang(mContext)));
    }

    @Nullable
    Long value() {
        return PlusConfig.parseAmount(field.value());
    }

    /** Whether the field holds something that is not an amount (empty is not that). */
    boolean unreadable() {
        return !field.value().trim().isEmpty() && value() == null;
    }

    void set(long value) {
        field.input.setText(value > 0 ? CloudWords.money(mContext, value) : "");
    }
}
