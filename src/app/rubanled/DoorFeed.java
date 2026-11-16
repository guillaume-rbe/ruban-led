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
 * (unregPropIdList), for codes 15, 9, 11 and the mirror's (all 0-127 while detecting the mirrors,
 * like Binnacle's logger). Nothing is ever sent to the MCU or the car.
 *
 * Code 15, key "doorval": 0x80 driver, 0x40 passenger, 0x20 rear right, 0x10 rear left,
 * 0x08 boot, 0x04 frunk. Code 9, keys "leftlight" / "rightlight": the indicators, 1 = lit,
 * 2 = off; they follow the actual flashing; "chargelight" = charging. Code 11: "jhmail" and
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
        /** The mirror field set up in the app: true unfolded, false folded. */
        void onMirrors(boolean open);
    }

    /** Codes always read; the mirror's code is added when it is set up. */
    private static final int[] BASE_CODES = {15, 9, 11};
    /** Codes left out of the mirror detection: sensors, speed/energy, tyres, unused range. */
    private static final int[] NOISY = {3, 4, 10, 12, 18};

    private static final String TAG = "rubanled";
    private static final String DESCRIPTOR = "com.mcu.iinterface.aidl.IMcuService";
    private static final String SVC_ACTION = "com.xbs.canapp.action.mcuservice";
    private static final String SVC_PKG = "com.xbs.canapp";
    private static final int TX_REGISTER = 1, TX_UNREGISTER = 2;
    private static final int CODE_DOORS = 15, CODE_LIGHTS = 9, CODE_BATTERY = 11;
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
    private boolean left, right, charging;
    private int percent = -1, jhmail = -1, batterylevel = -1;
    private final Battery battery = new Battery();

    /** Mirror field (set from the settings) and the detection mode (all codes, every value kept). */
    private volatile int mirrorCode = -1;
    private volatile String mirrorKey = "", mirrorOpen = "", mirrorFolded = "";
    private boolean learning;
    final java.util.HashMap<String, String> learnValues = new java.util.HashMap<>();
    final java.util.HashMap<String, Integer> learnChanges = new java.util.HashMap<>();

    /** looper: where messages and callbacks run (the service's high-priority thread). */
    DoorFeed(Context ctx, Listener listener, Looper looper) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        main = new Handler(looper);
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        messenger = new Messenger(new Handler(looper) {
            @Override public void handleMessage(Message msg) {
                if (!running) return;
                try {
                    Bundle b = msg.getData();
                    if (learning) learn(msg.what, b);
                    if (msg.what == mirrorCode && b.containsKey(mirrorKey)) {
                        String val = String.valueOf(b.get(mirrorKey)).trim();
                        if (!mirrorOpen.isEmpty() ? val.equals(mirrorOpen) : !val.equals(mirrorFolded)) {
                            DoorFeed.this.listener.onMirrors(true);
                        } else if (!mirrorFolded.isEmpty() ? val.equals(mirrorFolded) : !val.equals(mirrorOpen)) {
                            DoorFeed.this.listener.onMirrors(false);
                        }
                        if (msg.what != CODE_DOORS && msg.what != CODE_LIGHTS && msg.what != CODE_BATTERY) return;
                    }
                    if (msg.what == CODE_LIGHTS) {
                        left = lit(b, "leftlight", left);
                        right = lit(b, "rightlight", right);
                        DoorFeed.this.listener.onIndicators(left, right);
                        if (b.containsKey("chargelight")) {
                            charging = lit(b, "chargelight", charging);
                            DoorFeed.this.listener.onBattery(percent, charging);
                        }
                        return;
                    }
                    if (msg.what == CODE_BATTERY) {
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

    /** Values seen in detection mode: code:key -> last value, and how often each changed. */
    private void learn(int code, Bundle b) {
        for (int n : NOISY) if (n == code) return;
        for (String k : b.keySet()) {
            String id = code + ":" + k;
            String v = String.valueOf(b.get(k)).trim();
            String old = learnValues.put(id, v);
            if (old != null && !old.equals(v)) {
                Integer c = learnChanges.get(id);
                learnChanges.put(id, c == null ? 1 : c + 1);
            }
        }
    }

    /** Sets the mirror field (from the settings); re-registers if the code list changes. */
    void setMirror(int code, String key, String open, String folded) {
        boolean changed = code != mirrorCode;
        mirrorKey = key == null ? "" : key;
        mirrorOpen = open == null ? "" : open;
        mirrorFolded = folded == null ? "" : folded;
        mirrorCode = code;
        if (changed) reRegister();
    }

    /** Detection mode on: every code 0-127, values kept in learnValues (call on the looper). */
    void setLearning(boolean on) {
        if (on == learning) return;
        learning = on;
        learnValues.clear();
        learnChanges.clear();
        reRegister();
    }

    boolean isLearning() {
        return learning;
    }

    private void reRegister() {
        worker.post(new Runnable() {
            @Override public void run() {
                if (!running || binder == null) return;
                unregister();
                register();
            }
        });
    }

    private int[] codes() {
        if (learning) {
            int[] all = new int[128];
            for (int i = 0; i < all.length; i++) all[i] = i;
            return all;
        }
        int m = mirrorCode;
        for (int c : BASE_CODES) if (c == m) m = -1;
        if (m < 0) return BASE_CODES.clone();
        return new int[] {BASE_CODES[0], BASE_CODES[1], BASE_CODES[2], m};
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
            d.writeIntArray(codes());
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
