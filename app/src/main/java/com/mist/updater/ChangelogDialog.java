/*
 * SPDX-FileCopyrightText: 2026 Project Mist OS
 * SPDX-License-Identifier: Apache-2.0
 */
package com.mist.updater;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceManager;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.divider.MaterialDivider;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import com.mist.updater.misc.Utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ChangelogDialog extends Dialog {

    private static final String TAG = "ChangelogDialog";

    private static final long LIVE_REFRESH_MS = 15_000;
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final String PREF_ETAG = "changelog_etag_";
    private static final Pattern RAW_GITHUB = Pattern.compile(
            "^https?://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/([^/]+)/(.+)$");

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final PathInterpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);
    private static final PathInterpolator EMPHASIZED_ACCEL =
            new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);

    private final Activity mActivity;

    private View mRoot;
    private View mCard;
    private LinearLayout mContent;
    private TextView mSubtitle;
    private TextView mStatus;
    private TextView mEmpty;
    private View mLoading;
    private View mLiveDot;
    private LinearProgressIndicator mProgress;

    private String mShownText;
    private boolean mClosing;
    private boolean mFetching;
    private boolean mStarted;
    private ValueAnimator mBlurAnimator;
    private ObjectAnimator mDotPulse;
    private final Runnable mLiveRefresh = this::refresh;

    public ChangelogDialog(Activity activity) {
        super(activity, R.style.AppTheme_ChangelogDialog);
        mActivity = activity;
    }

    public static void prefetch(Context context) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                fetchSync(app);
            } catch (Exception e) {
                Log.d(TAG, "Prefetch failed", e);
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_changelog);

        Window window = getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            WindowCompat.setDecorFitsSystemWindows(window, false);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? 0.18f : 0.5f);
        }

        mRoot = findViewById(R.id.changelog_root);
        mCard = findViewById(R.id.changelog_card);
        mContent = findViewById(R.id.changelog_content);
        mSubtitle = findViewById(R.id.changelog_subtitle);
        mStatus = findViewById(R.id.changelog_status);
        mEmpty = findViewById(R.id.changelog_empty);
        mLoading = findViewById(R.id.changelog_loading);
        mLiveDot = findViewById(R.id.changelog_live_dot);
        mProgress = findViewById(R.id.changelog_progress);

        mSubtitle.setText(Build.MODEL);
        findViewById(R.id.changelog_close).setOnClickListener(v -> dismissAnimated());
        mRoot.setOnClickListener(v -> dismissAnimated()); // tap outside the card

        final int pad = dp(16);
        ViewCompat.setOnApplyWindowInsetsListener(mRoot, (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(pad, top + pad, pad, bottom + pad);
            v.post(this::sizeCard);
            return WindowInsetsCompat.CONSUMED;
        });

        mCard.setAlpha(0f);
        mCard.setScaleX(0.9f);
        mCard.setScaleY(0.9f);
        mCard.setTranslationY(dp(48));
    }

    @Override
    protected void onStart() {
        super.onStart();
        mClosing = false;
        mStarted = true;

        String cached = readCache(getContext());
        if (cached != null) {
            render(cached, false);
            setStatus(R.string.changelog_status_saved);
        } else {
            showLoading(true);
            setStatus(R.string.changelog_status_loading);
        }

        animateIn();
        startDotPulse();
        refresh();
    }

    @Override
    protected void onStop() {
        mStarted = false;
        MAIN.removeCallbacks(mLiveRefresh);
        if (mDotPulse != null) mDotPulse.cancel();
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        dismissAnimated();
    }

    @Override
    public void dismiss() {
        MAIN.removeCallbacks(mLiveRefresh);
        clearBlur();
        super.dismiss();
    }

    private void sizeCard() {
        int available = mRoot.getHeight() - mRoot.getPaddingTop() - mRoot.getPaddingBottom();
        if (available <= 0) return;
        ViewGroup.LayoutParams lp = mCard.getLayoutParams();
        int target = Math.round(available * 0.9f);
        if (lp.height != target) {
            lp.height = target;
            mCard.setLayoutParams(lp);
        }
    }

    private boolean animationsEnabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    private void animateIn() {
        if (!animationsEnabled()) {
            mCard.setAlpha(1f);
            mCard.setScaleX(1f);
            mCard.setScaleY(1f);
            mCard.setTranslationY(0f);
            setBlur(1f);
            return;
        }
        mCard.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                .setDuration(450).setInterpolator(EMPHASIZED).start();
        animateBlur(0f, 1f, 420);
    }

    private void dismissAnimated() {
        if (mClosing) return;
        mClosing = true;
        MAIN.removeCallbacks(mLiveRefresh);
        if (!animationsEnabled()) {
            dismiss();
            return;
        }
        mCard.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).translationY(dp(32))
                .setDuration(220).setInterpolator(EMPHASIZED_ACCEL)
                .withEndAction(this::dismiss).start();
        animateBlur(1f, 0f, 240);
    }

    private void animateBlur(float from, float to, long duration) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        if (mBlurAnimator != null) mBlurAnimator.cancel();
        mBlurAnimator = ValueAnimator.ofFloat(from, to);
        mBlurAnimator.setDuration(duration);
        mBlurAnimator.setInterpolator(EMPHASIZED);
        mBlurAnimator.addUpdateListener(a -> setBlur((float) a.getAnimatedValue()));
        mBlurAnimator.start();
    }

    private void setBlur(float fraction) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        View target = mActivity.getWindow().getDecorView();
        float radius = dp(28) * fraction;
        if (radius < 0.5f) {
            target.setRenderEffect(null);
        } else {
            target.setRenderEffect(
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        }
    }

    private void clearBlur() {
        if (mBlurAnimator != null) mBlurAnimator.cancel();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            mActivity.getWindow().getDecorView().setRenderEffect(null);
        }
    }

    private void startDotPulse() {
        if (!animationsEnabled() || mLiveDot == null) return;
        if (mDotPulse == null) {
            mDotPulse = ObjectAnimator.ofFloat(mLiveDot, View.ALPHA, 1f, 0.25f);
            mDotPulse.setDuration(900);
            mDotPulse.setRepeatMode(ValueAnimator.REVERSE);
            mDotPulse.setRepeatCount(ValueAnimator.INFINITE);
        }
        mDotPulse.start();
    }

    private void refresh() {
        if (mFetching || !mStarted || mClosing) return;
        mFetching = true;
        showProgress(true);
        final Context app = getContext().getApplicationContext();
        EXECUTOR.execute(() -> {
            FetchResult result;
            try {
                result = fetchSync(app);
            } catch (Exception e) {
                Log.w(TAG, "Changelog fetch failed", e);
                result = new FetchResult(readCache(app), false);
            }
            final FetchResult r = result;
            MAIN.post(() -> onFetched(r));
        });
    }

    private void onFetched(FetchResult result) {
        mFetching = false;
        if (!mStarted || mClosing) return;
        showProgress(false);
        showLoading(false);
        if (result.text != null && !result.text.equals(mShownText)) {
            boolean hadContent = mShownText != null;
            render(result.text, hadContent);
            setStatus(result.online ? (hadContent ? R.string.changelog_status_changed
                    : R.string.changelog_status_live) : R.string.changelog_status_offline);
        } else if (result.text == null && mShownText == null) {
            mEmpty.setText(R.string.changelog_failed);
            mEmpty.setVisibility(View.VISIBLE);
            setStatus(R.string.changelog_status_unreachable);
        } else {
            setStatus(result.online ? R.string.changelog_status_live
                    : R.string.changelog_status_offline);
        }
        MAIN.removeCallbacks(mLiveRefresh);
        MAIN.postDelayed(mLiveRefresh, LIVE_REFRESH_MS);
    }

    private void setStatus(int resId) {
        mStatus.setText(resId);
    }

    private void showProgress(boolean show) {
        if (mProgress == null) return;
        if (show) {
            mProgress.show();
        } else {
            mProgress.hide();
        }
        mProgress.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showLoading(boolean show) {
        mLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) mEmpty.setVisibility(View.GONE);
    }

    private void render(String text, boolean crossfade) {
        mShownText = text;
        mEmpty.setVisibility(View.GONE);
        showLoading(false);
        final List<Block> blocks = parse(text);
        updateSubtitle(blocks);
        if (crossfade && animationsEnabled()) {
            mContent.animate().alpha(0f).setDuration(140).withEndAction(() -> {
                buildContent(blocks, false);
                mContent.animate().alpha(1f).setDuration(260).setInterpolator(EMPHASIZED)
                        .start();
                flashCard();
            }).start();
        } else {
            buildContent(blocks, animationsEnabled());
        }
    }

    private void flashCard() {
        mCard.animate().scaleX(1.015f).scaleY(1.015f).setDuration(140).withEndAction(() ->
                mCard.animate().scaleX(1f).scaleY(1f).setDuration(260)
                        .setInterpolator(EMPHASIZED).start()).start();
    }

    private void updateSubtitle(List<Block> blocks) {
        String version = null;
        String codename = null;
        for (Block b : blocks) {
            if (b.type == Block.FACT && b.title != null) {
                String key = b.title.toLowerCase(Locale.ROOT);
                if (version == null && key.startsWith("version")) version = b.text;
                if (codename == null && key.startsWith("codename")) codename = b.text;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (version != null) sb.append("MistOS ").append(version);
        if (codename != null) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(codename);
        }
        if (sb.length() > 0) sb.append(" · ");
        sb.append(Build.MODEL);
        mSubtitle.setText(sb);
    }

    private void buildContent(List<Block> blocks, boolean stagger) {
        mContent.removeAllViews();
        Context ctx = getContext();
        int primary = color(androidx.appcompat.R.attr.colorPrimary);
        int tertiary = color(com.google.android.material.R.attr.colorTertiary);
        int onSurface = color(com.google.android.material.R.attr.colorOnSurface);
        int onSurfaceVariant = color(com.google.android.material.R.attr.colorOnSurfaceVariant);
        int container = color(com.google.android.material.R.attr.colorSurfaceContainer);

        ChipGroup facts = null;
        LinearLayout section = null;
        int sectionCount = 0;

        for (Block b : blocks) {
            switch (b.type) {
                case Block.FACT: {
                    section = null;
                    if (facts == null) {
                        facts = new ChipGroup(ctx);
                        facts.setChipSpacingHorizontal(dp(8));
                        facts.setChipSpacingVertical(dp(4));
                        addTop(facts, dp(4));
                    }
                    Chip chip = new Chip(ctx);
                    chip.setText(b.title + " · " + b.text);
                    chip.setClickable(false);
                    chip.setChipBackgroundColor(ColorStateList.valueOf(
                            color(com.google.android.material.R.attr.colorSecondaryContainer)));
                    chip.setTextColor(
                            color(com.google.android.material.R.attr.colorOnSecondaryContainer));
                    chip.setChipStrokeWidth(0f);
                    chip.setEnsureMinTouchTargetSize(false);
                    facts.addView(chip);
                    break;
                }
                case Block.DIVIDER: {
                    facts = null;
                    section = null;
                    MaterialDivider divider = new MaterialDivider(ctx);
                    divider.setDividerInsetStart(dp(8));
                    divider.setDividerInsetEnd(dp(8));
                    addTop(divider, dp(24));
                    break;
                }
                case Block.SECTION: {
                    facts = null;
                    TextView title = new TextView(ctx);
                    title.setText(b.text);
                    title.setTextAppearance(
                            com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
                    title.setTextColor(primary);
                    title.setLetterSpacing(0.02f);
                    title.setPaddingRelative(dp(4), 0, 0, 0);
                    addTop(title, sectionCount == 0 ? dp(18) : dp(22));
                    sectionCount++;
                    section = null;
                    break;
                }
                case Block.ITEM: {
                    facts = null;
                    if (section == null) {
                        section = new LinearLayout(ctx);
                        section.setOrientation(LinearLayout.VERTICAL);
                        section.setBackgroundResource(R.drawable.bg_tile);
                        section.setBackgroundTintList(ColorStateList.valueOf(container));
                        section.setPadding(dp(16), dp(14), dp(16), dp(14));
                        addTop(section, dp(10));
                    }
                    section.addView(buildItem(ctx, b, tertiary, onSurface, onSurfaceVariant),
                            itemParams(section.getChildCount() == 0 ? 0 : dp(12)));
                    break;
                }
                case Block.PARA:
                default: {
                    facts = null;
                    section = null;
                    TextView para = new TextView(ctx);
                    para.setText(b.text);
                    para.setTextAppearance(
                            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
                    para.setTextColor(onSurfaceVariant);
                    addTop(para, dp(10));
                    break;
                }
            }
        }

        if (stagger) {
            long delay = 80;
            for (int i = 0; i < mContent.getChildCount(); i++) {
                View child = mContent.getChildAt(i);
                child.setAlpha(0f);
                child.setTranslationY(dp(18));
                child.animate().alpha(1f).translationY(0f).setStartDelay(delay)
                        .setDuration(420).setInterpolator(EMPHASIZED).start();
                delay = Math.min(delay + 40, 520);
            }
        }
    }

    private View buildItem(Context ctx, Block b, int bullet, int onSurface, int onSurfaceVariant) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);

        View dot = new View(ctx);
        dot.setBackgroundResource(R.drawable.bg_step_square);
        dot.setBackgroundTintList(ColorStateList.valueOf(bullet));
        dot.setRotation(45f);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.topMargin = dp(8);
        dlp.setMarginEnd(dp(14));
        row.addView(dot, dlp);

        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(ctx);
        title.setText(b.title);
        title.setTextAppearance(
                com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        title.setTextColor(onSurface);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        texts.addView(title);
        if (!TextUtils.isEmpty(b.text)) {
            TextView desc = new TextView(ctx);
            desc.setText(b.text);
            desc.setTextAppearance(
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            desc.setTextColor(onSurfaceVariant);
            texts.addView(desc);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private LinearLayout.LayoutParams itemParams(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topMargin;
        return lp;
    }

    private void addTop(View view, int topMargin) {
        mContent.addView(view, itemParams(topMargin));
    }

    private int color(@AttrRes int attr) {
        return MaterialColors.getColor(mCard, attr, Color.GRAY);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getContext().getResources().getDisplayMetrics()));
    }

    static final class Block {
        static final int FACT = 0;
        static final int SECTION = 1;
        static final int ITEM = 2;
        static final int DIVIDER = 3;
        static final int PARA = 4;

        final int type;
        final String title;
        final String text;

        Block(int type, String title, String text) {
            this.type = type;
            this.title = title;
            this.text = text;
        }
    }

    private static final Pattern LETTER_OR_DIGIT = Pattern.compile("[\\p{L}\\p{N}]");
    private static final Pattern VERSION_LINE = Pattern.compile("(?i)^version\\s+(.+)$");

    static List<Block> parse(String text) {
        List<Block> out = new ArrayList<>();
        boolean pendingDivider = false;
        boolean seenItems = false;
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (!LETTER_OR_DIGIT.matcher(line).find()) {
                // Box drawing / separator lines (╔═╗ ━━━ etc.)
                if (seenItems) pendingDivider = true;
                continue;
            }
            line = line.replaceAll("^[║│|]+", "").replaceAll("[║│|]+$", "").trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("- ") || line.startsWith("• ") || line.startsWith("* ")) {
                String body = line.substring(2).trim();
                String title = body;
                String desc = null;
                int idx = indexOfSeparator(body);
                if (idx > 0) {
                    title = body.substring(0, idx).trim();
                    desc = body.substring(idx).replaceFirst("^\\s*[—–-]\\s*", "").trim();
                }
                if (pendingDivider) {
                    out.add(new Block(Block.DIVIDER, null, null));
                    pendingDivider = false;
                }
                out.add(new Block(Block.ITEM, title, desc));
                seenItems = true;
                continue;
            }

            Matcher version = VERSION_LINE.matcher(line);
            if (version.matches()) {
                out.add(new Block(Block.FACT, "Version", version.group(1).trim()));
                continue;
            }

            String clean = stripLeadingSymbols(line);
            if (clean.toUpperCase(Locale.ROOT).contains("CHANGELOG") && !seenItems
                    && !clean.contains(":")) {
                continue;
            }

            if (clean.endsWith(":-") || clean.endsWith(":")) {
                if (pendingDivider) {
                    out.add(new Block(Block.DIVIDER, null, null));
                    pendingDivider = false;
                }
                out.add(new Block(Block.SECTION, null,
                        clean.replaceAll("\\s*:-?$", "").trim()));
                continue;
            }

            int colon = clean.indexOf(':');
            if (colon > 0 && colon < 24) {
                String key = clean.substring(0, colon).trim();
                String value = clean.substring(colon + 1).trim();
                if (!value.isEmpty()) {
                    out.add(new Block(Block.FACT, key, value));
                    continue;
                }
            }

            if (clean.length() <= 40 && !clean.endsWith(".")) {
                if (pendingDivider) {
                    out.add(new Block(Block.DIVIDER, null, null));
                    pendingDivider = false;
                }
                out.add(new Block(Block.SECTION, null, clean));
            } else {
                out.add(new Block(Block.PARA, null, clean));
            }
        }
        return tidy(out);
    }

    private static List<Block> tidy(List<Block> in) {
        List<Block> out = new ArrayList<>();
        for (Block b : in) {
            Block prev = out.isEmpty() ? null : out.get(out.size() - 1);
            if (b.type == Block.SECTION && prev != null && prev.type == Block.SECTION) {
                continue;
            }
            if (b.type == Block.SECTION && prev != null && prev.type == Block.DIVIDER
                    && b.text.equalsIgnoreCase("Changelog")) {
                out.add(new Block(Block.SECTION, null, "Earlier changes"));
                continue;
            }
            out.add(b);
        }
        return out;
    }

    private static int indexOfSeparator(String s) {
        int i = s.indexOf(" — ");
        if (i < 0) i = s.indexOf(" – ");
        if (i < 0) i = s.indexOf(" - ");
        return i;
    }

    private static String stripLeadingSymbols(String s) {
        return s.replaceFirst("^[^\\p{L}\\p{N}]+", "").trim();
    }

    static final class FetchResult {
        final String text;
        final boolean online;

        FetchResult(String text, boolean online) {
            this.text = text;
            this.online = online;
        }
    }

    private static String changelogUrl(Context context) {
        String url = Utils.getChangelogURL(context);
        int scheme = url.indexOf("://");
        if (scheme > 0) {
            String head = url.substring(0, scheme + 3);
            String tail = url.substring(scheme + 3).replaceAll("/{2,}", "/");
            url = head + tail;
        }
        return url;
    }

    @Nullable
    private static String toApiUrl(String rawUrl) {
        Matcher m = RAW_GITHUB.matcher(rawUrl);
        if (!m.matches()) return null;
        return "https://api.github.com/repos/" + m.group(1) + "/" + m.group(2)
                + "/contents/" + m.group(4) + "?ref=" + m.group(3);
    }

    private static File cacheFile(Context context) {
        String url = changelogUrl(context);
        return new File(context.getCacheDir(),
                "changelog_" + Integer.toHexString(url.hashCode()) + ".txt");
    }

    @Nullable
    static String readCache(Context context) {
        File f = cacheFile(context);
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            String s = readAll(in);
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeCache(Context context, String text) {
        File f = cacheFile(context);
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "Unable to cache changelog", e);
            return;
        }
        tmp.renameTo(f);
    }

    static FetchResult fetchSync(Context context) throws IOException {
        String rawUrl = changelogUrl(context);
        String apiUrl = toApiUrl(rawUrl);
        String cached = readCache(context);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String etagKey = PREF_ETAG + Integer.toHexString(rawUrl.hashCode());
        if (apiUrl != null) {
            HttpURLConnection c = null;
            try {
                c = open(apiUrl);
                c.setRequestProperty("Accept", "application/vnd.github.raw");
                String etag = prefs.getString(etagKey, null);
                if (cached != null && etag != null) {
                    c.setRequestProperty("If-None-Match", etag);
                }
                int code = c.getResponseCode();
                if (code == HttpURLConnection.HTTP_NOT_MODIFIED && cached != null) {
                    return new FetchResult(cached, true);
                } else if (code == HttpURLConnection.HTTP_OK) {
                    String body = readAll(c.getInputStream());
                    if (!body.isEmpty()) {
                        writeCache(context, body);
                        prefs.edit().putString(etagKey, c.getHeaderField("ETag")).apply();
                        return new FetchResult(body, true);
                    }
                }
            } catch (IOException e) {
                Log.d(TAG, "API fetch failed, falling back to raw URL", e);
            } finally {
                if (c != null) c.disconnect();
            }
        }

        HttpURLConnection c = null;
        try {
            String busted = rawUrl + (rawUrl.contains("?") ? "&" : "?")
                    + "t=" + System.currentTimeMillis();
            c = open(busted);
            c.setUseCaches(false);
            if (c.getResponseCode() == HttpURLConnection.HTTP_OK) {
                String body = readAll(c.getInputStream());
                if (!body.isEmpty()) {
                    writeCache(context, body);
                    return new FetchResult(body, true);
                }
            }
        } catch (IOException e) {
            Log.d(TAG, "Raw changelog fetch failed", e);
        } finally {
            if (c != null) c.disconnect();
        }
        return new FetchResult(cached, false);
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(CONNECT_TIMEOUT_MS);
        c.setRequestProperty("User-Agent", "MistOS-Updater");
        c.setRequestProperty("Cache-Control", "no-cache");
        return c;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toString(StandardCharsets.UTF_8.name());
    }
}
