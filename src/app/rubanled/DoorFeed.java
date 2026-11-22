package app.rubanled;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.util.Log;

/**
 * Door state from the stock CAN app (com.xbs.canapp), READ-ONLY, the same way Binnacle's CanFeed
 * does it: the only binder calls are IMcuService transaction 1 (regPropIdList) and 2
 * (unregPropIdList), for codes 15, 9, 11 and 10. Nothing is ever sent to the MCU or the car.
 *
 * Code 15, key "doorval": 0x80 driver, 0x40 passenger, 0x20 rear right, 0x10 rear left,
 * 0x08 boot, 0x04 frunk. Code 9, keys "leftlight" / "rightlight": the indicators, 1 = lit,
 * 2 = off; they follow the actual flashing; "postlight" = the parking/welcome lights;
 * "chargelight" = charging; "autohandlight" = Auto Hold. Code 11: "jhmail" and
 * "batterylevel" for the battery % ({@link Battery}). (docs/FIELD_MAP.md in Binnacle.)
 */
final class DoorFeed {
    interface Listener {
        void onDoors(int doorval);
        /** Indicator lamps as last reported (each message may carry only some keys). */
        void onIndicators(boolean left, boolean right);
        /** Battery % (-1 unknown) and whether the car is charging (code 9 "chargelight"). */
        void onBattery(int percent, boolean charging);
        void onFeedState(String text);
        /** Code 9 "postlight": the parking / welcome lights (they come on when the car unlocks). */
        void onParkLights(boolean on);
        /** Code 9 "autohandlight": Auto Hold, true while held at a standstill in D/R (Binnacle, 28 Oct). */
        void onAutoHold(boolean on);
        /** Code 9 "autodrivermode": Autosteer on / off (Binnacle, verified 28 Oct). */
        void onAutosteer(boolean on);
        /** Code 10 "speedlimitsw", meaning unknown (the speed limiter?): its raw value when it changes. */
        void onSpeedLimitSw(String value);
        /** Code 11 "gear": 0 Park, 1 Reverse, 3 Drive (Binnacle, verified); when it changes. */
        void onGear(int gear);
    }

    /** The codes the rules read: doors, lights, battery, speed (for speedlimitsw). */
    private static final int[] CODES = {15, 9, 11, 10};
    /**
     * Subscribed: CODES plus the extra ones asked for in the app, for the journal only (0.20:
     * any CAN code can be recorded without a new build). Still read-only.
     */
    private volatile int[] codes = CODES.clone();
    private final Journal journal;
    /** Last journal line per code, so an unchanged message isn't written again. */
    private final java.util.HashMap<Integer, String> lastCan = new java.util.HashMap<>();
    private final java.util.HashMap<Integer, Long> lastCanMs = new java.util.HashMap<>();
    /** An unchanged message is still written this often (to show the feed is alive). */
    private static final long CAN_REPEAT_MS = 60000;

    private static final String TAG = "rubanled";
    private static final String DESCRIPTOR = "com.mcu.iinterface.aidl.IMcuService";
    private static final String SVC_ACTION = "com.xbs.canapp.action.mcuservice";
    private static final String SVC_PKG = "com.xbs.canapp";
    private static final int TX_REGISTER = 1, TX_UNREGISTER = 2;
    private static final int CODE_DOORS = 15, CODE_LIGHTS = 9, CODE_BATTERY = 11, CODE_SPEED = 10;
    private static final long RETRY_MS = 5000;

    private final Context ctx;
    private final Listener listener;
    private final Handler main;
    private final HandlerThread workerThread = new HandlerThread("door-feed");
    private final Handler worker;
    private final Messenger messenger;
    private boolean bound;
    private volatile boolean running;
    private volatile IBinder binder;
    private boolean registered;
    private boolean left, right, charging, park, hold, autosteer;
    private String limitSw;
    private int gear = -1;
    private int percent = -1, jhmail = -1, batterylevel = -1;
    private final Battery battery = new Battery();


    /** looper: where messages and callbacks run (the service's high-priority thread). */
    DoorFeed(Context ctx, Listener listener, Looper looper) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        journal = Journal.get(ctx);
        main = new Handler(looper);
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        messenger = new Messenger(new Handler(looper) {
            @Override public void handleMessage(Message msg) {
                if (!running) return;
                try {
                    Bundle b = msg.getData();
                    journalCan(msg.what, b);
                    if (msg.what == CODE_LIGHTS) {
                        left = lit(b, "leftlight", left);
                        right = lit(b, "rightlight", right);
                        DoorFeed.this.listener.onIndicators(left, right);
                        if (b.containsKey("postlight")) {
                            boolean was = park;
                            park = lit(b, "postlight", park);
                            if (park != was) DoorFeed.this.listener.onParkLights(park);
                        }
                        if (b.containsKey("autodrivermode")) {
                            boolean was = autosteer;
                            autosteer = lit(b, "autodrivermode", autosteer);
                            if (autosteer != was) DoorFeed.this.listener.onAutosteer(autosteer);
                        }
                        if (b.containsKey("autohandlight")) {
                            boolean was = hold;
                            hold = lit(b, "autohandlight", hold);
                            if (hold != was) DoorFeed.this.listener.onAutoHold(hold);
                        }
                        if (b.containsKey("chargelight")) {
                            charging = lit(b, "chargelight", charging);
                            DoorFeed.this.listener.onBattery(percent, charging);
                        }
                        return;
                    }
                    if (msg.what == CODE_SPEED) {
                        if (!b.containsKey("speedlimitsw")) return;
                        String v = String.valueOf(b.get("speedlimitsw"));
                        if (!v.equals(limitSw)) {
                            limitSw = v;
                            DoorFeed.this.listener.onSpeedLimitSw(v);
                        }
                        return;
                    }
                    if (msg.what == CODE_BATTERY) {
                        if (b.containsKey("gear")) {
                            int g = num(b, "gear", gear);
                            if (g != gear) { gear = g; DoorFeed.this.listener.onGear(g); }
                        }
                        jhmail = num(b, "jhmail", jhmail);
                        batterylevel = num(b, "batterylevel", batterylevel);
                        int p = battery.percent(jhmail, batterylevel, android.os.SystemClock.elapsedRealtime());
                        if (p >= 0) percent = p;
                        DoorFeed.this.listener.onBattery(percent, charging);
                        return;
                    }
                    if (msg.what != CODE_DOORS) return;
                    Object o = b.get("doorval");
                    int v;
                    if (o instanceof Number) v = ((Number) o).intValue();
                    else if (o instanceof String) v = Integer.parseInt(((String) o).trim());
                    else return;
                    DoorFeed.this.listener.onDoors(v);
                } catch (Throwable t) {
                    Log.w(TAG, "bad door message", t);
                }
            }
        });
    }

    private static int num(Bundle b, String k, int def) {
        if (!b.containsKey(k)) return def;
        Object o = b.get(k);
        if (o instanceof Number) return ((Number) o).intValue();
        if (o instanceof String) {
            try { return Math.round(Float.parseFloat(((String) o).trim())); } catch (NumberFormatException e) { return def; }
        }
        return def;
    }

    private static boolean lit(Bundle b, String k, boolean def) {
        if (!b.containsKey(k)) return def;
        Object o = b.get(k);
        if (o instanceof Number) return ((Number) o).intValue() == 1;
        if (o instanceof Boolean) return (Boolean) o;
        if (o instanceof String) return "1".equals(((String) o).trim()) || "true".equalsIgnoreCase(((String) o).trim());
        return def;
    }

    /**
     * Extra codes for the journal, e.g. "2,3,4" or "0-127" (empty: none). Subscribes again if
     * the list changed.
     */
    void setExtraCodes(String spec) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (int c : CODES) set.add(c);
        if (spec != null) {
            for (String part : spec.split("[,; ]+")) {
                try {
                    if (part.contains("-")) {
                        String[] r = part.split("-");
                        int a = Integer.parseInt(r[0].trim()), z = Integer.parseInt(r[1].trim());
                        for (int c = Math.max(0, a); c <= Math.min(255, z); c++) set.add(c);
                    } else if (!part.trim().isEmpty()) {
                        int c = Integer.parseInt(part.trim());
                        if (c >= 0 && c <= 255) set.add(c);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        int[] next = new int[set.size()];
        int i = 0;
        for (int c : set) next[i++] = c;
        if (java.util.Arrays.equals(next, codes)) return;
        codes = next;
        journal.log("CAN", "codes suivis : " + set);
        worker.post(new Runnable() {
            @Override public void run() {
                if (!running || binder == null) return;
                unregister();
                register();
            }
        });
    }

    /** Every message, all its fields: changes always, the same again only once a minute. */
    private void journalCan(int code, Bundle b) {
        boolean rules = code == CODE_DOORS || code == CODE_LIGHTS || code == CODE_BATTERY;
        if (!rules && !journal.full()) return;            // code 10 (speed) and extra codes: full journal only
        StringBuilder s = new StringBuilder();
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(b.keySet());
        for (String k : keys) s.append(k).append('=').append(b.get(k)).append(' ');
        String line = s.toString().trim();
        long now = android.os.SystemClock.elapsedRealtime();
        Long last = lastCanMs.get(code);
        if (line.equals(lastCan.get(code)) && last != null && now - last < CAN_REPEAT_MS) return;
        lastCan.put(code, line);
        lastCanMs.put(code, now);
        journal.log("CAN", code + " " + line);
    }

    void start() {
        if (running) return;
        running = true;
        bind();
    }

    void stop() {
        running = false;
        main.removeCallbacks(retry);
        worker.post(new Runnable() {
            @Override public void run() {
                unregister();
                binder = null;
                workerThread.quitSafely();
            }
        });
        if (bound) {
            try { ctx.unbindService(conn); } catch (Throwable ignored) {}
            bound = false;
        }
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, final IBinder s) {
            worker.post(new Runnable() {
                @Override public void run() {
                    binder = s;
                    registered = false;
                    register();
                }
            });
        }

        @Override public void onServiceDisconnected(ComponentName n) {
            worker.post(new Runnable() {
                @Override public void run() {
                    binder = null;
                    registered = false;
                }
            });
            listener.onFeedState("App CAN déconnectée, attente…");
        }

        @Override public void onBindingDied(ComponentName n) {
            if (bound) {
                try { ctx.unbindService(conn); } catch (Throwable ignored) {}
                bound = false;
            }
            scheduleRetry();
        }
    };

    private final Runnable retry = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (!bound) bind();
            else worker.post(new Runnable() {
                @Override public void run() { if (running && !registered && binder != null) register(); }
            });
        }
    };

    private void bind() {
        try {
            bound = ctx.bindService(new Intent(SVC_ACTION).setPackage(SVC_PKG), conn, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            bound = false;
        }
        if (!bound) {
            listener.onFeedState("App CAN introuvable, nouvel essai…");
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        main.removeCallbacks(retry);
        if (running) main.postDelayed(retry, RETRY_MS);
    }

    private void register() {
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeInt(1);
            messenger.writeToParcel(d, 0);
            d.writeIntArray(codes.clone());
            Parcel r = call(TX_REGISTER, d);
            registered = r != null && r.readInt() != 0;
            if (r != null) r.recycle();
        } catch (Throwable t) {
            registered = false;
        } finally {
            d.recycle();
        }
        final boolean ok = registered;
        main.post(new Runnable() {
            @Override public void run() {
                listener.onFeedState(ok ? "Portes : abonné à l'app CAN" : "App CAN : abonnement refusé, nouvel essai…");
                if (!ok) scheduleRetry();
            }
        });
    }

    private void unregister() {
        if (!registered) return;
        Parcel d = Parcel.obtain();
        try {
            d.writeInterfaceToken(DESCRIPTOR);
            d.writeInt(1);
            messenger.writeToParcel(d, 0);
            Parcel r = call(TX_UNREGISTER, d);
            if (r != null) r.recycle();
        } catch (Throwable ignored) {
        } finally {
            d.recycle();
            registered = false;
        }
    }

    /** The only binder entry point: register / unregister, nothing else. */
    private Parcel call(int txn, Parcel data) {
        if (txn != TX_REGISTER && txn != TX_UNREGISTER) return null;
        IBinder b = binder;
        if (b == null) return null;
        Parcel r = Parcel.obtain();
        try {
            b.transact(txn, data, r, 0);
            r.readException();
            return r;
        } catch (Throwable t) {
            Log.w(TAG, "IMcuService txn " + txn + " failed", t);
            r.recycle();
            return null;
        }
    }
}
