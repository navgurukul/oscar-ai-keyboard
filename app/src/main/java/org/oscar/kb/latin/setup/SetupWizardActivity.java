/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package org.oscar.kb.latin.setup;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Message;
import android.provider.Settings;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;
import android.text.SpannableString;


import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.google.firebase.FirebaseApp;
import com.google.firebase.crashlytics.FirebaseCrashlytics;
import org.oscar.kb.R;
import org.oscar.kb.latin.settings.SettingsActivity;
import org.oscar.kb.latin.utils.ActivityThemeUtils;
import org.oscar.kb.latin.utils.JniUtils;
import org.oscar.kb.latin.utils.LeakGuardHandlerWrapper;
import org.oscar.kb.latin.utils.ResourceUtils;
import org.oscar.kb.latin.utils.UncachedInputMethodManagerUtils;

import java.util.ArrayList;

public final class SetupWizardActivity extends AppCompatActivity implements View.OnClickListener {
    // For debugging purpose.
    private static final boolean FORCE_TO_SHOW_WELCOME_SCREEN = false;

    private InputMethodManager mImm;

    private View mSetupWizard;
    private View mWelcomeScreen;
    private View mSetupScreen;
    private View mActionStart;
    private TextView mActionNext;
    private Button start;
    private  String instructionText;
    private  String  spannableStrin;
    private  String  startIndex;

    private  String  endIndex;
    private SharedPreferences sharedPreferences;

    private  TextView text;

    private TextView mStep1Bullet;
    private TextView mActionFinish;
    private SetupStepGroup mSetupStepGroup;
    private static final String STATE_STEP = "step";
    private int mStepNumber;
    private boolean mNeedsToAdjustStepNumberToSystemState;
    private static final int STEP_WELCOME = 0;
    private static final int STEP_1 = 1;
    private static final int STEP_2 = 2;
    private static final int STEP_3 = 3;
    private static final int STEP_LAUNCHING_IME_SETTINGS = 4;
    private static final int STEP_BACK_FROM_IME_SETTINGS = 5;

    private SettingsPoolingHandler mHandler;

    private static final class SettingsPoolingHandler
            extends LeakGuardHandlerWrapper<SetupWizardActivity> {
        private static final int MSG_POLLING_IME_SETTINGS = 0;
        private static final long IME_SETTINGS_POLLING_INTERVAL = 200;

        private final InputMethodManager mImmInHandler;

        public SettingsPoolingHandler(@NonNull final SetupWizardActivity ownerInstance,
                final InputMethodManager imm) {
            super(ownerInstance);
            mImmInHandler = imm;
        }

        @Override
        public void handleMessage(@NonNull final Message msg) {
            final SetupWizardActivity setupWizardActivity = getOwnerInstance();
            if (setupWizardActivity == null) {
                return;
            }
            if (msg.what == MSG_POLLING_IME_SETTINGS) {
                if (UncachedInputMethodManagerUtils.isThisImeEnabled(setupWizardActivity,
                        mImmInHandler)) {
                    setupWizardActivity.invokeSetupWizardOfThisIme();
                    return;
                }
                startPollingImeSettings();
            }
        }

        public void startPollingImeSettings() {
            sendMessageDelayed(obtainMessage(MSG_POLLING_IME_SETTINGS),
                    IME_SETTINGS_POLLING_INTERVAL);
        }

        public void cancelPollingImeSettings() {
            removeMessages(MSG_POLLING_IME_SETTINGS);
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar == null) {
            return;
        }
        FirebaseApp.initializeApp(this);

        actionBar.hide();
        getWindow().setStatusBarColor(getResources().getColor(R.color.setup_background));
        ActivityThemeUtils.setActivityTheme(this);

        mImm = (InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        mHandler = new SettingsPoolingHandler(this, mImm);
        setContentView(R.layout.setup_wizard);
        mSetupWizard = findViewById(R.id.setup_wizard);

        if (savedInstanceState == null) {
            mStepNumber = determineSetupStepNumberFromLauncher();
        } else {
            mStepNumber = savedInstanceState.getInt(STATE_STEP);
        }

        sharedPreferences = getSharedPreferences("keyboard_prefs", Context.MODE_PRIVATE);
        if (isSetupComplete()) {
            navigateToMainActivity();
            return;
        }
        start = findViewById(R.id.getButton);
        start.setOnClickListener(this);
        String instructionText = "    Welcome to \n" + "Oscar Keyboard";
        SpannableString spannableStrin = new SpannableString(instructionText);
        int startIndex = instructionText.indexOf("Oscar Keyboard");
        int endIndex = startIndex + "Oscar Keyboard".length();
        if (startIndex >= 0) {
            // Apply ForegroundColorSpan
            spannableStrin.setSpan(
                    new ForegroundColorSpan(Color.parseColor("#85BDB9")),
                    startIndex,
                    endIndex,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            // Apply StyleSpan
            spannableStrin.setSpan(
                    new StyleSpan(Typeface.BOLD), // Set style to bold
                    startIndex,
                    endIndex,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

        }
        text=findViewById(R.id.welcometxt);
        text.setText(spannableStrin);
    }
    private boolean isSetupComplete() {
        return sharedPreferences.getBoolean("setup_complete", false);
    }

    private void setSetupCompleteFlag() {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean("setup_complete", true);
        editor.apply();
    }

    private void navigateToMainActivity() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            startActivity(intent);
            finish(); // Close the setup wizard activity
        } catch (Exception e) {
            // Log the exception in Firebase Crashlytics
            FirebaseCrashlytics.getInstance().recordException(e);
        }

    }
    @Override
    public void onClick(final View v) {
        if (v == mActionFinish) {
            finish();
            return;
        }
        final int currentStep = determineSetupStepNumber();
        final int nextStep;
        if (v == start) {
            setSetupCompleteFlag(); // Mark setup as complete
            navigateToMainActivity();
            startActivity(new Intent(this, MainActivity.class));
        } else if (v == mActionNext) {
            nextStep = mStepNumber + 1;
        } else if (v == mStep1Bullet && currentStep == STEP_2) {
            nextStep = STEP_1;
        } else {
            nextStep = mStepNumber;
        }
//        if (mStepNumber != nextStep) {
//            mStepNumber = nextStep;
//            updateSetupStepView();
//        }
    }

    void invokeSetupWizardOfThisIme() {
        try {
            final Intent intent = new Intent();
            intent.setClass(this, SetupWizardActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            mNeedsToAdjustStepNumberToSystemState = true;
        } catch (Exception e) {
            // Log the exception in Firebase Crashlytics
            FirebaseCrashlytics.getInstance().recordException(e);
        }

    }

    private void invokeSettingsOfThisIme() {
        final Intent intent = new Intent();
        intent.setClass(this, SettingsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(SettingsActivity.EXTRA_ENTRY_KEY,
                SettingsActivity.EXTRA_ENTRY_VALUE_APP_ICON);
        startActivity(intent);
    }

    void invokeLanguageAndInputSettings() {
        final Intent intent = new Intent();
        intent.setAction(Settings.ACTION_INPUT_METHOD_SETTINGS);
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        startActivity(intent);
        mNeedsToAdjustStepNumberToSystemState = true;
    }

    void invokeInputMethodPicker() {
        // Invoke input method picker.
        mImm.showInputMethodPicker();
        mNeedsToAdjustStepNumberToSystemState = true;
    }

    private int determineSetupStepNumberFromLauncher() {
        final int stepNumber = determineSetupStepNumber();
        if (stepNumber == STEP_1) {
            return STEP_WELCOME;
        }
        if (stepNumber == STEP_3) {
            return STEP_LAUNCHING_IME_SETTINGS;
        }
        return stepNumber;
    }

    private int determineSetupStepNumber() {
        mHandler.cancelPollingImeSettings();
        if (FORCE_TO_SHOW_WELCOME_SCREEN) {
            return STEP_1;
        }
        if (!UncachedInputMethodManagerUtils.isThisImeEnabled(this, mImm)) {
            return STEP_1;
        }
        if (!UncachedInputMethodManagerUtils.isThisImeCurrent(this, mImm)) {
            return STEP_2;
        }
        return STEP_3;
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_STEP, mStepNumber);
    }

    @Override
    protected void onRestoreInstanceState(@NonNull final Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        mStepNumber = savedInstanceState.getInt(STATE_STEP);
    }

    private static boolean isInSetupSteps(final int stepNumber) {
        return stepNumber >= STEP_1 && stepNumber <= STEP_3;
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // Probably the setup wizard has been invoked from "Recent" menu. The setup step number
        // needs to be adjusted to system state, because the state (IME is enabled and/or current)
        // may have been changed.
        if (isInSetupSteps(mStepNumber)) {
            mStepNumber = determineSetupStepNumber();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mStepNumber == STEP_LAUNCHING_IME_SETTINGS) {
            // Prevent white screen flashing while launching settings activity.
            mSetupWizard.setVisibility(View.INVISIBLE);
            invokeSettingsOfThisIme();
            mStepNumber = STEP_BACK_FROM_IME_SETTINGS;
            return;
        }
        if (mStepNumber == STEP_BACK_FROM_IME_SETTINGS) {
            finish();
            return;
        }

            // Log the current step number in Firebase Crashlytics
            FirebaseCrashlytics.getInstance().setCustomKey("Current Step Number", mStepNumber);

            try {
                updateSetupStepView();
            } catch (NullPointerException e) {
                // Log the NullPointerException in Firebase Crashlytics
                FirebaseCrashlytics.getInstance().recordException(e);
                FirebaseCrashlytics.getInstance().log("NullPointerException in updateSetupStepView in onResume");
            } catch (Exception e) {
                // Log any other non-fatal exception in Firebase Crashlytics
                FirebaseCrashlytics.getInstance().recordException(e);
            }
           }

    @Override
    public void onBackPressed() {
        if (mStepNumber == STEP_1) {
            mStepNumber = STEP_WELCOME;
            updateSetupStepView();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && mNeedsToAdjustStepNumberToSystemState) {
            mNeedsToAdjustStepNumberToSystemState = false;
            mStepNumber = determineSetupStepNumber();
            updateSetupStepView();
        }
    }

    private void updateSetupStepView() {
        mSetupWizard.setVisibility(View.VISIBLE);
        final boolean welcomeScreen = (mStepNumber == STEP_WELCOME);
        mWelcomeScreen.setVisibility(welcomeScreen ? View.VISIBLE : View.GONE);
        mSetupScreen.setVisibility(welcomeScreen ? View.GONE : View.VISIBLE);
        if (welcomeScreen) {
            return;
        }
        final boolean isStepActionAlreadyDone = mStepNumber < determineSetupStepNumber();
        mSetupStepGroup.enableStep(mStepNumber, isStepActionAlreadyDone);
        mActionNext.setVisibility(isStepActionAlreadyDone ? View.VISIBLE : View.GONE);
        mActionFinish.setVisibility((mStepNumber == STEP_3) ? View.VISIBLE : View.GONE);
    }

    static final class SetupStep implements View.OnClickListener {
        public final int mStepNo;
        private final View mStepView;
        private final TextView mBulletView;
        private final ColorStateList mTextColorStateList;
        private final String mInstruction;
        private final String mFinishedInstruction;
        private final TextView mActionLabel;
        private Runnable mAction;

        public SetupStep(final int stepNo,final String applicationName, final TextView bulletView,
                final View stepView, final int title, final int instruction,
                final int finishedInstruction, final int actionIcon, final int actionLabel) {
            mStepNo = stepNo;
            mStepView = stepView;
            mBulletView = bulletView;
            final Resources res = stepView.getResources();
            mTextColorStateList = AppCompatResources.getColorStateList(mStepView.getContext(), R.color.setup_step_action_text);

            final TextView titleView = mStepView.findViewById(R.id.setup_step_title);
            titleView.setText(res.getString(title, applicationName));

            mInstruction = (instruction == 0) ? null : res.getString(instruction, applicationName);
            mFinishedInstruction = (finishedInstruction == 0) ? null : res.getString(finishedInstruction, applicationName);

            mActionLabel = mStepView.findViewById(R.id.setup_step_action_label);
            mActionLabel.setText(res.getString(actionLabel));
            final Drawable actionIconDrawable = ResourcesCompat.getDrawable(res, actionIcon, null);
            if (actionIconDrawable == null) {
                return;
            }
            DrawableCompat.setTintList(actionIconDrawable, mTextColorStateList);
            if (actionIcon == 0) {
                final int paddingEnd = mActionLabel.getPaddingEnd();
                mActionLabel.setPaddingRelative(paddingEnd, 0, paddingEnd, 0);
            } else {
                final int size = ResourceUtils.toPx(24, res);
                actionIconDrawable.setBounds(0,0, size, size);
                mActionLabel.setCompoundDrawablesRelative(actionIconDrawable, null, null, null);
            }
        }

        public void setEnabled(final boolean enabled, final boolean isStepActionAlreadyDone) {
            mStepView.setVisibility(enabled ? View.VISIBLE : View.GONE);
            mBulletView.setTextColor(enabled
                    ? mBulletView.getContext().getResources().getColor(R.color.setup_step_action_text_pressed)
                    : mBulletView.getContext().getResources().getColor(R.color.setup_text_action));
            final TextView instructionView = mStepView.findViewById(R.id.setup_step_instruction);
            instructionView.setText(isStepActionAlreadyDone ? mFinishedInstruction : mInstruction);
            mActionLabel.setVisibility(isStepActionAlreadyDone ? View.GONE : View.VISIBLE);
        }

        public void setAction(final Runnable action) {
            mActionLabel.setOnClickListener(this);
            mAction = action;
        }

        @Override
        public void onClick(final View v) {
            if (v == mActionLabel && mAction != null)
                mAction.run();
        }
    }

    static final class SetupStepGroup {
        private final SetupStepIndicatorView mIndicatorView;
        private final ArrayList<SetupStep> mGroup = new ArrayList<>();

        public SetupStepGroup(final SetupStepIndicatorView indicatorView) {
            mIndicatorView = indicatorView;
        }

        public void addStep(final SetupStep step) {
            mGroup.add(step);
        }

        public void enableStep(final int enableStepNo, final boolean isStepActionAlreadyDone) {
            for (final SetupStep step : mGroup) {
                step.setEnabled(step.mStepNo == enableStepNo, isStepActionAlreadyDone);
            }
            mIndicatorView.setIndicatorPosition(enableStepNo - STEP_1, mGroup.size());
        }
    }
}
