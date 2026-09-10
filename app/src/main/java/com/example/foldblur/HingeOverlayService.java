package com.example.foldblur;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.graphics.PixelFormat;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.IBinder;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

public class HingeOverlayService extends Service implements SensorEventListener {
    private static final String CHANNEL = "foldblur_service";
    private SensorManager sensorManager;
    private Sensor hingeSensor;
    private WindowManager wm;
    private FrameLayout overlay;
    private ImageView outerView;
    private ImageView innerView;
    private boolean overlayAdded = false;
    private float lastAngle = 180f;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(1, new Notification.Builder(this, CHANNEL)
                .setContentTitle("Fold Blur POC")
                .setContentText("Listening to hinge angle")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build());

        wm = getSystemService(WindowManager.class);
        sensorManager = getSystemService(SensorManager.class);
        hingeSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE);
        if (hingeSensor != null) sensorManager.registerListener(this, hingeSensor, SensorManager.SENSOR_DELAY_GAME);
        buildOverlay();
    }

    private void buildOverlay() {
        overlay = new FrameLayout(this);
        overlay.setVisibility(View.GONE);

        outerView = new ImageView(this);
        outerView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        outerView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        innerView = new ImageView(this);
        innerView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        innerView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        overlay.addView(outerView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        overlay.addView(innerView, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        try {
            String outer = getSharedPreferences("foldblur", MODE_PRIVATE).getString("outer_uri", null);
            String inner = getSharedPreferences("foldblur", MODE_PRIVATE).getString("inner_uri", null);
            if (outer != null) outerView.setImageBitmap(load(Uri.parse(outer)));
            if (inner != null) innerView.setImageBitmap(load(Uri.parse(inner)));
        } catch (Throwable ignored) {}

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            wm.addView(overlay, lp);
            overlayAdded = true;
        } catch (Throwable ignored) {}
    }

    private Bitmap load(Uri uri) throws Exception {
        ImageDecoder.Source s = ImageDecoder.createSource(getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(s, (decoder, info, src) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_HINGE_ANGLE || event.values.length == 0 || overlay == null) return;
        float angle = Math.max(0f, Math.min(180f, event.values[0]));
        lastAngle = angle;

        if (angle <= 2f || angle >= 178f) {
            overlay.setVisibility(View.GONE);
            outerView.setRenderEffect(null);
            innerView.setRenderEffect(null);
            return;
        }

        overlay.setVisibility(View.VISIBLE);
        float p = angle / 180f;
        float middle = (float)Math.sin(Math.PI * p);
        middle = Math.max(0f, middle);
        float blur = 70f * middle;

        if (blur > 0.5f) {
            RenderEffect e = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP);
            outerView.setRenderEffect(e);
            innerView.setRenderEffect(e);
        } else {
            outerView.setRenderEffect(null);
            innerView.setRenderEffect(null);
        }

        float innerAlpha = smooth(0.38f, 0.75f, p);
        innerView.setAlpha(innerAlpha);
        outerView.setAlpha(1f - 0.85f * innerAlpha);

        outerView.setPivotX(0f);
        innerView.setPivotX(0f);
        outerView.setPivotY(overlay.getHeight() * 0.5f);
        innerView.setPivotY(overlay.getHeight() * 0.5f);

        outerView.setScaleX(1f + 0.18f * p);
        outerView.setScaleY(1f - 0.03f * middle);
        innerView.setScaleX(0.80f + 0.20f * smooth(0.25f, 0.92f, p));
        innerView.setScaleY(0.97f + 0.03f * smooth(0.25f, 0.92f, p));
    }

    private float smooth(float a, float b, float x) {
        float t = Math.max(0f, Math.min(1f, (x - a) / (b - a)));
        return t * t * (3f - 2f * t);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    public void onDestroy() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (overlayAdded && wm != null && overlay != null) {
            try { wm.removeView(overlay); } catch (Throwable ignored) {}
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel c = new NotificationChannel(CHANNEL, "Fold Blur Service", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(c);
    }
}
