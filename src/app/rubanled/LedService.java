package app.rubanled;

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
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.Calendar;

/**
 * Keeps the strip connected and applies the rules:
 * - a door (and optionally boot/frunk) open: blink, then put the strip back as it was;
 * - at the night/day hours: lower/raise the brightness, keeping the colour.
 * Everything runs on the main thread except the BLE and CAN plumbing.
 */
public final class LedService extends Service implements LedLink.Listener, DoorFeed.Listener {
    static final String ACTION_RELOAD = "app.rubanled.RELOAD";
    static final String ACTION_TEST_BLINK = "app.rubanled.TEST_BLINK";
    static final String ACTION_ON = "app.rubanled.ON";
    static final String ACTION_OFF = "app.rubanled.OFF";
    static final String ACTION_DEFAULT = "app.rubanled.DEFAULT";
    static final String ACTION_TEST_SEGMENTS = "app.rubanled.TEST_SEGMENTS";
    static final String ACTION_INTRO = "app.rubanled.INTRO";
    /** Extras: hue 0-359, sat 0-100, pct 1-100. Saved as the colour, applied at once. */
    static final String ACTION_SET_COLOUR = "app.rubanled.SET_COLOUR";
    /** Extra "target" of ACTION_SET_COLOUR: the steady colour, or the animations' one. */
    static final String TARGET_STEADY = "steady", TARGET_ANIM = "anim";
    /** After editing the animation colour, show it this long, then go back to the steady one. */
    private static final long ANIM_PREVIEW_MS = 2000;

    private static final String TAG = "rubanled";
    private static final String CHANNEL = "ruban";
    private static final long NIGHT_CHECK_MS = 30000;
    private static final long TEST_BLINK_SEC = 6;

    // shown by MainActivity
    static volatile String linkText = "Service arrêté";
    static volatile String feedText = "";
    static volatile String doorsText = "Portes : inconnu";
    static volatile String stripText = "Ruban : état inconnu";
    static volatile String ruleText = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private Settings settings;
    private LedLink link;
    private DoorFeed doors;

    private Zengge.State strip;      // what the strip is showing (our commands, its replies)
    /** The strip's replies describe the command before last: ignore them right after we send. */
    private static final long STALE_MS = 1500;
    private long lastSentMs;
    private Zengge.State saved;      // state to put back after a blink
    private boolean doorsOpen;
    private boolean blinking;
    private boolean blinkPhase;
    private long blinkUntil;
    private Boolean lastNight;
    private boolean brightnessPending;
    private boolean intro;
    private int introFrame;
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

    private static double introStart(int i) {
        if (i < DOOR_SEGMENTS) return (double) i / DOOR_SEGMENTS;                          // 0 .. 0.9
        return (double) (VISIBLE_SEGMENTS - 1 - i) / (VISIBLE_SEGMENTS - DOOR_SEGMENTS);   // 0.8 .. 0
    }

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            // the next door opened plays the animation
            introPending = true;
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                // the dash sleeps with the car: the strip (glovebox USB, powered while the car is
                // awake, Sentry included) goes dark, and stays dark until a door is opened
                stopBlinkNoRestore();
                main.removeCallbacks(previewEnd);
                main.removeCallbacks(introFinal);
                power(false);
                ruleText = "Écran éteint : ruban éteint";
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        startForeground(1, notification());
        settings = Settings.load(this);
        link = new LedLink(this, this);
        doors = new DoorFeed(this, this);
        link.start(settings.address);
        doors.start();
        main.post(nightCheck);
        IntentFilter screen = new IntentFilter(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenOn, screen);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (ACTION_RELOAD.equals(a)) {
            settings = Settings.load(this);
            link.start(settings.address);
            lastNight = null;          // re-apply the brightness rule with the new hours
            main.removeCallbacks(nightCheck);
            main.post(nightCheck);
        } else if (ACTION_TEST_BLINK.equals(a)) {
            startBlink(TEST_BLINK_SEC);
        } else if (ACTION_ON.equals(a)) {
            stopBlinkNoRestore();
            power(true);
        } else if (ACTION_OFF.equals(a)) {
            stopBlinkNoRestore();
            power(false);
        } else if (ACTION_TEST_SEGMENTS.equals(a)) {
            // segments 1-7 red, 8-14 green, 15-20 blue: shows how the 20 segments map to the strips
            stopBlinkNoRestore();
            int[] hues = new int[Zengge.SEGMENTS];
            for (int i = 0; i < hues.length; i++) hues[i] = i < 7 ? 0 : i < 14 ? 120 : 240;
            power(true);
            link.send(Zengge.segments(hues, 100));
            lastSentMs = SystemClock.elapsedRealtime();
        } else if (ACTION_INTRO.equals(a)) {
            playIntro();
        } else if (ACTION_SET_COLOUR.equals(a) && intent != null) {
            if (TARGET_ANIM.equals(intent.getStringExtra("target"))) {
                setAnimColour(intent.getIntExtra("hue", settings.blinkHue), intent.getIntExtra("sat", settings.blinkSat),
                    intent.getIntExtra("pct", settings.blinkPct));
            } else {
                setColour(intent.getIntExtra("hue", settings.defaultHue), intent.getIntExtra("sat", settings.defaultSat),
                    intent.getIntExtra("pct", settings.dayPct));
            }
        } else if (ACTION_DEFAULT.equals(a)) {
            stopBlinkNoRestore();
            // power first: "on" after a colour puts the strip back to white
            power(true);
            colour(settings.defaultHue, settings.defaultSat, targetPct(), true);
            queryLater();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screenOn); } catch (Throwable ignored) {}
        doors.stop();
        link.quit();
        linkText = "Service arrêté";
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- rules ----

    /** New animation colour: saved, shown briefly on the strip, then back to the steady colour. */
    private void setAnimColour(int hue, int sat, int pct) {
        settings.blinkHue = Math.max(0, Math.min(359, hue));
        settings.blinkSat = Math.max(0, Math.min(100, sat));
        settings.blinkPct = Math.max(1, Math.min(100, pct));
        settings.save(this);
        if (blinking || intro) return;     // the running animation picks it up
        if (strip == null || !strip.on) power(true);
        colour(settings.blinkHue, settings.blinkSat, settings.blinkPct, false);
        main.removeCallbacks(previewEnd);
        main.postDelayed(previewEnd, ANIM_PREVIEW_MS);
    }

    private final Runnable previewEnd = new Runnable() {
        @Override public void run() {
            if (blinking || intro) return;
            colour(settings.defaultHue, settings.defaultSat, targetPct(), true);
        }
    };

    /** New colour from the wheel: saved, and shown now unless an animation is running. */
    private void setColour(int hue, int sat, int pct) {
        settings.defaultHue = Math.max(0, Math.min(359, hue));
        settings.defaultSat = Math.max(0, Math.min(100, sat));
        settings.dayPct = Math.max(1, Math.min(100, pct));
        if (!settings.night) settings.nightPct = settings.dayPct;
        settings.save(this);
        int p = targetPct();
        if (blinking) {
            if (saved == null) saved = new Zengge.State();
            saved.on = true;
            saved.hasColour = true;
            saved.hue = settings.defaultHue;
            saved.sat = settings.defaultSat;
            saved.val = p;
            return;
        }
        if (intro) return;            // the animation ends on the new colour
        if (strip == null || !strip.on) power(true);
        colour(settings.defaultHue, settings.defaultSat, p, true);
    }

    /** A door opened while armed: the start-up animation (now, or once the strip connects). */
    private void maybeIntro() {
        if (!introPending || !settings.intro) return;
        if (!link.isReady()) {
            introWantedMs = SystemClock.elapsedRealtime();
            return;
        }
        playIntro();
    }

    /** Start-up animation: the strip lights up progressively, then stays on the chosen colour. */
    private void playIntro() {
        if (!link.isReady()) return;
        introPending = false;
        introWantedMs = 0;
        blinking = false;
        saved = null;
        main.removeCallbacks(blinkTick);
        main.removeCallbacks(introFinal);
        ruleText = "Animation d'allumage";
        intro = true;
        introFrame = 0;
        main.removeCallbacks(introTick);
        link.clearQueue();
        power(true);
        main.post(introTick);
    }

    private final Runnable introTick = new Runnable() {
        @Override public void run() {
            if (!intro) return;
            int pct = settings.blinkPct;      // the animation runs in the animation colour
            if (introFrame >= INTRO_FRAMES) {
                intro = false;
                ruleText = "";
                colour(settings.defaultHue, settings.defaultSat, targetPct(), true);   // then the steady one
                // a second time: if a frame was lost, the strip must not stay half lit
                main.postDelayed(introFinal, 700);
                queryLater();
                return;
            }
            int[] vals = new int[Zengge.SEGMENTS];
            for (int i = 0; i < vals.length; i++) {
                if (i >= VISIBLE_SEGMENTS) { vals[i] = pct; continue; }   // not shown anywhere
                double t = introFrame * (0.9 + INTRO_FADE) / (INTRO_FRAMES - 1);
                double f = (t - introStart(i)) / INTRO_FADE;
                vals[i] = f <= 0 ? 0 : f >= 1 ? pct : (int) Math.round(pct * f);
            }
            link.send(Zengge.levels(settings.blinkHue, settings.blinkSat, vals));
            lastSentMs = SystemClock.elapsedRealtime();
            introFrame++;
            main.postDelayed(this, INTRO_FRAME_MS);
        }
    };

    private final Runnable introFinal = new Runnable() {
        @Override public void run() {
            if (blinking || intro) return;
            colour(settings.defaultHue, settings.defaultSat, targetPct(), true);
        }
    };

    private void startBlink(long maxSec) {
        intro = false;
        main.removeCallbacks(introFinal);
        main.removeCallbacks(introTick);
        if (!blinking) {
            saved = copy(strip);
            blinking = true;
            blinkPhase = false;
            link.clearQueue();
            power(true);
            main.post(blinkTick);
        }
        blinkUntil = SystemClock.elapsedRealtime() + maxSec * 1000;
        ruleText = "Clignotement en cours";
    }

    private final Runnable blinkTick = new Runnable() {
        @Override public void run() {
            if (!blinking) return;
            if (SystemClock.elapsedRealtime() > blinkUntil) {
                stopBlink();
                return;
            }
            blinkPhase = !blinkPhase;
            colour(settings.blinkHue, settings.blinkSat, blinkPhase ? settings.blinkPct : 1, false);
            main.postDelayed(this, Math.max(150, settings.blinkMs));
        }
    };

    /** Stop blinking and put the strip back as it was before. */
    private void stopBlink() {
        if (!blinking) return;
        blinking = false;
        main.removeCallbacks(blinkTick);
        link.clearQueue();
        Zengge.State s = saved;
        saved = null;
        if (s != null && s.hasColour && s.on) {
            colour(s.hue, s.sat, s.val, true);   // already on from the blink
        } else if (s != null && s.hasColour) {
            colour(s.hue, s.sat, s.val, true);
            power(false);
        } else if (s != null) {
            power(s.on);
        } else {
            // never heard from the strip: the default colour at the brightness of the hour
            colour(settings.defaultHue, settings.defaultSat, targetPct(), true);
        }
        queryLater();
        ruleText = "";
    }

    private void stopBlinkNoRestore() {
        intro = false;
        main.removeCallbacks(introTick);
        blinking = false;
        saved = null;
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
        boolean night = settings.night && settings.isNight(Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
        return night ? settings.nightPct : settings.dayPct;
    }

    private final Runnable nightCheck = new Runnable() {
        @Override public void run() {
            boolean night = settings.night && settings.isNight(Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
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
        if (!brightnessPending || !link.isReady()) return;
        int pct = targetPct();
        if (blinking) {
            if (saved != null && saved.hasColour) saved.val = pct;
            brightnessPending = false;
            return;
        }
        if (strip == null) return;     // wait for the strip to tell us its colour
        brightnessPending = false;
        if (strip.on && strip.hasColour && strip.val != pct) {
            colour(strip.hue, strip.sat, pct, true);
            Log.i(TAG, "brightness " + pct + "%");
        }
    }

    // ---- callbacks ----

    @Override public void onLinkState(final boolean ready, final String text) {
        main.post(new Runnable() {
            @Override public void run() {
                linkText = text;
                long now = SystemClock.elapsedRealtime();
                if (ready) {
                    if (linkLostMs != 0 && now - linkLostMs > STRIP_OFF_MS) introPending = true;
                    linkLostMs = 0;
                    if (introWantedMs != 0 && now - introWantedMs < INTRO_WANTED_MS && doorsOpen) playIntro();
                    introWantedMs = 0;
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
        boolean open = (v & mask) != 0;
        doorsText = "Portes : " + (v == 0 ? "fermées" : describe(v));
        if (open == doorsOpen) return;
        doorsOpen = open;
        if (open && introPending && settings.intro) maybeIntro();     // getting in: the animation
        else if (open && settings.doorBlink) startBlink(settings.blinkMaxSec);
        else if (!open) stopBlink();
    }

    @Override public void onFeedState(String text) {
        feedText = text;
    }

    // ---- helpers ----

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

    private static Zengge.State copy(Zengge.State s) {
        if (s == null) return null;
        Zengge.State c = new Zengge.State();
        c.on = s.on;
        c.hasColour = s.hasColour;
        c.hue = s.hue;
        c.sat = s.sat;
        c.val = s.val;
        return c;
    }

    private Notification notification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Ruban LED", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), 0);
        return b.setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Ruban LED actif")
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
