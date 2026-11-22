package app.rubanled;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/**
 * The debug journal (0.20, owner 22 Nov): everything the app sees and does, kept on the dash
 * and sent on demand, so a problem can be read afterwards without a new build for each kind of
 * log. One plain-text file a day in the app's private storage, lines "HH:mm:ss.SSS TAG message".
 * Kept DAYS days and MAX_BYTES at most, the oldest go first. Writing runs on its own thread: the
 * rules' thread only hands a string over.
 *
 * Tags (see the README): EVT events of the events card, REGLE rule decisions, CAN car data,
 * BLE commands and strip replies, LIEN Bluetooth link, ECRAN dash commands, APP start / settings /
 * errors, ENVOI uploads.
 *
 * Sending: HTTPS PUT of a gzip to the server named in files/journaux.conf, with the client
 * certificate files/client.p12 (both put there over ADB, never in the public repository; see the
 * README). The server only accepts it while the owner opens it from Home Assistant.
 */
final class Journal {
    private static final String TAG = "rubanled";
    static final int DAYS = 7;
    /** 150 MB: a week with long drives at full detail (owner, 23 Nov: 3 h trips at weekends). */
    static final long MAX_BYTES = 150L * 1024 * 1024;
    /** The server takes 20 MB at most (about 150-200 MB of text once gzipped): the oldest days are left out beyond. */
    private static final long MAX_UPLOAD = 20L * 1024 * 1024 - 64 * 1024;
    private static final long FLUSH_MS = 2000;

    private static Journal instance;

    private final Context ctx;
    private final File dir;
    private final HandlerThread thread = new HandlerThread("journal", Process.THREAD_PRIORITY_BACKGROUND);
    private final Handler h;
    private final StringBuilder pending = new StringBuilder();
    private final SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.FRANCE);
    private final SimpleDateFormat day = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE);
    private volatile boolean full = true;

    private Journal(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        dir = new File(this.ctx.getFilesDir(), "journal");
        dir.mkdirs();
        thread.start();
        h = new Handler(thread.getLooper());
        h.postDelayed(flush, FLUSH_MS);
        h.post(new Runnable() { @Override public void run() { prune(); } });
    }

    static synchronized Journal get(Context ctx) {
        if (instance == null) instance = new Journal(ctx);
        return instance;
    }

    /** Full detail (every CAN message, every Bluetooth frame) or only the main lines. */
    void setFull(boolean full) {
        this.full = full;
    }

    boolean full() {
        return full;
    }

    /** Adds a line; cheap, any thread. */
    void log(String tag, String msg) {
        long now = System.currentTimeMillis();
        String line;
        synchronized (time) { line = time.format(new Date(now)) + " " + tag + " " + msg + "\n"; }
        synchronized (pending) {
            pending.append(line);
            // a burst (a full-detail animation) must not hold much in memory
            if (pending.length() > 256 * 1024) h.post(flush);
        }
    }

    /** A detail line, kept only in full mode. */
    void detail(String tag, String msg) {
        if (full) log(tag, msg);
    }

    private final Runnable flush = new Runnable() {
        @Override public void run() {
            h.removeCallbacks(this);
            String text;
            synchronized (pending) {
                text = pending.toString();
                pending.setLength(0);
            }
            if (!text.isEmpty()) {
                String name;
                synchronized (day) { name = day.format(new Date()) + ".log"; }
                try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, name), true), StandardCharsets.UTF_8)) {
                    w.write(text);
                } catch (IOException e) {
                    Log.w(TAG, "journal", e);
                }
            }
            h.postDelayed(this, FLUSH_MS);
        }
    };

    /** Days kept: DAYS, and MAX_BYTES in all (the oldest file goes first). Journal thread. */
    private void prune() {
        File[] files = sorted();
        long cutoff = System.currentTimeMillis() - DAYS * 24L * 3600 * 1000;
        long total = 0;
        for (File f : files) total += f.length();
        for (File f : files) {
            if (f.lastModified() < cutoff || total > MAX_BYTES) {
                total -= f.length();
                f.delete();
            }
        }
        h.postDelayed(new Runnable() { @Override public void run() { prune(); } }, 3600 * 1000L);
    }

    /** Journal files, oldest first. */
    private File[] sorted() {
        File[] files = dir.listFiles();
        if (files == null) return new File[0];
        Arrays.sort(files);
        return files;
    }

    // ---- sending ----

    interface Result {
        void done(boolean ok, String text);
    }

    /**
     * Where the current (or last) upload stands, shown by the app under the send buttons
     * (owner, 22 Nov): one line per step, "✓" done, "…" running, "✗" failed with the reason, and
     * the progress bar (-1: hidden).
     */
    static volatile String steps = "";
    static volatile int percent = -1;
    static volatile boolean sending;
    private final List<String> stepLines = new ArrayList<>();

    private void stepStart(String what, int pct) {
        stepLines.add("…  " + what);
        percent = pct;
        publish();
    }

    private void stepDone(String detail) {
        int i = stepLines.size() - 1;
        if (i < 0) return;
        stepLines.set(i, "✓  " + stepLines.get(i).substring(3) + (detail == null ? "" : " : " + detail));
        publish();
    }

    private void stepFail(String why) {
        int i = stepLines.size() - 1;
        if (i < 0) stepLines.add("✗  " + why);
        else stepLines.set(i, "✗  " + stepLines.get(i).substring(3) + " : " + why);
        publish();
    }

    private void publish() {
        StringBuilder b = new StringBuilder();
        for (String s : stepLines) b.append(b.length() == 0 ? "" : "\n").append(s);
        steps = b.toString();
    }

    /** "3 jours, 1,2 Mo" for the app. */
    String sizeText() {
        File[] f = sorted();
        long total = 0;
        for (File x : f) total += x.length();
        return "journal : " + f.length + " jour" + (f.length > 1 ? "s" : "") + ", "
            + String.format(Locale.FRANCE, "%.1f Mo", total / 1048576.0) + " (gardé " + DAYS + " jours, " + (MAX_BYTES >> 20) + " Mo max)";
    }

    /** Whether the certificate and the server address have been put on the dash. */
    boolean configured() {
        File d = ctx.getFilesDir();
        return new File(d, "client.p12").isFile() && new File(d, "journaux.conf").isFile();
    }

    /**
     * Sends the last `hours` hours (0: all that is kept) with a header (state, settings, app's own
     * logcat). Runs on the journal thread; result on that thread too.
     */
    void send(final int hours, final String header, final Result result) {
        if (sending) return;
        sending = true;
        stepLines.clear();
        stepLines.add("Envoi demandé à " + new SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(new Date())
            + (hours > 0 ? " (dernières " + hours + " h)" : " (tout le journal)"));
        stepLines.set(0, "✓  " + stepLines.get(0));
        percent = 0;
        publish();
        h.post(new Runnable() {
            @Override public void run() {
                flush.run();
                try {
                    String text = upload(hours, header);
                    stepLines.add("✓  Terminé : " + text);
                    percent = 100;
                    publish();
                    log("ENVOI", "ok " + text);
                    result.done(true, text);
                } catch (Throwable t) {
                    String why = explain(t);
                    stepFail(why);
                    percent = -1;
                    log("ENVOI", "échec " + why);
                    Log.w(TAG, "journal upload", t);
                    result.done(false, why);
                } finally {
                    sending = false;
                }
            }
        });
    }

    private String upload(int hours, String header) throws Exception {
        stepStart("Certificat et adresse du serveur", 5);
        if (!configured()) throw new IOException("certificat non installé (voir le README, « Journaux »)");
        Properties conf = new Properties();
        try (InputStream in = new FileInputStream(new File(ctx.getFilesDir(), "journaux.conf"))) { conf.load(in); }
        String base = conf.getProperty("url", "").trim();
        String pass = conf.getProperty("password", "");
        if (!base.startsWith("https://")) throw new IOException("adresse du serveur invalide dans journaux.conf");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(new File(ctx.getFilesDir(), "client.p12"))) { ks.load(in, pass.toCharArray()); }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass.toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);       // the server is checked against Android's own CAs
        stepDone(null);

        File body = build(hours, header);
        long size = body.length();
        String name = "ruban-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.FRANCE).format(new Date()) + ".log.gz";

        stepStart("Connexion au serveur", 35);
        HttpsURLConnection c = (HttpsURLConnection) new URL(base.replaceAll("/+$", "") + "/journaux/" + name).openConnection();
        c.setSSLSocketFactory(ssl.getSocketFactory());
        c.setConnectTimeout(10000);
        c.setReadTimeout(60000);
        c.setRequestMethod("PUT");
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(size);
        c.setRequestProperty("Content-Type", "application/gzip");
        c.connect();                                       // TLS + certificate exchange happen here
        stepDone(null);

        stepStart("Envoi de " + kb(size), 40);
        long t0 = android.os.SystemClock.elapsedRealtime();
        try (OutputStream out = c.getOutputStream(); InputStream in = new FileInputStream(body)) {
            byte[] chunk = new byte[32 * 1024];
            long sent = 0;
            int n;
            while ((n = in.read(chunk)) > 0) {
                out.write(chunk, 0, n);
                sent += n;
                percent = 40 + (int) (55L * sent / Math.max(1, size));
            }
        }
        long s = Math.max(1, (android.os.SystemClock.elapsedRealtime() - t0) / 1000);
        stepDone(s + " s");

        stepStart("Réponse du serveur", 96);
        int code = c.getResponseCode();
        c.disconnect();
        if (code == 201 || code == 204) stepDone(code + " reçu");
        body.delete();
        if (code == 201 || code == 204) return name + " (" + kb(size) + ")";
        if (code == 400) throw new IOException("certificat refusé par le serveur (400)");
        if (code == 429) throw new IOException("trop d'envois, réessayer dans une minute (429)");
        if (code == 413) throw new IOException("journal trop gros pour le serveur (413)");
        throw new IOException("réponse " + code + " du serveur");
    }

    /**
     * Header + the lines of the period, gzipped into a file in the app's cache, streamed (the whole
     * journal can be 150 MB: never all in memory). Over MAX_UPLOAD (rare: about 10 MB of text per
     * MB of gzip), the oldest days are left out until it fits.
     */
    private File build(int hours, String header) throws IOException {
        stepStart("Lecture et compression du journal", 10);
        long since = hours <= 0 ? 0 : System.currentTimeMillis() - hours * 3600L * 1000;
        List<File> files = new ArrayList<>();
        for (File file : sorted()) if (since == 0 || file.lastModified() >= since) files.add(file);
        File out = new File(ctx.getCacheDir(), "envoi.log.gz");
        int dropped = 0;
        long[] lines = new long[1];
        while (true) {
            List<File> part = files.subList(dropped, files.size());
            write(out, header + (dropped > 0 ? "\n(jours les plus anciens retirés pour tenir en " + (MAX_UPLOAD >> 20) + " Mo : " + dropped + ")\n" : ""),
                part, since, lines);
            if (out.length() <= MAX_UPLOAD || dropped >= files.size() - 1) break;
            dropped++;
        }
        stepDone(lines[0] + " lignes, " + kb(out.length()) + (dropped > 0 ? ", " + dropped + " jour(s) ancien(s) retiré(s) (" + (MAX_UPLOAD >> 20) + " Mo max)" : ""));
        return out;
    }

    private void write(File out, String header, List<File> files, long since, long[] lines) throws IOException {
        long total = 0, read = 0;
        for (File f : files) total += f.length();
        lines[0] = 0;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE);
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(out), 64 * 1024), StandardCharsets.UTF_8)) {
            w.write(header);
            w.write("\n==== JOURNAL ====\n");
            for (File file : files) {
                String d = file.getName().replace(".log", "");
                long dayStart = 0;
                try { dayStart = f.parse(d).getTime(); } catch (Exception ignored) {}
                try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8), 64 * 1024)) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        read += line.length() + 1;
                        if (since > 0 && dayStart > 0 && line.length() >= 12 && dayStart + parseTime(line) < since) continue;
                        w.write(d);
                        w.write(' ');
                        w.write(line);
                        w.write('\n');
                        if (++lines[0] % 5000 == 0) percent = 10 + (int) (20L * read / Math.max(1, total));
                    }
                }
            }
        }
    }

    private static String kb(long bytes) {
        return bytes < 1024 * 1024 ? (bytes / 1024) + " Ko" : String.format(Locale.FRANCE, "%.1f Mo", bytes / 1048576.0);
    }

    private static long parseTime(String line) {
        try {
            int hh = Integer.parseInt(line.substring(0, 2)), mm = Integer.parseInt(line.substring(3, 5));
            int ss = Integer.parseInt(line.substring(6, 8)), ms = Integer.parseInt(line.substring(9, 12));
            return ((hh * 60L + mm) * 60 + ss) * 1000 + ms;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Bytes as hex, at most max bytes (then "…"). */
    static String hex(byte[] b, int max) {
        if (b == null) return "null";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < b.length && i < max; i++) s.append(String.format("%02x", b[i] & 0xff));
        if (b.length > max) s.append("…(").append(b.length).append(")");
        return s.toString();
    }

    /** A crash: written at once (the process is about to die), then the usual handler runs. */
    void crash(Thread t, Throwable e) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, new SimpleDateFormat("yyyy-MM-dd", Locale.FRANCE).format(new Date()) + ".log"), true), StandardCharsets.UTF_8)) {
            synchronized (pending) { w.write(pending.toString()); pending.setLength(0); }
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            w.write(new SimpleDateFormat("HH:mm:ss.SSS", Locale.FRANCE).format(new Date()) + " APP PLANTAGE fil " + t.getName() + " : "
                + sw.toString().replace("\n", "\n    ") + "\n");
        } catch (Throwable ignored) {
        }
    }

    /** The app's own logcat (an app may read its own lines without any permission). */
    static String logcat(int maxLines) {
        StringBuilder b = new StringBuilder();
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-v", "threadtime", "-t", String.valueOf(maxLines),
                "--pid=" + android.os.Process.myPid()});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) b.append(line).append('\n');
            }
        } catch (Throwable t) {
            b.append("logcat illisible : ").append(t).append('\n');
        }
        return b.toString();
    }

    static String device() {
        return Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT
            + "), build " + Build.DISPLAY;
    }

    /** What a failure means, in the owner's words (the usual one: the server isn't open). */
    private static String explain(Throwable t) {
        String m = String.valueOf(t.getMessage());
        if (t instanceof javax.net.ssl.SSLPeerUnverifiedException || m.contains("Hostname") || m.contains("not verified")
                || t instanceof javax.net.ssl.SSLHandshakeException)
            return "serveur fermé : activer « Journaux Ruban LED » dans Home Assistant (" + m + ")";
        if (t instanceof java.net.UnknownHostException) return "pas d'Internet (partage de connexion ?)";
        if (t instanceof java.net.SocketTimeoutException || t instanceof java.net.ConnectException) return "serveur injoignable (" + m + ")";
        return m;
    }
}
