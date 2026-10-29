package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;

import java.io.File;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * The Night home (config.json "scene"), after couch-tv's Night look: a dusk photo, a huge glass clock behind
 * the mountain, the scenery cut out in front of it, and, for "night_rain", an overcast sky with two cloud
 * layers drifting at different speeds, rain in two depths and rings on the lake.
 *
 * Nothing here is blurred or decoded per frame. The glass is a blurred copy of the photo made ahead of time;
 * the clock is drawn into a small bitmap once a minute; the clouds are two soft half-resolution strips slid
 * sideways; the rain is a few hundred lines. About 25 MB of bitmaps in all, and the frame loop runs only while
 * Home is on screen, at scene_fps, and only when something moves.
 *
 * Sizes are designed on 1920x1080 and scaled to the view (SceneConfig).
 */
@SuppressLint("ViewConstructor")
final class SceneView extends View {

    static final float DESIGN_W = 1920f, DESIGN_H = 1080f;
    private static final String TAG = "SceneView";

    private final SceneConfig cfg;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint cloudPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint scrimPaint = new Paint();
    private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final Typeface antonio, inter;

    private Bitmap photo, glass, fg, cloudsFar, cloudsNear, clockBitmap;
    private float clockLeft, clockTop;
    private String clockText = "", dateText = "";
    private boolean dim;
    /** Design pixels to view pixels. */
    private float s = 1f;
    private boolean running, loading, released;
    private final long startMs = SystemClock.uptimeMillis();
    private Runnable onLoaded;

    // Rain, in design pixels: two layers (far, near), each drop in one of three brightness buckets so a layer
    // is drawn with three drawLines calls.
    private static final int BUCKETS = 3;
    private final Paint[] rainPaint = new Paint[2 * BUCKETS];
    private float[][] dropX = new float[2][], dropP = new float[2][], dropV = new float[2][], dropL = new float[2][];
    private int[][] dropB = new int[2][];
    private final float[][] lines = new float[2 * BUCKETS][];
    private final int[] lineCount = new int[2 * BUCKETS];
    private float[] rippleX = new float[0], rippleY = new float[0], rippleP = new float[0];
    private float tanWind;

    // The dock's frosted band (DockDecoration asks for it).
    private final Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint frostTint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frostRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint frostShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix frostMatrix = new Matrix();
    private final int[] hostLoc = new int[2], myLoc = new int[2];
    private BitmapShader frostShader;
    private Bitmap frostShaderSource;
    private float frostRimTop = Float.NaN, frostRimH = Float.NaN;

    SceneView(Context ctx, SceneConfig cfg) {
        super(ctx);
        this.cfg = cfg;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setFocusable(false);
        Resources res = ctx.getResources();
        antonio = font(res, R.font.scene_antonio_light, Typeface.create("sans-serif-condensed-light", Typeface.NORMAL));
        inter = font(res, R.font.scene_inter_medium, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        datePaint.setTypeface(inter);
        datePaint.setTextAlign(Paint.Align.CENTER);
        datePaint.setColor(cfg.dateColor);
        dimPaint.setColor(Color.argb(Math.round(cfg.dimPercent * 2.55f), 0, 0, 0));
        ripplePaint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < rainPaint.length; i++) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            rainPaint[i] = p;
        }
        frostTint.setColor(cfg.dockTint);
        frostRim.setStyle(Paint.Style.STROKE);
        frostShadow.setColor(Color.BLACK);
        tanWind = (float) Math.tan(Math.toRadians(cfg.wind));
        makeRain();
    }

    private static Typeface font(Resources res, int id, Typeface fallback) {
        try {
            Typeface t = res.getFont(id);
            return t != null ? t : fallback;
        } catch (Resources.NotFoundException e) {
            return fallback;
        }
    }

    /** Called once the pictures are in, so the dock can redraw its frosted band from the glass. */
    void setOnLoaded(Runnable r) {
        onLoaded = r;
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    /** Home is on screen: load what was released, catch up with the time, run the frame loop if anything moves. */
    void resume() {
        released = false;
        running = true;
        load();
        refreshTime();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, msToNextMinute());
        invalidate();
    }

    /** Home is covered: stop every timer. The pictures stay, so the return is instant. */
    void pause() {
        running = false;
        handler.removeCallbacks(tick);
    }

    /** Memory is short and Home is hidden: let the pictures go; resume() decodes them again. */
    void release() {
        pause();
        released = true;
        photo = glass = fg = cloudsFar = cloudsNear = clockBitmap = null;
        frostShader = null;
        frostShaderSource = null;
        clockText = "";
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshTime();
            handler.postDelayed(this, msToNextMinute());
        }
    };

    private static long msToNextMinute() {
        return 60_000L - System.currentTimeMillis() % 60_000L + 30L;
    }

    /** The clock, the date and the night darkening for now; the clock bitmap is redrawn only when the time changes. */
    void refreshTime() {
        Context ctx = getContext();
        boolean h24 = cfg.clock24 < 0 ? DateFormat.is24HourFormat(ctx) : cfg.clock24 == 1;
        Calendar now = Calendar.getInstance();
        String time = DateFormat.format(h24 ? "H:mm" : "h:mm", now).toString();
        Locale locale = Locale.getDefault();
        // couch-tv's "Thursday 29 October"; other languages get their own order of the same fields.
        String pattern = "en".equals(locale.getLanguage()) ? "EEEE d MMMM"
                : DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM");
        dateText = DateFormat.format(pattern, now).toString();
        dim = cfg.dimAt(now.get(Calendar.HOUR_OF_DAY));
        if (!time.equals(clockText) || clockBitmap == null) {
            clockText = time;
            renderClock();
        }
        invalidate();
    }

    // ---- pictures ----------------------------------------------------------------------------------

    private void load() {
        if (photo != null || loading) return;
        loading = true;
        Context app = getContext().getApplicationContext();
        File themeDir = Config.dir(app);
        File dir = themeDir == null ? null : new File(themeDir, "scene");
        boolean rain = cfg.rain;
        new Thread(() -> {
            Resources res = app.getResources();
            Bitmap p = decode(res, file(dir, "photo.jpg"), rain ? R.drawable.scene_rain_photo : R.drawable.scene_night_photo);
            Bitmap g = decode(res, file(dir, "glass.jpg"), rain ? R.drawable.scene_rain_glass : R.drawable.scene_night_glass);
            // The glass is only ever seen through text and a blurred band: half size looks the same at a quarter of the memory.
            if (g != null && g.getWidth() > 1200) g = Bitmap.createScaledBitmap(g, g.getWidth() / 2, g.getHeight() / 2, true);
            File fgFile = file(dir, "fg.webp");
            if (fgFile == null || !fgFile.isFile()) fgFile = file(dir, "fg.png");
            Bitmap f = decode(res, fgFile, rain ? R.drawable.scene_rain_fg : R.drawable.scene_night_fg);
            Bitmap cf = rain ? decode(res, file(dir, "clouds_far.webp"), R.drawable.scene_clouds_far) : null;
            Bitmap cn = rain ? decode(res, file(dir, "clouds_near.webp"), R.drawable.scene_clouds_near) : null;
            handler.post(() -> {
                loading = false;
                if (released) return;
                photo = p;
                glass = g;
                fg = f;
                cloudsFar = cf;
                cloudsNear = cn;
                clockText = "";
                refreshTime();
                if (onLoaded != null) onLoaded.run();
            });
        }, "scene-load").start();
    }

    private static File file(File dir, String name) {
        return dir == null ? null : new File(dir, name);
    }

    /** A theme's own picture when there is one that decodes, else the built-in one. */
    private static Bitmap decode(Resources res, File override, int resId) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        // 32-bit: a dark dusk sky bands in RGB_565.
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inScaled = false;
        try {
            if (override != null && override.isFile()) {
                Bitmap b = BitmapFactory.decodeFile(override.getPath(), o);
                if (b != null) return b;
                Log.w(TAG, "could not decode " + override + ", using the built-in picture");
            }
            return BitmapFactory.decodeResource(res, resId, o);
        } catch (OutOfMemoryError e) {
            Log.w(TAG, "out of memory decoding the scene");
            return null;
        }
    }

    // ---- size --------------------------------------------------------------------------------------

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        s = w / DESIGN_W;
        datePaint.setTextSize(cfg.dateSize * s);
        datePaint.setShadowLayer(9f * s, 0f, 2f * s, 0x40000000);
        float scrimStart = cfg.rain ? 0.55f : 0.60f;
        int scrimEnd = cfg.rain ? 0x8C000000 : 0x80000000;
        scrimPaint.setShader(new LinearGradient(0, h * scrimStart, 0, h, 0x00000000, scrimEnd, Shader.TileMode.CLAMP));
        ripplePaint.setStrokeWidth(Math.max(1f, s));
        for (int layer = 0; layer < 2; layer++) {
            for (int b = 0; b < BUCKETS; b++) {
                Paint p = rainPaint[layer * BUCKETS + b];
                p.setStrokeWidth(Math.max(1f, (layer == 0 ? 1f : 1.6f) * s));
                float base = layer == 0 ? 0.20f : 0.30f;
                float a = base * (0.6f + 0.4f * (b + 0.5f) / BUCKETS) * Math.min(1.4f, 0.5f + cfg.rainAmount / 120f);
                p.setColor(Color.argb(Math.round(Math.min(1f, a) * 255), 220, 230, 245));
            }
        }
        frostRimTop = Float.NaN;
        clockText = "";
        refreshTime();
    }

    // ---- the glass clock ---------------------------------------------------------------------------

    /**
     * The clock as couch-tv draws it: a soft drop shadow, the digits filled with the blurred glass photo (lined up
     * with the screen, so they read as frosted glass over the scene), a sheen from the top left and a thin rim.
     * Drawn once a minute into a bitmap the size of the digits.
     */
    private void renderClock() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || glass == null || clockText.isEmpty()) return;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(antonio);
        float size = cfg.clockSize * s;
        p.setTextSize(size);
        p.setLetterSpacing(-0.02f);
        float textW = p.measureText(clockText);
        Rect bounds = new Rect();
        p.getTextBounds(clockText, 0, clockText.length(), bounds);
        float baseline = cfg.clockBaseline * s;
        float x0 = (w - textW) / 2f;
        float pad = 48f * s;
        int left = (int) Math.max(0, Math.floor(x0 + bounds.left - pad));
        int top = (int) Math.max(0, Math.floor(baseline + bounds.top - pad));
        int right = (int) Math.min(w, Math.ceil(x0 + bounds.right + pad));
        int bottom = (int) Math.min(h, Math.ceil(baseline + bounds.bottom + pad + 6f * s));
        if (right <= left || bottom <= top) return;
        Bitmap bmp;
        try {
            bmp = Bitmap.createBitmap(right - left, bottom - top, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            return;
        }
        Canvas c = new Canvas(bmp);
        c.translate(-left, -top);

        // drop-shadow(0 6px 28px rgba(0,0,0,0.16))
        Paint shadow = new Paint(p);
        shadow.setColor(0x26000000);
        shadow.setMaskFilter(new BlurMaskFilter(Math.max(1f, 24f * s), BlurMaskFilter.Blur.NORMAL));
        c.drawText(clockText, x0, baseline + 6f * s, shadow);

        // The glass: the blurred photo, brighter and a little more saturated, at 90%.
        Paint fill = new Paint(p);
        BitmapShader shader = new BitmapShader(glass, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(w / (float) glass.getWidth(), h / (float) glass.getHeight());
        shader.setLocalMatrix(m);
        fill.setShader(shader);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(cfg.glassSaturation / 100f);
        ColorMatrix bright = new ColorMatrix();
        float k = cfg.glassBrightness / 100f;
        bright.setScale(k, k, k, 1f);
        cm.postConcat(bright);
        fill.setColorFilter(new ColorMatrixColorFilter(cm));
        fill.setAlpha(230);
        c.drawText(clockText, x0, baseline, fill);

        // The sheen: linear-gradient(160deg) across the clock's full-width line box.
        float lineH = 0.82f * size;
        float cy = baseline + bounds.top / 2f + bounds.bottom / 2f;
        double a = Math.toRadians(160);
        float dx = (float) Math.sin(a), dy = (float) -Math.cos(a);
        float half = (Math.abs(w * dx) + Math.abs(lineH * dy)) / 2f;
        float cx = w / 2f;
        Paint sheen = new Paint(p);
        sheen.setShader(new LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half,
                new int[]{0x8CFFFFFF, 0x00FFFFFF, 0x00FFFFFF, 0x38FFFFFF}, new float[]{0f, 0.38f, 0.70f, 1f},
                Shader.TileMode.CLAMP));
        c.drawText(clockText, x0, baseline, sheen);

        // The rim: a faint fill and a thin bright edge.
        Paint rimFill = new Paint(p);
        rimFill.setColor(cfg.rimFill);
        c.drawText(clockText, x0, baseline, rimFill);
        Paint rim = new Paint(p);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, 1.44f * s));
        rim.setColor(cfg.rimColor);
        c.drawText(clockText, x0, baseline, rim);

        clockBitmap = bmp;
        clockLeft = left;
        clockTop = top;
    }

    // ---- rain --------------------------------------------------------------------------------------

    private void makeRain() {
        float k = cfg.rainAmount / 60f;
        Random r = new Random(7);
        int[] counts = {Math.round(260 * k), Math.round(110 * k)};
        float[] speed = {1500f, 2300f}, length = {26f, 60f};
        for (int layer = 0; layer < 2; layer++) {
            int n = counts[layer];
            dropX[layer] = new float[n];
            dropP[layer] = new float[n];
            dropV[layer] = new float[n];
            dropL[layer] = new float[n];
            dropB[layer] = new int[n];
            for (int i = 0; i < n; i++) {
                dropX[layer][i] = r.nextFloat() * DESIGN_W;
                dropP[layer][i] = r.nextFloat() * (DESIGN_H + 400f);
                dropV[layer][i] = speed[layer] * (0.8f + r.nextFloat() * 0.4f);
                dropL[layer][i] = length[layer] * (0.7f + r.nextFloat() * 0.6f);
                dropB[layer][i] = Math.min(BUCKETS - 1, (int) (r.nextFloat() * BUCKETS));
            }
            for (int b = 0; b < BUCKETS; b++) lines[layer * BUCKETS + b] = new float[n * 4];
        }
        int rings = cfg.ripples ? Math.round(46 * Math.min(1.5f, k)) : 0;
        rippleX = new float[rings];
        rippleY = new float[rings];
        rippleP = new float[rings];
        for (int i = 0; i < rings; i++) {
            rippleX[i] = 120f + r.nextFloat() * 1680f;
            rippleY[i] = 640f + r.nextFloat() * 230f;
            rippleP[i] = r.nextFloat() * 0.9f;
        }
    }

    private void drawRain(Canvas canvas, float t) {
        float wrapH = DESIGN_H + 400f;
        for (int layer = 0; layer < 2; layer++) {
            for (int b = 0; b < BUCKETS; b++) lineCount[layer * BUCKETS + b] = 0;
            float[] xs = dropX[layer], ps = dropP[layer], vs = dropV[layer], ls = dropL[layer];
            int[] bs = dropB[layer];
            for (int i = 0; i < xs.length; i++) {
                float y = (ps[i] + t * vs[i]) % wrapH - 200f;
                float x = ((xs[i] - y * tanWind) % DESIGN_W + DESIGN_W) % DESIGN_W;
                int slot = layer * BUCKETS + bs[i];
                float[] pts = lines[slot];
                int o = lineCount[slot];
                pts[o] = x * s;
                pts[o + 1] = y * s;
                pts[o + 2] = (x + ls[i] * tanWind) * s;
                pts[o + 3] = (y - ls[i]) * s;
                lineCount[slot] = o + 4;
            }
        }
        for (int i = 0; i < lines.length; i++) {
            if (lineCount[i] > 0) canvas.drawLines(lines[i], 0, lineCount[i], rainPaint[i]);
        }
        for (int i = 0; i < rippleX.length; i++) {
            float k = ((t + rippleP[i]) % 0.9f) / 0.9f;
            float depth = (rippleY[i] - 640f) / 230f;
            float rx = (3f + 16f * k) * (0.6f + depth * 0.8f) * s;
            ripplePaint.setColor(Color.argb(Math.round(0.28f * (1f - k) * 255), 215, 228, 245));
            float cx = rippleX[i] * s, cy = rippleY[i] * s;
            canvas.drawOval(cx - rx, cy - rx * 0.22f, cx + rx, cy + rx * 0.22f, ripplePaint);
        }
    }

    // ---- drawing -----------------------------------------------------------------------------------

    private boolean moving() {
        return cfg.animate && cfg.rain && (cfg.rainAmount > 0 || cfg.cloudAmount > 0 && cfg.cloudSpeed > 0);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (photo == null) {
            canvas.drawColor(Color.BLACK);
            return;
        }
        long frameStart = SystemClock.uptimeMillis();
        float t = cfg.animate ? (frameStart - startMs) / 1000f : 0f;

        dst.set(0, 0, w, h);
        canvas.drawBitmap(photo, null, dst, bitmapPaint);

        if (cfg.rain && cfg.cloudAmount > 0) {
            float speed = cfg.cloudSpeed * s;
            drawClouds(canvas, cloudsFar, t * speed, cfg.cloudAmount / 100f * 0.95f);
            drawClouds(canvas, cloudsNear, t * speed * 2.15f, cfg.cloudAmount / 100f * 0.85f);
        }

        if (clockBitmap != null) canvas.drawBitmap(clockBitmap, clockLeft, clockTop, bitmapPaint);
        if (fg != null) canvas.drawBitmap(fg, null, dst, bitmapPaint);
        if (dim) canvas.drawRect(0, 0, w, h, dimPaint);

        if (cfg.rain && cfg.rainAmount > 0) drawRain(canvas, t);

        canvas.drawRect(0, h * 0.5f, w, h, scrimPaint);
        if (!dateText.isEmpty()) canvas.drawText(dateText, w / 2f, cfg.dateBaseline * s, datePaint);

        if (running && moving()) {
            long frameMs = 1000L / cfg.fps;
            postInvalidateDelayed(Math.max(1L, frameMs - (SystemClock.uptimeMillis() - frameStart)));
        }
    }

    /** One cloud strip, shown at 3840x600 design pixels and slid left by offset; two copies make it loop. */
    private void drawClouds(Canvas canvas, Bitmap strip, float offset, float alpha) {
        if (strip == null || alpha <= 0f) return;
        float stripW = 3840f * s, stripH = 600f * s;
        float x = -(offset % stripW);
        cloudPaint.setAlpha(Math.round(Math.min(1f, alpha) * 255));
        dst.set(x, 0, x + stripW, stripH);
        canvas.drawBitmap(strip, null, dst, cloudPaint);
        dst.set(x + stripW, 0, x + 2 * stripW, stripH);
        canvas.drawBitmap(strip, null, dst, cloudPaint);
        dst.set(0, 0, getWidth(), getHeight());
    }

    // ---- the dock's frosted band -------------------------------------------------------------------

    float frostPadH() {
        return 28f * s;
    }

    float frostPadV() {
        return 22f * s;
    }

    float frostRadius() {
        return 46f * s;
    }

    /**
     * Draws the dock band as frosted glass on the host's canvas: the glass photo behind the band's own place on
     * the screen, darkened and saturated like a backdrop blur, a tint, a rim brighter at the top and a soft shadow.
     * False until the glass is loaded; the caller then draws its plain band.
     */
    boolean drawFrost(Canvas c, RectF r, float radius, View host) {
        if (glass == null || getWidth() <= 0) return false;
        if (frostShader == null || frostShaderSource != glass) {
            frostShader = new BitmapShader(glass, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            frostShaderSource = glass;
            frostPaint.setShader(frostShader);
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(1.7f);
            ColorMatrix bright = new ColorMatrix();
            bright.setScale(0.80f, 0.80f, 0.80f, 1f);
            cm.postConcat(bright);
            frostPaint.setColorFilter(new ColorMatrixColorFilter(cm));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                frostShadow.setShadowLayer(30f * s, 0f, 24f * s, 0x47000000);
            }
        }
        host.getLocationInWindow(hostLoc);
        getLocationInWindow(myLoc);
        frostMatrix.setScale(getWidth() / (float) glass.getWidth(), getHeight() / (float) glass.getHeight());
        frostMatrix.postTranslate(myLoc[0] - hostLoc[0], myLoc[1] - hostLoc[1]);
        frostShader.setLocalMatrix(frostMatrix);
        if (r.top != frostRimTop || r.height() != frostRimH) {
            frostRim.setStrokeWidth(Math.max(1f, s));
            int top = blend(cfg.dockRim, cfg.dockHighlight);
            frostRim.setShader(new LinearGradient(0, r.top, 0, r.top + Math.min(r.height(), radius), top, cfg.dockRim,
                    Shader.TileMode.CLAMP));
            frostRimTop = r.top;
            frostRimH = r.height();
        }
        // Hardware-drawn shadows need Android 9; without one the band simply has none.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) c.drawRoundRect(r, radius, radius, frostShadow);
        c.drawRoundRect(r, radius, radius, frostPaint);
        c.drawRoundRect(r, radius, radius, frostTint);
        float in = frostRim.getStrokeWidth() / 2f;
        c.drawRoundRect(r.left + in, r.top + in, r.right - in, r.bottom - in, radius - in, radius - in, frostRim);
        return true;
    }

    /** The rim with the top highlight laid over it (two translucent whites add up). */
    private static int blend(int a, int b) {
        int alpha = Math.min(255, Color.alpha(a) + Color.alpha(b));
        return Color.argb(alpha, Math.max(Color.red(a), Color.red(b)), Math.max(Color.green(a), Color.green(b)),
                Math.max(Color.blue(a), Color.blue(b)));
    }
}
