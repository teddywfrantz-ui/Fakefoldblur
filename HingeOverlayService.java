package com.example.foldblur;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Presentation;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorDrawable;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Final non-root proof-of-concept service.
 *
 * The service deliberately keeps Presentation windows armed instead of creating them only
 * after the fold begins. The goal is to have the secondary render target already alive when
 * Samsung exposes/powers that panel. Primary and Presentation renderers all use the same
 * hinge-driven AGSL view and are updated from a single vsync loop.
 */
public class HingeOverlayService extends Service implements
        SensorEventListener,
        DisplayManager.DisplayListener,
        Choreographer.FrameCallback {

    static final String ACTION_RELOAD = "com.example.foldblur.RELOAD";
    static final String ACTION_UPDATE_CONFIG = "com.example.foldblur.UPDATE_CONFIG";

    private static final String CHANNEL = "foldblur_final_service";
    private static final int NOTIFICATION_ID = 7;
    private static final float CLOSED_DEG = 0.8f;
    private static final float OPEN_DEG = 179.2f;
    private static final String BUILT_IN_DISPLAYS = "android.hardware.display.category.BUILT_IN_DISPLAYS";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Integer, DuoPresentation> presentations = new HashMap<>();

    private SensorManager sensorManager;
    private Sensor hingeSensor;
    private DisplayManager displayManager;
    private WindowManager windowManager;
    private Choreographer choreographer;

    private PowerManager.WakeLock cpuWakeLock;
    private PowerManager.WakeLock transitionWakeLock;

    private Bitmap outerBitmap;
    private Bitmap innerBitmap;
    private VisualConfig config = VisualConfig.defaults();

    private FrameLayout overlayRoot;
    private FoldShaderView overlayView;
    private boolean overlayAdded;

    private float rawAngle = 180f;
    private float targetProgress = 1f;
    private float renderedProgress = 1f;
    private float lastRenderedProgress = 1f;
    private float direction = 1f;
    private long lastSensorMs;
    private long lastFrameNs;
    private boolean frameLoopStarted;
    private boolean transitioning;
    private boolean stopping;

    private final Runnable displayArmLoop = new Runnable() {
        @Override
        public void run() {
            if (stopping) return;
            syncPresentations();
            writeDiagnostics();
            handler.postDelayed(this, transitioning ? 70L : 260L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification());

        sensorManager = getSystemService(SensorManager.class);
        displayManager = getSystemService(DisplayManager.class);
        windowManager = getSystemService(WindowManager.class);
        choreographer = Choreographer.getInstance();

        if (displayManager != null) {
            displayManager.registerDisplayListener(this, handler);
        }

        acquireCpuWakeLock();
        config = VisualConfig.load(this);
        loadScreenshots();
        buildPrimaryOverlay();

        hingeSensor = sensorManager == null
                ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);

        if (hingeSensor != null) {
            sensorManager.registerListener(this, hingeSensor, SensorManager.SENSOR_DELAY_FASTEST, handler);
        }

        getSharedPreferences(VisualConfig.PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean("service_running", true)
                .putBoolean("hinge_available", hingeSensor != null)
                .apply();

        // Critical difference from the earlier builds: arm secondary display renderers now,
        // even at fully-open/fully-closed endpoints, and keep them alive while the service runs.
        syncPresentations();
        handler.post(displayArmLoop);
        startFrameLoop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_RELOAD.equals(action)) {
                loadScreenshots();
                updateRendererBitmaps();
                syncPresentations();
            } else if (ACTION_UPDATE_CONFIG.equals(action)) {
                config = VisualConfig.load(this);
                updateRendererConfig();
            }
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Fold Blur final test")
                .setContentText("Hinge shader active • secondary displays armed")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL,
                "Fold Blur final test",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the hinge-driven fold transition ready in the background.");
        nm.createNotificationChannel(channel);
    }

    private void acquireCpuWakeLock() {
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm == null) return;

            cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FoldBlur::CPU");
            cpuWakeLock.setReferenceCounted(false);
            cpuWakeLock.acquire();

            // Short-lived bright wake lock used only during actual hinge movement.
            // This is a best-effort request; Samsung still owns physical panel policy.
            //noinspection deprecation
            transitionWakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "FoldBlur::TransitionScreen");
            transitionWakeLock.setReferenceCounted(false);
        } catch (Throwable ignored) {
        }
    }

    private void wakeForTransition() {
        try {
            if (transitionWakeLock != null && !transitionWakeLock.isHeld()) {
                transitionWakeLock.acquire(8000L);
            }
        } catch (Throwable ignored) {
        }
    }

    private void releaseTransitionWakeSoon() {
        handler.postDelayed(() -> {
            if (!transitioning) {
                try {
                    if (transitionWakeLock != null && transitionWakeLock.isHeld()) {
                        transitionWakeLock.release();
                    }
                } catch (Throwable ignored) {
                }
            }
        }, 450L);
    }

    private void loadScreenshots() {
        SharedPreferences p = getSharedPreferences(VisualConfig.PREFS, MODE_PRIVATE);
        Bitmap newOuter = null;
        Bitmap newInner = null;
        try {
            String outer = p.getString("outer_uri", null);
            String inner = p.getString("inner_uri", null);
            if (outer != null) newOuter = loadBitmap(Uri.parse(outer));
            if (inner != null) newInner = loadBitmap(Uri.parse(inner));
        } catch (Throwable ignored) {
        }
        if (newOuter != null) outerBitmap = newOuter;
        if (newInner != null) innerBitmap = newInner;
    }

    private Bitmap loadBitmap(Uri uri) throws Exception {
        ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
        });
    }

    private void buildPrimaryOverlay() {
        if (windowManager == null || outerBitmap == null || innerBitmap == null) return;

        overlayRoot = new FrameLayout(this);
        overlayRoot.setBackgroundColor(Color.TRANSPARENT);
        overlayRoot.setVisibility(View.INVISIBLE);
        overlayRoot.setAlpha(0f);

        overlayView = new FoldShaderView(this, outerBitmap, innerBitmap);
        overlayView.setConfig(config);
        overlayRoot.addView(overlayView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(overlayRoot, lp);
            overlayAdded = true;
        } catch (Throwable ignored) {
            overlayAdded = false;
        }
    }

    private void updateRendererBitmaps() {
        if (outerBitmap == null || innerBitmap == null) return;
        if (overlayView != null) overlayView.setBitmaps(outerBitmap, innerBitmap);
        for (DuoPresentation p : new ArrayList<>(presentations.values())) {
            p.setBitmaps(outerBitmap, innerBitmap);
        }
    }

    private void updateRendererConfig() {
        if (overlayView != null) overlayView.setConfig(config);
        for (DuoPresentation p : new ArrayList<>(presentations.values())) {
            p.setConfig(config);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_HINGE_ANGLE || event.values.length == 0) return;

        long now = SystemClock.uptimeMillis();
        float angle = clamp(event.values[0], 0f, 180f);
        float delta = angle - rawAngle;

        if (Math.abs(delta) > 0.08f) {
            direction = delta >= 0f ? 1f : -1f;
        }

        rawAngle = angle;
        targetProgress = angle / 180f;
        lastSensorMs = now;

        boolean wasTransitioning = transitioning;
        transitioning = angle > CLOSED_DEG && angle < OPEN_DEG;

        if (transitioning && !wasTransitioning) {
            wakeForTransition();
            syncPresentations();
        } else if (!transitioning && wasTransitioning) {
            releaseTransitionWakeSoon();
        }

        getSharedPreferences(VisualConfig.PREFS, MODE_PRIVATE)
                .edit()
                .putFloat("hinge_angle", angle)
                .apply();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void startFrameLoop() {
        if (frameLoopStarted || choreographer == null) return;
        frameLoopStarted = true;
        choreographer.postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (stopping) return;

        float dtMs;
        if (lastFrameNs == 0L) {
            dtMs = 16.67f;
        } else {
            dtMs = clamp((frameTimeNanos - lastFrameNs) / 1_000_000f, 1f, 50f);
        }
        lastFrameNs = frameTimeNanos;

        float tau = Math.max(1f, config.smoothingMs);
        float alpha = 1f - (float) Math.exp(-dtMs / tau);

        // If the sensor moved significantly, follow aggressively; near rest, use the configured
        // temporal damping to remove the small staircase/jitter that looked cheap in early tests.
        float gap = Math.abs(targetProgress - renderedProgress);
        if (gap > 0.085f) alpha = Math.max(alpha, 0.72f);
        renderedProgress += (targetProgress - renderedProgress) * clamp(alpha, 0.06f, 1f);

        if (!transitioning && Math.abs(renderedProgress - targetProgress) < 0.0005f) {
            renderedProgress = targetProgress;
        }

        if (Math.abs(renderedProgress - lastRenderedProgress) > 0.00002f || transitioning) {
            renderAll(renderedProgress, direction);
            lastRenderedProgress = renderedProgress;
        } else {
            updateEndpointVisibility(renderedProgress);
        }

        choreographer.postFrameCallback(this);
    }

    private void renderAll(float progress, float direction) {
        float alpha = transitionAlpha(progress);

        if (overlayRoot != null) {
            if (alpha > 0.001f) {
                if (overlayRoot.getVisibility() != View.VISIBLE) overlayRoot.setVisibility(View.VISIBLE);
                overlayRoot.setAlpha(alpha);
            } else {
                overlayRoot.setAlpha(0f);
                overlayRoot.setVisibility(View.INVISIBLE);
            }
        }

        if (overlayView != null) {
            overlayView.setConfig(config);
            overlayView.setState(progress, direction);
        }

        for (DuoPresentation p : new ArrayList<>(presentations.values())) {
            p.setState(progress, direction, alpha);
        }
    }

    private void updateEndpointVisibility(float progress) {
        float alpha = transitionAlpha(progress);
        if (overlayRoot != null && alpha <= 0.001f) {
            overlayRoot.setAlpha(0f);
            overlayRoot.setVisibility(View.INVISIBLE);
        }
        for (DuoPresentation p : new ArrayList<>(presentations.values())) {
            p.setAlphaOnly(alpha);
        }
    }

    private float transitionAlpha(float p) {
        // Presentation windows remain SHOWN at endpoints but become transparent. This preserves
        // an already-created secondary render target without covering the real One UI at rest.
        float in = smoothstep(0.0015f, 0.010f, p);
        float out = 1f - smoothstep(0.990f, 0.9985f, p);
        return clamp(Math.min(in, out), 0f, 1f);
    }

    private void syncPresentations() {
        if (displayManager == null || outerBitmap == null || innerBitmap == null) return;

        int currentId = Display.DEFAULT_DISPLAY;
        try {
            Display current = windowManager == null ? null : windowManager.getDefaultDisplay();
            if (current != null) currentId = current.getDisplayId();
        } catch (Throwable ignored) {
        }

        // LinkedHashMap keeps diagnostics stable while merging all public discovery routes.
        Map<Integer, Display> candidates = new LinkedHashMap<>();
        addDisplays(candidates, safeGetAllDisplays());
        addDisplays(candidates, safeGetDisplays(BUILT_IN_DISPLAYS));
        addDisplays(candidates, safeGetDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION));

        for (Display display : candidates.values()) {
            if (display == null || display.getDisplayId() == currentId) continue;
            if (!display.isValid()) continue;
            if (!looksLikePhonePanel(display)) continue;

            int id = display.getDisplayId();
            DuoPresentation existing = presentations.get(id);
            if (existing != null) {
                Display pd = existing.getDisplay();
                if (pd != null && pd.isValid()) continue;
                presentations.remove(id);
                safeDismiss(existing);
            }

            try {
                DuoPresentation p = new DuoPresentation(this, display, outerBitmap, innerBitmap, config);
                p.setOnDismissListener(dialog -> {
                    presentations.remove(id);
                    if (!stopping) handler.postDelayed(this::syncPresentations, 45L);
                });
                p.show();
                presentations.put(id, p);
                p.setState(renderedProgress, direction, transitionAlpha(renderedProgress));
            } catch (Throwable ignored) {
                // The arm loop retries because Samsung may expose a panel only during part of the hinge motion.
            }
        }

        // Important: do NOT dismiss a still-valid Presentation merely because a subsequent
        // getDisplays() call temporarily stops listing it. Keep it armed until Android actually
        // removes/invalidates that display or the service stops.
        for (Map.Entry<Integer, DuoPresentation> e : new ArrayList<>(presentations.entrySet())) {
            DuoPresentation p = e.getValue();
            Display d = p == null ? null : p.getDisplay();
            if (p == null || d == null || !d.isValid()) {
                presentations.remove(e.getKey());
                safeDismiss(p);
            }
        }
    }

    private Display[] safeGetAllDisplays() {
        try {
            return displayManager.getDisplays();
        } catch (Throwable ignored) {
            return new Display[0];
        }
    }

    private Display[] safeGetDisplays(String category) {
        try {
            return displayManager.getDisplays(category);
        } catch (Throwable ignored) {
            return new Display[0];
        }
    }

    private void addDisplays(Map<Integer, Display> out, Display[] displays) {
        if (displays == null) return;
        for (Display d : displays) {
            if (d != null) out.put(d.getDisplayId(), d);
        }
    }

    private boolean looksLikePhonePanel(Display d) {
        try {
            Display.Mode m = d.getMode();
            if (m == null) return true;
            int w = m.getPhysicalWidth();
            int h = m.getPhysicalHeight();
            int min = Math.min(w, h);
            int max = Math.max(w, h);
            if (min < 600 || max < 900) return false;

            // Keep phone/tablet-shaped built-in candidates, reject extreme monitor shapes.
            float ratio = (float) min / (float) max;
            return ratio >= 0.38f && ratio <= 0.92f;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private void writeDiagnostics() {
        if (displayManager == null) return;
        StringBuilder s = new StringBuilder();
        try {
            Display[] all = displayManager.getDisplays();
            s.append("Displays seen: ").append(all == null ? 0 : all.length);
            if (all != null) {
                for (Display d : all) {
                    if (d == null) continue;
                    Display.Mode m = d.getMode();
                    s.append("\n#").append(d.getDisplayId())
                            .append(" ").append(d.getName())
                            .append(" state=").append(stateName(d.getState()));
                    if (m != null) {
                        s.append(" ").append(m.getPhysicalWidth()).append("x").append(m.getPhysicalHeight());
                    }
                    if (presentations.containsKey(d.getDisplayId())) s.append(" [ARMED]");
                }
            }
        } catch (Throwable t) {
            s.append("\nDisplay diagnostics unavailable");
        }
        s.append("\nArmed Presentation windows: ").append(presentations.size());
        s.append("\nLast hinge angle: ").append(Math.round(rawAngle * 10f) / 10f).append("°");

        getSharedPreferences(VisualConfig.PREFS, MODE_PRIVATE)
                .edit()
                .putString("diagnostics", s.toString())
                .apply();
    }

    private String stateName(int state) {
        if (state == Display.STATE_OFF) return "OFF";
        if (state == Display.STATE_ON) return "ON";
        if (state == Display.STATE_DOZE) return "DOZE";
        if (state == Display.STATE_DOZE_SUSPEND) return "DOZE_SUSPEND";
        if (state == Display.STATE_VR) return "VR";
        if (state == Display.STATE_ON_SUSPEND) return "ON_SUSPEND";
        return String.valueOf(state);
    }

    @Override
    public void onDisplayAdded(int displayId) {
        handler.post(this::syncPresentations);
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        DuoPresentation p = presentations.remove(displayId);
        safeDismiss(p);
        if (!stopping) handler.postDelayed(this::syncPresentations, 35L);
    }

    @Override
    public void onDisplayChanged(int displayId) {
        handler.post(this::syncPresentations);
    }

    @Override
    public void onDestroy() {
        stopping = true;
        handler.removeCallbacks(displayArmLoop);

        if (choreographer != null && frameLoopStarted) {
            try {
                choreographer.removeFrameCallback(this);
            } catch (Throwable ignored) {
            }
        }

        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (displayManager != null) {
            try {
                displayManager.unregisterDisplayListener(this);
            } catch (Throwable ignored) {
            }
        }

        for (DuoPresentation p : new ArrayList<>(presentations.values())) safeDismiss(p);
        presentations.clear();

        if (overlayAdded && windowManager != null && overlayRoot != null) {
            try {
                windowManager.removeView(overlayRoot);
            } catch (Throwable ignored) {
            }
        }

        try {
            if (transitionWakeLock != null && transitionWakeLock.isHeld()) transitionWakeLock.release();
        } catch (Throwable ignored) {
        }
        try {
            if (cpuWakeLock != null && cpuWakeLock.isHeld()) cpuWakeLock.release();
        } catch (Throwable ignored) {
        }

        getSharedPreferences(VisualConfig.PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean("service_running", false)
                .apply();

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static void safeDismiss(Presentation p) {
        if (p == null) return;
        try {
            p.dismiss();
        } catch (Throwable ignored) {
        }
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static final class DuoPresentation extends Presentation {
        private Bitmap outer;
        private Bitmap inner;
        private VisualConfig config;
        private FrameLayout root;
        private FoldShaderView view;

        DuoPresentation(Context context, Display display, Bitmap outer, Bitmap inner, VisualConfig config) {
            super(context, display);
            this.outer = outer;
            this.inner = inner;
            this.config = config;
        }

        @Override
        protected void onCreate(android.os.Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            Window window = getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.dimAmount = 0f;
                window.setAttributes(lp);
            }

            root = new FrameLayout(getContext());
            root.setBackgroundColor(Color.TRANSPARENT);
            root.setAlpha(0f);
            root.setKeepScreenOn(true);

            view = new FoldShaderView(getContext(), outer, inner);
            view.setConfig(config);
            root.addView(view, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            setContentView(root);
        }

        void setBitmaps(Bitmap outer, Bitmap inner) {
            this.outer = outer;
            this.inner = inner;
            if (view != null) view.setBitmaps(outer, inner);
        }

        void setConfig(VisualConfig config) {
            this.config = config;
            if (view != null) view.setConfig(config);
        }

        void setState(float progress, float direction, float alpha) {
            if (view != null) view.setState(progress, direction);
            if (root != null) {
                root.setVisibility(View.VISIBLE);
                root.setAlpha(alpha);
            }
        }

        void setAlphaOnly(float alpha) {
            if (root != null) {
                root.setVisibility(View.VISIBLE);
                root.setAlpha(alpha);
            }
        }
    }
}
