package app.rubanled;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps the strip connected and applies the rules (owner, 16 Nov):
 * - mirrors unfold (the owner is there): entrance animation, 3 times, then the steady colour;
 * - mirrors fold (the owner leaves): exit animation, 3 times, then off;
 * - the mirrors say whether the owner is there: folded, nothing lights up (no door blink, no
 *   hazards, no gauge) to save energy;
 * - a door opened with the owner there: a blink (without a mirror field: the entrance animation
 *   for the first door after the car wakes); afterwards always the steady colour;
 * - charging: a green gauge, only while the mirrors are unfolded;
 * - night (19 h winter / 21 h summer to 7 h): everything at 20 %.
 * The rules run on their own high-priority thread (not the UI thread), so a door message is
 * handled at once even when the screen is busy.
 */
public final class LedService extends Service implements LedLink.Listener, DoorFeed.Listener {
    static final String ACTION_RELOAD = "app.rubanled.RELOAD";
    static final String ACTION_TEST_BLINK = "app.rubanled.TEST_BLINK";
    static final String ACTION_ON = "app.rubanled.ON";
    static final String ACTION_OFF = "app.rubanled.OFF";
    static final String ACTION_DEFAULT = "app.rubanled.DEFAULT";
    static final String ACTION_TEST_SEGMENTS = "app.rubanled.TEST_SEGMENTS";
    static final String ACTION_INTRO = "app.rubanled.INTRO";
    static final String ACTION_EXIT = "app.rubanled.EXIT";
    /** Mirror detection: 1. mirrors unfolded, 2. mirrors folded, then pick (extra "index"). */
    static final String ACTION_LEARN_OPEN = "app.rubanled.LEARN_OPEN";
    static final String ACTION_LEARN_FOLDED = "app.rubanled.LEARN_FOLDED";
    static final String ACTION_LEARN_PICK = "app.rubanled.LEARN_PICK";
    static final String ACTION_MIRROR_FORGET = "app.rubanled.MIRROR_FORGET";
    /** Extras: hue 0-359, sat 0-100, pct 1-100. Saved as the colour, applied at once. */
    static final String ACTION_SET_COLOUR = "app.rubanled.SET_COLOUR";
    /** Extra "target" of ACTION_SET_COLOUR: the steady colour, or the animations' one. */
    static final String TARGET_STEADY = "steady", TARGET_ANIM = "anim";
    /** After editing the animation colour, show it this long, then go back to the steady one. */
    private static final long ANIM_PREVIEW_MS = 2000;

    private static final String TAG = "rubanled";
    private static final String CHANNEL = "ruban2";
    private static final long NIGHT_CHECK_MS = 30000;
    private static final long TEST_BLINK_SEC = 6;
    private static final long WATCHDOG_MS = 15 * 60 * 1000L;

    // shown by MainActivity
    static volatile String linkText = "Service arrêté";
    static volatile String feedText = "";
    static volatile String doorsText = "Portes : inconnu";
    static volatile String mirrorText = "Rétroviseurs : non configurés";
    static volatile String stripText = "Ruban : état inconnu";
    static volatile String ruleText = "";
    static volatile String eventsText = "";
    static volatile String learnText = "";
    /** Mirror detection result: one line per candidate, picked with ACTION_LEARN_PICK. */
    static volatile String[] learnChoices = new String[0];

    private HandlerThread logicThread;
    private Handler main;            // the rules' thread (high priority), not the UI thread
    private Settings settings;
    private LedLink link;
    private DoorFeed doors;
    private PowerManager.WakeLock wake;

    private Zengge.State strip;      // what the strip is showing (our commands, its replies)
    /** The strip's replies describe the command before last: ignore them right after we send. */
    private static final long STALE_MS = 1500;
    private long lastSentMs;
    private int doorVal;
    private boolean doorsOpen;
    private boolean blinking;
    private boolean blinkPhase;
    private long blinkUntil;
    /** Hazard lights on (both indicators seen lit together): the strip blinks until they stop. */
    private boolean hazardOn;
    private long lastBothLitMs;
    /** Indicators flash about every 0.7 s: no "both lit" for this long means the hazards are off. */
    private static final long HAZARD_OFF_MS = 2500;

    /** Mirrors as last reported (null: not set up or not heard yet, taken as unfolded). */
    private Boolean mirrorsOpen;

    /** Charging: the strip is a green gauge of the battery % (owner, 16 Nov). */
    private boolean charging;
    private int soc = -1;
    private boolean gauge;
    private boolean gaugeTip;
    private static final int GAUGE_HUE = 120;
    private static final long GAUGE_TICK_MS = 800;

    /** Exit animation: the light withdraws, then the strip is off. */
    private boolean screenIsOn = true;
    private boolean exiting;
    private int exitFrame, exitRound;
    private int exitHue, exitSat, exitPct;
    private static final int EXIT_FRAMES = 22;
    private static final long EXIT_FRAME_MS = 150;
    /** Pause between two rounds of an animation. */
    private static final long ROUND_GAP_MS = 600;
    private Boolean lastNight;
    private boolean brightnessPending;
    private boolean intro;
    private int introFrame, introRound;
    /**
     * Armed when the car wakes (service start, dash screen on, strip powered back): the first door
     * opened then plays the start-up animation instead of blinking (owner, 16 Nov).
     */
    private boolean introPending = true;
    /** A door opened while armed but before the strip was connected: play it once connected. */
    private long introWantedMs;
    private static final long INTRO_WANTED_MS = 60000;
    private long linkLostMs;

    /** A reconnect after this long without the strip means it was powered off (car asleep). */
    private static final long STRIP_OFF_MS = 60000;
    private static final long INTRO_FRAME_MS = 170;
    /**
     * Segments actually lit: ~7.5 cm each, doors (75 cm) show 1-10, the dashboard (110 cm) 1-15
     * (owner, 16 Nov). Frames: fill them one by one with a soft leading edge, then hold.
     */
    private static final int VISIBLE_SEGMENTS = 15;
    /** Segments the doors show (1-10, from the back of the door). */
    private static final int DOOR_SEGMENTS = 10;
    /**
     * Doors fill from back to front; the dashboard fills from both ends at once and the two meet
     * where the door part ends (segment 10/11), so the doors stay a plain sweep (owner, 16 Nov).
     * Segment i starts at start(i) in 0..1, and fades in over INTRO_FADE.
     */
    private static final double INTRO_FADE = 0.12;
    private static final int INTRO_FRAMES = 28;

    /** Mirror detection: values when unfolded (snapshot 1), and the candidates found. */
    private Map<String, String> learnOpen;
    private final List<String[]> learnFound = new ArrayList<>();   // {code:key, open, folded}
    private static final long LEARN_READ_MS = 3000;
    private static final long LEARN_TIMEOUT_MS = 5 * 60 * 1000L;
    /** A field that changed more often than this during the detection is noise, not the mirrors. */
    private static final int LEARN_MAX_CHANGES = 6;

    private static double introStart(int i) {
        if (i < DOOR_SEGMENTS) return (double) i / DOOR_SEGMENTS;                          // 0 .. 0.9
        return (double) (VISIBLE_SEGMENTS - 1 - i) / (VISIBLE_SEGMENTS - DOOR_SEGMENTS);   // 0.8 .. 0
    }

    /** Runs the calling thread at a high priority (the rules' and the Bluetooth link's threads). */
    static void boost() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        } catch (Throwable t) {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY); } catch (Throwable ignored) {}
        }
    }

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // the next door opened plays the animation
            introPending = true;
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                // the dash sleeps with the car: the strip (glovebox USB, powered while the car is
                // awake, Sentry included) withdraws and goes dark until a door is opened, unless
                // the car is charging with the owner there (the gauge stays)
                event("Écran éteint");
                screenIsOn = false;
                main.removeCallbacks(previewEnd);
                main.removeCallbacks(introFinal);
                if (charging && present()) showGauge();
                else if (!exiting) playExit();
            } else {
                event("Écran allumé");
                screenIsOn = true;
                if (exiting && !folded()) {
                    exiting = false;
                    main.removeCallbacks(exitTick);
                    power(false);
                }
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        startForeground(1, notification());
        logicThread = new HandlerThread("led-rules");
        logicThread.start();
        main = new Handler(logicThread.getLooper());
        main.post(new Runnable() {
            @Override public void run() { boost(); }
        });
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rubanled:animation");
        wake.setReferenceCounted(false);
        settings = Settings.load(this);
        link = new LedLink(this, this);
        doors = new DoorFeed(this, this, logicThread.getLooper());
        applyMirrorSettings();
        link.start(settings.address);
        doors.start();
        main.post(nightCheck);
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenOn, screen, null, main);
        watchdog();
        main.post(new Runnable() {
            @Override public void run() { event("Service démarré"); }
        });
    }

    /** Restarts the service if Android or the unit's power saver stopped it. */
    private void watchdog() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getForegroundService(this, 7, new Intent(this, LedService.class), 0);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + WATCHDOG_MS,
                WATCHDOG_MS, pi);
        } catch (Throwable t) {
            Log.w(TAG, "watchdog", t);
        }
    }

    @Override public int onStartCommand(final Intent intent, int flags, int startId) {
        main.post(new Runnable() {
            @Override public void run() { handle(intent); }
        });
        return START_STICKY;
    }

    private void handle(Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_RELOAD.equals(a)) {
            settings = Settings.load(this);
            link.start(settings.address);
            applyMirrorSettings();
            lastNight = null;          // re-apply the brightness rule with the new hours
            main.removeCallbacks(nightCheck);
            main.post(nightCheck);
        } else if (ACTION_TEST_BLINK.equals(a)) {
            startBlink(TEST_BLINK_SEC);
        } else if (ACTION_ON.equals(a)) {
            stopAll();
            power(true);
        } else if (ACTION_OFF.equals(a)) {
            stopAll();
            power(false);
        } else if (ACTION_TEST_SEGMENTS.equals(a)) {
            // segments 1-7 red, 8-14 green, 15-20 blue: shows how the 20 segments map to the strips
            stopAll();
            int[] hues = new int[Zengge.SEGMENTS];
            for (int i = 0; i < hues.length; i++) hues[i] = i < 7 ? 0 : i < 14 ? 120 : 240;
            power(true);
            link.send(Zengge.segments(hues, 100));
            lastSentMs = SystemClock.elapsedRealtime();
        } else if (ACTION_INTRO.equals(a)) {
            playIntro();
        } else if (ACTION_EXIT.equals(a)) {
            if (strip == null || !strip.on) { power(true); colour(settings.defaultHue, settings.defaultSat, targetPct(), true); }
            playExit();
        } else if (ACTION_LEARN_OPEN.equals(a)) {
            learnStart();
        } else if (ACTION_LEARN_FOLDED.equals(a)) {
            learnFolded();
        } else if (ACTION_LEARN_PICK.equals(a) && intent != null) {
            learnPick(intent.getIntExtra("index", -1));
        } else if (ACTION_MIRROR_FORGET.equals(a)) {
            learnStop("");
            settings.mirrorCode = -1;
            settings.mirrorKey = settings.mirrorOpen = settings.mirrorFolded = "";
            settings.save(this);
            applyMirrorSettings();
        } else if (ACTION_SET_COLOUR.equals(a) && intent != null) {
            if (TARGET_ANIM.equals(intent.getStringExtra("target"))) {
                setAnimColour(intent.getIntExtra("hue", settings.blinkHue), intent.getIntExtra("sat", settings.blinkSat),
                    intent.getIntExtra("pct", settings.blinkPct));
            } else {
                setColour(intent.getIntExtra("hue", settings.defaultHue), intent.getIntExtra("sat", settings.defaultSat),
                    intent.getIntExtra("pct", settings.dayPct));
            }
        } else if (ACTION_DEFAULT.equals(a)) {
            stopAll();
            steady();
        }
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screenOn); } catch (Throwable ignored) {}
        doors.stop();
        link.quit();
        if (wake.isHeld()) wake.release();
        logicThread.quitSafely();
        linkText = "Service arrêté";
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- state ----

    /** The owner is there: mirrors unfolded, or not known (not set up: the old rules). */
    private boolean present() {
        return mirrorsOpen == null || mirrorsOpen;
    }

    private boolean folded() {
        return mirrorsOpen != null && !mirrorsOpen;
    }

    private void applyMirrorSettings() {
        if (settings.mirrorsSetUp()) {
            doors.setMirror(settings.mirrorCode, settings.mirrorKey, settings.mirrorOpen, settings.mirrorFolded);
            if (mirrorsOpen == null) mirrorText = "Rétroviseurs : en attente de l'app CAN";
        } else {
            doors.setMirror(-1, "", "", "");
            mirrorsOpen = null;
            mirrorText = "Rétroviseurs : non configurés (règles écran et portes)";
        }
    }

    /** Animation brightness: the animation colour's, at most the night one at night. */
    private int animPct() {
        return settings.nightNow() ? Math.min(settings.blinkPct, settings.nightPct) : settings.blinkPct;
    }

    private int repeats() {
        return Math.max(1, Math.min(10, settings.animRepeat));
    }

    /** Keeps the CPU awake while an animation runs (screen off, the car still awake). */
    private void awake(long ms) {
        try { wake.acquire(ms); } catch (Throwable ignored) {}
    }

    // ---- rules ----

    /** New animation colour: saved, shown briefly on the strip, then back to the steady colour. */
    private void setAnimColour(int hue, int sat, int pct) {
        settings.blinkHue = Math.max(0, Math.min(359, hue));
        settings.blinkSat = Math.max(0, Math.min(100, sat));
        settings.blinkPct = Math.max(1, Math.min(100, pct));
        settings.save(this);
        if (blinking || intro || exiting) return;     // the running animation picks it up
        if (strip == null || !strip.on) power(true);
        colour(settings.blinkHue, settings.blinkSat, animPct(), false);
        main.removeCallbacks(previewEnd);
        main.postDelayed(previewEnd, ANIM_PREVIEW_MS);
    }

    private final Runnable previewEnd = new Runnable() {
        @Override public void run() {
            if (blinking || intro || exiting) return;
            colour(settings.defaultHue, settings.defaultSat, targetPct(), true);
        }
    };

    /** New colour from the wheel: saved, and shown now unless an animation is running. */
    private void setColour(int hue, int sat, int pct) {
        settings.defaultHue = Math.max(0, Math.min(359, hue));
        settings.defaultSat = Math.max(0, Math.min(100, sat));
        settings.dayPct = Math.max(1, Math.min(100, pct));
        settings.save(this);
        if (blinking || intro || exiting) return;      // the animation ends on the new colour
        if (strip == null || !strip.on) power(true);
        colour(settings.defaultHue, settings.defaultSat, targetPct(), true);
    }

    /** The steady colour at the brightness of the hour. */
    private void steady() {
        // power first: "on" after a colour puts the strip back to white
        if (strip == null || !strip.on) power(true);
        colour(settings.defaultHue, settings.defaultSat, targetPct(), true);
        main.removeCallbacks(introFinal);
        // a second time: if a frame was lost, the strip must not stay half lit
        main.postDelayed(introFinal, 700);
        queryLater();
    }

    /**
     * After any animation (owner, 16 Nov): always the steady colour; the green gauge instead while
     * charging; off if the mirrors are folded (the owner has left).
     */
    private void afterAnimation() {
        ruleText = "";
        if (folded()) {
            ruleText = "Rétroviseurs repliés : ruban éteint";
            power(false);
            return;
        }
        if (charging) { showGauge(); return; }
        if (!screenIsOn && mirrorsOpen == null) {
            // no mirror field: the screen tells whether the car sleeps
            power(false);
            return;
        }
        steady();
    }

    /** A door opened while armed: the start-up animation (now, or once the strip connects). */
    private void maybeIntro() {
        if (!link.isReady()) {
            introWantedMs = SystemClock.elapsedRealtime();
            event("Ruban pas encore connecté : animation à la connexion");
            awake(INTRO_WANTED_MS);
            return;
        }
        playIntro();
    }

    /** Start-up animation, 3 times: the strip lights up progressively, then the steady colour. */
    private void playIntro() {
        if (!link.isReady()) {
            introWantedMs = SystemClock.elapsedRealtime();
            return;
        }
        introPending = false;
        introWantedMs = 0;
        gauge = false;
        main.removeCallbacks(gaugeTick);
        exiting = false;
        main.removeCallbacks(exitTick);
        blinking = false;
        main.removeCallbacks(blinkTick);
        main.removeCallbacks(introFinal);
        main.removeCallbacks(previewEnd);
        ruleText = "Animation d'allumage";
        intro = true;
        introFrame = 0;
        introRound = 0;
        main.removeCallbacks(introTick);
        link.clearQueue();
        awake(repeats() * (INTRO_FRAMES * INTRO_FRAME_MS + ROUND_GAP_MS) + 3000);
        power(true);
        main.post(introTick);
        event("Animation d'allumage ×" + repeats());
    }

    private final Runnable introTick = new Runnable() {
        @Override public void run() {
            if (!intro) return;
            int pct = targetPct();    // the entrance runs in the steady (ambient) colour
            if (introFrame >= INTRO_FRAMES) {
                introRound++;
                if (introRound < repeats()) {
                    introFrame = 0;   // the next round starts dark
                    main.postDelayed(this, ROUND_GAP_MS);
                    return;
                }
                intro = false;
                afterAnimation();
                return;
            }
            int[] vals = new int[Zengge.SEGMENTS];
            for (int i = 0; i < vals.length; i++) {
                if (i >= VISIBLE_SEGMENTS) { vals[i] = pct; continue; }   // not shown anywhere
                double t = introFrame * (0.9 + INTRO_FADE) / (INTRO_FRAMES - 1);
                double f = (t - introStart(i)) / INTRO_FADE;
                vals[i] = f <= 0 ? 0 : f >= 1 ? pct : (int) Math.round(pct * f);
            }
            link.send(Zengge.levels(settings.defaultHue, settings.defaultSat, vals));
            lastSentMs = SystemClock.elapsedRealtime();
            introFrame++;
            main.postDelayed(this, INTRO_FRAME_MS);
        }
    };

    private final Runnable introFinal = new Runnable() {
        @Override public void run() {
            if (blinking || intro || exiting || gauge || folded()) return;
            colour(settings.defaultHue, settings.defaultSat, targetPct(), true);
        }
    };

    private void startBlink(long maxSec) {
        gauge = false;
        main.removeCallbacks(gaugeTick);
        exiting = false;
        main.removeCallbacks(exitTick);
        intro = false;
        main.removeCallbacks(introFinal);
        main.removeCallbacks(introTick);
        main.removeCallbacks(previewEnd);
        if (!blinking) {
            blinking = true;
            blinkPhase = false;
            link.clearQueue();
            power(true);
            main.post(blinkTick);
        }
        blinkUntil = SystemClock.elapsedRealtime() + maxSec * 1000;
        awake(maxSec * 1000 + 3000);
        ruleText = "Clignotement en cours";
    }

    private final Runnable blinkTick = new Runnable() {
        @Override public void run() {
            if (!blinking) return;
            if (!hazardOn && SystemClock.elapsedRealtime() > blinkUntil) {
                stopBlink();
                return;
            }
            blinkPhase = !blinkPhase;
            colour(settings.blinkHue, settings.blinkSat, blinkPhase ? animPct() : 1, false);
            main.postDelayed(this, Math.max(150, settings.blinkMs));
        }
    };

    /** Stop blinking: the steady colour (or off / the gauge, see afterAnimation). */
    private void stopBlink() {
        if (!blinking) return;
        blinking = false;
        main.removeCallbacks(blinkTick);
        link.clearQueue();
        afterAnimation();
    }

    /** Stops every animation and the gauge, without putting anything back. */
    private void stopAll() {
        gauge = false;
        main.removeCallbacks(gaugeTick);
        exiting = false;
        main.removeCallbacks(exitTick);
        hazardOn = false;
        main.removeCallbacks(hazardCheck);
        intro = false;
        main.removeCallbacks(introTick);
        main.removeCallbacks(introFinal);
        main.removeCallbacks(previewEnd);
        blinking = false;
        main.removeCallbacks(blinkTick);
        link.clearQueue();
        ruleText = "";
    }

    private void power(boolean on) {
        link.send(Zengge.power(on));
        lastSentMs = SystemClock.elapsedRealtime();
        if (strip == null) strip = new Zengge.State();
        strip.on = on;
        stripText = "Ruban : " + strip;
    }

    /** track: remember it as the strip's colour (not for blink frames). */
    private void colour(int hue, int sat, int val, boolean track) {
        link.send(Zengge.colour(hue, sat, val));
        lastSentMs = SystemClock.elapsedRealtime();
        if (!track) return;
        if (strip == null) strip = new Zengge.State();
        strip.hasColour = true;
        strip.hue = hue;
        strip.sat = sat;
        strip.val = val;
        stripText = "Ruban : " + strip;
    }

    /** Ask the strip once things have settled, so its answer is current. */
    private void queryLater() {
        main.removeCallbacks(query);
        main.postDelayed(query, STALE_MS + 500);
    }

    private final Runnable query = new Runnable() {
        @Override public void run() { link.query(); }
    };

    private int targetPct() {
        return settings.nightNow() ? settings.nightPct : settings.dayPct;
    }

    private final Runnable nightCheck = new Runnable() {
        @Override public void run() {
            boolean night = settings.nightNow();
            if (lastNight == null || night != lastNight) {
                lastNight = night;
                if (settings.night) brightnessPending = true;
            }
            applyBrightness();
            main.postDelayed(this, NIGHT_CHECK_MS);
        }
    };

    /** At a night/day change: same colour, brightness of the hour (once; manual changes stay). */
    private void applyBrightness() {
        if (!brightnessPending || !link.isReady() || gauge || exiting) return;
        if (blinking || intro) {
            brightnessPending = false;     // the animation ends on the brightness of the hour
            return;
        }
        if (strip == null) return;     // wait for the strip to tell us its colour
        brightnessPending = false;
        int pct = targetPct();
        if (strip.on && strip.hasColour && strip.val != pct) {
            colour(strip.hue, strip.sat, pct, true);
            Log.i(TAG, "brightness " + pct + "%");
        }
    }

    // ---- callbacks ----

    @Override public void onLinkState(final boolean ready, final String text) {
        main.post(new Runnable() {
            @Override public void run() {
                if (!text.equals(linkText)) event(text);
                linkText = text;
                long now = SystemClock.elapsedRealtime();
                if (ready) {
                    if (linkLostMs != 0 && now - linkLostMs > STRIP_OFF_MS) introPending = true;
                    linkLostMs = 0;
                    if (introWantedMs != 0 && now - introWantedMs < INTRO_WANTED_MS) playIntro();
                    introWantedMs = 0;
                    if (charging && present() && !gauge && !blinking && !intro && !exiting) showGauge();
                    applyBrightness();
                } else if (linkLostMs == 0) {
                    linkLostMs = now;
                }
            }
        });
    }

    @Override public void onStripState(final Zengge.State s) {
        main.post(new Runnable() {
            @Override public void run() {
                if (SystemClock.elapsedRealtime() - lastSentMs < STALE_MS) return;
                strip = s;
                stripText = "Ruban : " + s;
                applyBrightness();
            }
        });
    }

    @Override public void onDoors(int v) {
        int mask = settings.bootAndFrunk ? 0xfc : 0xf0;
        int opened = v & ~doorVal & mask;     // each newly opened lid counts, not only the first
        doorVal = v;
        boolean open = (v & mask) != 0;
        doorsText = "Portes : " + (v == 0 ? "fermées" : describe(v));
        doorsOpen = open;
        if (opened != 0) {
            event(describe(opened));
            if (intro) return;                          // the entrance animation goes on
            // the mirrors tell whether the owner is there (owner, 16 Nov): away, the strip stays
            // off to save energy; there, the arrival animation came with the mirrors, so a blink
            if (folded()) { event("Absent (rétros repliés) : ruban éteint"); return; }
            if (mirrorsOpen != null) { if (settings.doorBlink) startBlink(settings.blinkMaxSec); return; }
            if (introPending && settings.intro) maybeIntro();     // no mirror field: the first door
            else if (settings.doorBlink) startBlink(settings.blinkMaxSec);
        } else if (!open && !hazardOn) {
            stopBlink();
        }
    }

    @Override public void onMirrors(boolean open) {
        mirrorText = "Rétroviseurs : " + (open ? "dépliés" : "repliés");
        if (mirrorsOpen != null && mirrorsOpen == open) return;
        Boolean before = mirrorsOpen;
        mirrorsOpen = open;
        if (before == null) return;          // first reading: just note it
        event(open ? "Rétroviseurs dépliés" : "Rétroviseurs repliés");
        if (open) {
            playIntro();                     // the owner is back: lit, then the steady colour
        } else {
            if (strip == null || !strip.on) { power(false); return; }
            playExit();                      // the owner leaves: withdraw, then off
        }
    }

    @Override public void onIndicators(boolean left, boolean right) {
        if (!left || !right) return;            // one side alone is a turn signal
        lastBothLitMs = SystemClock.elapsedRealtime();
        if (hazardOn || !settings.hazard || folded()) return;     // away: no light
        hazardOn = true;
        event("Warnings");
        startBlink(settings.blinkMaxSec);
        ruleText = "Warnings : clignotement";
        main.removeCallbacks(hazardCheck);
        main.postDelayed(hazardCheck, 500);
    }

    private final Runnable hazardCheck = new Runnable() {
        @Override public void run() {
            if (!hazardOn) return;
            if (SystemClock.elapsedRealtime() - lastBothLitMs < HAZARD_OFF_MS) {
                main.postDelayed(this, 500);
                return;
            }
            hazardOn = false;
            if (doorsOpen && settings.doorBlink) {
                blinkUntil = SystemClock.elapsedRealtime() + settings.blinkMaxSec * 1000L;   // the door blink goes on
            } else {
                stopBlink();
            }
        }
    };

    @Override public void onBattery(int percent, boolean chg) {
        if (percent >= 0) soc = percent;
        if (chg == charging) return;
        charging = chg;
        event(charging ? "Recharge" : "Fin de recharge");
        if (charging) {
            showGauge();
        } else if (gauge) {
            gauge = false;
            main.removeCallbacks(gaugeTick);
            ruleText = "";
            if (screenIsOn && present()) steady();
            else playExit();
        }
    }

    /** Green gauge of the battery % while charging, only with the owner there (mirrors unfolded). */
    private void showGauge() {
        if (blinking || intro || !link.isReady() || !present()) return;
        exiting = false;
        main.removeCallbacks(exitTick);
        if (!gauge) power(true);
        gauge = true;
        ruleText = "Recharge : jauge de batterie";
        main.removeCallbacks(gaugeTick);
        main.post(gaugeTick);
    }

    private final Runnable gaugeTick = new Runnable() {
        @Override public void run() {
            if (!gauge) return;
            gaugeTip = !gaugeTip;
            int pct = targetPct();
            // segment 1 is at the right end of the dashboard (and the back of the doors)
            int lit = soc < 0 ? VISIBLE_SEGMENTS : Math.max(1, Math.round(soc * VISIBLE_SEGMENTS / 100f));
            int[] vals = new int[Zengge.SEGMENTS];
            for (int i = 0; i < VISIBLE_SEGMENTS && i < lit; i++) vals[i] = pct;
            if (lit < VISIBLE_SEGMENTS || soc < 100) vals[lit - 1] = gaugeTip ? pct : Math.max(1, pct / 4);
            link.send(Zengge.levels(GAUGE_HUE, 100, vals));
            lastSentMs = SystemClock.elapsedRealtime();
            main.postDelayed(this, GAUGE_TICK_MS);
        }
    };

    /** Exit, 3 times: the light withdraws in the reverse order of the entrance, then off. */
    private void playExit() {
        boolean wasGauge = gauge;
        stopAll();
        if (!link.isReady() || strip == null || !strip.on) {
            power(false);
            ruleText = "Ruban éteint";
            return;
        }
        exitHue = wasGauge ? settings.defaultHue : strip.hasColour ? strip.hue : settings.defaultHue;
        exitSat = wasGauge ? settings.defaultSat : strip.hasColour ? strip.sat : settings.defaultSat;
        exitPct = wasGauge || !strip.hasColour ? targetPct() : strip.val;
        if (exitPct < 5) exitPct = targetPct();
        exiting = true;
        exitFrame = 0;
        exitRound = 0;
        ruleText = "Animation d'extinction";
        awake(repeats() * (EXIT_FRAMES * EXIT_FRAME_MS + ROUND_GAP_MS) + 3000);
        main.post(exitTick);
        event("Animation d'extinction ×" + repeats());
    }

    private final Runnable exitTick = new Runnable() {
        @Override public void run() {
            if (!exiting) return;
            if (exitFrame >= EXIT_FRAMES) {
                exitRound++;
                if (exitRound < repeats()) {
                    exitFrame = 0;    // the next round starts fully lit
                    main.postDelayed(this, ROUND_GAP_MS);
                    return;
                }
                exiting = false;
                power(false);
                ruleText = "Ruban éteint";
                return;
            }
            double t = exitFrame * (0.9 + INTRO_FADE) / (EXIT_FRAMES - 1);
            int[] vals = new int[Zengge.SEGMENTS];
            for (int i = 0; i < vals.length; i++) {
                double start = i < VISIBLE_SEGMENTS ? 0.9 - introStart(i) : 0;   // last lit, first out
                double f = (t - start) / INTRO_FADE;
                vals[i] = f <= 0 ? exitPct : f >= 1 ? 0 : (int) Math.round(exitPct * (1 - f));
            }
            link.send(Zengge.levels(exitHue, exitSat, vals));
            lastSentMs = SystemClock.elapsedRealtime();
            exitFrame++;
            main.postDelayed(this, EXIT_FRAME_MS);
        }
    };

    @Override public void onFeedState(String text) {
        feedText = text;
    }

    // ---- mirror detection ----

    /** Step 1, mirrors unfolded: read every field for a few seconds, then keep their values. */
    private void learnStart() {
        main.removeCallbacks(learnSnapshot);
        main.removeCallbacks(learnTimeout);
        learnFound.clear();
        learnChoices = new String[0];
        doors.setLearning(false);
        doors.setLearning(true);
        learnText = "Lecture des données… (3 s)";
        main.postDelayed(learnSnapshot, LEARN_READ_MS);
        main.postDelayed(learnTimeout, LEARN_TIMEOUT_MS);
    }

    private final Runnable learnSnapshot = new Runnable() {
        @Override public void run() {
            learnOpen = new HashMap<>(doors.learnValues);
            learnText = learnOpen.size() + " données lues. Repliez les rétroviseurs (écran de la Tesla), "
                + "attendez qu'ils soient repliés, puis touchez « 2. Rétros repliés ».";
        }
    };

    private final Runnable learnTimeout = new Runnable() {
        @Override public void run() { learnStop("Détection annulée (5 min sans fin)"); }
    };

    /** Step 2, mirrors folded: the fields that changed since step 1, steadiest first. */
    private void learnFolded() {
        if (!doors.isLearning() || learnOpen == null) {
            learnText = "Touchez d'abord « 1. Rétros dépliés » (rétroviseurs dépliés).";
            return;
        }
        learnFound.clear();
        final Map<String, Integer> changes = doors.learnChanges;
        for (Map.Entry<String, String> e : doors.learnValues.entrySet()) {
            String before = learnOpen.get(e.getKey());
            if (e.getValue().equals(before)) continue;
            Integer n = changes.get(e.getKey());
            if (n != null && n > LEARN_MAX_CHANGES) continue;
            learnFound.add(new String[] {e.getKey(), before == null ? "" : before, e.getValue()});
        }
        Collections.sort(learnFound, new Comparator<String[]>() {
            @Override public int compare(String[] a, String[] b) {
                Integer x = changes.get(a[0]), y = changes.get(b[0]);
                return (x == null ? 0 : x) - (y == null ? 0 : y);
            }
        });
        while (learnFound.size() > 5) learnFound.remove(learnFound.size() - 1);
        String[] lines = new String[learnFound.size()];
        for (int i = 0; i < lines.length; i++) {
            String[] c = learnFound.get(i);
            String[] id = c[0].split(":", 2);
            lines[i] = "Code " + id[0] + " · " + id[1] + " : " + (c[1].isEmpty() ? "?" : c[1]) + " → " + c[2];
        }
        learnChoices = lines;
        learnText = lines.length == 0
            ? "Aucune donnée n'a changé : l'app CAN ne publie peut-être pas l'état des rétroviseurs. "
                + "Le ruban garde les règles écran / portes."
            : "Choisissez la donnée des rétroviseurs (en général la première) :";
        if (lines.length == 0) learnStop(learnText);
    }

    private void learnPick(int index) {
        if (index < 0 || index >= learnFound.size()) return;
        String[] c = learnFound.get(index);
        String[] id = c[0].split(":", 2);
        try {
            settings.mirrorCode = Integer.parseInt(id[0]);
        } catch (NumberFormatException e) {
            return;
        }
        settings.mirrorKey = id[1];
        settings.mirrorOpen = c[1];
        settings.mirrorFolded = c[2];
        settings.save(this);
        learnStop("Enregistré : code " + id[0] + " « " + id[1] + " » (dépliés " + (c[1].isEmpty() ? "autre" : c[1])
            + ", repliés " + c[2] + ")");
        mirrorsOpen = null;
        applyMirrorSettings();
        onMirrors(false);      // they are folded right now (step 2)
    }

    private void learnStop(String text) {
        main.removeCallbacks(learnSnapshot);
        main.removeCallbacks(learnTimeout);
        doors.setLearning(false);
        learnOpen = null;
        learnFound.clear();
        learnChoices = new String[0];
        learnText = text;
    }

    // ---- helpers ----

    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss", Locale.FRANCE);
    private final ArrayList<String> events = new ArrayList<>();

    /** Recent events, shown in the app (helps to see whether a door message arrived late). */
    private void event(String what) {
        events.add(0, CLOCK.format(new Date()) + "  " + what);
        while (events.size() > 8) events.remove(events.size() - 1);
        StringBuilder b = new StringBuilder();
        for (String e : events) b.append(b.length() == 0 ? "" : "\n").append(e);
        eventsText = b.toString();
        Log.i(TAG, what);
    }

    private static String describe(int v) {
        StringBuilder b = new StringBuilder();
        if ((v & 0x80) != 0) b.append("conducteur ");
        if ((v & 0x40) != 0) b.append("passager ");
        if ((v & 0x20) != 0) b.append("arrière droite ");
        if ((v & 0x10) != 0) b.append("arrière gauche ");
        if ((v & 0x08) != 0) b.append("coffre ");
        if ((v & 0x04) != 0) b.append("frunk ");
        return b.length() == 0 ? "0x" + Integer.toHexString(v) : b.toString().trim() + " ouvert";
    }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.deleteNotificationChannel("ruban");
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Ruban LED", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
        return b.setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Ruban LED actif")
            .setOngoing(true)
            .setContentIntent(open)
            .build();
    }

    static void send(Context ctx, String action) {
        Intent i = new Intent(ctx, LedService.class);
        if (action != null) i.setAction(action);
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
        else ctx.startService(i);
    }
}
