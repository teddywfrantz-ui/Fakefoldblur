package com.example.foldblur;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Presentation;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class HingeOverlayService extends Service implements SensorEventListener, DisplayManager.DisplayListener {

    private static final String CHANNEL = "foldblur_service";
    private static final float CLOSED = 2.0f;
    private static final float OPEN = 178.0f;
    private static final String BUILT_IN_DISPLAYS = "android.hardware.display.category.BUILT_IN_DISPLAYS";

    /*
     * AGSL compositor.
     *
     * Two screenshots are sampled directly inside one shader. The hinge angle drives
     * a left-to-right transition front. Around that front we apply localized blur,
     * compression, luminance falloff and a narrow specular edge. This is intentionally
     * spatial: areas behind the front settle clear while the wave continues across the
     * display, rather than applying one blur level to the whole image.
     */
    private static final String COMPOSITOR_SHADER =
            "uniform shader outerImage;\n" +
            "uniform shader innerImage;\n" +
            "uniform float2 size;\n" +
            "uniform float progress;\n" +
            "uniform float direction;\n" +
            "uniform float blurPx;\n" +
            "\n" +
            "float smoother(float x) {\n" +
            "  x = clamp(x, 0.0, 1.0);\n" +
            "  return x*x*x*(x*(x*6.0-15.0)+10.0);\n" +
            "}\n" +
            "\n" +
            "half4 sampleOuter(float2 p, float r) {\n" +
            "  half4 c = outerImage.eval(p) * 0.24;\n" +
            "  c += outerImage.eval(p + float2( r, 0.0)) * 0.11;\n" +
            "  c += outerImage.eval(p + float2(-r, 0.0)) * 0.11;\n" +
            "  c += outerImage.eval(p + float2(0.0,  r)) * 0.11;\n" +
            "  c += outerImage.eval(p + float2(0.0, -r)) * 0.11;\n" +
            "  c += outerImage.eval(p + float2( r*0.72,  r*0.72)) * 0.08;\n" +
            "  c += outerImage.eval(p + float2(-r*0.72,  r*0.72)) * 0.08;\n" +
            "  c += outerImage.eval(p + float2( r*0.72, -r*0.72)) * 0.08;\n" +
            "  c += outerImage.eval(p + float2(-r*0.72, -r*0.72)) * 0.08;\n" +
            "  return c;\n" +
            "}\n" +
            "\n" +
            "half4 sampleInner(float2 p, float r) {\n" +
            "  half4 c = innerImage.eval(p) * 0.24;\n" +
            "  c += innerImage.eval(p + float2( r, 0.0)) * 0.11;\n" +
            "  c += innerImage.eval(p + float2(-r, 0.0)) * 0.11;\n" +
            "  c += innerImage.eval(p + float2(0.0,  r)) * 0.11;\n" +
            "  c += innerImage.eval(p + float2(0.0, -r)) * 0.11;\n" +
            "  c += innerImage.eval(p + float2( r*0.72,  r*0.72)) * 0.08;\n" +
            "  c += innerImage.eval(p + float2(-r*0.72,  r*0.72)) * 0.08;\n" +
            "  c += innerImage.eval(p + float2( r*0.72, -r*0.72)) * 0.08;\n" +
            "  c += innerImage.eval(p + float2(-r*0.72, -r*0.72)) * 0.08;\n" +
            "  return c;\n" +
            "}\n" +
            "\n" +
            "half4 main(float2 p) {\n" +
            "  float w = max(size.x, 1.0);\n" +
            "  float x = p.x / w;\n" +
            "  float q = smoother(progress);\n" +
            "\n" +
            "  // Transition begins just outside the left edge and exits past the right.\n" +
            "  float front = -0.10 + 1.20 * q;\n" +
            "  float dx = x - front;\n" +
            "  float absDx = abs(dx);\n" +
            "\n" +
            "  // The inner screenshot is revealed behind the traveling front.\n" +
            "  float mixAmt = 1.0 - smoothstep(-0.105, 0.105, dx);\n" +
            "\n" +
            "  // A tight optical crest plus a broader pre-blur region.\n" +
            "  float crest = 1.0 - smoothstep(0.0, 0.085, absDx);\n" +
            "  float halo = 1.0 - smoothstep(0.035, 0.235, absDx);\n" +
            "  float aheadOpen = smoothstep(-0.03, 0.30, dx) * (1.0 - smoothstep(0.30, 0.62, dx));\n" +
            "  float aheadClose = smoothstep(-0.62, -0.30, dx) * (1.0 - smoothstep(-0.30, 0.03, dx));\n" +
            "  float ahead = mix(aheadClose, aheadOpen, step(0.0, direction));\n" +
            "  float localBlur = clamp(max(crest, halo*0.68 + ahead*0.40), 0.0, 1.0);\n" +
            "\n" +
            "  // Slight local horizontal warp makes the wave feel like a surface transition.\n" +
            "  float warp = crest * direction * w * 0.0125;\n" +
            "  float2 pOuter = float2(p.x - warp, p.y);\n" +
            "  float2 pInner = float2(p.x + warp*0.70, p.y);\n" +
            "\n" +
            "  float r = blurPx * (0.08 + 0.92 * localBlur);\n" +
            "  half4 outerBase = outerImage.eval(pOuter);\n" +
            "  half4 innerBase = innerImage.eval(pInner);\n" +
            "  half4 outerBlur = sampleOuter(pOuter, r);\n" +
            "  half4 innerBlur = sampleInner(pInner, r);\n" +
            "\n" +
            "  half4 outerC = mix(outerBase, outerBlur, half(localBlur));\n" +
            "  half4 innerC = mix(innerBase, innerBlur, half(localBlur));\n" +
            "  half4 c = mix(outerC, innerC, half(mixAmt));\n" +
            "\n" +
            "  // Subtle depth cue: dark leading edge, narrow gleam just behind it.\n" +
            "  float shadow = crest * 0.060 + halo * 0.018;\n" +
            "  float gleamCenter = front - 0.018 * direction;\n" +
            "  float gleam = (1.0 - smoothstep(0.0, 0.020, abs(x - gleamCenter))) * 0.050;\n" +
            "  c.rgb = c.rgb * half(1.0 - shadow) + half3(gleam);\n" +
            "\n" +
            "  // Very gentle vignette around the wave only; endpoints remain visually clean.\n" +
            "  float depth = halo * 0.025;\n" +
            "  c.rgb *= half(1.0 - depth);\n" +
            "  return c;\n" +
            "}\n";

    private SensorManager sensorManager;
    private Sensor hingeSensor;
    private DisplayManager displayManager;
    private WindowManager windowManager;
    private PowerManager.WakeLock cpuWakeLock;

    private FrameLayout overlayRoot;
    private TransitionView overlayView;
    private boolean overlayAdded = false;

    private Bitmap outerBitmap;
    private Bitmap innerBitmap;

    private final Map<Integer, DuoPresentation> presentations = new HashMap<>();

    private float filteredAngle = 180f;
    private float lastRawAngle = 180f;
    private float direction = 1f;
    private long lastSensorTime = 0L;
    private long lastDisplaySync = 0L;

    @Override
    public void onCreate() {
        super.onCreate();

        createChannel();
        startForeground(1, new Notification.Builder(this, CHANNEL)
                .setContentTitle("Fold Blur POC")
                .setContentText("Dual-display AGSL hinge transition active")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build());

        sensorManager = getSystemService(SensorManager.class);
        displayManager = getSystemService(DisplayManager.class);
        windowManager = getSystemService(WindowManager.class);

        if (displayManager != null) {
            displayManager.registerDisplayListener(this, null);
        }

        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null) {
                cpuWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FoldBlur:TransitionCPU");
                cpuWakeLock.setReferenceCounted(false);
                cpuWakeLock.acquire();
            }
        } catch (Throwable ignored) {
        }

        loadScreenshots();
        buildPrimaryOverlay();

        hingeSensor = sensorManager == null
                ? null
                : sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);

        if (hingeSensor != null) {
            sensorManager.registerListener(this, hingeSensor, SensorManager.SENSOR_DELAY_FASTEST);
        }
    }

    private void loadScreenshots() {
        try {
            String outer = getSharedPreferences("foldblur", MODE_PRIVATE).getString("outer_uri", null);
            String inner = getSharedPreferences("foldblur", MODE_PRIVATE).getString("inner_uri", null);
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

    private void buildPrimaryOverlay() {
        if (windowManager == null || outerBitmap == null || innerBitmap == null) return;

        overlayRoot = new FrameLayout(this);
        overlayRoot.setBackgroundColor(Color.BLACK);
        overlayRoot.setVisibility(View.GONE);

        overlayView = new TransitionView(this, outerBitmap, innerBitmap);
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
                        | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(overlayRoot, lp);
            overlayAdded = true;
        } catch (Throwable ignored) {
            overlayAdded = false;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_HINGE_ANGLE || event.values.length == 0) return;

        long now = SystemClock.uptimeMillis();
        float raw = clamp(event.values[0], 0f, 180f);
        float delta = raw - lastRawAngle;

        if (Math.abs(delta) > 0.18f) {
            direction = delta >= 0f ? 1f : -1f;
        }
        lastRawAngle = raw;

        // Mild temporal filtering: enough to remove sensor stepping, not enough to feel laggy.
        float alpha;
        if (lastSensorTime == 0L) {
            alpha = 1f;
        } else {
            long dt = Math.max(1L, now - lastSensorTime);
            alpha = clamp(dt / 28f, 0.28f, 0.72f);
        }
        filteredAngle += (raw - filteredAngle) * alpha;
        lastSensorTime = now;

        boolean transitioning = raw > CLOSED && raw < OPEN;

        if (!transitioning) {
            if (overlayRoot != null) overlayRoot.setVisibility(View.GONE);
            dismissPresentations();
            return;
        }

        float progress = clamp(filteredAngle / 180f, 0f, 1f);
        float energy = (float) Math.pow(Math.max(0f, Math.sin(Math.PI * progress)), 0.66);
        float blur = dp(22) + dp(34) * energy;

        if (overlayRoot != null) {
            overlayRoot.setVisibility(View.VISIBLE);
        }
        if (overlayView != null) {
            overlayView.setState(progress, direction, blur);
        }

        // Re-scan while the hinge is moving because Samsung may expose the other panel
        // only during a narrow portion of the physical handoff.
        if (now - lastDisplaySync > 90L) {
            syncPresentations();
            lastDisplaySync = now;
        }

        for (DuoPresentation presentation : new ArrayList<>(presentations.values())) {
            presentation.setState(progress, direction, blur);
        }
    }

    private void syncPresentations() {
        if (displayManager == null || outerBitmap == null || innerBitmap == null) return;

        int currentId = Display.DEFAULT_DISPLAY;
        try {
            Display current = windowManager == null ? null : windowManager.getDefaultDisplay();
            if (current != null) currentId = current.getDisplayId();
        } catch (Throwable ignored) {
        }

        Map<Integer, Display> candidates = new HashMap<>();

        try {
            Display[] builtIns = displayManager.getDisplays(BUILT_IN_DISPLAYS);
            if (builtIns != null) {
                for (Display d : builtIns) {
                    if (d != null) candidates.put(d.getDisplayId(), d);
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            Display[] presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            if (presentationDisplays != null) {
                for (Display d : presentationDisplays) {
                    if (d != null) candidates.put(d.getDisplayId(), d);
                }
            }
        } catch (Throwable ignored) {
        }

        Set<Integer> valid = new HashSet<>();

        for (Display display : candidates.values()) {
            if (display == null || !display.isValid() || display.getDisplayId() == currentId) continue;

            valid.add(display.getDisplayId());

            if (!presentations.containsKey(display.getDisplayId())) {
                try {
                    DuoPresentation p = new DuoPresentation(this, display, outerBitmap, innerBitmap);
                    p.show();
                    presentations.put(display.getDisplayId(), p);

                    float progress = clamp(filteredAngle / 180f, 0f, 1f);
                    float energy = (float) Math.pow(Math.max(0f, Math.sin(Math.PI * progress)), 0.66);
                    p.setState(progress, direction, dp(22) + dp(34) * energy);
                } catch (Throwable ignored) {
                    // OEM policy decides whether an inactive internal panel is presentation-capable.
                }
            }
        }

        for (Integer id : new HashSet<>(presentations.keySet())) {
            DuoPresentation p = presentations.get(id);
            if (p == null || p.getDisplay() == null || !p.getDisplay().isValid() || !valid.contains(id)) {
                presentations.remove(id);
                safeDismiss(p);
            }
        }
    }

    private void dismissPresentations() {
        for (DuoPresentation p : new ArrayList<>(presentations.values())) {
            safeDismiss(p);
        }
        presentations.clear();
    }

    private static void safeDismiss(Presentation presentation) {
        if (presentation == null) return;
        try {
            presentation.dismiss();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        syncPresentations();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        DuoPresentation p = presentations.remove(displayId);
        safeDismiss(p);
    }

    @Override
    public void onDisplayChanged(int displayId) {
        if (lastRawAngle > CLOSED && lastRawAngle < OPEN) {
            syncPresentations();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onDestroy() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (displayManager != null) {
            try {
                displayManager.unregisterDisplayListener(this);
            } catch (Throwable ignored) {
            }
        }

        dismissPresentations();

        if (overlayAdded && windowManager != null && overlayRoot != null) {
            try {
                windowManager.removeView(overlayRoot);
            } catch (Throwable ignored) {
            }
        }

        if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
            try {
                cpuWakeLock.release();
            } catch (Throwable ignored) {
            }
        }

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL,
                "Fold Blur Service",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the hinge-driven screenshot transition available in the background.");
        nm.createNotificationChannel(channel);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static final class DuoPresentation extends Presentation {
        private final Bitmap outer;
        private final Bitmap inner;
        private TransitionView transitionView;

        DuoPresentation(Context context, Display display, Bitmap outer, Bitmap inner) {
            super(context, display);
            this.outer = outer;
            this.inner = inner;
        }

        @Override
        protected void onCreate(android.os.Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            Window window = getWindow();
            if (window != null) {
                window.addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                );
                window.setStatusBarColor(Color.TRANSPARENT);
                window.setNavigationBarColor(Color.TRANSPARENT);
            }

            FrameLayout root = new FrameLayout(getContext());
            root.setBackgroundColor(Color.BLACK);
            transitionView = new TransitionView(getContext(), outer, inner);
            root.addView(transitionView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            setContentView(root);
        }

        void setState(float progress, float direction, float blurPx) {
            if (transitionView != null) {
                transitionView.setState(progress, direction, blurPx);
            }
        }
    }

    /**
     * Full-screen AGSL compositor. It owns both screenshots as shader inputs and performs
     * interpolation, blur and distortion in one render pass.
     */
    private static final class TransitionView extends View {
        private final Bitmap outerBitmap;
        private final Bitmap innerBitmap;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Matrix outerMatrix = new Matrix();
        private final Matrix innerMatrix = new Matrix();

        private RuntimeShader runtimeShader;
        private BitmapShader outerShader;
        private BitmapShader innerShader;
        private boolean shaderReady = false;

        private float progress = 1f;
        private float direction = 1f;
        private float blurPx = 0f;

        TransitionView(Context context, Bitmap outerBitmap, Bitmap innerBitmap) {
            super(context);
            this.outerBitmap = outerBitmap;
            this.innerBitmap = innerBitmap;
            setLayerType(View.LAYER_TYPE_HARDWARE, null);

            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    runtimeShader = new RuntimeShader(COMPOSITOR_SHADER);
                    outerShader = new BitmapShader(outerBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    innerShader = new BitmapShader(innerBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    runtimeShader.setInputShader("outerImage", outerShader);
                    runtimeShader.setInputShader("innerImage", innerShader);
                    paint.setShader(runtimeShader);
                    shaderReady = true;
                } catch (Throwable ignored) {
                    shaderReady = false;
                }
            }
        }

        void setState(float progress, float direction, float blurPx) {
            this.progress = clamp(progress, 0f, 1f);
            this.direction = direction >= 0f ? 1f : -1f;
            this.blurPx = Math.max(0f, blurPx);
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w <= 0 || h <= 0) return;
            configureCenterCrop(outerBitmap, w, h, outerMatrix);
            configureCenterCrop(innerBitmap, w, h, innerMatrix);
            if (outerShader != null) outerShader.setLocalMatrix(outerMatrix);
            if (innerShader != null) innerShader.setLocalMatrix(innerMatrix);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (getWidth() <= 0 || getHeight() <= 0) return;

            if (shaderReady && runtimeShader != null) {
                try {
                    runtimeShader.setFloatUniform("size", (float) getWidth(), (float) getHeight());
                    runtimeShader.setFloatUniform("progress", progress);
                    runtimeShader.setFloatUniform("direction", direction);
                    runtimeShader.setFloatUniform("blurPx", blurPx);
                    canvas.drawRect(0f, 0f, getWidth(), getHeight(), paint);
                    return;
                } catch (Throwable ignored) {
                    shaderReady = false;
                }
            }

            // Conservative fallback for devices where RuntimeShader fails unexpectedly.
            float mix = smoothstep(-0.10f, 1.10f, progress);
            fallbackPaint.setAlpha(Math.round(255f * (1f - mix)));
            canvas.drawBitmap(outerBitmap, outerMatrix, fallbackPaint);
            fallbackPaint.setAlpha(Math.round(255f * mix));
            canvas.drawBitmap(innerBitmap, innerMatrix, fallbackPaint);
            fallbackPaint.setAlpha(255);
        }

        private static void configureCenterCrop(Bitmap bitmap, int viewW, int viewH, Matrix out) {
            out.reset();
            if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) return;

            float scale = Math.max(
                    (float) viewW / (float) bitmap.getWidth(),
                    (float) viewH / (float) bitmap.getHeight());
            float dx = (viewW - bitmap.getWidth() * scale) * 0.5f;
            float dy = (viewH - bitmap.getHeight() * scale) * 0.5f;
            out.setScale(scale, scale);
            out.postTranslate(dx, dy);
        }

        private static float smoothstep(float edge0, float edge1, float x) {
            float t = clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
            return t * t * (3f - 2f * t);
        }
    }
}
