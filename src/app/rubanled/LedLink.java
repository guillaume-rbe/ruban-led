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
    private static final long RETRY_MS = 8000;
    private static final long WRITE_GAP_MS = 40;
    /** Only the newest commands matter (a blink makes many): drop the oldest beyond this. */
    private static final int MAX_QUEUE = 6;

    private final Context ctx;
    private final Listener listener;
    private final HandlerThread thread = new HandlerThread("led-link");
    private final Handler h;
    private final Zengge zengge = new Zengge();
    private final ArrayDeque<Cmd> queue = new ArrayDeque<>();

    private static final class Cmd {
        final byte[] data;
        final int type;
        Cmd(byte[] data, int type) { this.data = data; this.type = type; }
    }

    private String address;
    private boolean started;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeChar;
    /** With a response when FF01 allows it (0x0C on this strip): the strip acks each command. */
    private int writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
    private boolean ready;
    private boolean writing;

    LedLink(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        thread.start();
        h = new Handler(thread.getLooper());
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
                queue.addLast(new Cmd(cmd, type));
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
        if (started) h.postDelayed(reconnect, RETRY_MS);
    }

    private final Runnable reconnect = new Runnable() {
        @Override public void run() {
            closeGatt();
            connect();
        }
    };

    private void closeGatt() {
        ready = false;
        writing = false;
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
            // stack busy: try again shortly with the same slot
            h.postDelayed(new Runnable() {
                @Override public void run() { pump(); }
            }, WRITE_GAP_MS);
            return;
        }
        writing = true;
        // onCharacteristicWrite normally clears this; the timer is a safety net.
        h.postDelayed(writeDone, 1000);
    }

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
                    // let the descriptor write finish before the first command
                    h.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (g != gatt) return;
                            state(true, "Ruban connecté");
                            queue.addFirst(new Cmd(Zengge.QUERY_STATE, Zengge.TYPE_QUERY));
                            pump();
                        }
                    }, 300);
                }
            });
        }

        @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            h.removeCallbacks(writeDone);
            h.postDelayed(writeDone, WRITE_GAP_MS);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            Zengge.State s = Zengge.parse(c.getValue());
            if (s != null) listener.onStripState(s);
        }
    };
}
