/*
 * Copyright (C) 2026 The uwuAOSP Project
 * SPDX-FileCopyrightText: The uwuAOSP Project
 * SPDX-License-Identifier: Apache-2.0
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.app;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.app.Activity;
import android.content.Context;
import android.content.IClipboard;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.R;

/** Dialog shown when an app with ask-policy accesses the clipboard. */
public class ClipboardAccessPromptActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "ClipboardAccessPrompt";
    private static final String PACKAGE_NAME = "com.android.internal.app";

    private static final String EXTRA_PACKAGE_NAME = PACKAGE_NAME + ".extra.CLIPBOARD_PACKAGE";
    private static final String EXTRA_OPERATION = PACKAGE_NAME + ".extra.CLIPBOARD_OPERATION";
    private static final String EXTRA_PROMPT_ID = PACKAGE_NAME + ".extra.CLIPBOARD_PROMPT_ID";
    private static final String EXTRA_PROMPT_EXPIRES_AT =
            PACKAGE_NAME + ".extra.CLIPBOARD_PROMPT_EXPIRES_AT";

    public static final int OPERATION_READ = 0;
    public static final int OPERATION_WRITE = 1;

    private int mUserId;
    private int mOperation;
    private long mPromptId;
    private long mPromptExpiresAt;
    private final Runnable mExpirePrompt = this::finish;
    private String mPackageName;
    private CheckBox mPermanentRuleView;
    private boolean mPromptResolved;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        setFinishOnTouchOutside(true);

        final Intent intent = getIntent();
        mUserId = intent.getIntExtra(Intent.EXTRA_USER_ID, -1);
        mPackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME);
        mOperation = intent.getIntExtra(EXTRA_OPERATION, -1);
        mPromptId = intent.getLongExtra(EXTRA_PROMPT_ID, 0);
        mPromptExpiresAt = intent.getLongExtra(EXTRA_PROMPT_EXPIRES_AT, 0);
        if (mUserId < 0
                || mPackageName == null
                || mPromptId <= 0
                || (mOperation != OPERATION_READ && mOperation != OPERATION_WRITE)) {
            Log.wtf(TAG, "Invalid clipboard access prompt intent: " + intent);
            mPromptResolved = true;
            finish();
            return;
        }

        if (SystemClock.elapsedRealtime() >= mPromptExpiresAt) {
            finish();
            return;
        }
        showBottomSheet();
        getWindow().getDecorView().postDelayed(mExpirePrompt,
                mPromptExpiresAt - SystemClock.elapsedRealtime());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (SystemClock.elapsedRealtime() >= mPromptExpiresAt) {
            finish();
        }
    }

    private void showBottomSheet() {
        final Window window = getWindow();
        final WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        attributes.windowAnimations = R.style.AutofillHalfScreenAnimation;
        attributes.dimAmount = 0.32f;
        window.setAttributes(attributes);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, dp(16));

        final var metrics = getWindowManager().getCurrentWindowMetrics();
        final Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        final int availableWidth = metrics.getBounds().width() - insets.left - insets.right;
        final int availableHeight = metrics.getBounds().height() - insets.top - insets.bottom;
        final ScrollView scrollView = new ScrollView(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int maxHeight = Math.max(1, availableHeight - dp(32));
                if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                    maxHeight = Math.min(maxHeight, MeasureSpec.getSize(heightMeasureSpec));
                }
                super.onMeasure(widthMeasureSpec,
                        MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            }
        };
        scrollView.setBackground(roundedSurface(
                getColor(R.color.materialColorSurfaceContainerHigh), 28));
        scrollView.setClipToOutline(true);

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(24), dp(24), dp(24));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_clipboard_access_hand);
        icon.setImageTintList(ColorStateList.valueOf(getColor(R.color.materialColorPrimary)));
        icon.setBackground(roundedSurface(getColor(R.color.materialColorPrimaryContainer), 20));
        icon.setPadding(dp(16), dp(16), dp(16), dp(16));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        content.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));

        final TextView titleView = new TextView(this);
        titleView.setTextAppearance(R.style.TextAppearance_DeviceDefault_Headline);
        titleView.setTextSize(28);
        titleView.setTextColor(getColor(R.color.materialColorOnSurface));
        titleView.setAccessibilityHeading(true);
        final boolean isRead = mOperation == OPERATION_READ;
        titleView.setText(isRead ? R.string.clipboard_access_prompt_read_title
                : R.string.clipboard_access_prompt_write_title);
        content.addView(titleView, contentLayoutParams(24));

        final TextView messageView = new TextView(this);
        messageView.setTextAppearance(R.style.TextAppearance_DeviceDefault_Body1);
        messageView.setTextColor(getColor(R.color.materialColorOnSurfaceVariant));
        messageView.setText(getString(isRead ? R.string.clipboard_access_prompt_read_message
                : R.string.clipboard_access_prompt_write_message, loadAppLabel()));
        content.addView(messageView, contentLayoutParams(16));

        mPermanentRuleView = new CheckBox(this);
        mPermanentRuleView.setText(R.string.clipboard_access_write_permanent_rule);
        mPermanentRuleView.setChecked(false);
        mPermanentRuleView.setTextAppearance(R.style.TextAppearance_DeviceDefault_Body1);
        mPermanentRuleView.setTextColor(getColor(R.color.materialColorOnSurface));
        mPermanentRuleView.setButtonTintList(new ColorStateList(
                new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
                new int[] { getColor(R.color.materialColorPrimary),
                        getColor(R.color.materialColorOnSurfaceVariant) }));
        mPermanentRuleView.setMinHeight(dp(48));
        content.addView(mPermanentRuleView, contentLayoutParams(16));

        final LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final Button deny = actionButton(R.id.app_jump_deny_button, R.string.app_jump_deny, true);
        final Button allow = actionButton(R.id.app_jump_allow_button, R.string.app_jump_allow, false);
        buttons.addView(deny, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final LinearLayout.LayoutParams allowParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        allowParams.setMarginStart(dp(12));
        buttons.addView(allow, allowParams);
        content.addView(buttons, contentLayoutParams(24));
        setContentView(scrollView);
        window.setLayout(Math.max(1, Math.min(dp(560), availableWidth - dp(32))),
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private Button actionButton(int id, int text, boolean filled) {
        final Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setId(id);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(56));
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setTextColor(getColor(filled ? R.color.materialColorOnPrimary
                : R.color.materialColorOnSurface));
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(getColor(R.color.materialColorControlHighlight)),
                roundedSurface(getColor(filled ? R.color.materialColorPrimary
                        : R.color.materialColorSurfaceContainerHighest), 28),
                roundedSurface(Color.WHITE, 28)));
        button.setOnClickListener(this);
        return button;
    }

    private GradientDrawable roundedSurface(int color, int radiusDp) {
        final GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radiusDp));
        return background;
    }

    private LinearLayout.LayoutParams contentLayoutParams(int marginTopDp) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(marginTopDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onClick(View view) {
        if (SystemClock.elapsedRealtime() >= mPromptExpiresAt) {
            finish();
            return;
        }
        final int decision =
                view.getId() == R.id.app_jump_allow_button
                        ? Settings.Secure.UWU_APP_CLIPBOARD_POLICY_ALLOW
                        : Settings.Secure.UWU_APP_CLIPBOARD_POLICY_DENY;
        if (!resolvePrompt(decision, mPermanentRuleView.isChecked())) {
            Log.e(TAG, "Unable to apply clipboard choice for " + mPackageName);
            Toast.makeText(this, R.string.clipboard_access_policy_save_failed, Toast.LENGTH_LONG)
                    .show();
            return;
        }
        mPromptResolved = true;
        finish();
    }

    private boolean resolvePrompt(int decision, boolean persist) {
        final IClipboard clipboard =
                IClipboard.Stub.asInterface(ServiceManager.getService(Context.CLIPBOARD_SERVICE));
        if (clipboard == null) {
            return false;
        }
        try {
            return clipboard.resolveClipboardAccessPrompt(
                    mPackageName, mUserId, mOperation, decision, persist, mPromptId);
        } catch (RemoteException e) {
            Log.e(TAG, "Clipboard service rejected the prompt result", e);
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        getWindow().getDecorView().removeCallbacks(mExpirePrompt);
        if (isFinishing() && !mPromptResolved && mPackageName != null) {
            resolvePrompt(Settings.Secure.UWU_APP_CLIPBOARD_POLICY_ASK, false);
            mPromptResolved = true;
        }
        super.onDestroy();
    }

    private CharSequence loadAppLabel() {
        try {
            final ApplicationInfo appInfo =
                    getPackageManager().getApplicationInfoAsUser(mPackageName, 0, mUserId);
            return appInfo.loadSafeLabel(
                    getPackageManager(),
                    PackageItemInfo.DEFAULT_MAX_LABEL_SIZE_PX,
                    PackageItemInfo.SAFE_LABEL_FLAG_FIRST_LINE
                            | PackageItemInfo.SAFE_LABEL_FLAG_TRIM);
        } catch (PackageManager.NameNotFoundException e) {
            return mPackageName;
        }
    }

    public static Intent createIntent(
            Context context, int userId, String packageName, int operation, long promptId,
            long promptExpiresAt) {
        return new Intent()
                .setClass(context, ClipboardAccessPromptActivity.class)
                .putExtra(Intent.EXTRA_USER_ID, userId)
                .putExtra(EXTRA_PACKAGE_NAME, packageName)
                .putExtra(EXTRA_OPERATION, operation)
                .putExtra(EXTRA_PROMPT_ID, promptId)
                .putExtra(EXTRA_PROMPT_EXPIRES_AT, promptExpiresAt)
                .setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    }
}
