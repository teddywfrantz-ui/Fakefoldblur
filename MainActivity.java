package com.example.foldblur;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorDrawable;
import android.graphics.ImageDecoder;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Full-screen proof-of-concept modeled after the Reddit developer's stated architecture:
 * a foreground app owns the visible pixels, an AGSL shader is driven directly by
 * TYPE_HINGE_ANGLE, and Presentation renders the same hinge state on any second internal
 * display Android exposes as presentation-capable.
 *
 * This deliberately does NOT use SYSTEM_ALERT_WINDOW and does NOT render on top of One UI.
 * The previous overlay architecture caused ghosting, duplicate screenshots, display-role
 * mistakes and Presentation eligibility problems because our app was not the top visible task.
 */
public class MainActivity extends Activity implements
        SensorEventListener,
        DisplayManager.DisplayListener {

    private static final int REQ_OUTER = 101;
    private static final int REQ_INNER = 102;
    private static final String PREFS = VisualConfig.PREFS;

    private final Map<Integer, DemoPresentation> presentations = new HashMap<>();

    private SensorManager sensorManager;
    private Sensor hingeSensor;
    private DisplayManager displayManager;
    private PowerManager.WakeLock wakeLock;

    private Bitmap outerBitmap;
    private Bitmap innerBitmap;
    private FoldShaderView mainShader;
    private TextView statusView;

    private boolean demoMode;
    private float lastAngle = 180f;
    private float direction = 1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        sensorManager = getSystemService(SensorManager.class);
        displayManager = getSystemService(DisplayManager.class);
        hingeSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);

        if (displayManager != null) {
            displayManager.registerDisplayListener(this, null);
        }
        if (sensorManager != null && hingeSensor != null) {
            // Register once for the lifetime of the Activity. We do not tear this down during
            // cover/main display handoff, which avoids the "starts then freezes" failure.
            sensorManager.registerListener(this, hingeSensor, SensorManager.SENSOR_DELAY_FASTEST);
        }

        loadBitmaps();
        demoMode = savedInstanceState != null && savedInstanceState.getBoolean("demo_mode", false);

        configureWindowForWake();

        if (demoMode && outerBitmap != null && innerBitmap != null) {
            enterDemo(false);
        } else {
            showSetup();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("demo_mode", demoMode);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (displayManager != null) {
            try { displayManager.unregisterDisplayListener(this); } catch (Throwable ignored) {}
        }
        dismissAllPresentations();
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (demoMode) {
            configureWindowForWake();
            getWindow().getDecorView().post(() -> {
                forceMainRoleFromCurrentGeometry();
                syncPresentations();
                applyAngle(lastAngle);
            });
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && demoMode) {
            applyImmersive();
            getWindow().getDecorView().post(() -> {
                forceMainRoleFromCurrentGeometry();
                syncPresentations();
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (demoMode) {
            exitDemo();
        } else {
            super.onBackPressed();
        }
    }

    private void showSetup() {
        demoMode = false;
        dismissAllPresentations();
        releaseWakeLock();
        showSystemBars();

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(42), dp(20), dp(42));
        root.setBackgroundColor(Color.rgb(247, 247, 249));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Fold Blur POC", 30f, Color.BLACK);
        root.addView(title, mw());

        TextView body = text(
                "This test is a full-screen fake home-screen demo, not an overlay. That is intentional: the app itself must be the top visible task so Android can allow Presentation on another internal display. The screenshots are the screens; the hinge sensor drives the AGSL effect directly.",
                15.5f,
                Color.DKGRAY);
        LinearLayout.LayoutParams bodyLp = mw();
        bodyLp.topMargin = dp(10);
        root.addView(body, bodyLp);

        statusView = text("", 13.5f, Color.rgb(55, 55, 60));
        statusView.setBackgroundColor(Color.WHITE);
        statusView.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams statusLp = mw();
        statusLp.topMargin = dp(16);
        root.addView(statusView, statusLp);

        Button outer = button("SELECT OUTER SCREENSHOT");
        outer.setOnClickListener(v -> pickImage(REQ_OUTER));
        LinearLayout.LayoutParams outerLp = mw();
        outerLp.topMargin = dp(14);
        root.addView(outer, outerLp);

        Button inner = button("SELECT INNER SCREENSHOT");
        inner.setOnClickListener(v -> pickImage(REQ_INNER));
        root.addView(inner, mw());

        Button start = button("START FULL-SCREEN HINGE DEMO");
        start.setOnClickListener(v -> {
            loadBitmaps();
            if (outerBitmap == null || innerBitmap == null) {
                Toast.makeText(this, "Select both screenshots first.", Toast.LENGTH_LONG).show();
                return;
            }
            enterDemo(true);
        });
        LinearLayout.LayoutParams startLp = mw();
        startLp.topMargin = dp(12);
        root.addView(start, startLp);

        TextView exitHint = text(
                "While the demo is running, use Back to return here. For closing tests, also enable this app under Samsung Settings > Display > Continue apps on cover screen.",
                13.5f,
                Color.DKGRAY);
        LinearLayout.LayoutParams hintLp = mw();
        hintLp.topMargin = dp(12);
        root.addView(exitHint, hintLp);

        setContentView(scroll);
        refreshStatus();
    }

    private void enterDemo(boolean announce) {
        demoMode = true;
        acquireWakeLock();
        configureWindowForWake();
        applyImmersive();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setKeepScreenOn(true);

        mainShader = new FoldShaderView(this, outerBitmap, innerBitmap);
        root.addView(mainShader, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Invisible touch target in the upper-left. Long-press is an emergency exit if the
        // system Back gesture becomes awkward while the Fold is partly open.
        View exitTarget = new View(this);
        exitTarget.setBackgroundColor(Color.TRANSPARENT);
        exitTarget.setOnLongClickListener(v -> {
            exitDemo();
            return true;
        });
        FrameLayout.LayoutParams exitLp = new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.TOP | Gravity.START);
        root.addView(exitTarget, exitLp);

        setContentView(root);

        root.post(() -> {
            forceMainRoleFromCurrentGeometry();
            applyAngle(lastAngle);
            syncPresentations();
        });

        if (announce) {
            Toast.makeText(this, "Demo running. Fold/unfold now. Back exits.", Toast.LENGTH_LONG).show();
        }
    }

    private void exitDemo() {
        demoMode = false;
        dismissAllPresentations();
        mainShader = null;
        releaseWakeLock();
        showSetup();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_HINGE_ANGLE || event.values.length == 0) return;
        float angle = clamp(event.values[0], 0f, 180f);
        float delta = angle - lastAngle;
        if (Math.abs(delta) > 0.03f) direction = delta >= 0f ? 1f : -1f;
        lastAngle = angle;

        if (demoMode) {
            // Direct sensor -> shader update. Android coalesces invalidates to display vsync,
            // so there is no independent animation loop to stall during the display handoff.
            applyAngle(angle);
            syncPresentations();
        } else {
            refreshStatus();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void applyAngle(float angle) {
        float p = angle / 180f;
        if (mainShader != null) mainShader.setState(p, direction);
        for (DemoPresentation presentation : new ArrayList<>(presentations.values())) {
            presentation.setState(p, direction);
        }
    }

    private void forceMainRoleFromCurrentGeometry() {
        if (mainShader == null) return;
        View decor = getWindow().getDecorView();
        int w = decor.getWidth();
        int h = decor.getHeight();
        if (w <= 0 || h <= 0) return;
        mainShader.setRoleOverride(isInnerGeometry(w, h) ? FoldShaderView.ROLE_INNER : FoldShaderView.ROLE_OUTER);
    }

    private void syncPresentations() {
        if (!demoMode || displayManager == null || outerBitmap == null || innerBitmap == null) return;

        int currentId = -1;
        Display current = getDisplay();
        if (current != null) currentId = current.getDisplayId();

        Map<Integer, Display> candidates = new LinkedHashMap<>();

        // This is the important path on modern Android: only ask for displays the framework
        // explicitly marks as presentation-capable. Android 16 can expose built-in internal
        // displays here, provided the app itself is the top visible task on a different display.
        try {
            for (Display d : displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
                if (d != null) candidates.put(d.getDisplayId(), d);
            }
        } catch (Throwable ignored) {
        }

        // Runtime fallback: some OEM builds expose the same capability flag through getDisplays().
        try {
            for (Display d : displayManager.getDisplays()) {
                if (d != null && (d.getFlags() & Display.FLAG_PRESENTATION) != 0) {
                    candidates.put(d.getDisplayId(), d);
                }
            }
        } catch (Throwable ignored) {
        }

        // Never keep a Presentation on the display that now owns the Activity. That exact mistake
        // produced stacked copies / ghosting in the previous build after the fold handoff.
        for (Map.Entry<Integer, DemoPresentation> e : new ArrayList<>(presentations.entrySet())) {
            DemoPresentation p = e.getValue();
            Display d = p == null ? null : p.getDisplay();
            if (d == null || !d.isValid() || e.getKey() == currentId) {
                presentations.remove(e.getKey());
                safeDismiss(p);
            }
        }

        for (Display display : candidates.values()) {
            if (display == null || !display.isValid()) continue;
            int id = display.getDisplayId();
            if (id == currentId || presentations.containsKey(id)) continue;
            if (!looksLikePhonePanel(display)) continue;

            try {
                int role = roleForDisplay(display);
                DemoPresentation p = new DemoPresentation(this, display, outerBitmap, innerBitmap, role);
                p.setOnDismissListener(dialog -> {
                    presentations.remove(id);
                    if (demoMode) getWindow().getDecorView().postDelayed(this::syncPresentations, 30L);
                });
                p.show();
                presentations.put(id, p);
                p.setState(lastAngle / 180f, direction);
            } catch (Throwable ignored) {
                // Android may temporarily reject a built-in target during a device-state change.
                // Display callbacks and the next sensor sample will retry immediately.
            }
        }
    }

    private int roleForDisplay(Display display) {
        try {
            Display.Mode mode = display.getMode();
            if (mode != null) {
                return isInnerGeometry(mode.getPhysicalWidth(), mode.getPhysicalHeight())
                        ? FoldShaderView.ROLE_INNER
                        : FoldShaderView.ROLE_OUTER;
            }
        } catch (Throwable ignored) {
        }
        return FoldShaderView.ROLE_OUTER;
    }

    private boolean looksLikePhonePanel(Display d) {
        try {
            Display.Mode m = d.getMode();
            if (m == null) return true;
            int min = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
            int max = Math.max(m.getPhysicalWidth(), m.getPhysicalHeight());
            float ratio = max == 0 ? 0f : (float) min / (float) max;
            return min >= 600 && max >= 900 && ratio >= 0.36f && ratio <= 0.95f;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private boolean isInnerGeometry(int w, int h) {
        int min = Math.min(w, h);
        int max = Math.max(w, h);
        return max > 0 && ((float) min / (float) max) >= 0.62f;
    }

    @Override
    public void onDisplayAdded(int displayId) {
        if (demoMode) getWindow().getDecorView().post(this::syncPresentations);
        else refreshStatus();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        DemoPresentation p = presentations.remove(displayId);
        safeDismiss(p);
        if (demoMode) getWindow().getDecorView().post(this::syncPresentations);
        else refreshStatus();
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (demoMode) {
            getWindow().getDecorView().post(() -> {
                forceMainRoleFromCurrentGeometry();
                syncPresentations();
                applyAngle(lastAngle);
            });
        } else {
            refreshStatus();
        }
    }

    private void configureWindowForWake() {
        Window w = getWindow();
        if (w == null) return;
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        try {
            setTurnScreenOn(true);
            setShowWhenLocked(true);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm == null) return;
            wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "FoldBlur::DemoScreens");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(10 * 60 * 1000L);
        } catch (Throwable ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {
        }
        wakeLock = null;
    }

    private void applyImmersive() {
        Window window = getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        try {
            WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } catch (Throwable ignored) {
        }
    }

    private void showSystemBars() {
        Window window = getWindow();
        if (window == null) return;
        View decor = window.getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        try {
            WindowInsetsController c = decor.getWindowInsetsController();
            if (c != null) c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        } catch (Throwable ignored) {
        }
    }

    private void dismissAllPresentations() {
        for (DemoPresentation p : new ArrayList<>(presentations.values())) safeDismiss(p);
        presentations.clear();
    }

    private static void safeDismiss(Presentation p) {
        if (p == null) return;
        try { p.dismiss(); } catch (Throwable ignored) {}
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
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, uri.toString()).apply();
        loadBitmaps();
        refreshStatus();
    }

    private void loadBitmaps() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            String outer = p.getString("outer_uri", null);
            String inner = p.getString("inner_uri", null);
            if (outer != null) outerBitmap = loadBitmap(Uri.parse(outer));
            if (inner != null) innerBitmap = loadBitmap(Uri.parse(inner));
        } catch (Throwable ignored) {
        }
    }

    private Bitmap loadBitmap(Uri uri) throws Exception {
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(source, (decoder, info, src) ->
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
    }

    private void refreshStatus() {
        if (statusView == null) return;
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean outer = p.getString("outer_uri", null) != null;
        boolean inner = p.getString("inner_uri", null) != null;

        int all = 0;
        int presentation = 0;
        StringBuilder details = new StringBuilder();
        if (displayManager != null) {
            try {
                Display[] allDisplays = displayManager.getDisplays();
                all = allDisplays.length;
                for (Display d : allDisplays) {
                    Display.Mode m = d.getMode();
                    details.append("\n#").append(d.getDisplayId())
                            .append(" ").append(d.getName())
                            .append(" state=").append(d.getState());
                    if (m != null) {
                        details.append(" ").append(m.getPhysicalWidth()).append("x").append(m.getPhysicalHeight());
                    }
                    if ((d.getFlags() & Display.FLAG_PRESENTATION) != 0) details.append(" PRESENTATION");
                }
            } catch (Throwable ignored) {
            }
            try {
                presentation = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).length;
            } catch (Throwable ignored) {
            }
        }

        statusView.setText(
                "Outer screenshot: " + (outer ? "SET" : "NOT SET")
                        + "\nInner screenshot: " + (inner ? "SET" : "NOT SET")
                        + "\nContinuous hinge sensor: " + (hingeSensor != null ? "DETECTED" : "NOT DETECTED")
                        + "\nHinge angle: " + Math.round(lastAngle * 10f) / 10f + "°"
                        + "\nDisplays visible to app: " + all
                        + "\nPresentation-capable displays: " + presentation
                        + details);
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

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static final class DemoPresentation extends Presentation {
        private final Bitmap outer;
        private final Bitmap inner;
        private final int role;
        private FoldShaderView shaderView;

        DemoPresentation(Context context, Display display, Bitmap outer, Bitmap inner, int role) {
            super(context, display);
            this.outer = outer;
            this.inner = inner;
            this.role = role;
            setCancelable(false);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            Window w = getWindow();
            if (w != null) {
                w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
                w.setStatusBarColor(Color.TRANSPARENT);
                w.setNavigationBarColor(Color.TRANSPARENT);
                View decor = w.getDecorView();
                decor.setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }

            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(Color.BLACK);
            root.setKeepScreenOn(true);

            shaderView = new FoldShaderView(getContext(), outer, inner);
            shaderView.setRoleOverride(role);
            root.addView(shaderView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            setContentView(root);
        }

        void setState(float progress, float direction) {
            if (shaderView != null) shaderView.setState(progress, direction);
        }
    }
}
