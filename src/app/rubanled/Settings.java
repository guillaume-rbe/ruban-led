package app.rubanled;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Calendar;
import java.util.TimeZone;

/** Saved settings (one SharedPreferences file). */
final class Settings {
    /** No default: each strip has its own address, typed in the app (never committed). */
    static final String DEFAULT_ADDRESS = "";

    String address;
    boolean doorBlink;
    boolean bootAndFrunk;
    boolean hazard;
    /** Music on when CarPlay connects (CarPlayAutoPlay; owner, 17 Nov). */
    boolean autoPlay;
    int defaultHue;
    int defaultSat;
    int blinkHue;
    int blinkSat;
    int blinkPct;
    int blinkMs;
    int blinkMaxSec;
    boolean intro;
    /** How many times the entrance / exit animations play (owner, 16 Nov: 3). */
    int animRepeat;
    /**
     * Night mode, on by default (owner, 16 Nov): from nightStartWinter (19 h) in winter time and
     * nightStartSummer (21 h) in summer time until nightEnd (7 h), everything at nightPct (20 %).
     */
    boolean night;
    int nightStartWinter;
    int nightStartSummer;
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
        s.autoPlay = p.getBoolean("autoPlay", true);
        s.defaultHue = p.getInt("defaultHue", 0);
        s.defaultSat = p.getInt("defaultSat", 100);
        s.blinkHue = p.getInt("blinkHue", 0);
        s.blinkSat = p.getInt("blinkSat", 100);
        s.blinkPct = p.getInt("blinkPct", 100);
        s.blinkMs = p.getInt("blinkMs", 400);
        s.blinkMaxSec = p.getInt("blinkMaxSec", 120);
        s.intro = p.getBoolean("intro", true);
        s.animRepeat = p.getInt("animRepeat", 3);
        // new keys in 0.5 so the new defaults (on, 20 %) apply to installs that had it off
        s.night = p.getBoolean("night5", true);
        s.nightStartWinter = p.getInt("nightStartWinter", 19);
        s.nightStartSummer = p.getInt("nightStartSummer", 21);
        s.nightEnd = p.getInt("nightEnd5", 7);
        s.nightPct = p.getInt("nightPct5", 20);
        s.dayPct = p.getInt("dayPct", 100);
        return s;
    }

    void save(Context ctx) {
        prefs(ctx).edit()
            .putString("address", address)
            .putBoolean("doorBlink", doorBlink)
            .putBoolean("bootAndFrunk", bootAndFrunk)
            .putBoolean("hazard", hazard)
            .putBoolean("autoPlay", autoPlay)
            .putInt("defaultHue", defaultHue)
            .putInt("defaultSat", defaultSat)
            .putInt("blinkHue", blinkHue)
            .putInt("blinkSat", blinkSat)
            .putInt("blinkPct", blinkPct)
            .putInt("blinkMs", blinkMs)
            .putInt("blinkMaxSec", blinkMaxSec)
            .putBoolean("intro", intro)
            .putInt("animRepeat", animRepeat)
            .putBoolean("night5", night)
            .putInt("nightStartWinter", nightStartWinter)
            .putInt("nightStartSummer", nightStartSummer)
            .putInt("nightEnd5", nightEnd)
            .putInt("nightPct5", nightPct)
            .putInt("dayPct", dayPct)
            .apply();
    }

    /** Night mode on and it's night now. */
    boolean nightNow() {
        return night && isNight(Calendar.getInstance());
    }

    /** Start hour of tonight: the summer one in summer time, the winter one otherwise. */
    int nightStart(Calendar c) {
        return isSummerTime(c) ? nightStartSummer : nightStartWinter;
    }

    /** Night between the start (included) and nightEnd (excluded), across midnight if needed. */
    boolean isNight(Calendar c) {
        int hour = c.get(Calendar.HOUR_OF_DAY);
        int start = nightStart(c);
        if (start == nightEnd) return false;
        if (start < nightEnd) return hour >= start && hour < nightEnd;
        return hour >= start || hour < nightEnd;
    }

    /**
     * Summer time: the time zone's own daylight saving when it has one; otherwise (a dash set to a
     * fixed offset) the European rule, last Sunday of March to last Sunday of October.
     */
    static boolean isSummerTime(Calendar c) {
        TimeZone tz = c.getTimeZone();
        if (tz.useDaylightTime()) return tz.inDaylightTime(c.getTime());
        int month = c.get(Calendar.MONTH);
        if (month > Calendar.MARCH && month < Calendar.OCTOBER) return true;
        if (month < Calendar.MARCH || month > Calendar.OCTOBER) return false;
        int day = c.get(Calendar.DAY_OF_MONTH);
        int lastSunday = lastSunday(c);
        return month == Calendar.MARCH ? day >= lastSunday : day < lastSunday;
    }

    private static int lastSunday(Calendar c) {
        Calendar x = (Calendar) c.clone();
        x.set(Calendar.DAY_OF_MONTH, x.getActualMaximum(Calendar.DAY_OF_MONTH));
        while (x.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY) x.add(Calendar.DAY_OF_MONTH, -1);
        return x.get(Calendar.DAY_OF_MONTH);
    }

    /**
     * Last lock state heard (owner, 17 Nov: kept across an update or a restart of the app):
     * null not known, true unlocked (the owner is there), false locked. Kept apart from save(),
     * which the settings screen calls with values it read earlier.
     */
    static Boolean loadPresence(Context ctx) {
        int v = prefs(ctx).getInt("presence", -1);
        return v < 0 ? null : v == 1;
    }

    static void savePresence(Context ctx, Boolean there) {
        prefs(ctx).edit().putInt("presence", there == null ? -1 : there ? 1 : 0).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("ruban", Context.MODE_PRIVATE);
    }
}
