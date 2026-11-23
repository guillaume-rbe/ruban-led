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
import java.util.Date;
import java.util.Locale;

/**
 * Keeps the strip connected and applies the rules (owner, 16-17 Nov):
 * - the car unlocks (the owner is back): entrance animation, 3 times, in the steady (ambient)
 *   colour, then the steady colour;
 * - the car locks (the owner leaves): exit animation, 3 times, then off;
 * - locked, nothing lights up (no hazards, no gauge) to save energy;
 * - a door opened with the owner there: a blink in the alert colour (red), the only use of red
 *   with the hazards; afterwards always the steady colour;
 * - charging: a green gauge, only while the owner is there;
 * Locking is read from the indicators (code 9), the car's own lock confirmation, seen in the CAN
 * feed on 17 Nov (the mirrors aren't in it): one flash of both indicators = locked; two flashes,
 * or the parking/welcome lights coming on (postlight) = unlocked; three or more = hazards.
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
    /** Plays the Auto Hold animations one after the other, HOLD_TEST_MS each press. */
    static final String ACTION_HOLD_TEST = "app.rubanled.HOLD_TEST";
    /** Sends the journal (extra "hours": 24, or 0 for all of it); the app shows the steps (Journal). */
    static final String ACTION_SEND_LOGS = "app.rubanled.SEND_LOGS";
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
    static volatile String presenceText = "Présence : inconnue (en attente d'un verrouillage)";
    static volatile String stripText = "Ruban : état inconnu";
    static volatile String ruleText = "";
    static volatile String eventsText = "";

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

    /**
     * The owner is there (car unlocked) or not (locked), from the lock confirmation flashes.
     * null: not known yet (since the car woke), taken as there with the screen/door rules.
     */
    private Boolean ownerThere;
    /** Both indicators lit right now, and the flashes counted in the current burst. */
    private boolean bothLit;
    private int flashes;
    /** Welcome lights (postlight) on, and when the last unlock was seen. */
    private boolean parkOn;
    private long lastUnlockMs = -100000, parkOnMs = -100000;
    /** A single flash this soon after an unlock is part of the unlock, not a lock. */
    private static final long UNLOCK_GRACE_MS = 5000;
    /** A burst of flashes is over after this long without a new one (they come ~0.5 s apart). */
    private static final long BURST_END_MS = 1200;
    /** From this many flashes in one burst it is the hazards, not a lock confirmation. */
    private static final int HAZARD_FLASHES = 3;

    /** Charging: the strip is a green gauge of the battery % (owner, 16 Nov). */
    private boolean charging;
    private int soc = -1;
    private boolean gauge;
    /**
     * Gauge (owner, 18 Nov): a steady green bar of the battery % in the middle of the dashboard,
     * and from both ends of the dashboard a green fill running towards it, over and over. The
     * right one (segment 1 upwards) is a back-to-front sweep on the doors, which end in the bar.
     */
    private int gaugeFrame;
    private static final int GAUGE_HUE = 120;
    private static final long GAUGE_FRAME_MS = 170;
    private static final int GAUGE_FILL_FRAMES = 22, GAUGE_HOLD_FRAMES = 4, GAUGE_GAP_FRAMES = 3;

    /**
     * Auto Hold (owner, 22 Nov): held for HOLD_DELAY_MS, one time in settings.holdChance (random)
     * one of the HoldShow animations, picked at random (never twice in a row), plays until the car
     * moves off. It fades in from the steady colour and back to it when the hold ends.
     */
    private boolean autoHoldOn;
    private boolean apFlash;
    /** In reverse, and the reverse white is showing. */
    private boolean inReverse, reverseLit;
    private static final int AP_HUE = 212, AP_SAT = 88;
    private static final long AP_FLASH_MS = 1000;
    private boolean hold, holdEnding;
    private HoldShow holdShow;
    private int holdFrame, holdKind = -1, holdTestKind = -1;
    private double holdW;
    /** Test from the app: stops by itself after this long. */
    private long holdUntil;
    private static final long HOLD_DELAY_MS = 1000;
    private static final long HOLD_TEST_MS = 10000;
    /** Fade in over this many frames, fade back out over HOLD_OUT_FRAMES. */
    private static final int HOLD_IN_FRAMES = 6, HOLD_OUT_FRAMES = 3;
    private final java.util.Random rnd = new java.util.Random();
    private final int[] holdHues = new int[Zengge.SEGMENTS], holdSats = new int[Zengge.SEGMENTS],
        holdVals = new int[Zengge.SEGMENTS];

    /**
     * Arriving with the boot first (owner, 22 Nov): the boot (or frunk) opened since the arrival and
     * before any door blinks red as usual, and the entrance animation plays when a door opens.
     */
    private long arrivalMs = -100000000L;
    private boolean doorSinceArrival, introOwed;
    /** An arrival (unlock) counts for the boot-then-door rule this long. */
    private static final long ARRIVAL_MS = 15 * 60 * 1000L;

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


    /** Exit rounds 2 and 3: a band this wide (in the 0..0.9 sweep) runs from dark to dark. */
    private static final double BAND_WIDTH = 0.25;
    private static final int BAND_FRAMES = 28;

    private static double clamp01(double x) {
        return x < 0 ? 0 : x > 1 ? 1 : x;
    }

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
            // the car slept or woke: the lock state is heard again at the next flash (a missed
            // unlock must not keep the strip dark)
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction()) && Boolean.FALSE.equals(ownerThere)) {
                ownerThere = null;
                Settings.savePresence(LedService.this, null);
                presenceText = "Présence : inconnue (en attente d'un verrouillage)";
            }
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                // the dash sleeps with the car: the strip (glovebox USB, powered while the car is
                // awake, Sentry included) goes dark, without an animation (only lock / unlock
                // animate, owner 17 Nov), unless the car is charging with the owner there
                event("Écran éteint");
                screenIsOn = false;
                main.removeCallbacks(previewEnd);
                main.removeCallbacks(introFinal);
                if (charging && present()) showGauge();
                else if (!exiting) { stopAll(); power(false); ruleText = "Ruban éteint"; }
            } else {
                event("Écran allumé");
                screenIsOn = true;
                link.wake();            // the strip powers up with the car: connect now
                if (exiting && !away()) {
                    exiting = false;
                    main.removeCallbacks(exitTick);
                    power(false);
                }
            }
        }
    };

    /** The debug journal (0.20): events, rule decisions, CAN, Bluetooth; see Journal. */
    private Journal journal;

    @Override public void onCreate() {
        super.onCreate();
        journal = Journal.get(this);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                journal.crash(t, e);
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
        journal.log("APP", "service démarré, Ruban LED " + version() + " ; " + Journal.device());
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
        // the lock state from before the restart (an update restarts the app)
        ownerThere = Settings.loadPresence(this);
        if (ownerThere != null) {
            presenceText = "Présence : " + (ownerThere ? "déverrouillée (conducteur là)" : "verrouillée (absent)")
                + " · état d'avant le redémarrage";
        }
        link = new LedLink(this, this);
        doors = new DoorFeed(this, this, logicThread.getLooper());
        link.start(settings.address);
        doors.start();
        applyJournalSettings();
        main.post(nightCheck);
        unitThread.start();
        unit = new android.os.Handler(unitThread.getLooper());
        unitApply(0);
        applyRemote();
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenOn, screen, null, main);
        watchdog();
        autoPlay = new CarPlayAutoPlay(this, main, new CarPlayAutoPlay.Events() {
            @Override public void event(String what) { LedService.this.event(what); }
        });
        main.post(new Runnable() {
            @Override public void run() {
                event("Service démarré");
                autoPlay.setEnabled(settings.autoPlay);
            }
        });
    }

    /** The iPhone page on the CarPlay Wi-Fi (0.25), null while its switch is off. */
    private RemoteServer remote;
    private String remoteCode;

    /** Starts or stops the iPhone page as its switch says (a new code restarts it). */
    private void applyRemote() {
        String code = Settings.remoteCode(this);
        if (remote != null && (!settings.remote || !code.equals(remoteCode))) {
            remote.stop();
            remote = null;
            if (!settings.remote) event("iPhone : contrôle coupé");
        }
        if (settings.remote && remote == null) {
            remoteCode = code;
            remote = new RemoteServer(this, new RemoteServer.Host() {
                @Override public void handle(final Intent i) {
                    main.post(new Runnable() { @Override public void run() { LedService.this.handle(i); } });
                }
                @Override public void event(final String what) {
                    main.post(new Runnable() { @Override public void run() { LedService.this.event(what); } });
                }
            }, code);
            remote.start();
            event("iPhone : contrôle activé (port " + RemoteServer.PORT + ")");
        }
    }

    /** Music on when CarPlay connects (owner, 17 Nov). */
    private CarPlayAutoPlay autoPlay;

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
        // every startForegroundService() (the app, the watchdog, an update) must be answered within
        // 5 s, not only the first: on 23 Nov a restart during one was killed by Android (ANR)
        startForeground(1, notification());
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
            autoPlay.setEnabled(settings.autoPlay);
            lastNight = null;          // re-apply the brightness rule with the new hours
            main.removeCallbacks(nightCheck);
            main.post(nightCheck);
            unitApply(0);              // the dash switch may have changed
            applyRemote();             // the iPhone switch, or a new code
            applyJournalSettings();
            journal.log("APP", "réglages : " + settingsLine());
        } else if (ACTION_TEST_BLINK.equals(a)) {
            startBlink(TEST_BLINK_SEC);
        } else if (ACTION_ON.equals(a)) {
            stopAll();
            power(true, true);
        } else if (ACTION_OFF.equals(a)) {
            stopAll();
            power(false, true);
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
        } else if (ACTION_SEND_LOGS.equals(a)) {
            int hours = intent.getIntExtra("hours", 24);
            journal.log("ENVOI", "demandé (" + (hours > 0 ? hours + " h" : "tout") + ")");
            journal.send(hours, header(), new Journal.Result() {
                @Override public void done(final boolean ok, final String text) {
                    main.post(new Runnable() {
                        @Override public void run() { event(ok ? "Journaux envoyés : " + text : "Envoi des journaux impossible : " + text); }
                    });
                }
            });
        } else if (ACTION_HOLD_TEST.equals(a)) {
            stopAll();
            power(true);
            holdTestKind = (holdTestKind + 1) % HoldShow.COUNT;
            startHold(holdTestKind, HOLD_TEST_MS);
        } else if (ACTION_EXIT.equals(a)) {
            if (strip == null || !strip.on) { power(true); colour(settings.defaultHue, settings.defaultSat, targetPct(), true); }
            playExit();
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
        unit.removeCallbacksAndMessages(null);
        unitThread.quitSafely();
        try { unregisterReceiver(screenOn); } catch (Throwable ignored) {}
        autoPlay.stop();
        if (remote != null) { remote.stop(); remote = null; }
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

    /** The owner is there: unlocked, or not known yet (then the screen and door rules). */
    private boolean present() {
        return ownerThere == null || ownerThere;
    }

    /** The car is locked: the owner has left. */
    private boolean away() {
        return ownerThere != null && !ownerThere;
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

    /** Something other than the steady colour is on the strip (an animation, the gauge, a flash). */
    private boolean busy() {
        return blinking || intro || exiting || gauge || hold || apFlash || reverseLit;
    }

    /** The steady colour is showing with the owner there: a short effect (Auto Hold, Autosteer) may play. */
    /** Why the strip is busy or an effect can't play, for the journal. */
    private String busyWhy() {
        StringBuilder b = new StringBuilder();
        if (!link.isReady()) b.append("ruban pas connecté, ");
        if (!present()) b.append("voiture verrouillée, ");
        if (!screenIsOn) b.append("écran éteint, ");
        if (blinking) b.append("clignotement, ");
        if (intro) b.append("allumage, ");
        if (exiting) b.append("extinction, ");
        if (gauge) b.append("jauge, ");
        if (hold) b.append("animation d'arrêt, ");
        if (apFlash) b.append("bleu Autosteer, ");
        if (reverseLit) b.append("marche arrière, ");
        if (strip == null) b.append("état du ruban inconnu, ");
        else if (!strip.on) b.append("ruban éteint, ");
        return b.length() == 0 ? "rien" : b.substring(0, b.length() - 2);
    }

    private void trace(String what) {
        journal.log("REGLE", what);
    }

    private boolean canDecorate() {
        return link.isReady() && present() && screenIsOn && !busy() && strip != null && strip.on;
    }

    /** New animation colour: saved, shown briefly on the strip, then back to the steady colour. */
    private void setAnimColour(int hue, int sat, int pct) {
        settings.blinkHue = Math.max(0, Math.min(359, hue));
        settings.blinkSat = Math.max(0, Math.min(100, sat));
        settings.blinkPct = Math.max(1, Math.min(100, pct));
        settings.save(this);
        if (busy()) return;     // the running animation picks it up
        if (strip == null || !strip.on) power(true);
        colour(settings.blinkHue, settings.blinkSat, animPct(), false);
        main.removeCallbacks(previewEnd);
        main.postDelayed(previewEnd, ANIM_PREVIEW_MS);
    }

    private final Runnable previewEnd = new Runnable() {
        @Override public void run() {
            if (busy()) return;
            colour(settings.defaultHue, settings.defaultSat, targetPct(), true);
        }
    };

    /** New colour from the wheel: saved, and shown now unless an animation is running. */
    private void setColour(int hue, int sat, int pct) {
        settings.defaultHue = Math.max(0, Math.min(359, hue));
        settings.defaultSat = Math.max(0, Math.min(100, sat));
        settings.dayPct = Math.max(1, Math.min(100, pct));
        settings.save(this);
        if (busy()) return;      // the animation ends on the new colour
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
        probeShortLater();
    }

    /**
     * After any animation (owner, 16 Nov): always the steady colour; the green gauge instead while
     * charging; off if the car is locked (the owner has left).
     */
    private void afterAnimation() {
        trace("après animation : " + (away() ? "verrouillée → éteint" : charging ? "recharge → jauge"
            : inReverse && settings.reverseWhite ? "marche arrière → blanc" : !screenIsOn && ownerThere == null ? "écran éteint → éteint" : "couleur permanente"));
        ruleText = "";
        if (away()) {
            ruleText = "Voiture verrouillée : ruban éteint";
            power(false);
            return;
        }
        if (charging) { showGauge(); return; }
        if (inReverse && settings.reverseWhite && screenIsOn) {
            showReverse();                   // still in reverse (a door blinked): white again
            if (reverseLit) return;
        }
        if (!screenIsOn && ownerThere == null) {
            // lock state not known: the screen tells whether the car sleeps
            power(false);
            return;
        }
        steady();
    }

    /** Start-up animation, 3 times: the strip lights up progressively, then the steady colour. */
    private void playIntro() {
        trace("animation d'allumage" + (link.isReady() ? "" : " (ruban pas connecté : jouée à la connexion)"));
        if (!link.isReady()) {
            introWantedMs = SystemClock.elapsedRealtime();
            return;
        }
        introWantedMs = 0;
        stopHold();
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
            if (busy() || away()) return;
            colour(settings.defaultHue, settings.defaultSat, targetPct(), true);
        }
    };

    private void startBlink(long maxSec) {
        trace("clignotement " + maxSec + " s max" + (hazardOn ? " (warnings)" : "") + (busy() ? ", interrompt : " + busyWhy() : ""));
        stopHold();
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
        stopHold();
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
        power(on, false);
    }

    /**
     * Strip on / off. Skipped when the strip is known to be so already on this connection: one
     * BLE round trip less before the first frame (a door blink shows sooner), and no "on" after a
     * colour, which puts the strip back to white. force: always sent (the app's buttons).
     */
    private void power(boolean on, boolean force) {
        if (!force && strip != null && strip.on == on && link.isReady()) return;
        link.send(Zengge.power(on));
        lastSentMs = SystemClock.elapsedRealtime();
        if (strip == null) strip = new Zengge.State();
        strip.on = on;
        stripText = "Ruban : " + strip;
    }

    /** track: remember it as the strip's colour (not for blink frames). */
    private void colour(int hue, int sat, int val, boolean track) {
        // the short command once it is known to work on this strip, the long one otherwise
        link.send(settings.shortColour == 1 ? Zengge.shortColour(hue, sat, val) : Zengge.colour(hue, sat, val));
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
        if (blinking || intro || hold || apFlash || reverseLit) {
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

    // ---- the dash itself (0.19) ----

    /** Commands on the dash run here: they block (a socket to its ADB). */
    private final HandlerThread unitThread = new HandlerThread("unit-shell");
    private android.os.Handler unit;
    /** At start-up the dash's ADB may not be up yet: try again this often, this many times. */
    private static final long UNIT_RETRY_MS = 20000;
    private static final int UNIT_TRIES = 9;

    /**
     * Owner, 22 Nov: Google's "location off" popup at every start (Find My Device: Fermer /
     * Activer la localisation). Location goes on in network-only mode, without GPS (no module
     * plugged in), at every start of the app (the firmware may reset it at boot). Turned off in the
     * app: the mode the dash had before is put back. The screen in front is logged again a bit
     * later, to name the popup if it still shows.
     */
    private void unitApply(final int attempt) {
        unit.removeCallbacksAndMessages(null);
        unit.postDelayed(new Runnable() {
            @Override public void run() {
                boolean want = settings.unitQuiet;
                boolean applied = Settings.loadUnitApplied(LedService.this);
                if (!want && !applied) return;               // never touched: nothing to do
                try {
                    String before = UnitShell.run(UnitShell.Command.STATE);
                    if (want) {
                        if (!applied) Settings.saveUnitModeBefore(LedService.this, modeOf(before));
                        UnitShell.run(UnitShell.Command.LOCATION_DEVICE_ONLY);
                    } else {
                        int old = Settings.loadUnitModeBefore(LedService.this);
                        if (old >= 0) UnitShell.run(UnitShell.Command.LOCATION_MODE, old);
                    }
                    Settings.saveUnitApplied(LedService.this, want);
                    final String after = UnitShell.run(UnitShell.Command.STATE);
                    final String line = (want ? "Écran : position appareil seul (GPS)" : "Écran : position remise comme avant")
                        + " (avant " + before + " ; après " + after + ")";
                    main.post(new Runnable() { @Override public void run() { event(line); } });
                    if (want && attempt == 0) unitLogLater(45000);
                } catch (final Throwable t) {
                    Log.w(TAG, "unit shell", t);
                    if (attempt + 1 < UNIT_TRIES) { unitApply(attempt + 1); return; }
                    main.post(new Runnable() {
                        @Override public void run() { event("Écran : réglage GPS / alertes impossible (" + t.getMessage() + ")"); }
                    });
                }
            }
        }, attempt == 0 ? 3000 : UNIT_RETRY_MS);
    }

    /** Mode in a STATE line ("mode=2 ..."), -1 if not readable. */
    private static int modeOf(String state) {
        try {
            int i = state.indexOf("mode=");
            return i < 0 ? -1 : Integer.parseInt(state.substring(i + 5, i + 6));
        } catch (Throwable t) {
            return -1;
        }
    }

    /** The state and the screen in front, once, a bit after start-up (the popup came at start). */
    private void unitLogLater(long delayMs) {
        unit.postDelayed(new Runnable() {
            @Override public void run() {
                try {
                    final String now = UnitShell.run(UnitShell.Command.STATE);
                    main.post(new Runnable() { @Override public void run() { event("Écran après démarrage : " + now); } });
                } catch (Throwable ignored) {}
            }
        }, delayMs);
    }

    // ---- short colour command check (0.18) ----

    /**
     * Once per strip: with the steady colour showing, send it with the short command at a
     * brightness one point off (not visible), then ask the strip. If its reply shows that
     * brightness, the short command works and is used from then on; otherwise the long one stays.
     * The answer is saved; the steady colour is put back with the long command either way.
     */
    private boolean probing;
    private int probeHue, probeSat, probeVal;
    private static final long PROBE_WAIT_MS = 2500;

    private void probeShortLater() {
        if (settings.shortColour != -1 || probing) return;
        main.removeCallbacks(probeStart);
        main.postDelayed(probeStart, 4000);
    }

    private final Runnable probeStart = new Runnable() {
        @Override public void run() {
            if (settings.shortColour != -1 || probing || !link.isReady() || busy() || away() || strip == null
                    || !strip.on || !strip.hasColour) return;
            probeHue = strip.hue & ~1;      // the short command carries the hue in steps of 2
            probeSat = strip.sat;
            probeVal = strip.val > 50 ? strip.val - 1 : strip.val + 1;
            probing = true;
            link.send(Zengge.shortColour(probeHue, probeSat, probeVal));
            lastSentMs = SystemClock.elapsedRealtime();
            main.postDelayed(probeAsk, STALE_MS + 300);
            main.postDelayed(probeGiveUp, PROBE_WAIT_MS + STALE_MS);
        }
    };

    private final Runnable probeAsk = new Runnable() {
        @Override public void run() { if (probing) link.query(); }
    };

    private final Runnable probeGiveUp = new Runnable() {
        @Override public void run() { if (probing) probeResult(false); }
    };

    /** A state reply while checking. */
    private void probeReply(Zengge.State s) {
        boolean ok = s.on && s.hasColour && Math.abs(s.val - probeVal) <= 0 && Math.abs(s.hue - probeHue) <= 2;
        probeResult(ok);
    }

    private void probeResult(boolean ok) {
        probing = false;
        main.removeCallbacks(probeAsk);
        main.removeCallbacks(probeGiveUp);
        settings.shortColour = ok ? 1 : 0;
        settings.save(this);
        event(ok ? "Commande couleur courte : reconnue, utilisée désormais"
                 : "Commande couleur courte : non reconnue, commande longue gardée");
        if (!busy() && !away()) {
            // put the exact steady colour back with the long command (whatever the short one did)
            link.send(Zengge.colour(settings.defaultHue, settings.defaultSat, targetPct()));
            lastSentMs = SystemClock.elapsedRealtime();
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
                    linkLostMs = 0;
                    if (introWantedMs != 0 && now - introWantedMs < INTRO_WANTED_MS) playIntro();
                    introWantedMs = 0;
                    if (charging && present() && !busy()) showGauge();
                    applyBrightness();
                } else {
                    if (linkLostMs == 0) linkLostMs = now;
                    // the strip may lose power with the car: its state is asked again on reconnect,
                    // and the first command sends "on" whatever we thought
                    strip = null;
                }
            }
        });
    }

    @Override public void onStripState(final Zengge.State s) {
        main.post(new Runnable() {
            @Override public void run() {
                if (SystemClock.elapsedRealtime() - lastSentMs < STALE_MS) return;
                if (probing) { probeReply(s); return; }
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
            boolean door = (opened & 0xf0) != 0;      // a door, not only the boot / frunk
            long now = SystemClock.elapsedRealtime();
            // arriving: the car was locked, or it was unlocked lately and no door has opened since
            boolean arriving = away() || (!doorSinceArrival && now - arrivalMs < ARRIVAL_MS);
            // a door can only open unlocked: if the unlock was missed (or a flash was mistaken for
            // a lock), this is the arrival, even during an animation (17 Nov: the strip went dark).
            // The boot first: no entrance yet, it waits for the door (below)
            if (away()) {
                onPresence(true, "Ouverture voiture verrouillée", door || !settings.intro);
                if (door) { doorSinceArrival = true; return; }
            }
            if (door) {
                doorSinceArrival = true;
                // the owner opened the boot on arriving and now gets in: the entrance he never
                // saw from the boot (owner, 22 Nov), in place of the blink
                if (introOwed) {
                    introOwed = false;
                    if (now - arrivalMs < ARRIVAL_MS) {
                        event("Portière après le coffre : animation d'allumage");
                        playIntro();
                        return;
                    }
                }
                if (intro) return;                      // the entrance animation goes on
            } else if (arriving && settings.intro) {
                // boot / frunk on arriving: the usual red blink now (it stops the entrance if it
                // was playing), the entrance when a door opens
                introOwed = true;
            } else if (intro) {
                return;
            }
            // otherwise always the alert blink (owner, 17 Nov)
            if (settings.doorBlink) startBlink(settings.blinkMaxSec);
            else if (introOwed) { stopAll(); steady(); }      // no blink wanted: lit, entrance later
        } else if (!open && !hazardOn) {
            stopBlink();
        }
    }

    /** The owner arrives (unlocked) or leaves (locked). */
    private void onPresence(boolean there, String why) {
        onPresence(there, why, true);
    }

    /** entrance: play the entrance animation now (false: the boot opened first, see onDoors). */
    private void onPresence(boolean there, String why, boolean entrance) {
        presenceText = "Présence : " + (there ? "déverrouillée (conducteur là)" : "verrouillée (absent)");
        if (ownerThere != null && ownerThere == there) return;
        ownerThere = there;
        Settings.savePresence(this, there);
        event(why);
        introOwed = false;
        if (there) {
            lastUnlockMs = SystemClock.elapsedRealtime();
            arrivalMs = lastUnlockMs;
            doorSinceArrival = false;
            if (!entrance) return;               // onDoors blinks for the boot and owes the entrance
            // the owner is back: lit, then the steady colour (an entrance already playing just
            // goes on: it ends on the steady colour now that the car counts as unlocked)
            if (intro) return;
            if (settings.intro) playIntro();
            else { stopAll(); steady(); }
        } else {
            if (hazardOn) { hazardOn = false; main.removeCallbacks(hazardCheck); }
            if (!settings.intro || strip == null || !strip.on) { stopAll(); power(false); return; }
            playExit();                      // the owner leaves: withdraw, then off
        }
    }

    /**
     * Each flash of both indicators together is counted; when the burst is over, one flash is the
     * lock confirmation, two the unlock one. Three or more: the hazards (only then the strip blinks,
     * so locking no longer looks like hazards). One side alone is a turn signal.
     */
    @Override public void onIndicators(boolean left, boolean right) {
        boolean both = left && right;
        boolean rising = both && !bothLit;
        bothLit = both;
        if (!both) return;
        lastBothLitMs = SystemClock.elapsedRealtime();
        if (!rising) return;
        flashes++;
        main.removeCallbacks(burstEnd);
        main.postDelayed(burstEnd, BURST_END_MS);
        if (flashes < HAZARD_FLASHES || hazardOn || !settings.hazard || away()) return;
        hazardOn = true;
        event("Warnings");
        startBlink(settings.blinkMaxSec);
        ruleText = "Warnings : clignotement";
        main.removeCallbacks(hazardCheck);
        main.postDelayed(hazardCheck, 500);
    }

    private final Runnable burstEnd = new Runnable() {
        @Override public void run() {
            int n = flashes;
            trace("salve de clignotants : " + n + " flash(s)");
            flashes = 0;
            if (n == 1) {
                // the welcome lights are on with the unlock and off at the lock; and a single flash
                // right after an unlock is its double flash half seen (17 Nov 12:52: the app had
                // just started and saw one of the two, and took it for a lock)
                // (welcome lights only if they just came on: at night the lights can stay on a
                // while after getting out, and the lock must still count then)
                long now = SystemClock.elapsedRealtime();
                if ((parkOn && now - parkOnMs < UNLOCK_GRACE_MS) || now - lastUnlockMs < UNLOCK_GRACE_MS) {
                    event("1 flash ignoré (déverrouillage en cours)");
                    return;
                }
                onPresence(false, "Verrouillage (1 flash)");
            } else if (n == 2) {
                onPresence(true, "Déverrouillage (2 flashs)");
            }
        }
    };

    /** The parking/welcome lights come on with the unlock, just before its two flashes. */
    @Override public void onParkLights(boolean on) {
        parkOn = on;
        if (on) parkOnMs = SystemClock.elapsedRealtime();
        if (on && away()) onPresence(true, "Déverrouillage (feux d'accueil)");
    }

    /**
     * Autosteer on: 1 s in Binnacle's Autopilot blue (CentreView.AP_BLUE 0xFF1F86FF = hue 212,
     * sat 88) at the brightness the strip already has, then back (owner, 22 Nov).
     */
    @Override public void onAutosteer(boolean on) {
        event(on ? "Autosteer activé" : "Autosteer désactivé");
        if (on && settings.apFlash && !canDecorate()) trace("bleu Autosteer non joué : " + busyWhy());
        if (!on || !settings.apFlash || !canDecorate()) return;
        trace("bleu Autosteer 1 s");
        int pct = strip.hasColour && strip.val > 0 ? strip.val : targetPct();
        apFlash = true;
        link.clearQueue();
        colour(AP_HUE, AP_SAT, pct, false);
        main.removeCallbacks(apFlashEnd);
        main.postDelayed(apFlashEnd, AP_FLASH_MS);
    }

    private final Runnable apFlashEnd = new Runnable() {
        @Override public void run() {
            if (!apFlash) return;
            apFlash = false;
            afterAnimation();
        }
    };

    /** Reverse gear (code 11 gear = 1): a breathing white while it lasts, then the usual colour. */
    @Override public void onGear(int gear) {
        boolean r = gear == 1;
        if (r == inReverse) return;
        inReverse = r;
        event(r ? "Marche arrière" : "Fin de marche arrière");
        if (r) showReverse();
        else if (reverseLit) { reverseLit = false; main.removeCallbacks(reverseTick); afterAnimation(); }
    }

    /** Reverse breathing: one cycle, from the white down to REVERSE_LOW of it and back (owner, 23 Nov). */
    private static final long REVERSE_PERIOD_MS = 2000, REVERSE_FRAME_MS = 120;
    private static final double REVERSE_LOW = 0.15;
    private int reverseWhite, reverseFrame;

    /** The reverse white, breathing, if wanted and nothing else is playing. */
    private void showReverse() {
        trace("marche arrière : blanc" + (busy() && !reverseLit ? " refusé (" + busyWhy() + ")" : ""));
        if (!inReverse || !settings.reverseWhite || !link.isReady() || !present() || !screenIsOn
                || (busy() && !reverseLit)) return;
        int pct = targetPct();
        int white = Math.min(100, Math.max(pct + 30, Math.round(pct * 1.5f)));   // 20 % at night: 50 %
        if (strip == null || !strip.on) power(true);
        boolean was = reverseLit;
        reverseLit = true;
        reverseWhite = white;
        ruleText = "Marche arrière : blanc (respiration)";
        if (was) return;                     // already breathing
        reverseFrame = 0;
        main.removeCallbacks(reverseTick);
        main.post(reverseTick);
    }

    private final Runnable reverseTick = new Runnable() {
        @Override public void run() {
            if (!reverseLit) return;
            double t = (reverseFrame * REVERSE_FRAME_MS % REVERSE_PERIOD_MS) / (double) REVERSE_PERIOD_MS;
            double level = REVERSE_LOW + (1 - REVERSE_LOW) * (0.5 + 0.5 * Math.cos(2 * Math.PI * t));
            if (reverseFrame % 50 == 0) awake(10000);
            if (link.isReady()) colour(0, 0, Math.max(1, (int) Math.round(reverseWhite * level)), false);
            reverseFrame++;
            main.postDelayed(this, REVERSE_FRAME_MS);
        }
    };

    /** Auto Hold on or off (code 9 "autohandlight"): after 1 s held, maybe an animation. */
    @Override public void onAutoHold(boolean on) {
        if (on == autoHoldOn) return;
        autoHoldOn = on;
        main.removeCallbacks(holdStart);
        if (on) {
            main.postDelayed(holdStart, HOLD_DELAY_MS);
        } else if (hold && holdUntil == 0 && !holdEnding) {
            holdEnding = true;              // the car moves off: back to the steady colour
            event("Auto Hold relâché");
        }
    }

    private final Runnable holdStart = new Runnable() {
        @Override public void run() {
            if (!autoHoldOn || !settings.holdAnim) return;
            if (!canDecorate() || charging || inReverse) {
                trace("Auto Hold : pas d'animation, " + (charging ? "recharge" : inReverse ? "marche arrière" : busyWhy()));
                return;
            }
            int chance = Math.max(1, settings.holdChance);
            if (rnd.nextInt(chance) != 0) {
                event("Auto Hold : pas d'animation cette fois (1 sur " + chance + ")");
                return;
            }
            // at random, but never the same one twice in a row
            int k = rnd.nextInt(holdKind < 0 ? HoldShow.COUNT : HoldShow.COUNT - 1);
            if (holdKind >= 0 && k >= holdKind) k++;
            startHold(k, 0);
        }
    };

    /** testMs > 0: a test from the app, ending by itself; 0: until the Auto Hold ends. */
    private void startHold(int kind, long testMs) {
        trace("animation d'arrêt " + HoldShow.NAMES[kind] + (testMs > 0 ? " (test)" : ""));
        holdKind = kind;
        holdShow = new HoldShow(kind, rnd);
        hold = true;
        holdEnding = false;
        holdFrame = 0;
        holdW = 0;
        holdUntil = testMs > 0 ? SystemClock.elapsedRealtime() + testMs : 0;
        link.clearQueue();
        ruleText = "Auto Hold : " + HoldShow.NAMES[kind];
        event((testMs > 0 ? "Test arrêt : " : "Auto Hold : ") + HoldShow.NAMES[kind]);
        main.removeCallbacks(holdTick);
        main.post(holdTick);
    }

    /** Stops the Auto Hold animation and the Autosteer flash at once, without putting anything back. */
    private void stopHold() {
        if (probing) {               // an animation takes over: check again another time
            probing = false;
            main.removeCallbacks(probeAsk);
            main.removeCallbacks(probeGiveUp);
        }
        main.removeCallbacks(probeStart);
        main.removeCallbacks(apFlashEnd);
        apFlash = false;
        reverseLit = false;
        main.removeCallbacks(reverseTick);
        main.removeCallbacks(holdStart);
        main.removeCallbacks(holdTick);
        if (hold) ruleText = "";
        hold = false;
        holdEnding = false;
        holdShow = null;
    }

    private final Runnable holdTick = new Runnable() {
        @Override public void run() {
            if (!hold) return;
            if (holdUntil > 0 && SystemClock.elapsedRealtime() > holdUntil) holdEnding = true;
            if (holdEnding) {
                holdW -= 1.0 / HOLD_OUT_FRAMES;
                if (holdW <= 0) {
                    stopHold();
                    link.clearQueue();
                    afterAnimation();
                    return;
                }
            } else {
                holdW = Math.min(1, holdW + 1.0 / HOLD_IN_FRAMES);
            }
            if (holdFrame % 20 == 0) awake(10000);
            int hue = settings.defaultHue, sat = settings.defaultSat, pct = targetPct();
            holdShow.frame(holdFrame, hue, sat, pct, holdHues, holdSats, holdVals);
            // fade between the steady colour and the animation (smoothstep of holdW)
            double w = holdW * holdW * (3 - 2 * holdW);
            for (int i = Zengge.SEGMENTS - 1; i >= 0; i--) {  // from the end: 16-20 copy 15 before it changes
                int j = Math.min(i, HoldShow.N - 1);        // segments 16-20: not shown anywhere
                int dh = ((holdHues[j] - hue + 540) % 360) - 180;
                holdHues[i] = ((hue + (int) Math.round(w * dh)) % 360 + 360) % 360;
                holdSats[i] = (int) Math.round(sat + w * (holdSats[j] - sat));
                holdVals[i] = (int) Math.round(pct + w * (holdVals[j] - pct));
            }
            link.send(Zengge.pixels(holdHues, holdSats, holdVals));
            lastSentMs = SystemClock.elapsedRealtime();
            holdFrame++;
            main.postDelayed(this, HoldShow.FRAME_MS);
        }
    };

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
            else { stopAll(); power(false); }
        }
    }

    /** Green gauge of the battery % while charging, only with the owner there (unlocked). */
    private void showGauge() {
        if (blinking || intro || !link.isReady() || !present()) return;
        stopHold();
        exiting = false;
        main.removeCallbacks(exitTick);
        if (!gauge) { power(true); gaugeFrame = 0; }
        gauge = true;
        ruleText = "Recharge : jauge de batterie";
        main.removeCallbacks(gaugeTick);
        main.post(gaugeTick);
    }

    private final Runnable gaugeTick = new Runnable() {
        @Override public void run() {
            if (!gauge) return;
            int pct = targetPct();
            // segment 1 is at the right end of the dashboard (and the back of the doors), 15 at
            // its left end; the bar is centred on segment 8, the middle of the dashboard
            int bar = soc < 0 ? 0 : Math.max(1, Math.round(soc * VISIBLE_SEGMENTS / 100f));
            int right = (VISIBLE_SEGMENTS - bar) / 2;          // segments 1..right: fill upwards
            int left = VISIBLE_SEGMENTS - bar - right;         // the last `left` ones: fill downwards
            int cycle = GAUGE_FILL_FRAMES + GAUGE_HOLD_FRAMES + GAUGE_GAP_FRAMES;
            int frame = gaugeFrame % cycle;
            double t = frame >= GAUGE_FILL_FRAMES + GAUGE_HOLD_FRAMES ? -1    // gap: sides dark
                    : Math.min(frame, GAUGE_FILL_FRAMES - 1) * (1 + INTRO_FADE) / (GAUGE_FILL_FRAMES - 1);
            int[] vals = new int[Zengge.SEGMENTS];
            for (int i = 0; i < vals.length; i++) {
                if (i >= VISIBLE_SEGMENTS) { vals[i] = pct; continue; }   // not shown anywhere
                int k, n;                                 // k-th of n from the outer end of its side
                if (i < right) { k = i; n = right; }
                else if (i >= VISIBLE_SEGMENTS - left) { k = VISIBLE_SEGMENTS - 1 - i; n = left; }
                else { vals[i] = pct; continue; }        // the bar
                double f = (t - (double) k / n) / INTRO_FADE;
                vals[i] = f <= 0 ? 0 : f >= 1 ? pct : (int) Math.round(pct * f);
            }
            link.send(Zengge.levels(GAUGE_HUE, 100, vals));
            lastSentMs = SystemClock.elapsedRealtime();
            gaugeFrame++;
            main.postDelayed(this, GAUGE_FRAME_MS);
        }
    };

    /** Exit, 3 times: the light withdraws in the reverse order of the entrance, then off. */
    private void playExit() {
        trace("animation d'extinction");
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
            // round 1 withdraws the lit strip; rounds 2 and 3 start dark and send a band of light
            // the same way, so the strip stays dark between rounds (owner, 17 Nov: lighting it
            // all up again for the next round looked bad)
            boolean band = exitRound > 0;
            int frames = band ? BAND_FRAMES : EXIT_FRAMES;
            if (exitFrame >= frames) {
                exitRound++;
                if (exitRound < repeats()) {
                    exitFrame = 0;    // the next round starts dark
                    link.send(Zengge.levels(exitHue, exitSat, new int[Zengge.SEGMENTS]));
                    lastSentMs = SystemClock.elapsedRealtime();
                    main.postDelayed(this, ROUND_GAP_MS);
                    return;
                }
                exiting = false;
                power(false);
                ruleText = "Ruban éteint";
                return;
            }
            double span = 0.9 + INTRO_FADE + (band ? BAND_WIDTH : 0);
            double t = exitFrame * span / (frames - 1);
            int[] vals = new int[Zengge.SEGMENTS];
            for (int i = 0; i < vals.length; i++) {
                if (band && i >= VISIBLE_SEGMENTS) continue;                        // not shown: dark
                double start = i < VISIBLE_SEGMENTS ? 0.9 - introStart(i) : 0;   // last lit, first out
                double off = clamp01((t - start - (band ? BAND_WIDTH : 0)) / INTRO_FADE);
                double on = band ? clamp01((t - start) / INTRO_FADE) : 1;
                vals[i] = (int) Math.round(exitPct * Math.max(0, on - off));
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

    // ---- helpers ----

    private void applyJournalSettings() {
        journal.setFull(settings.journalFull);
        doors.setExtraCodes(settings.journalCodes);
    }

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** Settings for the journal, the strip's address left out. */
    private String settingsLine() {
        Settings s = settings;
        return "couleur T" + s.defaultHue + " S" + s.defaultSat + " " + s.dayPct + "%, alerte T" + s.blinkHue + " " + s.blinkPct + "%"
            + ", nuit " + (s.night ? s.nightStartWinter + "h/" + s.nightStartSummer + "h-" + s.nightEnd + "h " + s.nightPct + "%" : "non")
            + ", animations " + (s.intro ? "×" + s.animRepeat : "non") + ", portes " + s.doorBlink + ", coffre " + s.bootAndFrunk
            + ", warnings " + s.hazard + ", arrêt " + (s.holdAnim ? "1/" + s.holdChance : "non") + ", autosteer " + s.apFlash
            + ", marche arrière " + s.reverseWhite + ", écran position appareil " + s.unitQuiet + ", iPhone " + s.remote + ", commande courte " + s.shortColour
            + ", musique CarPlay " + s.autoPlay + ", journal " + (s.journalFull ? "détaillé" : "normal")
            + (s.journalCodes == null || s.journalCodes.isEmpty() ? "" : " codes+ " + s.journalCodes);
    }

    /** Head of an upload: what the app is and does right now, then its own logcat. */
    private String header() {
        return "Ruban LED " + version() + " — " + new Date() + "\n"
            + Journal.device() + "\n"
            + "Liaison : " + linkText + " | " + stripText + "\n"
            + "Présence : " + presenceText + " | " + doorsText + " | " + feedText + "\n"
            + "Règle en cours : " + (ruleText.isEmpty() ? "aucune" : ruleText) + " | occupé : " + busyWhy() + "\n"
            + "État : propriétaire=" + ownerThere + " recharge=" + charging + " batterie=" + soc + "% autohold=" + autoHoldOn
            + " marche arrière=" + inReverse + " écran=" + screenIsOn + " nuit=" + settings.nightNow() + "\n"
            + "Réglages : " + settingsLine() + "\n"
            + "Derniers événements :\n" + eventsText + "\n"
            + "==== LOGCAT (appli) ====\n" + Journal.logcat(3000);
    }

    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss", Locale.FRANCE);
    private final ArrayList<String> events = new ArrayList<>();

    /** Recent events, shown in the app (helps to see whether a door message arrived late). */
    private void event(String what) {
        journal.log("EVT", what);
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
