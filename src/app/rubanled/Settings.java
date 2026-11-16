package app.rubanled;

import android.content.Context;
import android.content.SharedPreferences;

/** Saved settings (one SharedPreferences file). */
final class Settings {
    /** No default: each strip has its own address, typed in the app (never committed). */
    static final String DEFAULT_ADDRESS = "";

    String address;
    boolean doorBlink;
    boolean bootAndFrunk;
    boolean hazard;
    int defaultHue;
    int defaultSat;
    int blinkHue;
    int blinkSat;
    int blinkPct;
    int blinkMs;
    int blinkMaxSec;
    boolean intro;
    boolean night;
    int nightStart;
    int nightEnd;
    int nightPct;
    int dayPct;

    static Settings load(Context ctx) {
        SharedPreferences p = prefs(ctx);
        Settings s = new Settings();
        s.address = p.getString("address", DEFAULT_ADDRESS);
        s.doorBlink = p.getBoolean("doorBlink", true);
        s.bootAndFrunk = p.getBoolean("bootAndFrunk", true);
        s.hazard = p.getBoolean("hazard", true);
        s.defaultHue = p.getInt("defaultHue", 0);
        s.defaultSat = p.getInt("defaultSat", 100);
        s.blinkHue = p.getInt("blinkHue", 0);
        s.blinkSat = p.getInt("blinkSat", 100);
        s.blinkPct = p.getInt("blinkPct", 100);
        s.blinkMs = p.getInt("blinkMs", 400);
        s.blinkMaxSec = p.getInt("blinkMaxSec", 120);
        s.intro = p.getBoolean("intro", true);
        s.night = p.getBoolean("night", false);
        s.nightStart = p.getInt("nightStart", 20);
        s.nightEnd = p.getInt("nightEnd", 7);
        s.nightPct = p.getInt("nightPct", 100);
        s.dayPct = p.getInt("dayPct", 100);
        return s;
    }

    void save(Context ctx) {
        prefs(ctx).edit()
            .putString("address", address)
            .putBoolean("doorBlink", doorBlink)
            .putBoolean("bootAndFrunk", bootAndFrunk)
            .putBoolean("hazard", hazard)
            .putInt("defaultHue", defaultHue)
            .putInt("defaultSat", defaultSat)
            .putInt("blinkHue", blinkHue)
            .putInt("blinkSat", blinkSat)
            .putInt("blinkPct", blinkPct)
            .putInt("blinkMs", blinkMs)
            .putInt("blinkMaxSec", blinkMaxSec)
            .putBoolean("intro", intro)
            .putBoolean("night", night)
            .putInt("nightStart", nightStart)
            .putInt("nightEnd", nightEnd)
            .putInt("nightPct", nightPct)
            .putInt("dayPct", dayPct)
            .apply();
    }

    /** Night between nightStart (included) and nightEnd (excluded), across midnight if needed. */
    boolean isNight(int hour) {
        if (nightStart == nightEnd) return false;
        if (nightStart < nightEnd) return hour >= nightStart && hour < nightEnd;
        return hour >= nightStart || hour < nightEnd;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("ruban", Context.MODE_PRIVATE);
    }
}
