package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONObject;

/**
 * The Night scene's settings (SceneView), read from a pushed theme's config.json. Off unless a theme sets
 * "scene". Every size and position is in pixels of a 1920x1080 screen and scaled to the TV's real size, so a
 * theme can move or recolour anything without a rebuild:
 *
 *   scene                 "night" (clear dusk) or "night_rain" (overcast, drifting clouds, rain); "" = off
 *   scene_rain            0-100, how much rain (60)            scene_clouds      0-100, cloud opacity (80)
 *   scene_cloud_speed     0-200, px per second (14)            scene_wind        -30 to 30, rain angle in degrees (8)
 *   scene_ripples         rain rings on the lake (true)         scene_fps         10-60, rain frames per second (30)
 *   scene_clock_size      clock text size (400)                 scene_clock_baseline  clock baseline (450)
 *   scene_clock           "auto", "12h" or "24h" (auto follows the TV's setting)
 *   scene_date_size       (34)                                  scene_date_baseline   (127)
 *   scene_dim             0-90, darkening at night in percent (28), from scene_dim_from to scene_dim_to o'clock (21, 6)
 *   scene_dock_tile       app tile size (112)                   scene_dock_gap    gap between tiles (26)
 *   scene_dock_top        top of the tiles (900)                scene_focus_scale 100-130, focused tile zoom (116)
 *   scene_glass_brightness, scene_glass_saturation   the glass clock's fill, in percent
 *   scene_rim_color, scene_rim_fill, scene_date_color, scene_dock_tint, scene_dock_rim, scene_dock_highlight
 *                         colours as "#AARRGGBB"
 *
 * Pictures can be replaced too: a theme's scene/ folder (photo.jpg, glass.jpg, fg.webp or fg.png,
 * clouds_far.webp, clouds_near.webp) wins over the built-in ones.
 */
final class SceneConfig {

    static final String NIGHT = "night", NIGHT_RAIN = "night_rain";

    final boolean enabled;
    final boolean rain;
    final boolean animate;
    final int rainAmount, cloudAmount, cloudSpeed, wind, fps;
    final boolean ripples;
    final int clockSize, clockBaseline, dateSize, dateBaseline;
    /** -1 follows the TV's 12/24 hour setting, 0 is 12 hour, 1 is 24 hour. */
    final int clock24;
    final int dimPercent, dimFrom, dimTo;
    final int dockTile, dockGap, dockTop, focusScale;
    final int glassBrightness, glassSaturation;
    final int rimColor, rimFill, dateColor, dockTint, dockRim, dockHighlight;

    private SceneConfig(JSONObject j, boolean animate) {
        String scene = j == null ? "" : string(j, "scene", "");
        enabled = NIGHT.equals(scene) || NIGHT_RAIN.equals(scene);
        rain = NIGHT_RAIN.equals(scene);
        this.animate = animate;
        rainAmount = integer(j, "scene_rain", 60, 0, 100);
        cloudAmount = integer(j, "scene_clouds", 80, 0, 100);
        cloudSpeed = integer(j, "scene_cloud_speed", 14, 0, 200);
        wind = integer(j, "scene_wind", 8, -30, 30);
        fps = integer(j, "scene_fps", 30, 10, 60);
        ripples = bool(j, "scene_ripples", true);
        clockSize = integer(j, "scene_clock_size", 400, 100, 700);
        clockBaseline = integer(j, "scene_clock_baseline", 450, 100, 1000);
        dateSize = integer(j, "scene_date_size", 34, 12, 120);
        dateBaseline = integer(j, "scene_date_baseline", 127, 20, 1000);
        String c = j == null ? "auto" : string(j, "scene_clock", "auto");
        clock24 = "24h".equalsIgnoreCase(c) ? 1 : "12h".equalsIgnoreCase(c) ? 0 : -1;
        dimPercent = integer(j, "scene_dim", 28, 0, 90);
        dimFrom = integer(j, "scene_dim_from", 21, 0, 24);
        dimTo = integer(j, "scene_dim_to", 6, 0, 24);
        dockTile = integer(j, "scene_dock_tile", 112, 48, 240);
        dockGap = integer(j, "scene_dock_gap", 26, 0, 120);
        dockTop = integer(j, "scene_dock_top", 900, 300, 1040);
        focusScale = integer(j, "scene_focus_scale", 116, 100, 130);
        // couch-tv's Night: a pink glass; the rainy evening: a cool, grey-blue one.
        glassBrightness = integer(j, "scene_glass_brightness", rain ? 135 : 115, 30, 250);
        glassSaturation = integer(j, "scene_glass_saturation", rain ? 110 : 120, 0, 300);
        rimColor = color(j, "scene_rim_color", rain ? 0x80E1EBF8 : 0x80FFD7E1);
        rimFill = color(j, "scene_rim_fill", rain ? 0x0FD2E1F0 : 0x0FFFC8D7);
        dateColor = color(j, "scene_date_color", rain ? 0xEBE8F0FA : 0xEBFFE8EE);
        dockTint = color(j, "scene_dock_tint", rain ? 0x57141A22 : 0x521E141C);
        dockRim = color(j, "scene_dock_rim", rain ? 0x29D2E1F0 : 0x29FFD2DC);
        dockHighlight = color(j, "scene_dock_highlight", rain ? 0x24DCE8F5 : 0x24FFDCE6);
    }

    /** @param animate false (animations off, or reduced_motion) draws the scene still: no rain or cloud motion. */
    static SceneConfig load(Context ctx, boolean animate) {
        return new SceneConfig(Config.themeJson(ctx), animate);
    }

    /** True while the night darkening is on at this hour (it may run across midnight). */
    boolean dimAt(int hour) {
        if (dimPercent <= 0 || dimFrom == dimTo) return false;
        return dimFrom < dimTo ? hour >= dimFrom && hour < dimTo : hour >= dimFrom || hour < dimTo;
    }

    private static String string(JSONObject o, String key, String fallback) {
        Object v = o.opt(key);
        return v instanceof String ? ((String) v).trim() : fallback;
    }

    private static boolean bool(JSONObject o, String key, boolean fallback) {
        if (o == null) return fallback;
        Object v = o.opt(key);
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    private static int integer(JSONObject o, String key, int fallback, int min, int max) {
        if (o == null) return fallback;
        Object v = o.opt(key);
        if (!(v instanceof Number)) return fallback;
        int i = ((Number) v).intValue();
        return i < min || i > max ? fallback : i;
    }

    /** "#RRGGBB" or "#AARRGGBB"; translucent colours are kept as they are. */
    private static int color(JSONObject o, String key, int fallback) {
        if (o == null) return fallback;
        Object v = o.opt(key);
        if (!(v instanceof String)) return fallback;
        try {
            return Color.parseColor(((String) v).trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
