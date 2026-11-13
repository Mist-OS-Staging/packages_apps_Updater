/*
 * Copyright (C) 2017-2023 The LineageOS Project
 * Copyright (C) 2026 Project Mist OS
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

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.icu.text.DateFormat;
import android.icu.text.MeasureFormat;
import android.icu.util.Measure;
import android.icu.util.MeasureUnit;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.AttrRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import android.transition.AutoTransition;
import android.transition.TransitionManager;

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import org.json.JSONException;
import com.mist.updater.controller.UpdaterController;
import com.mist.updater.controller.UpdaterService;
import com.mist.updater.download.DownloadClient;
import com.mist.updater.misc.BuildInfoUtils;
import com.mist.updater.misc.Constants;
import com.mist.updater.misc.StringGenerator;
import com.mist.updater.misc.Utils;
import com.mist.updater.model.Update;
import com.mist.updater.model.UpdateInfo;
import com.mist.updater.model.UpdateStatus;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class UpdatesActivity extends UpdatesListActivity implements UpdateImporter.Callbacks {

    private static final String TAG = "UpdatesActivity";

    /** What the big colour header is currently telling the user. */
    private enum HeroState {
        UP_TO_DATE, CHECKING, AVAILABLE, DOWNLOADING, PAUSED, VERIFYING, READY, INSTALLING, REBOOT
    }

    private static final PathInterpolator EMPHASIZED =
            new PathInterpolator(0.2f, 0f, 0f, 1f);

    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;

    private UpdatesListAdapter mAdapter;

    private boolean mIsTV;
    private boolean mIsChecking;

    private UpdateInfo mToBeExported = null;

    private RandomMessageTextView noUpdatesTextView;

    // Hero
    private View mHeroContainer;
    private ImageView mHeroShape;
    private View mHeroLoading;
    private CircularProgressIndicator mHeroRing;
    private ImageView mHeroIcon;
    private TextView mHeroRingText;
    private ImageView mHeroBadge;
    private TextView mHeroEyebrow;
    private TextView mHeroTitle;
    private TextView mHeroSubtitle;
    private LinearProgressIndicator mHeroProgress;
    private View mHeroStats;
    private TextView mHeroStatSize;
    private TextView mHeroStatSizeLabel;
    private TextView mHeroStatSpeed;
    private TextView mHeroStatEta;
    private TextView mHeaderLastCheck;
    private CollapsingToolbarLayout mCollapsingToolbar;
    private Toolbar mToolbar;
    private MaterialButton mCheckButton;
    private MaterialButton mLocalButton;

    private HeroState mHeroState;
    private int mHeroBgColor = Color.TRANSPARENT;
    private ValueAnimator mHeroColorAnimator;
    private ObjectAnimator mShapeSpin;
    private boolean mAnimationsEnabled = true;
    private boolean mEntranceDone;

    /** Debug builds only: long-press the header to preview every state on a real device. */
    private HeroState mDemoState;

    private final ActivityResultLauncher<Intent> mExportUpdate = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent intent = result.getData();
                    if (intent != null) {
                        Uri uri = intent.getData();
                        exportUpdate(uri);
                    }
                }
            });

    private UpdateImporter mUpdateImporter;
    private AlertDialog importDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiModeManager uiModeManager = getSystemService(UiModeManager.class);
        mIsTV = uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;

        if (!mIsTV) {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        }
        setContentView(R.layout.activity_updates);

        mUpdateImporter = new UpdateImporter(this, this);
        mAnimationsEnabled = ValueAnimator.areAnimatorsEnabled();

        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        mAdapter = new UpdatesListAdapter(this);
        recyclerView.setAdapter(mAdapter);
        RecyclerView.LayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (mUpdaterService == null) {
                    return;
                }
                String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    handleDownloadStatusChange(downloadId);
                    mAdapter.notifyItemChanged(downloadId);
                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction())
                        || UpdaterController.ACTION_INSTALL_PROGRESS.equals(intent.getAction())) {
                    if (update != null) {
                        mAdapter.notifyItemChanged(downloadId);
                    }
                } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(intent.getAction())) {
                    mAdapter.removeItem(downloadId);
                    List<UpdateInfo> sortedUpdates =
                            mUpdaterService.getUpdaterController().getUpdates();
                    if (sortedUpdates.isEmpty()) {
                        showUpdatesList(false);
                    }
                }
                refreshHero();
            }
        };

        bindHeroViews();

        if (!mIsTV) {
            setupToolbarAndInsets();
        } else {
            View refresh = findViewById(R.id.refresh);
            if (refresh != null) refresh.setOnClickListener(v -> downloadUpdatesList(true));
            View prefs = findViewById(R.id.preferences);
            if (prefs != null) prefs.setOnClickListener(v -> showPreferencesDialog());
        }

        TextView headerTitle = findViewById(R.id.header_title);
        if (mIsTV && headerTitle != null) {
            headerTitle.setText(getString(R.string.header_title_text,
                    BuildInfoUtils.getBuildVersion()));
        }

        updateLastCheckedString();

        noUpdatesTextView = findViewById(R.id.no_updates_text);

        mCheckButton = findViewById(R.id.fab_refresh);
        if (mCheckButton != null) {
            mCheckButton.setOnClickListener(view -> {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                downloadUpdatesList(true);
                if (noUpdatesTextView != null
                        && noUpdatesTextView.getVisibility() == View.VISIBLE) {
                    noUpdatesTextView.animate().alpha(0f).setDuration(150)
                            .withEndAction(() -> {
                                noUpdatesTextView.updateRandomMessage();
                                noUpdatesTextView.animate().alpha(1f).setDuration(250).start();
                            }).start();
                }
            });
        }
        mLocalButton = findViewById(R.id.fab_local_update);
        if (mLocalButton != null) {
            mLocalButton.setOnClickListener(view -> {
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                mUpdateImporter.openImportPicker();
            });
        }

        View changelog = findViewById(R.id.changelog_row);
        if (changelog != null) {
            changelog.setOnClickListener(v -> openChangelog());
        }

        if (isDebuggable() && mHeroContainer != null) {
            mHeroContainer.setOnLongClickListener(v -> {
                cycleDemoState();
                return true;
            });
        }

        // Warm the changelog cache so the popup opens instantly.
        ChangelogDialog.prefetch(this);

        refreshHero();
    }

    // ---------------------------------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------------------------------

    private void bindHeroViews() {
        mHeroContainer = findViewById(R.id.hero_container);
        mHeroShape = findViewById(R.id.hero_shape);
        mHeroLoading = findViewById(R.id.hero_loading);
        mHeroRing = findViewById(R.id.hero_ring);
        mHeroIcon = findViewById(R.id.hero_icon);
        mHeroRingText = findViewById(R.id.hero_ring_text);
        mHeroBadge = findViewById(R.id.hero_badge);
        mHeroEyebrow = findViewById(R.id.hero_eyebrow);
        mHeroTitle = findViewById(R.id.header_title);
        mHeroSubtitle = findViewById(R.id.hero_subtitle);
        mHeroProgress = findViewById(R.id.hero_progress);
        mHeroStats = findViewById(R.id.hero_stats);
        mHeroStatSize = findViewById(R.id.hero_stat_size);
        mHeroStatSizeLabel = findViewById(R.id.hero_stat_size_label);
        mHeroStatSpeed = findViewById(R.id.hero_stat_speed);
        mHeroStatEta = findViewById(R.id.hero_stat_eta);
        mHeaderLastCheck = findViewById(R.id.header_last_check);
        mCollapsingToolbar = findViewById(R.id.collapsing_toolbar);
    }

    private void setupToolbarAndInsets() {
        mToolbar = findViewById(R.id.toolbar);
        setSupportActionBar(mToolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(false);
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        final int actionBarSize;
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            actionBarSize = TypedValue.complexToDimensionPixelSize(
                    tv.data, getResources().getDisplayMetrics());
        } else {
            actionBarSize = dp(64);
        }

        final View root = findViewById(R.id.main_container);
        final View bottomBar = findViewById(R.id.bottom_bar);
        final View scroll = findViewById(R.id.scroll_view);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            int top = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            int bottom = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.ime()).bottom;
            int left = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).left;
            int right = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).right;

            ViewGroup.MarginLayoutParams lp =
                    (ViewGroup.MarginLayoutParams) mToolbar.getLayoutParams();
            lp.topMargin = top;
            mToolbar.setLayoutParams(lp);

            mHeroContainer.setPadding(dp(28) + left, top + actionBarSize + dp(4),
                    dp(28) + right, mHeroContainer.getPaddingBottom());

            bottomBar.setPadding(dp(16) + left, dp(14), dp(16) + right, dp(16) + bottom);
            bottomBar.post(() -> scroll.setPadding(left, scroll.getPaddingTop(), right,
                    bottomBar.getHeight() + dp(24)));
            return WindowInsetsCompat.CONSUMED;
        });

        // Collapse: fade the big header content out and the toolbar title in.
        final AppBarLayout appBar = findViewById(R.id.app_bar);
        appBar.addOnOffsetChangedListener(new AppBarLayout.OnOffsetChangedListener() {
            boolean mIsShown = false;

            @Override
            public void onOffsetChanged(AppBarLayout appBarLayout, int verticalOffset) {
                int scrollRange = appBarLayout.getTotalScrollRange();
                if (scrollRange <= 0) return;
                float fraction = Math.min(1f, -verticalOffset / (float) scrollRange);
                float contentAlpha = Math.max(0f, 1f - fraction * 1.6f);
                ViewGroup hero = (ViewGroup) mHeroContainer;
                for (int i = 0; i < hero.getChildCount(); i++) {
                    View child = hero.getChildAt(i);
                    child.setAlpha(child == mHeroSubtitle ? 0.86f * contentAlpha : contentAlpha);
                    child.setTranslationY(-dp(24) * fraction);
                }
                if (!mIsShown && scrollRange + verticalOffset < 10) {
                    mCollapsingToolbar.setTitle(getString(R.string.system_updates_title));
                    mIsShown = true;
                } else if (mIsShown && scrollRange + verticalOffset > 100) {
                    mCollapsingToolbar.setTitle(null);
                    mIsShown = false;
                }
            }
        });

        if (!Utils.hasTouchscreen(this)) {
            // This can't be collapsed without a touchscreen
            appBar.setExpanded(false);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(this, UpdaterService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startShapeSpin();
        if (!mEntranceDone) {
            mEntranceDone = true;
            playEntranceAnimation();
        }
    }

    @Override
    protected void onPause() {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
            mUpdateImporter.stopImport();
        }
        if (mShapeSpin != null) {
            mShapeSpin.pause();
        }

        super.onPause();
    }

    @Override
    public void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
        if (mUpdaterService != null) {
            unbindService(mConnection);
        }
        super.onStop();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_toolbar, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.menu_preferences) {
            showPreferencesDialog();
            return true;
        } else if (itemId == R.id.menu_show_changelog) {
            openChangelog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (!mUpdateImporter.onResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Local import
    // ---------------------------------------------------------------------------------------

    @Override
    public void onImportStarted() {
        if (importDialog != null && importDialog.isShowing()) {
            importDialog.dismiss();
        }

        importDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.local_update_import)
                .setView(R.layout.progress_dialog)
                .setCancelable(false)
                .create();

        importDialog.show();
    }

    @Override
    public void onImportCompleted(Update update) {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
        }

        if (update == null) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.local_update_import)
                    .setMessage(R.string.local_update_import_failure)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        mAdapter.notifyDataSetChanged();

        final Runnable deleteUpdate = () -> UpdaterController.getInstance(this)
                .deleteUpdate(update.getDownloadId());

        new AlertDialog.Builder(this)
                .setTitle(R.string.local_update_import)
                .setMessage(getString(R.string.local_update_import_success, update.getVersion()))
                .setPositiveButton(R.string.local_update_import_install, (dialog, which) -> {
                    mAdapter.addItem(update.getDownloadId());
                    // Update UI
                    getUpdatesList();
                    Utils.triggerUpdate(this, update.getDownloadId());
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> deleteUpdate.run())
                .setOnCancelListener((dialog) -> deleteUpdate.run())
                .show();
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
            mAdapter.setUpdaterController(mUpdaterService.getUpdaterController());
            getUpdatesList();
            refreshHero();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mAdapter.setUpdaterController(null);
            mUpdaterService = null;
            mAdapter.notifyDataSetChanged();
        }
    };

    // ---------------------------------------------------------------------------------------
    // Update list
    // ---------------------------------------------------------------------------------------

    private void showUpdatesList(boolean hasUpdates) {
        View empty = findViewById(R.id.no_new_updates_view);
        View list = findViewById(R.id.recycler_view);
        if (empty != null) empty.setVisibility(hasUpdates ? View.GONE : View.VISIBLE);
        if (list != null) list.setVisibility(hasUpdates ? View.VISIBLE : View.GONE);
        if (noUpdatesTextView != null) {
            noUpdatesTextView.setVisibility(hasUpdates ? View.GONE : View.VISIBLE);
        }
    }

    private void loadUpdatesList(File jsonFile, boolean manualRefresh)
            throws IOException, JSONException {
        Log.d(TAG, "Adding remote updates");
        UpdaterController controller = mUpdaterService.getUpdaterController();
        boolean newUpdates = false;

        List<UpdateInfo> updates = Utils.parseJson(jsonFile, true);
        List<String> updatesOnline = new ArrayList<>();
        for (UpdateInfo update : updates) {
            newUpdates |= controller.addUpdate(update);
            updatesOnline.add(update.getDownloadId());
        }
        controller.setUpdatesAvailableOnline(updatesOnline, true);

        if (manualRefresh) {
            showSnackbar(
                    newUpdates ? R.string.snack_updates_found : R.string.snack_no_updates_found,
                    Snackbar.LENGTH_SHORT);
        }

        List<String> updateIds = new ArrayList<>();
        List<UpdateInfo> sortedUpdates = controller.getUpdates();
        if (sortedUpdates.isEmpty()) {
            showUpdatesList(false);
        } else {
            showUpdatesList(true);
            sortedUpdates.sort((u1, u2) -> Long.compare(u2.getTimestamp(), u1.getTimestamp()));
            for (UpdateInfo update : sortedUpdates) {
                updateIds.add(update.getDownloadId());
            }
            mAdapter.setData(updateIds);
            mAdapter.notifyDataSetChanged();
        }
        // The json also carries maintainer, build type and support links.
        updateLastCheckedString();
        refreshHero();
    }

    private void getUpdatesList() {
        File jsonFile = Utils.getCachedUpdateList(this);
        if (jsonFile.exists()) {
            try {
                loadUpdatesList(jsonFile, false);
                Log.d(TAG, "Cached list parsed");
            } catch (IOException | JSONException e) {
                Log.e(TAG, "Error while parsing json list", e);
            }
        } else {
            downloadUpdatesList(false);
        }
    }

    private void processNewJson(File json, File jsonNew, boolean manualRefresh) {
        try {
            loadUpdatesList(jsonNew, manualRefresh);
            SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
            long millis = System.currentTimeMillis();
            preferences.edit().putLong(Constants.PREF_LAST_UPDATE_CHECK, millis).apply();
            updateLastCheckedString();
            if (json.exists() && Utils.isUpdateCheckEnabled(this) &&
                    Utils.checkForNewUpdates(json, jsonNew)) {
                UpdatesCheckReceiver.updateRepeatingUpdatesCheck(this);
            }
            // In case we set a one-shot check because of a previous failure
            UpdatesCheckReceiver.cancelUpdatesCheck(this);
            //noinspection ResultOfMethodCallIgnored
            jsonNew.renameTo(json);
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Could not read json", e);
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
        }
    }

    private void downloadUpdatesList(final boolean manualRefresh) {
        final File jsonFile = Utils.getCachedUpdateList(this);
        final File jsonFileTmp = new File(jsonFile.getAbsolutePath() + UUID.randomUUID());
        String url = Utils.getServerURL(this);
        Log.d(TAG, "Checking " + url);

        DownloadClient.DownloadCallback callback = new DownloadClient.DownloadCallback() {
            @Override
            public void onFailure(final boolean cancelled) {
                Log.e(TAG, "Could not download updates list");
                runOnUiThread(() -> {
                    if (!cancelled) {
                        showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
                    }
                    refreshAnimationStop();
                });
            }

            @Override
            public void onResponse(DownloadClient.Headers headers) {
            }

            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Log.d(TAG, "List downloaded");
                    if (mUpdaterService != null) {
                        processNewJson(jsonFile, jsonFileTmp, manualRefresh);
                    }
                    refreshAnimationStop();
                });
            }
        };

        final DownloadClient downloadClient;
        try {
            downloadClient = new DownloadClient.Builder()
                    .setUrl(url)
                    .setDestination(jsonFileTmp)
                    .setDownloadCallback(callback)
                    .build();
        } catch (IOException exception) {
            Log.e(TAG, "Could not build download client");
            showSnackbar(R.string.snack_updates_check_failed, Snackbar.LENGTH_LONG);
            return;
        }

        refreshAnimationStart();
        downloadClient.start();
    }

    // ---------------------------------------------------------------------------------------
    // Device tiles + support links
    // ---------------------------------------------------------------------------------------

    private void updateLastCheckedString() {
        final SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(this);
        long lastCheck = preferences.getLong(Constants.PREF_LAST_UPDATE_CHECK, -1) / 1000;
        TextView headerLastCheck = findViewById(R.id.header_last_check);
        if (headerLastCheck != null) {
            if (lastCheck > 0) {
                headerLastCheck.setText(getString(R.string.header_last_updates_check,
                        StringGenerator.getDateLocalized(this, DateFormat.LONG, lastCheck),
                        StringGenerator.getTimeLocalized(this, lastCheck)));
                headerLastCheck.setTag(Boolean.TRUE);
            } else {
                headerLastCheck.setTag(Boolean.FALSE);
                headerLastCheck.setVisibility(View.GONE);
            }
        }

        String buildVersion = BuildInfoUtils.getBuildVersion();
        setText(R.id.tile_version_value, TextUtils.isEmpty(buildVersion)
                ? getString(R.string.tile_unknown) : buildVersion);

        String codename = SystemProperties.get(Constants.PROP_DEVICE, Build.DEVICE);
        setText(R.id.tile_device_label, getString(R.string.tile_device,
                TextUtils.isEmpty(codename) ? Build.DEVICE : codename));
        setText(R.id.tile_device_value, Build.MODEL);

        TextView headerBuildVersion = findViewById(R.id.header_build_version);
        if (headerBuildVersion != null) {
            headerBuildVersion.setText(mIsTV
                    ? getString(R.string.header_android_version, Build.VERSION.RELEASE)
                    : Build.VERSION.RELEASE);
        }

        TextView headerBuildDate = findViewById(R.id.header_build_date);
        if (headerBuildDate != null) {
            String date = StringGenerator.getDateLocalizedUTC(this,
                    mIsTV ? DateFormat.LONG : DateFormat.MEDIUM,
                    BuildInfoUtils.getBuildDateTimestamp());
            headerBuildDate.setText(mIsTV ? getString(R.string.current_build_date, date) : date);
        }

        TextView headerBuildType = findViewById(R.id.header_build_type);
        String buildType = Utils.getBuildType();
        if (TextUtils.isEmpty(buildType)) {
            buildType = BuildInfoUtils.getBuildType();
        }
        if (headerBuildType != null) {
            if (TextUtils.isEmpty(buildType)) {
                headerBuildType.setText(mIsTV ? getString(R.string.build_type_unknown)
                        : getString(R.string.tile_unknown));
            } else {
                headerBuildType.setText(mIsTV
                        ? getString(R.string.current_build_type, buildType)
                        : buildType.toUpperCase(Locale.ROOT));
            }
        }

        // Coloured OFFICIAL / UNOFFICIAL badge next to the build type.
        TextView releaseBadge = findViewById(R.id.build_release_badge);
        if (releaseBadge != null) {
            String release = Utils.getCurrentReleaseType();
            if (TextUtils.isEmpty(release)) {
                releaseBadge.setVisibility(View.GONE);
            } else {
                boolean official = Utils.isOfficialRelease(release);
                int badgeBg = attr(official ? androidx.appcompat.R.attr.colorPrimary
                        : androidx.appcompat.R.attr.colorError);
                int badgeFg = attr(official ? com.google.android.material.R.attr.colorOnPrimary
                        : com.google.android.material.R.attr.colorOnError);
                releaseBadge.setText(release);
                releaseBadge.setTextColor(badgeFg);
                releaseBadge.setBackgroundTintList(ColorStateList.valueOf(badgeBg));
                Drawable icon = getDrawable(R.drawable.ic_verified);
                if (icon != null && official) {
                    icon = icon.mutate();
                    icon.setTint(badgeFg);
                    icon.setBounds(0, 0, dp(14), dp(14));
                    releaseBadge.setCompoundDrawablesRelative(icon, null, null, null);
                } else {
                    releaseBadge.setCompoundDrawablesRelative(null, null, null, null);
                }
                releaseBadge.setVisibility(View.VISIBLE);
            }
        }

        String maintainer = Utils.getMaintainer();
        TextView maintainerName = findViewById(R.id.maintainer_name);
        View maintainerTile = findViewById(R.id.tile_maintainer);
        View maintainerTarget = maintainerTile != null ? maintainerTile : maintainerName;
        if (TextUtils.isEmpty(maintainer)) {
            if (maintainerTarget != null) maintainerTarget.setVisibility(View.GONE);
        } else {
            if (maintainerName != null) {
                maintainerName.setText(mIsTV
                        ? getString(R.string.maintainer_name, maintainer) : maintainer);
            }
            setText(R.id.tile_maintainer_avatar,
                    maintainer.substring(0, 1).toUpperCase(Locale.getDefault()));
            if (maintainerTarget != null) maintainerTarget.setVisibility(View.VISIBLE);
            bindMaintainerCard();
        }
        // The maintainer card has its own Changelog button; avoid showing it twice.
        setVisible(findViewById(R.id.changelog_row), TextUtils.isEmpty(maintainer)
                || findViewById(R.id.maintainer_changelog) == null);

        // Support links: the device's OTA json first, then the ROM-wide defaults.
        // Order: community first, then donations.
        int shown = 0;
        shown += bindSupportLink(R.id.support_telegram,
                pickLink(Utils.getTelegram(), R.string.config_support_telegram));
        shown += bindSupportLink(R.id.support_forum,
                pickLink(Utils.getForum(), R.string.config_support_forum));
        shown += bindSupportLink(R.id.support_recovery,
                pickLink(Utils.getRecovery(), R.string.config_support_recovery));
        shown += bindSupportLink(R.id.support_paypal,
                pickLink(Utils.getPaypal(), R.string.config_support_paypal));
        shown += bindUpi(R.id.support_upi);
        shown += bindSupportLink(R.id.support_coffee,
                pickLink(Utils.getBuyMeACoffee(), R.string.config_support_buymeacoffee));
        styleSupportTiles();
        View supportGroup = findViewById(R.id.support_icons);
        View supportHeader = findViewById(R.id.header_support);
        int vis = shown > 0 ? View.VISIBLE : View.GONE;
        if (supportGroup != null) supportGroup.setVisibility(vis);
        if (supportHeader != null) supportHeader.setVisibility(vis);
    }

    private void bindMaintainerCard() {
        TextView role = findViewById(R.id.maintainer_role);
        if (role == null) return; // TV layout
        boolean official = Utils.isOfficialRelease(Utils.getCurrentReleaseType());
        role.setText(getString(official ? R.string.maintainer_role_official
                : R.string.maintainer_role, Build.MODEL));
        setVisible(findViewById(R.id.tile_maintainer_verified), official);

        View contact = findViewById(R.id.maintainer_contact);
        final String link = pickLink(Utils.getTelegram(), R.string.config_support_telegram);
        if (contact != null) {
            contact.setVisibility(link != null ? View.VISIBLE : View.GONE);
            contact.setOnClickListener(v -> openUrl(link));
        }
        View changelog = findViewById(R.id.maintainer_changelog);
        if (changelog != null) {
            changelog.setOnClickListener(v -> openChangelog());
        }
    }

    /** Icon, text and accent colour for each support tile, plus 2-column row spans. */
    private void styleSupportTiles() {
        int primary = attr(androidx.appcompat.R.attr.colorPrimary);
        int onPrimary = attr(com.google.android.material.R.attr.colorOnPrimary);
        int tertiary = attr(com.google.android.material.R.attr.colorTertiary);
        int onTertiary = attr(com.google.android.material.R.attr.colorOnTertiary);
        int secondaryC = attr(com.google.android.material.R.attr.colorSecondaryContainer);
        int onSecondaryC = attr(com.google.android.material.R.attr.colorOnSecondaryContainer);

        styleSupportTile(R.id.support_telegram, R.drawable.ic_telegram,
                getString(R.string.support_telegram), getString(R.string.support_sub_telegram),
                primary, onPrimary);
        styleSupportTile(R.id.support_forum, R.drawable.ic_forum,
                getString(R.string.support_forum), getString(R.string.support_sub_forum),
                secondaryC, onSecondaryC);
        styleSupportTile(R.id.support_recovery, R.drawable.ic_recovery,
                getString(R.string.support_recovery), getString(R.string.support_sub_recovery),
                secondaryC, onSecondaryC);
        styleSupportTile(R.id.support_paypal, R.drawable.ic_paypal,
                getString(R.string.support_donate), getString(R.string.support_sub_paypal),
                tertiary, onTertiary);
        styleSupportTile(R.id.support_upi, R.drawable.ic_upi,
                getString(R.string.support_upi), mUpiId, tertiary, onTertiary);
        styleSupportTile(R.id.support_coffee, R.drawable.ic_coffee,
                getString(R.string.support_coffee), getString(R.string.support_sub_coffee),
                tertiary, onTertiary);

        arrangeSupportTiles();
    }

    /** Tiles in their original order, captured once; rows are rebuilt from this list. */
    private List<View> mSupportTiles;

    /** Puts the visible support tiles two per row; an odd last tile takes the full row. */
    private void arrangeSupportTiles() {
        View container = findViewById(R.id.support_icons);
        if (!(container instanceof android.widget.LinearLayout) || mIsTV) return;
        android.widget.LinearLayout grid = (android.widget.LinearLayout) container;
        if (mSupportTiles == null) {
            mSupportTiles = new ArrayList<>();
            for (int i = 0; i < grid.getChildCount(); i++) {
                mSupportTiles.add(grid.getChildAt(i));
            }
        }
        for (View tile : mSupportTiles) {
            if (tile.getParent() instanceof ViewGroup) {
                ((ViewGroup) tile.getParent()).removeView(tile);
            }
        }
        grid.removeAllViews();

        List<View> visible = new ArrayList<>();
        for (View tile : mSupportTiles) {
            if (tile.getVisibility() != View.GONE) visible.add(tile);
        }
        android.widget.LinearLayout row = null;
        for (int i = 0; i < visible.size(); i++) {
            if (i % 2 == 0) {
                row = new android.widget.LinearLayout(this);
                row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                grid.addView(row, new android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout
                    .LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(visible.get(i), lp);
        }
    }

    private void styleSupportTile(int id, int iconRes, CharSequence title,
            @Nullable CharSequence subtitle, int iconBg, int iconFg) {
        View tile = findViewById(id);
        if (tile == null) return;
        ImageView icon = tile.findViewById(R.id.support_tile_icon);
        TextView t = tile.findViewById(R.id.support_tile_title);
        TextView s = tile.findViewById(R.id.support_tile_subtitle);
        if (icon == null || t == null) return; // TV layout uses plain icons
        icon.setImageResource(iconRes);
        icon.setBackgroundTintList(ColorStateList.valueOf(iconBg));
        icon.setImageTintList(ColorStateList.valueOf(iconFg));
        t.setText(title);
        if (s != null) {
            s.setText(subtitle);
            s.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
        }
        tile.setContentDescription(title);
    }

    private void openUrl(@Nullable String url) {
        if (url == null) return;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "No activity to open " + url, e);
        }
    }

    private String mUpiId;

    /** The device link if it is a real http(s) URL, else the ROM default, else null. */
    @Nullable
    private String pickLink(@Nullable String deviceValue, int defaultRes) {
        if (isWebLink(deviceValue)) {
            return deviceValue.trim();
        }
        String fallback = getString(defaultRes);
        return isWebLink(fallback) ? fallback.trim() : null;
    }

    private static final java.util.regex.Pattern UPI_ID =
            java.util.regex.Pattern.compile("^[A-Za-z0-9._-]{2,256}@[A-Za-z][A-Za-z0-9]{1,64}$");

    /**
     * UPI chip: opens the installed UPI apps (GPay, PhonePe, Paytm...) with the ID filled in.
     * Long-press copies the ID; with no UPI app installed, tapping copies it too.
     */
    private int bindUpi(int viewId) {
        View view = findViewById(viewId);
        if (view == null) return 0;
        String upi = Utils.getUpi();
        String name = Utils.getMaintainer();
        if (upi == null || !UPI_ID.matcher(upi.trim()).matches()) {
            upi = getString(R.string.config_support_upi);
            name = getString(R.string.config_support_upi_name);
        }
        if (upi == null || !UPI_ID.matcher(upi.trim()).matches()) {
            view.setVisibility(View.GONE);
            return 0;
        }
        final String id = upi.trim();
        mUpiId = id;
        final String payee = TextUtils.isEmpty(name) ? "MistOS" : name;
        view.setVisibility(View.VISIBLE);
        view.setOnClickListener(v -> {
            Uri uri = new Uri.Builder().scheme("upi").authority("pay")
                    .appendQueryParameter("pa", id)
                    .appendQueryParameter("pn", payee)
                    .appendQueryParameter("tn", getString(R.string.upi_pay_note))
                    .appendQueryParameter("cu", "INR")
                    .build();
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            if (intent.resolveActivity(getPackageManager()) == null) {
                copyUpi(id); // no UPI app installed
                return;
            }
            try {
                startActivity(Intent.createChooser(intent, getString(R.string.support_upi)));
            } catch (Exception e) {
                copyUpi(id);
            }
        });
        view.setOnLongClickListener(v -> {
            copyUpi(id);
            return true;
        });
        return 1;
    }

    private void copyUpi(String id) {
        android.content.ClipboardManager cm = getSystemService(
                android.content.ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    getString(R.string.upi_label), id));
        }
        Toast.makeText(this, getString(R.string.upi_copied, id), Toast.LENGTH_SHORT).show();
    }

    private static boolean isWebLink(@Nullable String value) {
        if (value == null) return false;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("https://") || v.startsWith("http://");
    }

    private int bindSupportLink(int viewId, String url) {
        View view = findViewById(viewId);
        if (view == null) {
            return 0;
        }
        if (TextUtils.isEmpty(url)) {
            view.setVisibility(View.GONE);
            return 0;
        }
        view.setVisibility(View.VISIBLE);
        view.setOnClickListener(v -> openUrl(url));
        return 1;
    }

    private ChangelogDialog mChangelogDialog;

    private void openChangelog() {
        if (isFinishing() || isDestroyed()) return;
        if (mChangelogDialog != null && mChangelogDialog.isShowing()) return;
        mChangelogDialog = new ChangelogDialog(this);
        mChangelogDialog.show();
    }

    // ---------------------------------------------------------------------------------------
    // Hero header
    // ---------------------------------------------------------------------------------------

    private void refreshHero() {
        if (mHeroContainer == null || mHeroTitle == null || mIsTV) {
            return;
        }
        if (mDemoState != null) {
            applyHero(mDemoState, null, true);
            return;
        }
        if (mIsChecking) {
            applyHero(HeroState.CHECKING, null, false);
            return;
        }

        HeroState state = HeroState.UP_TO_DATE;
        UpdateInfo active = null;
        UpdaterController c = mUpdaterService != null
                ? mUpdaterService.getUpdaterController() : null;
        if (c != null) {
            List<UpdateInfo> updates = c.getUpdates();
            updates.sort((u1, u2) -> Long.compare(u2.getTimestamp(), u1.getTimestamp()));
            for (UpdateInfo u : updates) {
                String id = u.getDownloadId();
                if (c.isWaitingForReboot(id)) {
                    state = HeroState.REBOOT; active = u; break;
                } else if (c.isInstallingUpdate(id)) {
                    state = HeroState.INSTALLING; active = u; break;
                } else if (c.isVerifyingUpdate(id)) {
                    state = HeroState.VERIFYING; active = u; break;
                } else if (c.isDownloading(id)) {
                    state = HeroState.DOWNLOADING; active = u; break;
                }
            }
            if (active == null) {
                for (UpdateInfo u : updates) {
                    if (u.getPersistentStatus() == UpdateStatus.Persistent.VERIFIED
                            && Utils.canInstall(u)) {
                        state = HeroState.READY; active = u; break;
                    } else if (u.getPersistentStatus() == UpdateStatus.Persistent.INCOMPLETE) {
                        state = HeroState.PAUSED; active = u; break;
                    } else if (Utils.canInstall(u) && u.getAvailableOnline()) {
                        state = HeroState.AVAILABLE; active = u; break;
                    }
                }
            }
        }
        applyHero(state, active, false);
    }

    private void applyHero(HeroState state, @Nullable UpdateInfo update, boolean demo) {
        final boolean changed = state != mHeroState;
        if (changed && mHeroState != null && mAnimationsEnabled) {
            TransitionManager.beginDelayedTransition((ViewGroup) mHeroContainer,
                    new AutoTransition().setDuration(320).setInterpolator(EMPHASIZED));
        }
        mHeroState = state;

        // ----- Colours: each state has its own container colour -----
        final int bg;
        final int on;
        final int shape;
        final int onShape;
        switch (state) {
            case AVAILABLE:
                bg = attr(com.google.android.material.R.attr.colorTertiaryContainer);
                on = attr(com.google.android.material.R.attr.colorOnTertiaryContainer);
                shape = attr(com.google.android.material.R.attr.colorTertiary);
                onShape = attr(com.google.android.material.R.attr.colorOnTertiary);
                break;
            case REBOOT:
                bg = attr(androidx.appcompat.R.attr.colorPrimary);
                on = attr(com.google.android.material.R.attr.colorOnPrimary);
                shape = on;
                onShape = bg;
                break;
            default:
                bg = attr(com.google.android.material.R.attr.colorPrimaryContainer);
                on = attr(com.google.android.material.R.attr.colorOnPrimaryContainer);
                shape = attr(androidx.appcompat.R.attr.colorPrimary);
                onShape = attr(com.google.android.material.R.attr.colorOnPrimary);
                break;
        }
        animateHeroColor(bg);
        mHeroTitle.setTextColor(on);
        mHeroSubtitle.setTextColor(on);
        if (mHeaderLastCheck != null) {
            mHeaderLastCheck.setTextColor(on);
            mHeaderLastCheck.setBackgroundTintList(
                    ColorStateList.valueOf(ColorUtils.setAlphaComponent(on, 0x1F)));
            if (mHeaderLastCheck.getCompoundDrawablesRelative()[0] != null) {
                mHeaderLastCheck.getCompoundDrawablesRelative()[0].setTint(on);
            }
        }
        ColorStateList statBg = ColorStateList.valueOf(ColorUtils.setAlphaComponent(on, 0x1A));
        tintStat(R.id.hero_stat_size_box, statBg, on);
        tintStat(R.id.hero_stat_speed_box, statBg, on);
        tintStat(R.id.hero_stat_eta_box, statBg, on);
        mHeroShape.setImageTintList(ColorStateList.valueOf(shape));
        mHeroIcon.setImageTintList(ColorStateList.valueOf(onShape));
        if (mHeroProgress != null) {
            mHeroProgress.setIndicatorColor(shape);
            mHeroProgress.setTrackColor(ColorUtils.setAlphaComponent(shape, 0x48));
        }
        if (mHeroRing != null) {
            mHeroRing.setIndicatorColor(shape);
            mHeroRing.setTrackColor(ColorUtils.setAlphaComponent(shape, 0x48));
        }
        if (mHeroRingText != null) {
            mHeroRingText.setTextColor(on);
        }
        tintToolbar(on, bg);

        // ----- Content -----
        final String device = Build.MODEL;
        final String currentVersion = getString(R.string.list_build_version,
                BuildInfoUtils.getBuildVersion());
        final String version = update != null
                ? getString(R.string.list_build_version, update.getVersion())
                : (demo ? currentVersion : currentVersion);
        final String rawVersion = update != null ? update.getVersion()
                : BuildInfoUtils.getBuildVersion();

        // defaults
        boolean showShape = true, showLoading = false, showRing = false, showIcon = true;
        boolean showBadge = false, showEyebrow = false, showSubtitle = true;
        boolean showProgress = false, showStats = false, showLastCheck = false;
        boolean bigNumber = false;
        int iconRes = R.drawable.ic_mist;
        int shapeRes = R.drawable.shape_cookie_9;
        CharSequence title;
        CharSequence subtitle = null;
        CharSequence eyebrow = null;
        int eyebrowIcon = 0;

        switch (state) {
            case CHECKING:
                showShape = false;
                showLoading = true;
                showIcon = false;
                showProgress = true;
                title = getString(R.string.hero_checking);
                subtitle = getString(R.string.hero_checking_subtitle, device);
                setHeroProgress(-1);
                break;
            case AVAILABLE: {
                showEyebrow = true;
                eyebrow = getString(R.string.hero_new_update);
                eyebrowIcon = R.drawable.ic_sparkle;
                title = "MistOS\n" + rawVersion;
                String size = update != null
                        ? Formatter.formatShortFileSize(this, update.getFileSize()) : "3.1 GB";
                subtitle = getString(R.string.hero_update_available) + " · " + size;
                showLastCheck = true;
                break;
            }
            case DOWNLOADING:
            case PAUSED: {
                showShape = false;
                showIcon = false;
                bigNumber = true;
                showEyebrow = true;
                showProgress = true;
                showStats = true;
                showSubtitle = false;
                eyebrowIcon = state == HeroState.PAUSED ? R.drawable.ic_pause : R.drawable.ic_download;
                eyebrow = getString(state == HeroState.PAUSED
                        ? R.string.hero_download_paused : R.string.hero_downloading, version);
                int progress = update != null ? update.getProgress() : 38;
                title = percent(progress);
                setHeroProgress(progress);
                long done = update != null ? update.getFile().length() : 1194308326L;
                long total = update != null ? update.getFileSize() : 3142916649L;
                setText(mHeroStatSize, Formatter.formatShortFileSize(this, done));
                setText(mHeroStatSizeLabel, getString(R.string.stat_of,
                        Formatter.formatShortFileSize(this, total)));
                if (state == HeroState.DOWNLOADING) {
                    long speed = update != null ? update.getSpeed() : 12400000L;
                    long eta = update != null ? update.getEta() : 162L;
                    setText(mHeroStatSpeed, speed > 0 ? getString(R.string.stat_speed_value,
                            Formatter.formatShortFileSize(this, speed))
                            : getString(R.string.stat_unknown));
                    setText(mHeroStatEta, eta > 0 ? formatShortDuration(eta)
                            : getString(R.string.stat_unknown));
                } else {
                    setText(mHeroStatSpeed, getString(R.string.stat_unknown));
                    setText(mHeroStatEta, getString(R.string.stat_unknown));
                }
                break;
            }
            case VERIFYING:
                showShape = false;
                showLoading = true;
                showIcon = false;
                showProgress = true;
                title = getString(R.string.hero_verifying);
                subtitle = getString(R.string.hero_verifying_subtitle);
                setHeroProgress(-1);
                break;
            case READY:
                iconRes = R.drawable.ic_download;
                showBadge = true;
                title = getString(R.string.hero_ready);
                subtitle = getString(R.string.hero_ready_subtitle, version);
                break;
            case INSTALLING: {
                showShape = false;
                showIcon = false;
                showRing = true;
                showEyebrow = true;
                int progress = update != null ? update.getInstallProgress() : 64;
                boolean finalizing = update != null && update.getFinalizing();
                boolean ab = mUpdaterService == null
                        || mUpdaterService.getUpdaterController().isInstallingABUpdate() || demo;
                eyebrow = getString(R.string.hero_install_step, finalizing ? 2 : 1, 2);
                title = getString(R.string.hero_installing);
                String stage = getString(!ab ? R.string.dialog_prepare_zip_message
                        : finalizing ? R.string.finalizing_package
                        : R.string.preparing_ota_first_boot);
                subtitle = getString(R.string.hero_keep_using, stage);
                if (mHeroRing != null) {
                    mHeroRing.setIndeterminate(false);
                    mHeroRing.setProgressCompat(progress, true);
                }
                setText(mHeroRingText, percent(progress));
                break;
            }
            case REBOOT:
                iconRes = R.drawable.ic_check;
                title = getString(R.string.hero_installed);
                subtitle = getString(R.string.hero_installed_subtitle, device, version);
                break;
            case UP_TO_DATE:
            default:
                showBadge = true;
                title = getString(R.string.hero_up_to_date);
                subtitle = getString(R.string.hero_current_build, currentVersion, device);
                showLastCheck = true;
                break;
        }

        mHeroShape.setImageResource(shapeRes);
        mHeroIcon.setImageResource(iconRes);
        ViewGroup.LayoutParams ilp = mHeroIcon.getLayoutParams();
        boolean logo = iconRes == R.drawable.ic_mist;
        ilp.width = dp(logo ? 84 : 60);
        ilp.height = dp(logo ? 51 : 60);
        mHeroIcon.setLayoutParams(ilp);

        setVisible(mHeroShape, showShape);
        setVisible(mHeroLoading, showLoading);
        setVisible(mHeroRing, showRing);
        setVisible(mHeroRingText, showRing);
        setVisible(mHeroIcon, showIcon);
        setVisible(findViewById(R.id.hero_art), showShape || showLoading || showRing);
        if (showBadge && mHeroBadge.getVisibility() != View.VISIBLE) {
            popIn(mHeroBadge);
        }
        setVisible(mHeroBadge, showBadge);

        setVisible(mHeroEyebrow, showEyebrow);
        if (showEyebrow) {
            mHeroEyebrow.setText(eyebrow);
            Drawable d = eyebrowIcon != 0 ? getDrawable(eyebrowIcon) : null;
            if (d != null) {
                d = d.mutate();
                d.setTint(attr(com.google.android.material.R.attr.colorOnTertiary));
                d.setBounds(0, 0, dp(16), dp(16));
            }
            mHeroEyebrow.setCompoundDrawablesRelative(d, null, null, null);
        }

        mHeroTitle.setText(title);
        if (bigNumber) {
            mHeroTitle.setTextAppearance(R.style.TextAppearance_Updater_HeroNumber);
            mHeroTitle.setTextColor(shape);
        } else {
            mHeroTitle.setTextAppearance(R.style.TextAppearance_Updater_Hero);
            mHeroTitle.setTextColor(on);
        }

        setVisible(mHeroSubtitle, showSubtitle && subtitle != null);
        if (subtitle != null) mHeroSubtitle.setText(subtitle);
        setVisible(mHeroProgress, showProgress);
        setVisible(mHeroStats, showStats);
        if (mHeaderLastCheck != null) {
            boolean hasCheck = Boolean.TRUE.equals(mHeaderLastCheck.getTag());
            setVisible(mHeaderLastCheck, showLastCheck && hasCheck);
        }

        if (changed) {
            startShapeSpin();
            View root = findViewById(R.id.main_container);
            if (root != null) {
                root.performHapticFeedback(state == HeroState.REBOOT
                        ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.CLOCK_TICK);
            }
        }
    }

    private void setHeroProgress(int progress) {
        if (mHeroProgress == null) return;
        if (progress < 0) {
            if (!mHeroProgress.isIndeterminate()) {
                mHeroProgress.setVisibility(View.INVISIBLE);
                mHeroProgress.setIndeterminate(true);
            }
        } else {
            if (mHeroProgress.isIndeterminate()) {
                mHeroProgress.setVisibility(View.INVISIBLE);
                mHeroProgress.setIndeterminate(false);
            }
            mHeroProgress.setProgressCompat(progress, true);
        }
    }

    private void animateHeroColor(int target) {
        if (mHeroBgColor == target) return;
        final int from = mHeroBgColor == Color.TRANSPARENT ? target : mHeroBgColor;
        mHeroBgColor = target;
        if (mHeroColorAnimator != null) mHeroColorAnimator.cancel();
        if (!mAnimationsEnabled || from == target) {
            setHeroBg(target);
            return;
        }
        mHeroColorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), from, target);
        mHeroColorAnimator.setDuration(380);
        mHeroColorAnimator.setInterpolator(EMPHASIZED);
        mHeroColorAnimator.addUpdateListener(a -> setHeroBg((int) a.getAnimatedValue()));
        mHeroColorAnimator.start();
    }

    private void setHeroBg(int color) {
        mHeroContainer.setBackgroundTintList(ColorStateList.valueOf(color));
        if (mCollapsingToolbar != null) {
            GradientDrawable scrim = new GradientDrawable();
            scrim.setColor(color);
            float r = dp(28);
            scrim.setCornerRadii(new float[]{0, 0, 0, 0, r, r, r, r});
            mCollapsingToolbar.setContentScrim(scrim);
        }
        boolean light = ColorUtils.calculateLuminance(color) > 0.5;
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(light);
    }

    private void tintToolbar(int on, int bg) {
        if (mToolbar == null) return;
        Drawable nav = mToolbar.getNavigationIcon();
        if (nav != null) nav.mutate().setTint(on);
        Drawable overflow = mToolbar.getOverflowIcon();
        if (overflow != null) overflow.mutate().setTint(on);
        if (mCollapsingToolbar != null) {
            mCollapsingToolbar.setCollapsedTitleTextColor(on);
        }
    }

    private void tintStat(int id, ColorStateList bg, int on) {
        View box = findViewById(id);
        if (box == null) return;
        box.setBackgroundTintList(bg);
        if (box instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) box;
            for (int i = 0; i < g.getChildCount(); i++) {
                if (g.getChildAt(i) instanceof TextView) {
                    ((TextView) g.getChildAt(i)).setTextColor(on);
                }
            }
        }
    }

    private void startShapeSpin() {
        if (mHeroShape == null || !mAnimationsEnabled) return;
        if (mShapeSpin == null) {
            mShapeSpin = ObjectAnimator.ofFloat(mHeroShape, View.ROTATION, 0f, 360f);
            mShapeSpin.setDuration(30000);
            mShapeSpin.setInterpolator(new LinearInterpolator());
            mShapeSpin.setRepeatCount(ValueAnimator.INFINITE);
            mShapeSpin.start();
        } else if (mShapeSpin.isPaused()) {
            mShapeSpin.resume();
        }
    }

    private void popIn(View v) {
        if (!mAnimationsEnabled) return;
        v.setScaleX(0f);
        v.setScaleY(0f);
        v.animate().scaleX(1f).scaleY(1f).setStartDelay(220).setDuration(450)
                .setInterpolator(new OvershootInterpolator(2.2f)).start();
    }

    private void playEntranceAnimation() {
        if (!mAnimationsEnabled || mIsTV) return;
        ViewGroup content = findViewById(R.id.content);
        if (content == null) return;
        long delay = 120;
        for (int i = 0; i < content.getChildCount(); i++) {
            View child = content.getChildAt(i);
            child.setAlpha(0f);
            child.setTranslationY(dp(24));
            child.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(520)
                    .setInterpolator(EMPHASIZED).start();
            delay += 45;
        }
        View bar = findViewById(R.id.bottom_bar);
        if (bar != null) {
            bar.setTranslationY(dp(96));
            bar.animate().translationY(0f).setStartDelay(200).setDuration(520)
                    .setInterpolator(EMPHASIZED).start();
        }
        if (mHeroTitle != null) {
            mHeroTitle.setAlpha(0f);
            mHeroTitle.setTranslationY(dp(16));
            mHeroTitle.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(520)
                    .setInterpolator(EMPHASIZED).start();
        }
    }

    // ----- Debug-only preview of every state (long-press the header) -----

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    private void cycleDemoState() {
        HeroState[] all = HeroState.values();
        if (mDemoState == null) {
            mDemoState = all[0];
        } else if (mDemoState.ordinal() == all.length - 1) {
            mDemoState = null;
        } else {
            mDemoState = all[mDemoState.ordinal() + 1];
        }
        Toast.makeText(this, mDemoState == null ? "Demo off" : "Demo: " + mDemoState,
                Toast.LENGTH_SHORT).show();
        refreshHero();
    }

    // ---------------------------------------------------------------------------------------
    // Misc
    // ---------------------------------------------------------------------------------------

    private void handleDownloadStatusChange(String downloadId) {
        if (Update.LOCAL_ID.equals(downloadId)) {
            return;
        }

        UpdateInfo update = mUpdaterService.getUpdaterController().getUpdate(downloadId);
        if (update == null) {
            return;
        }
        switch (update.getStatus()) {
            case PAUSED_ERROR:
                showSnackbar(R.string.snack_download_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFICATION_FAILED:
                showSnackbar(R.string.snack_download_verification_failed, Snackbar.LENGTH_LONG);
                break;
            case VERIFIED:
                showSnackbar(R.string.snack_download_verified, Snackbar.LENGTH_LONG);
                break;
            default:
                break;
        }
    }

    @Override
    public void exportUpdate(UpdateInfo update) {
        mToBeExported = update;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, update.getName());

        mExportUpdate.launch(intent);
    }

    private void exportUpdate(Uri uri) {
        Intent intent = new Intent(this, ExportUpdateService.class);
        intent.setAction(ExportUpdateService.ACTION_START_EXPORTING);
        intent.putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, mToBeExported.getFile());
        intent.putExtra(ExportUpdateService.EXTRA_DEST_URI, uri);
        startService(intent);
    }

    @Override
    public void showSnackbar(int stringId, int duration) {
        Snackbar snackbar = Snackbar.make(findViewById(R.id.main_container), stringId, duration);
        View anchor = findViewById(R.id.bottom_bar);
        if (anchor != null) {
            snackbar.setAnchorView(anchor);
        }
        snackbar.show();
    }

    private void refreshAnimationStart() {
        mIsChecking = true;
        if (!mIsTV) {
            if (mCheckButton != null) {
                mCheckButton.setEnabled(false);
                mCheckButton.setText(R.string.action_checking);
            }
            if (mLocalButton != null) mLocalButton.setEnabled(false);
            refreshHero();
        } else {
            setVisible(findViewById(R.id.recycler_view), false);
            setVisible(findViewById(R.id.no_new_updates_view), false);
            setVisible(findViewById(R.id.refresh_progress), true);
        }
    }

    private void refreshAnimationStop() {
        mIsChecking = false;
        if (!mIsTV) {
            if (mCheckButton != null) {
                mCheckButton.setEnabled(true);
                mCheckButton.setText(R.string.action_check_updates);
            }
            if (mLocalButton != null) mLocalButton.setEnabled(true);
            refreshHero();
        } else {
            setVisible(findViewById(R.id.refresh_progress), false);
            if (mAdapter.getItemCount() > 0) {
                setVisible(findViewById(R.id.recycler_view), true);
            } else {
                setVisible(findViewById(R.id.no_new_updates_view), true);
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showPreferencesDialog() {
        View view = LayoutInflater.from(this).inflate(R.layout.preferences_dialog, null);
        MaterialButtonToggleGroup autoCheckInterval =
                view.findViewById(R.id.preferences_auto_updates_check_interval);
        SwitchCompat autoDelete = view.findViewById(R.id.preferences_auto_delete_updates);
        SwitchCompat meteredNetworkWarning = view.findViewById(
                R.id.preferences_metered_network_warning);
        SwitchCompat abPerfMode = view.findViewById(R.id.preferences_ab_perf_mode);
        SwitchCompat updateRecovery = view.findViewById(R.id.preferences_update_recovery);

        // Whole row toggles its switch
        linkRow(view, R.id.row_auto_delete, autoDelete);
        linkRow(view, R.id.row_metered, meteredNetworkWarning);
        linkRow(view, R.id.row_ab_perf, abPerfMode);

        if (!Utils.isABDevice()) {
            view.findViewById(R.id.row_ab_perf).setVisibility(View.GONE);
        }

        final int[] intervalButtons = {R.id.interval_never, R.id.interval_daily,
                R.id.interval_weekly, R.id.interval_monthly};

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        int interval = Utils.getUpdateCheckSetting(this);
        if (interval < 0 || interval >= intervalButtons.length) {
            interval = Constants.AUTO_UPDATES_CHECK_INTERVAL_DAILY;
        }
        autoCheckInterval.check(intervalButtons[interval]);
        autoDelete.setChecked(prefs.getBoolean(Constants.PREF_AUTO_DELETE_UPDATES, false));
        meteredNetworkWarning.setChecked(prefs.getBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                prefs.getBoolean(Constants.PREF_MOBILE_DATA_WARNING, true)));
        abPerfMode.setChecked(prefs.getBoolean(Constants.PREF_AB_PERF_MODE, false));

        if (getResources().getBoolean(R.bool.config_hideRecoveryUpdate)) {
            // Hide the update feature if explicitly requested.
            // Might be the case of A-only devices using prebuilt vendor images.
            view.findViewById(R.id.row_update_recovery).setVisibility(View.GONE);
        } else if (Utils.isRecoveryUpdateExecPresent()) {
            linkRow(view, R.id.row_update_recovery, updateRecovery);
            updateRecovery.setChecked(
                    SystemProperties.getBoolean(Constants.UPDATE_RECOVERY_PROPERTY, false));
        } else {
            // There is no recovery updater script in the device, so the feature is considered
            // forcefully enabled, just to avoid users to be confused and complain that
            // recovery gets overwritten. That's the case of A/B and recovery-in-boot devices.
            updateRecovery.setChecked(true);
            updateRecovery.setOnTouchListener(new View.OnTouchListener() {
                private Toast forcedUpdateToast = null;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() != MotionEvent.ACTION_UP) {
                        return true;
                    }
                    if (forcedUpdateToast != null) {
                        forcedUpdateToast.cancel();
                    }
                    forcedUpdateToast = Toast.makeText(getApplicationContext(),
                            getString(R.string.toast_forced_update_recovery), Toast.LENGTH_SHORT);
                    forcedUpdateToast.show();
                    return true;
                }
            });
        }

        BottomSheetDialog sheet = new BottomSheetDialog(this);
        sheet.setContentView(view);
        sheet.getBehavior().setSkipCollapsed(true);
        sheet.getBehavior().setState(
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        view.findViewById(R.id.preferences_done).setOnClickListener(v -> sheet.dismiss());
        sheet.setOnDismissListener(dialogInterface -> {
            int checked = autoCheckInterval.getCheckedButtonId();
            int selected = Constants.AUTO_UPDATES_CHECK_INTERVAL_DAILY;
            for (int i = 0; i < intervalButtons.length; i++) {
                if (intervalButtons[i] == checked) selected = i;
            }
            prefs.edit()
                    .putInt(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL, selected)
                    .putBoolean(Constants.PREF_AUTO_DELETE_UPDATES, autoDelete.isChecked())
                    .putBoolean(Constants.PREF_METERED_NETWORK_WARNING,
                            meteredNetworkWarning.isChecked())
                    .putBoolean(Constants.PREF_AB_PERF_MODE, abPerfMode.isChecked())
                    .apply();

            if (Utils.isUpdateCheckEnabled(this)) {
                UpdatesCheckReceiver.scheduleRepeatingUpdatesCheck(this);
            } else {
                UpdatesCheckReceiver.cancelRepeatingUpdatesCheck(this);
                UpdatesCheckReceiver.cancelUpdatesCheck(this);
            }

            if (Utils.isABDevice() && mUpdaterService != null) {
                boolean enableABPerfMode = abPerfMode.isChecked();
                try {
                    mUpdaterService.getUpdaterController().setPerformanceMode(enableABPerfMode);
                } catch (RuntimeException e) {
                    // update_engine is only reachable from the privileged system app
                    // (not from the unprivileged Android Studio debug build).
                    Log.w(TAG, "Unable to set A/B performance mode", e);
                }
            }
            if (Utils.isRecoveryUpdateExecPresent()) {
                boolean enableRecoveryUpdate = updateRecovery.isChecked();
                SystemProperties.set(Constants.UPDATE_RECOVERY_PROPERTY,
                        String.valueOf(enableRecoveryUpdate));
            }
        });
        sheet.show();
    }

    private static void linkRow(View root, int rowId, SwitchCompat sw) {
        View row = root.findViewById(rowId);
        if (row != null) {
            row.setOnClickListener(v -> sw.toggle());
        }
    }

    // ----- small helpers -----

    private int attr(@AttrRes int attr) {
        return MaterialColors.getColor(this, attr, Color.GRAY);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setText(int id, CharSequence text) {
        setText((TextView) findViewById(id), text);
    }

    private static void setText(@Nullable TextView view, CharSequence text) {
        if (view != null) view.setText(text);
    }

    private static void setVisible(@Nullable View view, boolean visible) {
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private static String percent(int progress) {
        return java.text.NumberFormat.getPercentInstance().format(progress / 100f);
    }

    private String formatShortDuration(long seconds) {
        MeasureFormat fmt = MeasureFormat.getInstance(Locale.getDefault(),
                MeasureFormat.FormatWidth.SHORT);
        if (seconds < 60) {
            return fmt.format(new Measure(seconds, MeasureUnit.SECOND));
        } else if (seconds < 3600) {
            return fmt.format(new Measure(Math.round(seconds / 60f), MeasureUnit.MINUTE));
        }
        return fmt.formatMeasures(new Measure(seconds / 3600, MeasureUnit.HOUR),
                new Measure((seconds % 3600) / 60, MeasureUnit.MINUTE));
    }
}
