package com.example.foldblur;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * Full-screen AGSL fold compositor used by both the normal overlay and Presentation windows.
 *
 * Design rules for this POC:
 * - No whole-screen crossfade.
 * - Each physical display primarily renders the screenshot that matches its aspect ratio.
 * - The other screenshot is used only as a very small "through-glass" contribution at the crest.
 * - The inner display's optical treatment is restricted to the LEFT half; the right half stays crisp.
 * - Opening drives the optical front left -> right. Closing naturally reverses it.
 */
final class FoldShaderView extends View {

    static final int ROLE_AUTO = -1;
    static final int ROLE_OUTER = 0;
    static final int ROLE_INNER = 1;

    private static final String SHADER =
            "uniform shader outerImage;\n" +
            "uniform shader innerImage;\n" +
            "uniform float2 viewSize;\n" +
            "uniform float2 outerSize;\n" +
            "uniform float2 innerSize;\n" +
            "uniform float progress;\n" +
            "uniform float direction;\n" +
            "uniform float role;\n" +
            "uniform float blurStrength;\n" +
            "uniform float feather;\n" +
            "uniform float refraction;\n" +
            "uniform float glassMix;\n" +
            "uniform float innerWidth;\n" +
            "uniform float scrubBars;\n" +
            "\n" +
            "float sat(float x) { return clamp(x, 0.0, 1.0); }\n" +
            "float smooth5(float x) {\n" +
            "  x = sat(x);\n" +
            "  return x*x*x*(x*(x*6.0 - 15.0) + 10.0);\n" +
            "}\n" +
            "float2 scrub(float2 p) {\n" +
            "  if (scrubBars < 0.5) return p;\n" +
            "  float yn = p.y / max(viewSize.y, 1.0);\n" +
            "  if (yn < 0.050) p.y = viewSize.y * 0.052;\n" +
            "  if (yn > 0.925) p.y = viewSize.y * 0.922;\n" +
            "  return p;\n" +
            "}\n" +
            "float2 mapToImage(float2 p, float2 imageSize) {\n" +
            "  p = scrub(p);\n" +
            "  float sx = viewSize.x / max(imageSize.x, 1.0);\n" +
            "  float sy = viewSize.y / max(imageSize.y, 1.0);\n" +
            "  float s = max(sx, sy);\n" +
            "  float2 scaled = imageSize * s;\n" +
            "  float2 crop = (scaled - viewSize) * 0.5;\n" +
            "  return (p + crop) / s;\n" +
            "}\n" +
            "half4 outerAt(float2 p) { return outerImage.eval(mapToImage(p, outerSize)); }\n" +
            "half4 innerAt(float2 p) { return innerImage.eval(mapToImage(p, innerSize)); }\n" +
            "half4 dominantAt(float2 p) {\n" +
            "  return role < 0.5 ? outerAt(p) : innerAt(p);\n" +
            "}\n" +
            "half4 alternateAt(float2 p) {\n" +
            "  return role < 0.5 ? innerAt(p) : outerAt(p);\n" +
            "}\n" +
            "half4 blurDominant(float2 p, float r) {\n" +
            "  // 13-tap soft directional Gaussian approximation. Horizontal energy is\n" +
            "  // intentionally a little stronger to sell the moving glass edge.\n" +
            "  half4 c = dominantAt(p) * 0.22;\n" +
            "  c += dominantAt(p + float2( r*0.45, 0.0)) * 0.10;\n" +
            "  c += dominantAt(p + float2(-r*0.45, 0.0)) * 0.10;\n" +
            "  c += dominantAt(p + float2( r, 0.0)) * 0.075;\n" +
            "  c += dominantAt(p + float2(-r, 0.0)) * 0.075;\n" +
            "  c += dominantAt(p + float2(0.0,  r*0.42)) * 0.07;\n" +
            "  c += dominantAt(p + float2(0.0, -r*0.42)) * 0.07;\n" +
            "  c += dominantAt(p + float2( r*0.58,  r*0.34)) * 0.055;\n" +
            "  c += dominantAt(p + float2(-r*0.58,  r*0.34)) * 0.055;\n" +
            "  c += dominantAt(p + float2( r*0.58, -r*0.34)) * 0.055;\n" +
            "  c += dominantAt(p + float2(-r*0.58, -r*0.34)) * 0.055;\n" +
            "  c += dominantAt(p + float2(0.0,  r*0.78)) * 0.04;\n" +
            "  c += dominantAt(p + float2(0.0, -r*0.78)) * 0.04;\n" +
            "  return c;\n" +
            "}\n" +
            "half4 main(float2 p) {\n" +
            "  float w = max(viewSize.x, 1.0);\n" +
            "  float x = p.x / w;\n" +
            "  float q = smooth5(progress);\n" +
            "  float energy = pow(max(0.0, sin(3.14159265 * q)), 0.72);\n" +
            "\n" +
            "  // Outer screen: optical front crosses the complete panel.\n" +
            "  // Inner screen: it only crosses the LEFT half, matching the folding panel.\n" +
            "  float outerFront = -0.10 + 1.20 * q;\n" +
            "  float innerFront = -0.065 + (innerWidth + 0.105) * q;\n" +
            "  float front = role < 0.5 ? outerFront : innerFront;\n" +
            "\n" +
            "  float innerPanelMask = 1.0 - smoothstep(innerWidth - 0.015, innerWidth + 0.025, x);\n" +
            "  float panelMask = role < 0.5 ? 1.0 : innerPanelMask;\n" +
            "\n" +
            "  float width = (role < 0.5 ? 0.145 : 0.120) * max(feather, 0.35);\n" +
            "  float d = x - front;\n" +
            "  float ad = abs(d);\n" +
            "  float crest = 1.0 - smoothstep(0.0, width * 0.34, ad);\n" +
            "  float halo  = 1.0 - smoothstep(width * 0.16, width, ad);\n" +
            "  float veil  = 1.0 - smoothstep(width * 0.35, width * 1.65, ad);\n" +
            "  float optical = sat((crest * 0.82 + halo * 0.38 + veil * 0.12) * energy * panelMask);\n" +
            "\n" +
            "  // Soft refraction: strongest exactly at the crest, never a whole-screen scale.\n" +
            "  float warpPx = direction * refraction * w * energy * panelMask *\n" +
            "                 (crest * 0.0120 + halo * 0.0035);\n" +
            "  // A tiny curvature term makes the wave less mechanically straight.\n" +
            "  float yn = p.y / max(viewSize.y, 1.0);\n" +
            "  float bow = sin((yn - 0.5) * 3.14159265) * w * 0.0018 * refraction * crest * energy;\n" +
            "  float2 warped = p + float2(warpPx + bow, 0.0);\n" +
            "\n" +
            "  float radius = min(viewSize.x, viewSize.y) * 0.0165 * max(blurStrength, 0.0) * optical;\n" +
            "  half4 sharp = dominantAt(warped);\n" +
            "  half4 soft = blurDominant(warped, radius);\n" +
            "  half4 c = mix(sharp, soft, half(optical));\n" +
            "\n" +
            "  // The other screenshot only appears inside the optical crest, like a faint\n" +
            "  // through-glass reflection. This avoids the double-exposure failure of v3.\n" +
            "  float otherAmt = sat(glassMix * crest * energy * panelMask);\n" +
            "  half4 other = alternateAt(p - float2(warpPx * 0.45, 0.0));\n" +
            "  c = mix(c, other, half(otherAmt));\n" +
            "\n" +
            "  // Apple-like depth cues kept deliberately subtle: no blue tint, no hard line.\n" +
            "  float shadow = (halo * 0.026 + crest * 0.018) * energy * panelMask;\n" +
            "  float gleamCenter = front - 0.012 * direction;\n" +
            "  float gleam = (1.0 - smoothstep(0.0, 0.013 * max(feather, 0.45), abs(x - gleamCenter)))\n" +
            "                * 0.026 * energy * panelMask;\n" +
            "  c.rgb = c.rgb * half(1.0 - shadow) + half3(gleam);\n" +
            "\n" +
            "  // On the inner display only, the left folding panel gets a tiny perspective\n" +
            "  // depth reduction when nearly closed. The right half remains untouched.\n" +
            "  float nearClosed = (1.0 - smoothstep(0.05, 0.42, q));\n" +
            "  float leftDepth = role > 0.5 ? nearClosed * innerPanelMask * (1.0 - smoothstep(0.0, innerWidth, x)) * 0.055 : 0.0;\n" +
            "  c.rgb *= half(1.0 - leftDepth);\n" +
            "\n" +
            "  return c;\n" +
            "}\n";

    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private Bitmap outerBitmap;
    private Bitmap innerBitmap;
    private RuntimeShader runtimeShader;
    private boolean shaderReady;

    private float progress = 1f;
    private float direction = 1f;
    private int roleOverride = ROLE_AUTO;
    private float detectedRole = 0f;
    private VisualConfig config = VisualConfig.defaults();

    FoldShaderView(Context context, Bitmap outer, Bitmap inner) {
        super(context);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setBitmaps(outer, inner);
    }

    void setBitmaps(Bitmap outer, Bitmap inner) {
        outerBitmap = outer;
        innerBitmap = inner;
        rebuildShader();
        detectRole(getWidth(), getHeight());
        invalidate();
    }

    void setConfig(VisualConfig newConfig) {
        if (newConfig != null) config = newConfig;
        invalidate();
    }

    void setRoleOverride(int role) {
        roleOverride = role;
        invalidate();
    }

    int getEffectiveRole() {
        if (roleOverride == ROLE_OUTER || roleOverride == ROLE_INNER) return roleOverride;
        return detectedRole >= 0.5f ? ROLE_INNER : ROLE_OUTER;
    }

    void setState(float newProgress, float newDirection) {
        progress = clamp(newProgress, 0f, 1f);
        direction = newDirection >= 0f ? 1f : -1f;
        invalidate();
    }

    private void rebuildShader() {
        shaderReady = false;
        runtimeShader = null;
        shaderPaint.setShader(null);

        if (Build.VERSION.SDK_INT < 33 || outerBitmap == null || innerBitmap == null) return;

        try {
            RuntimeShader shader = new RuntimeShader(SHADER);
            BitmapShader outer = new BitmapShader(outerBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            BitmapShader inner = new BitmapShader(innerBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shader.setInputShader("outerImage", outer);
            shader.setInputShader("innerImage", inner);
            runtimeShader = shader;
            shaderPaint.setShader(shader);
            shaderReady = true;
        } catch (Throwable ignored) {
            shaderReady = false;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        detectRole(w, h);
    }

    private void detectRole(int w, int h) {
        if (w <= 0 || h <= 0 || outerBitmap == null || innerBitmap == null) return;

        float viewAspect = shortLongRatio(w, h);
        float outerAspect = shortLongRatio(outerBitmap.getWidth(), outerBitmap.getHeight());
        float innerAspect = shortLongRatio(innerBitmap.getWidth(), innerBitmap.getHeight());

        detectedRole = Math.abs(viewAspect - innerAspect) < Math.abs(viewAspect - outerAspect) ? 1f : 0f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0 || outerBitmap == null || innerBitmap == null) return;

        float role = getEffectiveRole() == ROLE_INNER ? 1f : 0f;

        if (shaderReady && runtimeShader != null) {
            try {
                runtimeShader.setFloatUniform("viewSize", (float) getWidth(), (float) getHeight());
                runtimeShader.setFloatUniform("outerSize", (float) outerBitmap.getWidth(), (float) outerBitmap.getHeight());
                runtimeShader.setFloatUniform("innerSize", (float) innerBitmap.getWidth(), (float) innerBitmap.getHeight());
                runtimeShader.setFloatUniform("progress", progress);
                runtimeShader.setFloatUniform("direction", direction);
                runtimeShader.setFloatUniform("role", role);
                runtimeShader.setFloatUniform("blurStrength", config.blurStrength);
                runtimeShader.setFloatUniform("feather", config.feather);
                runtimeShader.setFloatUniform("refraction", config.refraction);
                runtimeShader.setFloatUniform("glassMix", config.glassMix);
                runtimeShader.setFloatUniform("innerWidth", config.innerWidth);
                runtimeShader.setFloatUniform("scrubBars", config.scrubBars ? 1f : 0f);
                canvas.drawRect(0f, 0f, getWidth(), getHeight(), shaderPaint);
                return;
            } catch (Throwable ignored) {
                shaderReady = false;
            }
        }

        // Fallback is intentionally conservative: show only the screenshot for this display.
        // Never fall back to the old whole-frame double exposure.
        Bitmap b = role < 0.5f ? outerBitmap : innerBitmap;
        drawCenterCrop(canvas, b, fallbackPaint);
    }

    private void drawCenterCrop(Canvas canvas, Bitmap bitmap, Paint paint) {
        float scale = Math.max((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        float dx = (getWidth() - bitmap.getWidth() * scale) * 0.5f;
        float dy = (getHeight() - bitmap.getHeight() * scale) * 0.5f;
        canvas.save();
        canvas.translate(dx, dy);
        canvas.scale(scale, scale);
        canvas.drawBitmap(bitmap, 0f, 0f, paint);
        canvas.restore();
    }

    private static float shortLongRatio(int w, int h) {
        int min = Math.min(w, h);
        int max = Math.max(w, h);
        return max == 0 ? 0f : (float) min / (float) max;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

final class VisualConfig {
    static final String PREFS = "foldblur";

    float blurStrength = 1.00f;
    float feather = 1.00f;
    float refraction = 1.00f;
    float glassMix = 0.04f;
    float smoothingMs = 22f;
    float innerWidth = 0.52f;
    boolean scrubBars = true;

    static VisualConfig defaults() {
        return new VisualConfig();
    }

    static VisualConfig load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        VisualConfig c = new VisualConfig();
        c.blurStrength = p.getFloat("blur_strength", 1.00f);
        c.feather = p.getFloat("feather", 1.00f);
        c.refraction = p.getFloat("refraction", 1.00f);
        c.glassMix = p.getFloat("glass_mix", 0.04f);
        c.smoothingMs = p.getFloat("smoothing_ms", 22f);
        c.innerWidth = p.getFloat("inner_width", 0.52f);
        c.scrubBars = p.getBoolean("scrub_bars", true);
        return c;
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putFloat("blur_strength", blurStrength)
                .putFloat("feather", feather)
                .putFloat("refraction", refraction)
                .putFloat("glass_mix", glassMix)
                .putFloat("smoothing_ms", smoothingMs)
                .putFloat("inner_width", innerWidth)
                .putBoolean("scrub_bars", scrubBars)
                .apply();
    }
}

