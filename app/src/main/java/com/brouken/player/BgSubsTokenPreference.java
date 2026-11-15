package com.brouken.player;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Keep;
import androidx.preference.EditTextPreference;

/** Вход row in Settings → BG субтитри: shows whether this device is signed in and offers to sign out. */
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
        setSummaryProvider(preference -> {
            String token = ((EditTextPreference) preference).getText();
            return getContext().getString(token == null || token.isEmpty()
                    ? R.string.bgsubs_login_status_out : R.string.bgsubs_login_status_in);
        });
    }

    /** No text field here: logging in happens with the password from the BG button; this only signs out. */
    @Override
    protected void onClick() {
        String token = getText();
        if (token == null || token.isEmpty()) {
            new android.app.AlertDialog.Builder(getContext(), android.R.style.Theme_Material_Dialog_Alert)
                    .setMessage(R.string.bgsubs_login_how)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        new android.app.AlertDialog.Builder(getContext(), android.R.style.Theme_Material_Dialog_Alert)
                .setMessage(R.string.bgsubs_logout_confirm)
                .setPositiveButton(R.string.bgsubs_logout, (d, w) -> setText(null))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
