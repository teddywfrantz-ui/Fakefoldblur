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
 * Screenshot compositor for the fold proof-of-concept.
 *
 * Important design rules:
 * - The current physical display renders only its own screenshot.
 * - No whole-frame outer/inner crossfade, which caused ghosting/double images before.
 * - Outer: a soft moving optical blur/refraction front travels left -> right as the hinge opens.
 * - Inner: only the LEFT half is affected. Near closed it is broadly glassy/blurred, then
 *   progressively resolves from the left edge toward the center as the hinge opens.
 * - The right half of the inner image is always sampled sharp and receives zero blur/warp.
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
            "uniform float innerWidth;\n" +
            "uniform float scrubBars;\n" +
            "\n" +
            "float sat(float v) { return clamp(v, 0.0, 1.0); }\n" +
            "float smoother(float v) {\n" +
            "  v = sat(v);\n" +
            "  return v*v*v*(v*(v*6.0 - 15.0) + 10.0);\n" +
            "}\n" +
            "float2 cleanBars(float2 p) {\n" +
            "  if (scrubBars < 0.5) return p;\n" +
            "  float y = p.y / max(viewSize.y, 1.0);\n" +
            "  if (y < 0.040) p.y = viewSize.y * 0.042;\n" +
            "  if (y > 0.955) p.y = viewSize.y * 0.952;\n" +
            "  return p;\n" +
            "}\n" +
            "float2 mapCrop(float2 p, float2 imageSize) {\n" +
            "  p = cleanBars(p);\n" +
            "  float sx = viewSize.x / max(imageSize.x, 1.0);\n" +
            "  float sy = viewSize.y / max(imageSize.y, 1.0);\n" +
            "  float s = max(sx, sy);\n" +
            "  float2 scaled = imageSize * s;\n" +
            "  float2 crop = (scaled - viewSize) * 0.5;\n" +
            "  return (p + crop) / s;\n" +
            "}\n" +
            "half4 sampleSource(float2 p) {\n" +
            "  return role < 0.5 ? outerImage.eval(mapCrop(p, outerSize))\n" +
            "                    : innerImage.eval(mapCrop(p, innerSize));\n" +
            "}\n" +
            "half4 blurSource(float2 p, float r) {\n" +
            "  if (r < 0.35) return sampleSource(p);\n" +
            "  half4 c = sampleSource(p) * 0.180;\n" +
            "  c += sampleSource(p + float2( r*0.32, 0.0)) * 0.105;\n" +
            "  c += sampleSource(p + float2(-r*0.32, 0.0)) * 0.105;\n" +
            "  c += sampleSource(p + float2( r*0.72, 0.0)) * 0.075;\n" +
            "  c += sampleSource(p + float2(-r*0.72, 0.0)) * 0.075;\n" +
            "  c += sampleSource(p + float2(0.0,  r*0.36)) * 0.085;\n" +
            "  c += sampleSource(p + float2(0.0, -r*0.36)) * 0.085;\n" +
            "  c += sampleSource(p + float2( r*0.50,  r*0.28)) * 0.060;\n" +
            "  c += sampleSource(p + float2(-r*0.50,  r*0.28)) * 0.060;\n" +
            "  c += sampleSource(p + float2( r*0.50, -r*0.28)) * 0.060;\n" +
            "  c += sampleSource(p + float2(-r*0.50, -r*0.28)) * 0.060;\n" +
            "  c += sampleSource(p + float2(0.0,  r*0.78)) * 0.025;\n" +
            "  c += sampleSource(p + float2(0.0, -r*0.78)) * 0.025;\n" +
            "  return c;\n" +
            "}\n" +
            "half4 main(float2 p) {\n" +
            "  float w = max(viewSize.x, 1.0);\n" +
            "  float h = max(viewSize.y, 1.0);\n" +
            "  float x = p.x / w;\n" +
            "  float y = p.y / h;\n" +
            "  float q = smoother(progress);\n" +
            "  float optical = 0.0;\n" +
            "  float crest = 0.0;\n" +
            "  float front = 0.0;\n" +
            "\n" +
            "  if (role < 0.5) {\n" +
            "    // COVER DISPLAY: a feathered optical front moves across the complete image.\n" +
            "    float travel = smoother(sat((q - 0.015) / 0.78));\n" +
            "    front = -0.10 + 1.20 * travel;\n" +
            "    float width = 0.145 * max(feather, 0.45);\n" +
            "    float d = abs(x - front);\n" +
            "    crest = 1.0 - smoothstep(0.0, width * 0.30, d);\n" +
            "    float halo = 1.0 - smoothstep(width * 0.12, width, d);\n" +
            "    float veil = 1.0 - smoothstep(width * 0.42, width * 1.65, d);\n" +
            "    float energy = sin(3.14159265 * sat(q / 0.92));\n" +
            "    energy = pow(max(0.0, energy), 0.58);\n" +
            "    optical = sat((crest*0.88 + halo*0.38 + veil*0.10) * energy);\n" +
            "  } else {\n" +
            "    // INNER DISPLAY: NEVER affect the right side. At a small hinge angle most of\n" +
            "    // the left panel is glassy. As opening continues, a soft clearing edge moves\n" +
            "    // from the far left toward the crease and leaves sharp pixels behind it.\n" +
            "    float limit = clamp(innerWidth, 0.46, 0.52);\n" +
            "    float leftMask = 1.0 - smoothstep(limit - 0.008, limit + 0.008, x);\n" +
            "    float resolve = smoother(sat((q - 0.015) / 0.965));\n" +
            "    front = -0.015 + (limit + 0.030) * resolve;\n" +
            "    float edgeWidth = 0.105 * max(feather, 0.45);\n" +
            "    crest = (1.0 - smoothstep(0.0, edgeWidth * 0.52, abs(x - front))) * leftMask;\n" +
            "    float uncleared = smoothstep(front - 0.040, front + 0.050, x) * leftMask;\n" +
            "    float baseGlass = uncleared * (1.0 - smoothstep(0.78, 1.0, resolve));\n" +
            "    optical = sat(baseGlass * 0.58 + crest * 0.82);\n" +
            "    // Hard safety gate: right half receives exactly zero optical treatment.\n" +
            "    optical *= leftMask;\n" +
            "  }\n" +
            "\n" +
            "  float bow = sin((y - 0.5) * 3.14159265);\n" +
            "  float warp = direction * refraction * w * crest * 0.0060;\n" +
            "  warp += bow * refraction * w * crest * 0.0012;\n" +
            "  if (role > 0.5) {\n" +
            "    float limit = clamp(innerWidth, 0.46, 0.52);\n" +
            "    float leftMask = 1.0 - smoothstep(limit - 0.008, limit + 0.008, x);\n" +
            "    warp *= leftMask;\n" +
            "  }\n" +
            "\n" +
            "  float2 wp = p + float2(warp, 0.0);\n" +
            "  float radius = min(w, h) * 0.020 * max(blurStrength, 0.0) * optical;\n" +
            "  half4 sharp = sampleSource(wp);\n" +
            "  half4 soft = blurSource(wp, radius);\n" +
            "  half4 c = mix(sharp, soft, half(sat(optical)));\n" +
            "\n" +
            "  // Very subtle glass depth around the moving boundary. No tint and no hard line.\n" +
            "  float gleam = crest * 0.022;\n" +
            "  float shade = crest * 0.030;\n" +
            "  c.rgb = c.rgb * half(1.0 - shade) + half3(gleam);\n" +
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
    private int detectedRole = ROLE_OUTER;
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
        return detectedRole;
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
            shader.setInputShader("outerImage",
                    new BitmapShader(outerBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            shader.setInputShader("innerImage",
                    new BitmapShader(innerBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
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
        if (w <= 0 || h <= 0) return;
        // Fold cover screens are very tall/narrow; the inner panel is substantially squarer.
        // Using the actual view geometry avoids the screenshot-aspect misclassification that
        // made the previous build apply the full-width OUTER effect on the INNER display.
        detectedRole = shortLongRatio(w, h) >= 0.62f ? ROLE_INNER : ROLE_OUTER;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (getWidth() <= 0 || getHeight() <= 0 || outerBitmap == null || innerBitmap == null) return;

        int effectiveRole = getEffectiveRole();
        float role = effectiveRole == ROLE_INNER ? 1f : 0f;

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
                runtimeShader.setFloatUniform("innerWidth", config.innerWidth);
                runtimeShader.setFloatUniform("scrubBars", config.scrubBars ? 1f : 0f);
                canvas.drawRect(0f, 0f, getWidth(), getHeight(), shaderPaint);
                return;
            } catch (Throwable ignored) {
                shaderReady = false;
            }
        }

        Bitmap b = effectiveRole == ROLE_INNER ? innerBitmap : outerBitmap;
        drawCenterCrop(canvas, b, fallbackPaint);
    }

    private void drawCenterCrop(Canvas canvas, Bitmap bitmap, Paint paint) {
        if (bitmap == null) return;
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

/**
 * Kept source-compatible with the previous service so the old Java file can remain in the repo
 * without causing compile errors. The new POC does not use the service or overlay path.
 */
final class VisualConfig {
    static final String PREFS = "foldblur";

    float blurStrength = 1.08f;
    float feather = 1.08f;
    float refraction = 0.82f;
    float glassMix = 0.0f;
    float smoothingMs = 0f;
    float innerWidth = 0.505f;
    boolean scrubBars = false;

    static VisualConfig defaults() {
        return new VisualConfig();
    }

    static VisualConfig load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        VisualConfig c = defaults();
        c.blurStrength = p.getFloat("blur_strength", c.blurStrength);
        c.feather = p.getFloat("feather", c.feather);
        c.refraction = p.getFloat("refraction", c.refraction);
        c.glassMix = 0f;
        c.smoothingMs = 0f;
        c.innerWidth = p.getFloat("inner_width", c.innerWidth);
        c.scrubBars = p.getBoolean("scrub_bars", c.scrubBars);
        return c;
    }

    void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putFloat("blur_strength", blurStrength)
                .putFloat("feather", feather)
                .putFloat("refraction", refraction)
                .putFloat("inner_width", innerWidth)
                .putBoolean("scrub_bars", scrubBars)
                .apply();
    }
}
