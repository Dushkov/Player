package com.brouken.player;

import android.content.Context;
import android.text.InputType;
import android.util.AttributeSet;

import androidx.annotation.Keep;
import androidx.preference.EditTextPreference;

/** Token field for BG subtitles: hidden while typing, summary shows only the last 4 characters. */
@Keep
public class BgSubsTokenPreference extends EditTextPreference {

    public BgSubsTokenPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public BgSubsTokenPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public BgSubsTokenPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BgSubsTokenPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setOnBindEditTextListener(editText -> editText.setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
        setSummaryProvider(preference -> {
            String token = ((EditTextPreference) preference).getText();
            if (token == null || token.isEmpty()) {
                return getContext().getString(R.string.bgsubs_token_not_set);
            }
            return "••••" + token.substring(Math.max(0, token.length() - 4));
        });
    }
}
