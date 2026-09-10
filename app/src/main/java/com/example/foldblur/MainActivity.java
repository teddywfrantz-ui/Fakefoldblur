package com.example.foldblur;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

public class MainActivity extends Activity {
    private static final int REQ_OUTER = 101;
    private static final int REQ_INNER = 102;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int p = dp(24);
        root.setPadding(p, dp(42), p, p);

        TextView title = new TextView(this);
        title.setText("Fold Blur Screenshot POC");
        title.setTextSize(28f);
        root.addView(title, mw());

        TextView body = new TextView(this);
        body.setText("Select screenshots of your real outer and inner One UI Home screens. The service will animate those images based on the live hinge angle, then disappear at the endpoints so the real UI is visible again.");
        body.setTextSize(16f);
        LinearLayout.LayoutParams bp = mw();
        bp.topMargin = dp(16);
        root.addView(body, bp);

        status = new TextView(this);
        status.setTextSize(15f);
        LinearLayout.LayoutParams sp = mw();
        sp.topMargin = dp(18);
        root.addView(status, sp);

        Button outer = new Button(this);
        outer.setText("SELECT OUTER SCREENSHOT");
        outer.setOnClickListener(v -> pickImage(REQ_OUTER));
        LinearLayout.LayoutParams op = mw(); op.topMargin = dp(16);
        root.addView(outer, op);

        Button inner = new Button(this);
        inner.setText("SELECT INNER SCREENSHOT");
        inner.setOnClickListener(v -> pickImage(REQ_INNER));
        root.addView(inner, mw());

        Button overlay = new Button(this);
        overlay.setText("ALLOW DRAW OVER OTHER APPS");
        overlay.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        root.addView(overlay, mw());

        Button start = new Button(this);
        start.setText("START HINGE TRANSITION SERVICE");
        start.setOnClickListener(v -> startTransitionService());
        root.addView(start, mw());

        Button stop = new Button(this);
        stop.setText("STOP SERVICE");
        stop.setOnClickListener(v -> stopService(new Intent(this, HingeOverlayService.class)));
        root.addView(stop, mw());

        setContentView(root);
        refreshStatus();

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
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
        } catch (Throwable ignored) {}
        String key = requestCode == REQ_OUTER ? "outer_uri" : "inner_uri";
        getSharedPreferences("foldblur", MODE_PRIVATE).edit().putString(key, uri.toString()).apply();
        refreshStatus();
    }

    private void startTransitionService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow Draw over other apps first.", Toast.LENGTH_LONG).show();
            return;
        }
        String outer = getSharedPreferences("foldblur", MODE_PRIVATE).getString("outer_uri", null);
        String inner = getSharedPreferences("foldblur", MODE_PRIVATE).getString("inner_uri", null);
        if (outer == null || inner == null) {
            Toast.makeText(this, "Select both screenshots first.", Toast.LENGTH_LONG).show();
            return;
        }
        Intent s = new Intent(this, HingeOverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(s); else startService(s);
        Toast.makeText(this, "Service started. Return to One UI Home and fold/unfold slowly.", Toast.LENGTH_LONG).show();
    }

    private void refreshStatus() {
        String outer = getSharedPreferences("foldblur", MODE_PRIVATE).getString("outer_uri", null);
        String inner = getSharedPreferences("foldblur", MODE_PRIVATE).getString("inner_uri", null);
        status.setText("Outer screenshot: " + (outer == null ? "NOT SET" : "SET")
                + "\nInner screenshot: " + (inner == null ? "NOT SET" : "SET")
                + "\nOverlay permission: " + (Settings.canDrawOverlays(this) ? "ALLOWED" : "NOT ALLOWED"));
    }

    private LinearLayout.LayoutParams mw() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
}
