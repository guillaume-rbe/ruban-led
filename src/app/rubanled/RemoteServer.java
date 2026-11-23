package app.rubanled;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Control from the iPhone (0.25, owner 23 Nov): a small web page served on the CarPlay Wi-Fi only.
 *
 * Wireless CarPlay puts the iPhone on a Wi-Fi Direct group that the dash runs (xcertplay's
 * createGroup), on an interface named p2p-…; the page answers there and nowhere else: a
 * connection that came in on any other interface (home Wi-Fi wlan0, loopback…) is closed without
 * a reply. Every path starts with the code shown in the app (6 digits, kept on the dash), so the
 * address in the iPhone's favourites is http://&lt;dash&gt;:8080/&lt;code&gt;. Off by default (Settings.remote).
 *
 * It only does what the app's own screen does: the same actions, colours and rule switches, sent
 * to LedService as the screen sends them. Nothing here reaches the car.
 */
final class RemoteServer {
    static final int PORT = 8080;
    /** Shown in the app: the address to open on the iPhone, or why there is none. */
    static volatile String addressText = "";
    /** Counts colour and rule changes made from the iPhone, once applied: the app's screen redraws them. */
    static volatile int changes;

    interface Host {
        /** Runs an intent through LedService.handle, on its rules thread. */
        void handle(Intent i);
        void event(String what);
    }

    private static final String TAG = "rubanled";
    private static final int MAX_REQUEST = 8192;
    private static final int READ_TIMEOUT_MS = 5000;
    private static final long ADDRESS_CHECK_MS = 5000;
    /** Wrong codes: after this many from one address, it is ignored for LOCK_MS. */
    private static final int MAX_WRONG = 5;
    private static final long LOCK_MS = 60000;
    /** Rule switches the page may change (the same as the app's Règles card, dash settings aside). */
    static final String[] RULES = {
        "intro", "holdAnim", "apFlash", "reverseWhite", "doorBlink", "hazard", "autoPlay", "night",
    };

    private final Context ctx;
    private final Host host;
    private final String code;
    private volatile boolean running;
    private ServerSocket server;
    private Thread acceptThread, addressThread;
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Map<String, int[]> wrong = new HashMap<>();
    private final Map<String, Long> lockedUntil = new HashMap<>();
    private String lastAddress;

    RemoteServer(Context ctx, Host host, String code) {
        this.ctx = ctx.getApplicationContext();
        this.host = host;
        this.code = code;
    }

    void start() {
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "remote-accept");
        acceptThread.start();
        addressThread = new Thread(new Runnable() {
            @Override public void run() { addressLoop(); }
        }, "remote-address");
        addressThread.start();
    }

    void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        if (addressThread != null) addressThread.interrupt();
        pool.shutdownNow();
        addressText = "";
    }

    // ---- addresses ----

    /** The dash's IPv4 address on the CarPlay Wi-Fi (an interface named p2p…), or null. */
    static String carPlayAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || !isCarPlay(ni.getName())) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Wi-Fi Direct (CarPlay) interfaces only; never wlan0 (home Wi-Fi) or anything else. */
    static boolean isCarPlay(String name) {
        return name != null && name.startsWith("p2p");
    }

    /** Every interface with an IPv4 address, for the journal ("wlan0 10.0.0.5, p2p-wlan0-0 …"). */
    private static String allAddresses() {
        StringBuilder b = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    if (b.length() > 0) b.append(", ");
                    b.append(ni.getName()).append(' ').append(a.getHostAddress());
                }
            }
        } catch (Throwable ignored) {}
        return b.length() == 0 ? "aucune" : b.toString();
    }

    private void addressLoop() {
        while (running) {
            String a = carPlayAddress();
            String url = a == null ? null : "http://" + a + ":" + PORT + "/" + code;
            addressText = url != null ? url
                : "En attente du Wi-Fi CarPlay (adresse affichée quand l'iPhone est connecté)";
            if (url == null ? lastAddress != null : !url.equals(lastAddress)) {
                lastAddress = url;
                host.event(url != null ? "iPhone : page sur " + url
                    : "iPhone : plus de Wi-Fi CarPlay (adresses : " + allAddresses() + ")");
            }
            try { Thread.sleep(ADDRESS_CHECK_MS); } catch (InterruptedException e) { return; }
        }
    }

    // ---- HTTP ----

    private void acceptLoop() {
        try {
            server = new ServerSocket();
            server.setReuseAddress(true);    // before bind: a restart (new code) gets the port at once
            server.bind(new java.net.InetSocketAddress(PORT));
        } catch (IOException e) {
            host.event("iPhone : page impossible (port " + PORT + " : " + e.getMessage() + ")");
            return;
        }
        while (running) {
            final Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (running) Log.w(TAG, "remote accept", e);
                continue;
            }
            try {
                pool.execute(new Runnable() {
                    @Override public void run() { serve(s); }
                });
            } catch (Throwable t) {
                close(s);
            }
        }
    }

    private void serve(Socket s) {
        try {
            NetworkInterface ni = NetworkInterface.getByInetAddress(s.getLocalAddress());
            if (ni == null || !isCarPlay(ni.getName())) {
                // never on the home Wi-Fi or any other network: closed without a word
                Log.i(TAG, "remote: refused on " + (ni == null ? "?" : ni.getName()));
                return;
            }
            s.setSoTimeout(READ_TIMEOUT_MS);
            String peer = s.getInetAddress().getHostAddress();
            String[] req = readRequest(s.getInputStream());
            if (req == null) return;
            String method = req[0], path = req[1];
            OutputStream out = s.getOutputStream();
            if (locked(peer)) { reply(out, 429, "text/plain", "Trop d'essais, réessayer dans une minute"); return; }

            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }
            String[] parts = path.split("/");
            // parts[0] is "" (path starts with "/"), parts[1] the code
            if (parts.length < 2 || !code.equals(parts[1])) {
                if (!"/favicon.ico".equals(path)) wrongCode(peer);
                reply(out, 404, "text/plain", "Introuvable");
                return;
            }
            Map<String, String> p = params(query);
            String what = parts.length > 2 ? parts[2] : "";
            if (what.isEmpty() && "GET".equals(method)) {
                reply(out, 200, "text/html; charset=utf-8", page());
            } else if ("state".equals(what) && "GET".equals(method)) {
                reply(out, 200, "application/json; charset=utf-8", state());
            } else if ("action".equals(what) && "POST".equals(method)) {
                String a = action(p.get("do"));
                if (a == null) { reply(out, 400, "text/plain", "Action inconnue"); return; }
                host.handle(new Intent(a));
                reply(out, 200, "application/json", "{\"ok\":true}");
            } else if ("colour".equals(what) && "POST".equals(method)) {
                Intent i = new Intent(LedService.ACTION_SET_COLOUR)
                    .putExtra("target", "anim".equals(p.get("t")) ? LedService.TARGET_ANIM : LedService.TARGET_STEADY)
                    .putExtra("hue", clamp(num(p.get("h")), 0, 359))
                    .putExtra("sat", clamp(num(p.get("s")), 0, 100))
                    .putExtra("pct", clamp(num(p.get("p")), 1, 100));
                host.handle(i);
                reply(out, 200, "application/json", "{\"ok\":true}");
            } else if ("night".equals(what) && "POST".equals(method)) {
                Settings ns = Settings.load(ctx);
                ns.nightStartWinter = clamp(num(p.get("w")), 0, 23);
                ns.nightStartSummer = clamp(num(p.get("s")), 0, 23);
                ns.nightEnd = clamp(num(p.get("e")), 0, 23);
                ns.nightPct = clamp(num(p.get("p")), 1, 100);
                ns.save(ctx);
                host.handle(new Intent(LedService.ACTION_RELOAD));
                reply(out, 200, "application/json", "{\"ok\":true}");
            } else if ("rule".equals(what) && "POST".equals(method)) {
                if (!setRule(p.get("k"), "1".equals(p.get("v")))) { reply(out, 400, "text/plain", "Règle inconnue"); return; }
                host.handle(new Intent(LedService.ACTION_RELOAD));
                reply(out, 200, "application/json", "{\"ok\":true}");
            } else {
                reply(out, 404, "text/plain", "Introuvable");
            }
        } catch (Throwable t) {
            Log.w(TAG, "remote", t);
        } finally {
            close(s);
        }
    }

    /** {method, path} from the request line; headers read and dropped. Null if malformed. */
    private static String[] readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, last4 = 0;
        while ((c = in.read()) != -1) {
            b.write(c);
            last4 = (last4 << 8) | c;
            if (last4 == 0x0D0A0D0A || (last4 & 0xFFFF) == 0x0A0A) break;
            if (b.size() > MAX_REQUEST) return null;
        }
        String head = new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
        int eol = head.indexOf('\n');
        String line = (eol < 0 ? head : head.substring(0, eol)).trim();
        String[] f = line.split(" ");
        if (f.length < 2 || !f[1].startsWith("/")) return null;
        return new String[] {f[0], f[1]};
    }

    private static void reply(OutputStream out, int status, String type, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 400 ? "Bad Request" : status == 404 ? "Not Found" : "Too Many Requests";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
            + "Content-Type: " + type + "\r\n"
            + "Content-Length: " + data.length + "\r\n"
            + "Cache-Control: no-store\r\n"
            + "X-Content-Type-Options: nosniff\r\n"
            + "Referrer-Policy: no-referrer\r\n"
            + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
    }

    private synchronized boolean locked(String peer) {
        Long until = lockedUntil.get(peer);
        return until != null && System.currentTimeMillis() < until;
    }

    private synchronized void wrongCode(String peer) {
        int[] n = wrong.get(peer);
        if (n == null) wrong.put(peer, n = new int[1]);
        if (++n[0] >= MAX_WRONG) {
            n[0] = 0;
            lockedUntil.put(peer, System.currentTimeMillis() + LOCK_MS);
            host.event("iPhone : mauvais code " + MAX_WRONG + " fois depuis " + peer + ", bloqué 1 min");
        }
    }

    // ---- what the page does ----

    private static String action(String name) {
        if (name == null) return null;
        switch (name) {
            case "on": return LedService.ACTION_ON;
            case "off": return LedService.ACTION_OFF;
            case "default": return LedService.ACTION_DEFAULT;
            case "intro": return LedService.ACTION_INTRO;
            case "exit": return LedService.ACTION_EXIT;
            case "blink": return LedService.ACTION_TEST_BLINK;
            case "hold": return LedService.ACTION_HOLD_TEST;
            default: return null;
        }
    }

    /** One of RULES, saved like the app's switches (then LedService reloads). */
    private boolean setRule(String key, boolean v) {
        if (key == null) return false;
        Settings s = Settings.load(ctx);
        switch (key) {
            case "intro": s.intro = v; break;
            case "holdAnim": s.holdAnim = v; break;
            case "apFlash": s.apFlash = v; break;
            case "reverseWhite": s.reverseWhite = v; break;
            case "doorBlink": s.doorBlink = v; s.bootAndFrunk = v; break;
            case "hazard": s.hazard = v; break;
            case "autoPlay": s.autoPlay = v; break;
            case "night": s.night = v; break;
            default: return false;
        }
        s.save(ctx);
        return true;
    }

    private String state() {
        Settings s = Settings.load(ctx);
        StringBuilder b = new StringBuilder("{");
        str(b, "link", LedService.linkText).append(',');
        str(b, "strip", LedService.stripText).append(',');
        str(b, "doors", LedService.doorsText + "   ·   " + LedService.feedText).append(',');
        str(b, "rule", LedService.ruleText).append(',');
        str(b, "presence", LedService.presenceText).append(',');
        str(b, "events", LedService.eventsText).append(',');
        b.append("\"steady\":[").append(s.defaultHue).append(',').append(s.defaultSat).append(',').append(s.dayPct).append("],");
        b.append("\"anim\":[").append(s.blinkHue).append(',').append(s.blinkSat).append(',').append(s.blinkPct).append("],");
        b.append("\"rules\":{");
        boolean[] v = {s.intro, s.holdAnim, s.apFlash, s.reverseWhite, s.doorBlink, s.hazard, s.autoPlay, s.night};
        for (int i = 0; i < RULES.length; i++) {
            if (i > 0) b.append(',');
            b.append('"').append(RULES[i]).append("\":").append(v[i]);
        }
        b.append("},");
        str(b, "night", nightText(s)).append(',');
        b.append("\"nightVals\":[").append(s.nightStartWinter).append(',').append(s.nightStartSummer).append(',')
            .append(s.nightEnd).append(',').append(s.nightPct).append(']');
        return b.append('}').toString();
    }

    /** "Hiver dès 19 h, été dès 21 h, jusqu'à 7 h : tout à 20 %", as the app shows it. */
    static String nightText(Settings s) {
        return "Hiver dès " + s.nightStartWinter + " h, été dès " + s.nightStartSummer + " h, jusqu'à "
            + s.nightEnd + " h : tout à " + s.nightPct + " %";
    }

    private static StringBuilder str(StringBuilder b, String key, String value) {
        b.append('"').append(key).append("\":\"");
        String v = value == null ? "" : value;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c == '<' || c == '>' || c == '&') b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.append('"');
    }

    private String page;

    /** res/raw/remote.html, read once. */
    private String page() throws IOException {
        if (page != null) return page;
        try (InputStream in = ctx.getResources().openRawResource(R.raw.remote)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            page = new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
        return page;
    }

    private static Map<String, String> params(String query) {
        Map<String, String> m = new HashMap<>();
        for (String kv : query.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0) m.put(kv.substring(0, e), kv.substring(e + 1));
        }
        return m;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s); } catch (Throwable t) { return 0; }
    }

    private static int clamp(int x, int lo, int hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private static void close(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }
}
