package app.rubanled;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.ArrayDeque;
import java.util.UUID;

/**
 * One BLE connection to the strip: connects, asks for a bigger MTU (colour commands are 95 bytes),
 * finds FF01/FF02, turns notifications on, then writes queued commands one at a time.
 * Reconnects by itself every few seconds while started. All work runs on its own thread.
 */
final class LedLink {
    interface Listener {
        void onLinkState(boolean ready, String text);
        void onStripState(Zengge.State s);
    }

    private static final String TAG = "rubanled";
    /** Reconnect quickly: the strip is powered with the car, a door opened right after must blink. */
    private static final long RETRY_MS = 2500;
    /** Right after the car wakes (the strip powers up with it): retry this often, for FAST_WINDOW_MS. */
    private static final long FAST_RETRY_MS = 500, FAST_WINDOW_MS = 30000;
    /**
     * Pause after each write. With a response the strip has already acked the command, so a short
     * one is enough; without, the pause is all that keeps us from flooding it.
     */
    private static final long GAP_ACKED_MS = 8, GAP_UNACKED_MS = 40;
    /** Safety net: a write whose callback never came is taken as done after this long. */
    private static final long WRITE_TIMEOUT_MS = 600;
    /** Commands kept at most (power and queries; frames coalesce, see send). */
    private static final int MAX_QUEUE = 6;
    /** Readiness without waiting for the notification descriptor's callback, if it never comes. */
    private static final long READY_FALLBACK_MS = 300;

    private final Context ctx;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("led-link");
    private final Handler h;
    private final Zengge zengge = new Zengge();
    private final ArrayDeque<Cmd> queue = new ArrayDeque<>();

    private static final class Cmd {
        final byte[] data;
        final int type;
        /** A full picture (colour / levels / pixels, E1 ...): only the newest one matters. */
        final boolean frame;
        Cmd(byte[] data, int type) {
            this.data = data;
            this.type = type;
            frame = type == Zengge.TYPE_COMMAND && data.length > 0 && data[0] == (byte) 0xe1;
        }
    }

    /** Frames replaced by a newer one before they were written (logged, for latency). */
    private int coalesced;

    private String address;
    private boolean started;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    /** With a response when FF01 allows it (0x0C on this strip): the strip acks each command. */
    private int writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
    private volatile boolean ready;
    /** Waiting for the reconnect timer (no attempt under way). */
    private boolean waitingRetry;
    private long fastUntil;
    private boolean writing;
    /** This connection has gone ready (the descriptor callback or the fallback timer). */
    private boolean readied;

    LedLink(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        thread.start();
        h = new Handler(thread.getLooper());
        h.post(new Runnable() {
            @Override public void run() { LedService.boost(); }
        });
    }

    void start(final String addr) {
        h.post(new Runnable() {
            @Override public void run() {
                if (started && addr.equalsIgnoreCase(address)) return;
                closeGatt();
                address = addr;
                started = true;
                connect();
            }
        });
    }

    void stop() {
        h.post(new Runnable() {
            @Override public void run() {
                started = false;
                h.removeCallbacksAndMessages(null);
                closeGatt();
            }
        });
    }

    void quit() {
        stop();
        thread.quitSafely();
    }

    /**
     * The car woke (screen on): the strip is powering up. Connect now instead of at the next
     * retry, and retry fast for a while (0.18). An attempt already under way is left alone: it
     * succeeds as soon as the strip advertises.
     */
    void wake() {
        h.post(new Runnable() {
            @Override public void run() {
                fastUntil = android.os.SystemClock.elapsedRealtime() + FAST_WINDOW_MS;
                if (!started || ready || !waitingRetry) return;
                h.removeCallbacks(reconnect);
                reconnect.run();
            }
        });
    }

    boolean isReady() {
        return ready;
    }

    /** Queues a command (unwrapped); written once the link is ready. */
    void send(byte[] cmd) {
        send(cmd, Zengge.TYPE_COMMAND);
    }

    /** Asks the strip for its state (answered by {@link Listener#onStripState}). */
    void query() {
        send(Zengge.QUERY_STATE, Zengge.TYPE_QUERY);
    }

    private void send(final byte[] cmd, final int type) {
        h.post(new Runnable() {
            @Override public void run() {
                Cmd c = new Cmd(cmd, type);
                // latest wins: a frame waiting at the end of the queue is replaced, not followed, so
                // an animation never lags behind the link (the order with power commands is kept)
                if (c.frame && !queue.isEmpty() && queue.peekLast().frame) {
                    queue.pollLast();
                    if (++coalesced % 50 == 0) Log.i(TAG, coalesced + " frames coalesced");
                }
                queue.addLast(c);
                while (queue.size() > MAX_QUEUE) queue.removeFirst();
                pump();
            }
        });
    }

    /** Drops anything not yet written (a new rule takes over). */
    void clearQueue() {
        h.post(new Runnable() {
            @Override public void run() { queue.clear(); }
        });
    }

    // ---- link thread ----

    private void connect() {
        if (!started) return;
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            state(false, "Bluetooth Android éteint");
            retry();
            return;
        }
        if (address == null || !BluetoothAdapter.checkBluetoothAddress(address)) {
            state(false, "Saisir l'adresse Bluetooth du ruban et enregistrer");
            return;
        }
        try {
            BluetoothDevice dev = adapter.getRemoteDevice(address);
            state(false, "Connexion au ruban…");
            gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE);
        } catch (Throwable t) {
            Log.w(TAG, "connectGatt failed", t);
            state(false, "Adresse du ruban invalide");
            retry();
        }
    }

    private void retry() {
        h.removeCallbacks(reconnect);
        if (!started) return;
        waitingRetry = true;
        boolean fast = android.os.SystemClock.elapsedRealtime() < fastUntil;
        h.postDelayed(reconnect, fast ? FAST_RETRY_MS : RETRY_MS);
    }

    private final Runnable reconnect = new Runnable() {
        @Override public void run() {
            waitingRetry = false;
            closeGatt();
            connect();
        }
    };

    private void closeGatt() {
        ready = false;
        readied = false;
        writing = false;
        h.removeCallbacks(writeDone);
        h.removeCallbacks(readyFallback);
        writeChar = null;
        if (gatt != null) {
            try { gatt.disconnect(); } catch (Throwable ignored) {}
            try { gatt.close(); } catch (Throwable ignored) {}
            gatt = null;
        }
    }

    private void pump() {
        if (!ready || writing || queue.isEmpty() || gatt == null || writeChar == null) return;
        Cmd c = queue.removeFirst();
        byte[] packet = zengge.wrap(c.data, c.type);
        writeChar.setWriteType(writeType);
        writeChar.setValue(packet);
        boolean ok;
        try {
            ok = gatt.writeCharacteristic(writeChar);
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            // stack busy: put it back (a newer frame may still replace it) and try again shortly
            queue.addFirst(c);
            h.removeCallbacks(retryPump);
            h.postDelayed(retryPump, GAP_ACKED_MS);
            return;
        }
        writing = true;
        // onCharacteristicWrite normally clears this; the timer is a safety net.
        h.removeCallbacks(writeDone);
        h.postDelayed(writeDone, WRITE_TIMEOUT_MS);
    }

    private final Runnable retryPump = new Runnable() {
        @Override public void run() { pump(); }
    };

    private long gap() {
        return writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT ? GAP_ACKED_MS : GAP_UNACKED_MS;
    }

    /** Notifications are on (or the fallback timer ran): commands may go. */
    private void goReady(BluetoothGatt g) {
        if (g == null || g != gatt || readied || writeChar == null) return;
        readied = true;
        h.removeCallbacks(readyFallback);
        state(true, "Ruban connecté");
        queue.addFirst(new Cmd(Zengge.QUERY_STATE, Zengge.TYPE_QUERY));
        pump();
    }

    private final Runnable readyFallback = new Runnable() {
        @Override public void run() { goReady(gatt); }
    };

    private final Runnable writeDone = new Runnable() {
        @Override public void run() {
            writing = false;
            pump();
        }
    };

    private void state(final boolean r, final String text) {
        ready = r;
        listener.onLinkState(r, text);
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(final BluetoothGatt g, final int status, final int newState) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (g != gatt) return;
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        state(false, "Connecté, préparation…");
                        // short connection interval from the start: discovery and every command go faster
                        try { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH); } catch (Throwable ignored) {}
                        if (!g.requestMtu(247)) g.discoverServices();
                    } else {
                        state(false, "Ruban déconnecté (" + status + "), nouvel essai…");
                        retry();
                    }
                }
            });
        }

        @Override public void onMtuChanged(final BluetoothGatt g, int mtu, int status) {
            Log.i(TAG, "mtu " + mtu + " status " + status);
            h.post(new Runnable() {
                @Override public void run() { if (g == gatt) g.discoverServices(); }
            });
        }

        @Override public void onServicesDiscovered(final BluetoothGatt g, int status) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (g != gatt) return;
                    BluetoothGattService svc = g.getService(UUID.fromString(Zengge.SERVICE));
                    if (svc == null) {
                        state(false, "Service FFFF absent : est-ce le bon ruban ?");
                        retry();
                        return;
                    }
                    writeChar = svc.getCharacteristic(UUID.fromString(Zengge.WRITE_CHAR));
                    BluetoothGattCharacteristic notify = svc.getCharacteristic(UUID.fromString(Zengge.NOTIFY_CHAR));
                    if (notify != null) {
                        g.setCharacteristicNotification(notify, true);
                        BluetoothGattDescriptor d = notify.getDescriptor(UUID.fromString(Zengge.CCCD));
                        if (d != null) {
                            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                            g.writeDescriptor(d);
                        }
                    }
                    if (writeChar == null) {
                        state(false, "Caractéristique FF01 absente");
                        retry();
                        return;
                    }
                    writeType = (writeChar.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                        ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
                    Log.i(TAG, "FF01 properties 0x" + Integer.toHexString(writeChar.getProperties())
                        + (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT ? " write with response" : " write no response"));
                    // the first command waits for the descriptor write (onDescriptorWrite), at most
                    // READY_FALLBACK_MS
                    h.removeCallbacks(readyFallback);
                    h.postDelayed(readyFallback, READY_FALLBACK_MS);
                }
            });
        }

        @Override public void onDescriptorWrite(final BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            h.post(new Runnable() {
                @Override public void run() { goReady(g); }
            });
        }

        @Override public void onCharacteristicWrite(final BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            h.post(new Runnable() {
                @Override public void run() {
                    if (g != gatt) return;
                    h.removeCallbacks(writeDone);
                    h.postDelayed(writeDone, gap());
                }
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            Zengge.State s = Zengge.parse(c.getValue());
            if (s != null) listener.onStripState(s);
        }
    };
}
