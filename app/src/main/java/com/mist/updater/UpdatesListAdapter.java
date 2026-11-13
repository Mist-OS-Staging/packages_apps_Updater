/*
 * Copyright (C) 2017-2024 The LineageOS Project
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
package com.mist.updater;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.text.TextUtils;
import android.os.PowerManager;
import android.text.SpannableString;
import android.text.format.Formatter;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.view.menu.MenuBuilder;
import androidx.appcompat.view.menu.MenuPopupHelper;
import androidx.appcompat.widget.PopupMenu;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;

import com.mist.updater.controller.UpdaterController;
import com.mist.updater.controller.UpdaterService;
import com.mist.updater.misc.BuildInfoUtils;
import com.mist.updater.misc.Constants;
import com.mist.updater.misc.StringGenerator;
import com.mist.updater.misc.Utils;
import com.mist.updater.model.UpdateInfo;
import com.mist.updater.model.UpdateStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class UpdatesListAdapter extends RecyclerView.Adapter<UpdatesListAdapter.ViewHolder> {

    private static final String TAG = "UpdateListAdapter";

    private static final int BATTERY_PLUGGED_ANY = BatteryManager.BATTERY_PLUGGED_AC
            | BatteryManager.BATTERY_PLUGGED_USB
            | BatteryManager.BATTERY_PLUGGED_WIRELESS;

    private final float mAlphaDisabledValue;

    private List<String> mDownloadIds;
    private String mSelectedDownload;
    private UpdaterController mUpdaterController;
    private final UpdatesListActivity mActivity;

    private AlertDialog infoDialog;

    private enum Action {
        DOWNLOAD,
        PAUSE,
        RESUME,
        INSTALL,
        INFO,
        DELETE,
        CANCEL_INSTALLATION,
        REBOOT,
    }

    /** Step tracker stages: download → verify → install → restart. */
    private static final int STAGE_NONE = -1;
    private static final int STAGE_DOWNLOAD = 0;
    private static final int STAGE_VERIFY = 1;
    private static final int STAGE_INSTALL = 2;
    private static final int STAGE_RESTART = 3;

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final Button mAction;
        private final ImageButton mMenu;

        private final TextView mBuildDate;
        private final TextView mBuildVersion;
        private final TextView mBuildSize;

        private final LinearLayout mProgress;
        private final ProgressBar mProgressBar;
        private final TextView mProgressText;
        private final TextView mPercentage;

        // Material 3 Expressive card extras (absent on the TV layout).
        @Nullable private final TextView mBuildTypeChip;
        @Nullable private final TextView mDeviceChip;
        @Nullable private final View mSteps;
        @Nullable private final View[] mStepRows;
        @Nullable private final TextView mHint;
        @Nullable private final Button mSecondary;

        public ViewHolder(final View view) {
            super(view);
            mAction = view.findViewById(R.id.update_action);
            mMenu = view.findViewById(R.id.update_menu);

            mBuildDate = view.findViewById(R.id.build_date);
            mBuildVersion = view.findViewById(R.id.build_version);
            mBuildSize = view.findViewById(R.id.build_size);

            mProgress = view.findViewById(R.id.progress);
            mProgressBar = view.findViewById(R.id.progress_bar);
            mProgressText = view.findViewById(R.id.progress_text);
            mPercentage = view.findViewById(R.id.progress_percent);

            mBuildTypeChip = view.findViewById(R.id.build_type_chip);
            mDeviceChip = view.findViewById(R.id.build_device_chip);
            mSteps = view.findViewById(R.id.steps);
            mStepRows = mSteps == null ? null : new View[] {
                    view.findViewById(R.id.step_1), view.findViewById(R.id.step_2),
                    view.findViewById(R.id.step_3), view.findViewById(R.id.step_4)};
            mHint = view.findViewById(R.id.update_hint);
            mSecondary = view.findViewById(R.id.update_secondary);
        }

        boolean isExpressive() {
            return mSteps != null;
        }
    }

    public UpdatesListAdapter(UpdatesListActivity activity) {
        mActivity = activity;

        TypedValue tv = new TypedValue();
        mActivity.getTheme().resolveAttribute(android.R.attr.disabledAlpha, tv, true);
        mAlphaDisabledValue = tv.getFloat();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup viewGroup, int i) {
        View view = LayoutInflater.from(viewGroup.getContext())
                .inflate(R.layout.update_item_view, viewGroup, false);
        return new ViewHolder(view);
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ViewHolder holder) {
        super.onViewDetachedFromWindow(holder);

        if (infoDialog != null) {
            infoDialog.dismiss();
        }
    }

    public void setUpdaterController(UpdaterController updaterController) {
        mUpdaterController = updaterController;
        notifyDataSetChanged();
    }

    private void handleActiveStatus(ViewHolder viewHolder, UpdateInfo update) {
        boolean canDelete = false;

        final String downloadId = update.getDownloadId();
        if (mUpdaterController.isDownloading(downloadId)) {
            canDelete = true;
            String downloaded = Formatter.formatShortFileSize(mActivity,
                    update.getFile().length());
            String total = Formatter.formatShortFileSize(mActivity, update.getFileSize());
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getProgress() / 100.f);
            viewHolder.mPercentage.setText(percentage);
            long eta = update.getEta();
            if (eta > 0) {
                CharSequence etaString = StringGenerator.formatETA(mActivity, eta * 1000);
                viewHolder.mProgressText.setText(mActivity.getString(
                        R.string.list_download_progress_eta_newer, downloaded, total, etaString));
            } else {
                viewHolder.mProgressText.setText(mActivity.getString(
                        R.string.list_download_progress_newer, downloaded, total));
            }
            setButtonAction(viewHolder.mAction, Action.PAUSE, downloadId, true);
            setSecondaryCancel(viewHolder, downloadId);
            setIndeterminate(viewHolder.mProgressBar, update.getStatus() == UpdateStatus.STARTING);
            viewHolder.mProgressBar.setProgress(update.getProgress());
            bindSteps(viewHolder, STAGE_DOWNLOAD, update,
                    mActivity.getString(R.string.step_download_active));
        } else if (mUpdaterController.isInstallingUpdate(downloadId)) {
            setButtonAction(viewHolder.mAction, Action.CANCEL_INSTALLATION, downloadId, true);
            hideSecondary(viewHolder);
            boolean notAB = !mUpdaterController.isInstallingABUpdate();
            viewHolder.mProgressText.setText(notAB ? R.string.dialog_prepare_zip_message :
                    update.getFinalizing() ?
                            R.string.finalizing_package :
                            R.string.preparing_ota_first_boot);
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getInstallProgress() / 100.f);
            viewHolder.mPercentage.setText(percentage);
            setIndeterminate(viewHolder.mProgressBar, false);
            viewHolder.mProgressBar.setProgress(update.getInstallProgress());
            bindSteps(viewHolder, STAGE_INSTALL, update, notAB ? null
                    : mActivity.getString(update.getFinalizing() ? R.string.finalizing_package
                            : R.string.step_install_ab));
            setHint(viewHolder, R.drawable.ic_battery, mActivity.getString(R.string.hint_battery,
                    mActivity.getResources().getInteger(
                            R.integer.battery_ok_percentage_discharging)));
        } else if (mUpdaterController.isVerifyingUpdate(downloadId)) {
            setButtonAction(viewHolder.mAction, Action.INSTALL, downloadId, false);
            hideSecondary(viewHolder);
            viewHolder.mProgressText.setText(R.string.list_verifying_update);
            viewHolder.mPercentage.setText(null);
            setIndeterminate(viewHolder.mProgressBar, true);
            bindSteps(viewHolder, STAGE_VERIFY, update, null);
        } else {
            canDelete = true;
            setButtonAction(viewHolder.mAction, Action.RESUME, downloadId, !isBusy());
            setSecondaryCancel(viewHolder, downloadId);
            bindSteps(viewHolder, STAGE_DOWNLOAD, update, null);
            String downloaded = Formatter.formatShortFileSize(mActivity,
                    update.getFile().length());
            String total = Formatter.formatShortFileSize(mActivity, update.getFileSize());
            String percentage = NumberFormat.getPercentInstance().format(
                    update.getProgress() / 100.f);
            viewHolder.mPercentage.setText(percentage);
            viewHolder.mProgressText.setText(mActivity.getString(
                    R.string.list_download_progress_newer, downloaded, total));
            setIndeterminate(viewHolder.mProgressBar, false);
            viewHolder.mProgressBar.setProgress(update.getProgress());
        }

        viewHolder.mMenu.setOnClickListener(getClickListener(update, canDelete, viewHolder.mMenu));
        viewHolder.mProgress.setVisibility(View.VISIBLE);
        viewHolder.mProgressText.setVisibility(View.VISIBLE);
        if (viewHolder.isExpressive()) {
            // The size chip stays; the big progress lives in the hero header.
            viewHolder.mBuildSize.setText(
                    Formatter.formatShortFileSize(mActivity, update.getFileSize()));
            viewHolder.mBuildSize.setVisibility(View.VISIBLE);
        } else {
            viewHolder.mBuildSize.setVisibility(View.INVISIBLE);
        }
    }

    private void handleNotActiveStatus(ViewHolder viewHolder, UpdateInfo update) {
        final String downloadId = update.getDownloadId();
        hideSecondary(viewHolder);
        setHint(viewHolder, 0, null);
        String fileSize = Formatter.formatShortFileSize(mActivity, update.getFileSize());
        if (mUpdaterController.isWaitingForReboot(downloadId)) {
            viewHolder.mMenu.setOnClickListener(getClickListener(update, false, viewHolder.mMenu));
            setButtonAction(viewHolder.mAction, Action.REBOOT, downloadId, true);
            bindSteps(viewHolder, STAGE_RESTART, update,
                    mActivity.getString(R.string.step_restart_summary));
        } else if (update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED) {
            viewHolder.mMenu.setOnClickListener(getClickListener(update, true, viewHolder.mMenu));
            boolean canInstall = Utils.canInstall(update);
            setButtonAction(viewHolder.mAction,
                    canInstall ? Action.INSTALL : Action.DELETE,
                    downloadId, !isBusy());
            bindSteps(viewHolder, canInstall ? STAGE_INSTALL : STAGE_NONE, update, null);
            if (canInstall) {
                setHint(viewHolder, R.drawable.ic_battery, mActivity.getString(
                        R.string.hint_battery, mActivity.getResources().getInteger(
                                R.integer.battery_ok_percentage_discharging)));
            }
        } else if (!Utils.canInstall(update)) {
            viewHolder.mMenu.setOnClickListener(getClickListener(update, false, viewHolder.mMenu));
            setButtonAction(viewHolder.mAction, Action.INFO, downloadId, !isBusy());
            bindSteps(viewHolder, STAGE_NONE, update, null);
        } else {
            viewHolder.mMenu.setOnClickListener(getClickListener(update, false, viewHolder.mMenu));
            setButtonAction(viewHolder.mAction, Action.DOWNLOAD, downloadId, !isBusy());
            bindSteps(viewHolder, STAGE_NONE, update, null);
            setHint(viewHolder, R.drawable.ic_wifi,
                    mActivity.getString(R.string.hint_wifi, fileSize));
        }
        viewHolder.mBuildSize.setText(fileSize);

        viewHolder.mProgress.setVisibility(viewHolder.isExpressive() ? View.GONE : View.INVISIBLE);
        viewHolder.mProgressText.setVisibility(View.INVISIBLE);
        viewHolder.mBuildSize.setVisibility(View.VISIBLE);
    }

    // ----- Material 3 Expressive card helpers -----

    private static void setIndeterminate(ProgressBar bar, boolean indeterminate) {
        if (bar.isIndeterminate() == indeterminate) {
            return;
        }
        int visibility = bar.getVisibility();
        if (visibility == View.VISIBLE) {
            // Material progress indicators may only switch mode while hidden.
            bar.setVisibility(View.INVISIBLE);
            bar.setIndeterminate(indeterminate);
            bar.setVisibility(View.VISIBLE);
        } else {
            bar.setIndeterminate(indeterminate);
        }
    }

    private void setSecondaryCancel(ViewHolder holder, String downloadId) {
        if (holder.mSecondary == null) return;
        holder.mSecondary.setVisibility(View.VISIBLE);
        holder.mSecondary.setText(R.string.action_cancel);
        holder.mSecondary.setOnClickListener(v -> getDeleteDialog(downloadId).show());
    }

    private static void hideSecondary(ViewHolder holder) {
        if (holder.mSecondary != null) holder.mSecondary.setVisibility(View.GONE);
    }

    private void setHint(ViewHolder holder, int iconRes, @Nullable CharSequence text) {
        if (holder.mHint == null) return;
        if (text == null) {
            holder.mHint.setVisibility(View.GONE);
            return;
        }
        holder.mHint.setText(text);
        holder.mHint.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
        holder.mHint.setVisibility(View.VISIBLE);
    }

    private void bindSteps(ViewHolder holder, int stage, UpdateInfo update,
            @Nullable CharSequence activeSummary) {
        if (holder.mSteps == null || holder.mStepRows == null) return;
        if (stage == STAGE_NONE) {
            holder.mSteps.setVisibility(View.GONE);
            return;
        }
        holder.mSteps.setVisibility(View.VISIBLE);
        String size = Formatter.formatShortFileSize(mActivity, update.getFileSize());
        int[][] titles = {
                {R.string.step_download, 0},
                {R.string.step_verify, R.string.step_verified},
                {R.string.step_install, R.string.step_installed},
                {R.string.step_restart, 0},
        };
        int primary = MaterialColors.getColor(holder.itemView, androidx.appcompat.R.attr.colorPrimary);
        int onPrimary = MaterialColors.getColor(holder.itemView,
                com.google.android.material.R.attr.colorOnPrimary);
        int tertiary = MaterialColors.getColor(holder.itemView,
                com.google.android.material.R.attr.colorTertiary);
        int onTertiary = MaterialColors.getColor(holder.itemView,
                com.google.android.material.R.attr.colorOnTertiary);
        int onSurface = MaterialColors.getColor(holder.itemView,
                com.google.android.material.R.attr.colorOnSurface);
        int onSurfaceVariant = MaterialColors.getColor(holder.itemView,
                com.google.android.material.R.attr.colorOnSurfaceVariant);

        for (int i = 0; i < holder.mStepRows.length; i++) {
            View row = holder.mStepRows[i];
            if (row == null) continue;
            TextView dot = row.findViewById(R.id.step_dot);
            TextView title = row.findViewById(R.id.step_title);
            TextView summary = row.findViewById(R.id.step_summary);
            boolean done = i < stage;
            boolean active = i == stage;

            if (done) {
                dot.setText(null);
                dot.setBackgroundResource(R.drawable.bg_step_square);
                dot.setBackgroundTintList(ColorStateList.valueOf(primary));
                Drawable check = mActivity.getDrawable(R.drawable.ic_check).mutate();
                check.setTint(onPrimary);
                check.setBounds(0, 0, dp(18), dp(18));
                dot.setCompoundDrawablesRelative(check, null, null, null);
                dot.setPaddingRelative(dp(7), 0, 0, 0);
                title.setText(i == STAGE_DOWNLOAD
                        ? mActivity.getString(R.string.step_downloaded, size)
                        : mActivity.getString(titles[i][1] != 0 ? titles[i][1] : titles[i][0]));
                title.setTextColor(onSurface);
            } else {
                dot.setCompoundDrawablesRelative(null, null, null, null);
                dot.setPaddingRelative(0, 0, 0, 0);
                dot.setText(String.valueOf(i + 1));
                if (active) {
                    dot.setBackgroundResource(R.drawable.bg_pill);
                    dot.setBackgroundTintList(ColorStateList.valueOf(tertiary));
                    dot.setTextColor(onTertiary);
                    title.setTextColor(onSurface);
                } else {
                    dot.setBackgroundResource(R.drawable.bg_step_outline);
                    dot.setBackgroundTintList(null);
                    dot.setTextColor(onSurfaceVariant);
                    title.setTextColor(onSurfaceVariant);
                }
                title.setText(titles[i][0]);
            }
            if (active && !TextUtils.isEmpty(activeSummary)) {
                summary.setText(activeSummary);
                summary.setVisibility(View.VISIBLE);
            } else {
                summary.setVisibility(View.GONE);
            }
            row.setPaddingRelative(0, 0, 0, i == holder.mStepRows.length - 1 ? 0 : dp(10));
        }
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder viewHolder, int i) {
        if (mDownloadIds == null) {
            viewHolder.mAction.setEnabled(false);
            return;
        }

        final String downloadId = mDownloadIds.get(i);
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        if (update == null) {
            // The update was deleted
            viewHolder.mAction.setEnabled(false);
            viewHolder.mAction.setText(R.string.action_download);
            return;
        }

        viewHolder.itemView.setSelected(downloadId.equals(mSelectedDownload));

        boolean activeLayout;
        switch (update.getPersistentStatus()) {
            case UpdateStatus.Persistent.UNKNOWN:
                activeLayout = update.getStatus() == UpdateStatus.STARTING;
                break;
            case UpdateStatus.Persistent.VERIFIED:
                activeLayout = update.getStatus() == UpdateStatus.INSTALLING;
                break;
            case UpdateStatus.Persistent.INCOMPLETE:
                activeLayout = true;
                break;
            default:
                throw new RuntimeException("Unknown update status");
        }

        String buildDate = StringGenerator.getDateLocalizedUTC(mActivity,
                DateFormat.LONG, update.getTimestamp());
        String buildType = update.getBuildType();
        boolean hasType = buildType != null && !buildType.isEmpty();
        String versionText = hasType && !viewHolder.isExpressive()
                ? update.getVersion() + " " + buildType
                : update.getVersion();
        String buildVersion = mActivity.getString(R.string.list_build_version, versionText);
        viewHolder.mBuildDate.setText(buildDate);
        viewHolder.mBuildVersion.setText(buildVersion);
        viewHolder.mBuildVersion.setCompoundDrawables(null, null, null, null);
        if (viewHolder.mBuildTypeChip != null) {
            viewHolder.mBuildTypeChip.setText(hasType ? buildType.toUpperCase(Locale.ROOT) : null);
            viewHolder.mBuildTypeChip.setVisibility(hasType ? View.VISIBLE : View.GONE);
        }
        if (viewHolder.mDeviceChip != null) {
            viewHolder.mDeviceChip.setText(Build.MODEL);
        }
        View releaseChip = viewHolder.itemView.findViewById(R.id.build_release_chip);
        if (releaseChip instanceof com.google.android.material.chip.Chip) {
            String release = Utils.getReleaseTypeFromName(update.getName());
            com.google.android.material.chip.Chip chip =
                    (com.google.android.material.chip.Chip) releaseChip;
            if (release == null) {
                chip.setVisibility(View.GONE);
            } else {
                boolean official = Utils.isOfficialRelease(release);
                int bg = MaterialColors.getColor(chip, official
                        ? androidx.appcompat.R.attr.colorPrimary
                        : androidx.appcompat.R.attr.colorError);
                int fg = MaterialColors.getColor(chip, official
                        ? com.google.android.material.R.attr.colorOnPrimary
                        : com.google.android.material.R.attr.colorOnError);
                chip.setChipBackgroundColor(ColorStateList.valueOf(bg));
                chip.setTextColor(fg);
                chip.setChipIconTint(ColorStateList.valueOf(fg));
                chip.setText(release);
                chip.setVisibility(View.VISIBLE);
            }
        }

        if (activeLayout) {
            handleActiveStatus(viewHolder, update);
        } else {
            handleNotActiveStatus(viewHolder, update);
        }
    }

    @Override
    public int getItemCount() {
        return mDownloadIds == null ? 0 : mDownloadIds.size();
    }

    public void setData(List<String> downloadIds) {
        mDownloadIds = downloadIds;
    }

    public void addItem(String downloadId) {
        if (mDownloadIds == null) {
            mDownloadIds = new ArrayList<>();
        }
        mDownloadIds.add(0, downloadId);
        notifyItemInserted(0);
    }

    public void notifyItemChanged(String downloadId) {
        if (mDownloadIds == null) {
            return;
        }
        notifyItemChanged(mDownloadIds.indexOf(downloadId));
    }

    public void removeItem(String downloadId) {
        if (mDownloadIds == null) {
            return;
        }
        int position = mDownloadIds.indexOf(downloadId);
        mDownloadIds.remove(downloadId);
        notifyItemRemoved(position);
        notifyItemRangeChanged(position, getItemCount());
    }

    private void startDownloadWithWarning(final String downloadId) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean warn = preferences.getBoolean(Constants.PREF_METERED_NETWORK_WARNING, true);
        if (!(Utils.isNetworkMetered(mActivity) && warn)) {
            mUpdaterController.startDownload(downloadId);
            return;
        }

        View checkboxView = LayoutInflater.from(mActivity).inflate(R.layout.checkbox_view, null);
        CheckBox checkbox = checkboxView.findViewById(R.id.checkbox);
        checkbox.setText(R.string.checkbox_metered_network_warning);

        new AlertDialog.Builder(mActivity)
                .setTitle(R.string.update_over_metered_network_title)
                .setMessage(R.string.update_over_metered_network_message)
                .setView(checkboxView)
                .setPositiveButton(R.string.action_download,
                        (dialog, which) -> {
                            if (checkbox.isChecked()) {
                                preferences.edit()
                                        .putBoolean(Constants.PREF_METERED_NETWORK_WARNING, false)
                                        .apply();
                                mActivity.supportInvalidateOptionsMenu();
                            }
                            mUpdaterController.startDownload(downloadId);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setButtonAction(Button button, Action action, final String downloadId,
            boolean enabled) {
        final View.OnClickListener clickListener;
        switch (action) {
            case DOWNLOAD:
                button.setText(R.string.action_download);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> startDownloadWithWarning(downloadId) : null;
                break;
            case PAUSE:
                button.setText(R.string.action_pause);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> mUpdaterController.pauseDownload(downloadId)
                        : null;
                break;
            case RESUME: {
                button.setText(R.string.action_resume);
                button.setEnabled(enabled);
                UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                final boolean canInstall = Utils.canInstall(update) ||
                        update.getFile().length() == update.getFileSize();
                clickListener = enabled ? view -> {
                    if (canInstall) {
                        mUpdaterController.resumeDownload(downloadId);
                    } else {
                        mActivity.showSnackbar(R.string.snack_update_not_installable,
                                Snackbar.LENGTH_LONG);
                    }
                } : null;
            }
            break;
            case INSTALL: {
                button.setText(R.string.action_install);
                button.setEnabled(enabled);
                UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                final boolean canInstall = Utils.canInstall(update);
                clickListener = enabled ? view -> {
                    if (canInstall) {
                        AlertDialog.Builder installDialog = getInstallDialog(downloadId);
                        if (installDialog != null) {
                            installDialog.show();
                        }
                    } else {
                        mActivity.showSnackbar(R.string.snack_update_not_installable,
                                Snackbar.LENGTH_LONG);
                    }
                } : null;
            }
            break;
            case INFO: {
                button.setText(R.string.action_info);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> showInfoDialog() : null;
            }
            break;
            case DELETE: {
                button.setText(R.string.action_delete);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> getDeleteDialog(downloadId).show() : null;
            }
            break;
            case CANCEL_INSTALLATION: {
                button.setText(R.string.action_cancel);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> getCancelInstallationDialog().show() : null;
            }
            break;
            case REBOOT: {
                button.setText(R.string.reboot);
                button.setEnabled(enabled);
                clickListener = enabled ? view -> {
                    PowerManager pm = mActivity.getSystemService(PowerManager.class);
                    pm.reboot(null);
                } : null;
            }
            break;
            default:
                clickListener = null;
        }
        if (button instanceof MaterialButton) {
            int icon;
            switch (action) {
                case DOWNLOAD: icon = R.drawable.ic_download; break;
                case PAUSE: icon = R.drawable.ic_pause; break;
                case RESUME: icon = R.drawable.ic_download; break;
                case INSTALL: icon = R.drawable.ic_system_update; break;
                case INFO: icon = R.drawable.ic_info; break;
                case DELETE:
                case CANCEL_INSTALLATION: icon = R.drawable.ic_close; break;
                case REBOOT: icon = R.drawable.ic_restart; break;
                default: icon = 0;
            }
            ((MaterialButton) button).setIconResource(icon);
        }
        button.setAlpha(enabled ? 1.f : mAlphaDisabledValue);

        // Disable action mode when a button is clicked
        button.setOnClickListener(v -> {
            if (clickListener != null) {
                clickListener.onClick(v);
            }
        });
    }

    private boolean isBusy() {
        return mUpdaterController.hasActiveDownloads() || mUpdaterController.isVerifyingUpdate()
                || mUpdaterController.isInstallingUpdate();
    }

    private AlertDialog.Builder getDeleteDialog(final String downloadId) {
        return new AlertDialog.Builder(mActivity)
                .setTitle(R.string.confirm_delete_dialog_title)
                .setMessage(R.string.confirm_delete_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            mUpdaterController.pauseDownload(downloadId);
                            mUpdaterController.deleteUpdate(downloadId);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private View.OnClickListener getClickListener(final UpdateInfo update,
            final boolean canDelete, View anchor) {
        return view -> startActionMode(update, canDelete, anchor);
    }

    private AlertDialog.Builder getInstallDialog(final String downloadId) {
        if (!isBatteryLevelOk()) {
            Resources resources = mActivity.getResources();
            String message = resources.getString(R.string.dialog_battery_low_message_pct,
                    resources.getInteger(R.integer.battery_ok_percentage_discharging),
                    resources.getInteger(R.integer.battery_ok_percentage_charging));
            return new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.dialog_battery_low_title)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        if (isScratchMounted()) {
            return new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.dialog_scratch_mounted_title)
                    .setMessage(R.string.dialog_scratch_mounted_message)
                    .setPositiveButton(android.R.string.ok, null);
        }
        UpdateInfo update = mUpdaterController.getUpdate(downloadId);
        int resId;
        try {
            if (Utils.isABUpdate(update.getFile())) {
                resId = R.string.apply_update_dialog_message_ab;
            } else {
                resId = R.string.apply_update_dialog_message;
            }
        } catch (IOException e) {
            Log.e(TAG, "Could not determine the type of the update");
            return null;
        }

        String buildDate = StringGenerator.getDateLocalizedUTC(mActivity,
                DateFormat.MEDIUM, update.getTimestamp());
        String buildType = update.getBuildType();
        String versionText = buildType != null && !buildType.isEmpty()
                ? update.getVersion() + " " + buildType
                : update.getVersion();
        String buildInfoText = mActivity.getString(R.string.list_build_version_date,
                versionText, buildDate);
        return new AlertDialog.Builder(mActivity)
                .setTitle(R.string.apply_update_dialog_title)
                .setMessage(mActivity.getString(resId, buildInfoText,
                        mActivity.getString(android.R.string.ok)))
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            Utils.triggerUpdate(mActivity, downloadId);
                            maybeShowInfoDialog();
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private AlertDialog.Builder getCancelInstallationDialog() {
        return new AlertDialog.Builder(mActivity)
                .setMessage(R.string.cancel_installation_dialog_message)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> {
                            Intent intent = new Intent(mActivity, UpdaterService.class);
                            intent.setAction(UpdaterService.ACTION_INSTALL_STOP);
                            mActivity.startService(intent);
                        })
                .setNegativeButton(android.R.string.cancel, null);
    }

    private void maybeShowInfoDialog() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean alreadySeen = preferences.getBoolean(Constants.HAS_SEEN_INFO_DIALOG, false);
        if (alreadySeen) {
            return;
        }
        new AlertDialog.Builder(mActivity)
                .setTitle(R.string.info_dialog_title)
                .setMessage(R.string.info_dialog_message)
                .setPositiveButton(R.string.info_dialog_ok, (dialog, which) -> preferences.edit()
                        .putBoolean(Constants.HAS_SEEN_INFO_DIALOG, true)
                        .apply())
                .show();
    }

    private void startActionMode(final UpdateInfo update, final boolean canDelete, View anchor) {
        mSelectedDownload = update.getDownloadId();
        notifyItemChanged(update.getDownloadId());

        ContextThemeWrapper wrapper = new ContextThemeWrapper(mActivity,
                R.style.AppTheme_PopupMenuOverlapAnchor);
        PopupMenu popupMenu = new PopupMenu(wrapper, anchor, Gravity.NO_GRAVITY);
        popupMenu.inflate(R.menu.menu_action_mode);

        boolean shouldShowDelete = canDelete;
        boolean isVerified = update.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED;
        if (isVerified && !Utils.canInstall(update) && !update.getAvailableOnline()) {
            shouldShowDelete = false;
        }
        MenuBuilder menu = (MenuBuilder) popupMenu.getMenu();
        menu.findItem(R.id.menu_delete_action).setVisible(shouldShowDelete);
        menu.findItem(R.id.menu_copy_url).setVisible(update.getAvailableOnline());
        menu.findItem(R.id.menu_export_update).setVisible(isVerified);

        popupMenu.setOnMenuItemClickListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.menu_delete_action) {
                getDeleteDialog(update.getDownloadId()).show();
                return true;
            } else if (itemId == R.id.menu_copy_url) {
                Utils.addToClipboard(mActivity,
                        mActivity.getString(R.string.label_download_url),
                        update.getDownloadUrl(),
                        mActivity.getString(R.string.toast_download_url_copied));
                return true;
            } else if (itemId == R.id.menu_export_update) {
                if (mActivity != null) {
                    mActivity.exportUpdate(update);
                }
                return true;
            }
            return false;
        });

        MenuPopupHelper helper = new MenuPopupHelper(wrapper, menu, anchor);
        helper.show();
    }

    private void showInfoDialog() {
        if (infoDialog != null) {
            infoDialog.dismiss();
        }
        infoDialog = new AlertDialog.Builder(mActivity)
                .setTitle(R.string.blocked_update_dialog_title)
                .setPositiveButton(android.R.string.ok, null)
                .setMessage(R.string.blocked_update_dialog_message_custom)
                .show();
        TextView textView = infoDialog.findViewById(android.R.id.message);
        if (textView != null) {
            textView.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    private boolean isBatteryLevelOk() {
        Intent intent = mActivity.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (!intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) {
            return true;
        }
        int percent = Math.round(100.f * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) /
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int required = (plugged & BATTERY_PLUGGED_ANY) != 0 ?
                mActivity.getResources().getInteger(R.integer.battery_ok_percentage_charging) :
                mActivity.getResources().getInteger(R.integer.battery_ok_percentage_discharging);
        return percent >= required;
    }

    private static boolean isScratchMounted() {
        try (Stream<String> lines = Files.lines(Path.of("/proc/mounts"))) {
            return lines.anyMatch(x -> x.split(" ")[1].equals("/mnt/scratch"));
        } catch (IOException e) {
            return false;
        }
    }
}
