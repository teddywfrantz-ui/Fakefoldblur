package com.example.foldblur;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_OUTER = 101;
    private static final int REQ_INNER = 102;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (prefs, key) -> {
                if ("diagnostics".equals(key) || "service_running".equals(key) || "hinge_angle".equals(key)) {
                    refreshStatus();
                }
            };

    private TextView status;
    private TextView previewAngle;
    private FrameLayout previewFrame;
    private FoldShaderView previewView;
    private SeekBar previewSeek;
    private Button previewRoleButton;

    private SeekBar blurSeek;
    private SeekBar featherSeek;
    private SeekBar refractionSeek;
    private SeekBar glassSeek;
    private SeekBar smoothingSeek;
    private SeekBar innerWidthSeek;
    private CheckBox scrubBarsCheck;

    private Bitmap outerBitmap;
    private Bitmap innerBitmap;
    private VisualConfig config;
    private int previewRole = FoldShaderView.ROLE_INNER;
    private float previewProgress = 0.30f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        config = VisualConfig.load(this);
        buildUi();
        loadPreviewBitmaps();
        applyConfigToControls();
        refreshStatus();

        getSharedPreferences(VisualConfig.PREFS, MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefListener);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        getSharedPreferences(VisualConfig.PREFS, MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefListener);
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(36), dp(20), dp(42));
        root.setBackgroundColor(Color.rgb(247, 247, 249));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Fold Blur — Final Test", 29f, Color.BLACK);
        root.addView(title, mw());

        TextView body = text(
                "This build keeps One UI Home. It pre-arms Presentation windows on secondary displays, drives the same AGSL renderer from the live hinge angle, and keeps the inner optical effect on the LEFT half only. The right half of the inner screenshot stays crisp.",
                15.5f,
                Color.DKGRAY);
        LinearLayout.LayoutParams bodyLp = mw();
        bodyLp.topMargin = dp(10);
        root.addView(body, bodyLp);

        TextView warning = text(
                "For this test, the service aggressively keeps display render targets ready while it is running. Stop the service when you are finished testing.",
                13.5f,
                Color.rgb(95, 65, 0));
        LinearLayout.LayoutParams warningLp = mw();
        warningLp.topMargin = dp(10);
        root.addView(warning, warningLp);

        status = text("", 13.5f, Color.rgb(55, 55, 60));
        status.setBackgroundColor(Color.WHITE);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams statusLp = mw();
        statusLp.topMargin = dp(16);
        root.addView(status, statusLp);

        Button outer = button("SELECT OUTER SCREENSHOT");
        outer.setOnClickListener(v -> pickImage(REQ_OUTER));
        LinearLayout.LayoutParams outerLp = mw();
        outerLp.topMargin = dp(14);
        root.addView(outer, outerLp);

        Button inner = button("SELECT INNER SCREENSHOT");
        inner.setOnClickListener(v -> pickImage(REQ_INNER));
        root.addView(inner, mw());

        Button overlay = button("ALLOW DRAW OVER OTHER APPS");
        overlay.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        root.addView(overlay, mw());

        Button start = button("START + ARM BOTH DISPLAYS");
        start.setOnClickListener(v -> startTransitionService());
        root.addView(start, mw());

        Button stop = button("STOP SERVICE");
        stop.setOnClickListener(v -> {
            stopService(new Intent(this, HingeOverlayService.class));
            Toast.makeText(this, "Fold Blur service stopped.", Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::refreshStatus, 250L);
        });
        root.addView(stop, mw());

        addSectionHeader(root, "ON-DEVICE PREVIEW");

        TextView previewHelp = text(
                "Use this before folding. It is the exact same shader used by the overlay and Presentation windows, so visual tuning no longer requires another GitHub build.",
                13.5f,
                Color.DKGRAY);
        root.addView(previewHelp, mw());

        previewRoleButton = button("PREVIEW: INNER / LEFT-HALF EFFECT");
        previewRoleButton.setOnClickListener(v -> {
            previewRole = previewRole == FoldShaderView.ROLE_INNER
                    ? FoldShaderView.ROLE_OUTER
                    : FoldShaderView.ROLE_INNER;
            updatePreviewRoleLabel();
            if (previewView != null) previewView.setRoleOverride(previewRole);
        });
        LinearLayout.LayoutParams roleLp = mw();
        roleLp.topMargin = dp(8);
        root.addView(previewRoleButton, roleLp);

        previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(330));
        frameLp.topMargin = dp(8);
        root.addView(previewFrame, frameLp);

        previewAngle = text("Preview hinge: 54°", 13.5f, Color.DKGRAY);
        LinearLayout.LayoutParams angleLp = mw();
        angleLp.topMargin = dp(8);
        root.addView(previewAngle, angleLp);

        previewSeek = new SeekBar(this);
        previewSeek.setMax(1800);
        previewSeek.setProgress(540);
        previewSeek.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                previewProgress = progress / 1800f;
                previewAngle.setText("Preview hinge: " + Math.round(previewProgress * 180f) + "°");
                if (previewView != null) previewView.setState(previewProgress, 1f);
            }
        });
        root.addView(previewSeek, mw());

        addSectionHeader(root, "VISUAL TUNING — LIVE, NO REBUILD");

        blurSeek = addTuningSlider(root, "Blur strength", 60, 150, percent(config.blurStrength));
        featherSeek = addTuningSlider(root, "Edge softness / feather", 65, 150, percent(config.feather));
        refractionSeek = addTuningSlider(root, "Glass refraction", 0, 160, percent(config.refraction));
        glassSeek = addTuningSlider(root, "Other-screen glass reflection", 0, 12, Math.round(config.glassMix * 100f));
        smoothingSeek = addTuningSlider(root, "Motion smoothing (ms)", 0, 50, Math.round(config.smoothingMs));
        innerWidthSeek = addTuningSlider(root, "Inner LEFT-side affected width", 45, 60, Math.round(config.innerWidth * 100f));

        SeekBar.OnSeekBarChangeListener tuningListener = new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) readControlsAndApply();
            }
        };
        blurSeek.setOnSeekBarChangeListener(tuningListener);
        featherSeek.setOnSeekBarChangeListener(tuningListener);
        refractionSeek.setOnSeekBarChangeListener(tuningListener);
        glassSeek.setOnSeekBarChangeListener(tuningListener);
        smoothingSeek.setOnSeekBarChangeListener(tuningListener);
        innerWidthSeek.setOnSeekBarChangeListener(tuningListener);

        scrubBarsCheck = new CheckBox(this);
        scrubBarsCheck.setText("Suppress duplicate status/navigation bars from screenshots");
        scrubBarsCheck.setTextSize(13.5f);
        scrubBarsCheck.setChecked(config.scrubBars);
        scrubBarsCheck.setOnCheckedChangeListener((buttonView, isChecked) -> readControlsAndApply());
        root.addView(scrubBarsCheck, mw());

        Button reset = button("RESET APPLE-LIKE DEFAULTS");
        reset.setOnClickListener(v -> {
            config = VisualConfig.defaults();
            config.save(this);
            applyConfigToControls();
            applyConfigEverywhere();
        });
        root.addView(reset, mw());

        setContentView(scroll);
        updatePreviewRoleLabel();
    }

    private void addSectionHeader(LinearLayout root, String label) {
        TextView h = text(label, 15f, Color.BLACK);
        h.setAllCaps(false);
        h.setPadding(0, dp(18), 0, dp(7));
        root.addView(h, mw());
    }

    private SeekBar addTuningSlider(LinearLayout root, String label, int min, int max, int value) {
        TextView l = text(label, 13.5f, Color.DKGRAY);
        root.addView(l, mw());
        SeekBar s = new SeekBar(this);
        s.setMin(min);
        s.setMax(max);
        s.setProgress(Math.max(min, Math.min(max, value)));
        root.addView(s, mw());
        return s;
    }

    private void applyConfigToControls() {
        if (blurSeek == null) return;
        blurSeek.setProgress(percent(config.blurStrength));
        featherSeek.setProgress(percent(config.feather));
        refractionSeek.setProgress(percent(config.refraction));
        glassSeek.setProgress(Math.round(config.glassMix * 100f));
        smoothingSeek.setProgress(Math.round(config.smoothingMs));
        innerWidthSeek.setProgress(Math.round(config.innerWidth * 100f));
        scrubBarsCheck.setChecked(config.scrubBars);
    }

    private int percent(float value) {
        return Math.round(value * 100f);
    }

    private void readControlsAndApply() {
        config.blurStrength = blurSeek.getProgress() / 100f;
        config.feather = featherSeek.getProgress() / 100f;
        config.refraction = refractionSeek.getProgress() / 100f;
        config.glassMix = glassSeek.getProgress() / 100f;
        config.smoothingMs = smoothingSeek.getProgress();
        config.innerWidth = innerWidthSeek.getProgress() / 100f;
        config.scrubBars = scrubBarsCheck.isChecked();
        config.save(this);
        applyConfigEverywhere();
    }

    private void applyConfigEverywhere() {
        if (previewView != null) previewView.setConfig(config);
        if (getSharedPreferences(VisualConfig.PREFS, MODE_PRIVATE)
                .getBoolean("service_running", false)) {
            Intent update = new Intent(this, HingeOverlayService.class);
            update.setAction(HingeOverlayService.ACTION_UPDATE_CONFIG);
            try {
                startService(update);
            } catch (Throwable ignored) {
            }
        }
    }

    private void updatePreviewRoleLabel() {
        if (previewRoleButton == null) return;
        previewRoleButton.setText(previewRole == FoldShaderView.ROLE_INNER
                ? "PREVIEW: INNER / LEFT-HALF EFFECT"
                : "PREVIEW: OUTER / FULL-PANEL EFFECT");
    }

    private void pickImage(int request) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        startActivityForResult(i, request);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }

        String key = requestCode == REQ_OUTER ? "outer_uri" : "inner_uri";
        getSharedPreferences(VisualConfig.PREFS, MODE_PRIVATE)
                .edit()
                .putString(key, uri.toString())
                .apply();

        loadPreviewBitmaps();
        if (getSharedPreferences(VisualConfig.PREFS, MODE_PRIVATE)
                .getBoolean("service_running", false)) {
            Intent reload = new Intent(this, HingeOverlayService.class);
            reload.setAction(HingeOverlayService.ACTION_RELOAD);
            try {
                startService(reload);
            } catch (Throwable ignored) {
            }
        }
        refreshStatus();
    }

    private void loadPreviewBitmaps() {
        SharedPreferences p = getSharedPreferences(VisualConfig.PREFS, MODE_PRIVATE);
        try {
            String outer = p.getString("outer_uri", null);
            String inner = p.getString("inner_uri", null);
            if (outer != null) outerBitmap = loadBitmap(Uri.parse(outer));
            if (inner != null) innerBitmap = loadBitmap(Uri.parse(inner));
        } catch (Throwable ignored) {
        }
        rebuildPreview();
    }

    private Bitmap loadBitmap(Uri uri) throws Exception {
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
        });
    }

    private void rebuildPreview() {
        if (previewFrame == null) return;
        previewFrame.removeAllViews();
        previewView = null;

        if (outerBitmap == null || innerBitmap == null) {
            TextView empty = text("Select both screenshots to preview the shader.", 14f, Color.WHITE);
            empty.setGravity(Gravity.CENTER);
            previewFrame.addView(empty, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            return;
        }

        previewView = new FoldShaderView(this, outerBitmap, innerBitmap);
        previewView.setRoleOverride(previewRole);
        previewView.setConfig(config);
        previewView.setState(previewProgress, 1f);
        previewFrame.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void startTransitionService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Draw over other apps first.", Toast.LENGTH_LONG).show();
            return;
        }

        SharedPreferences p = getSharedPreferences(VisualConfig.PREFS, MODE_PRIVATE);
        if (p.getString("outer_uri", null) == null || p.getString("inner_uri", null) == null) {
            Toast.makeText(this, "Select both screenshots first.", Toast.LENGTH_LONG).show();
            return;
        }

        Intent service = new Intent(this, HingeOverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
        Toast.makeText(this,
                "Final test armed. Return to One UI Home, then open/close the Fold slowly.",
                Toast.LENGTH_LONG).show();
        handler.postDelayed(this::refreshStatus, 350L);
    }

    private void refreshStatus() {
        if (status == null) return;
        SharedPreferences p = getSharedPreferences(VisualConfig.PREFS, MODE_PRIVATE);
        boolean outerSet = p.getString("outer_uri", null) != null;
        boolean innerSet = p.getString("inner_uri", null) != null;
        boolean running = p.getBoolean("service_running", false);
        boolean hinge = p.getBoolean("hinge_available", false);
        String diagnostics = p.getString("diagnostics", "No display diagnostics yet.");

        status.setText(
                "Outer screenshot: " + (outerSet ? "SET" : "NOT SET")
                        + "\nInner screenshot: " + (innerSet ? "SET" : "NOT SET")
                        + "\nOverlay permission: " + (Settings.canDrawOverlays(this) ? "ALLOWED" : "NOT ALLOWED")
                        + "\nService: " + (running ? "RUNNING / ARMED" : "STOPPED")
                        + "\nHinge sensor: " + (hinge ? "DETECTED" : (running ? "NOT DETECTED" : "checked when service starts"))
                        + "\n\n" + diagnostics);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        return b;
    }

    private TextView text(String content, float size, int color) {
        TextView t = new TextView(this);
        t.setText(content);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams mw() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }
}
