package app.rubanled;

import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Updates from the GitHub releases of guillaume-rbe/ruban-led: the newest release tagged
 * {@code ruban-led-v<versionCode>} with a higher versionCode than this one, whose
 * {@code ruban-led.apk} matches the SHA-256 in {@code ruban-led.apk.sha256}. Installed through
 * Android's PackageInstaller, locked to this package, so Android shows its own confirm screen and
 * only accepts an APK signed with the same key. Runs only when the user taps the button.
 *
 * Downloads go through the app's own connection; when Android refuses it a socket ("socket
 * failed: EPERM", seen on the A007 after the update that added the INTERNET permission), they go
 * through Android's DownloadManager instead, which downloads on the app's behalf into its own
 * external files folder (read, then deleted).
 */
final class Updater {
    interface Listener {
        void onUpdateStatus(String text);
    }

    /** Animations update (0.28): the steps so far, the download's percent (-1: no bar, -2: size unknown), done. */
    interface AnimListener {
        void onAnimSteps(String steps, int percent, boolean done);
    }

    /** Bytes received so far, and the total (-1 if the server doesn't say). */
    private interface Progress {
        void bytes(long done, long total);
    }

    static final String REPO = "guillaume-rbe/ruban-led";

    /**
     * What the updates are doing, for the app's screen and the iPhone page (0.28): either can start
     * them, both show the same steps. percent: -1 no bar, -2 size unknown, else 0-100.
     */
    static volatile String appText = "", animText = "";
    static volatile int appPercent = -1, animPercent = -1;
    static volatile boolean appBusy, animBusy;
    private static final String RELEASES = "https://api.github.com/repos/" + REPO + "/releases?per_page=30";
    private static final String TAG_PREFIX = "ruban-led-v";
    private static final String APK = "ruban-led.apk";
    private static final String SHA = "ruban-led.apk.sha256";
    private static final String LOG = "rubanled";
    private static final String UPDATE_CHANNEL = "update";
    private static final int UPDATE_NOTE = 7;
    static final String ANIM_PATH = "animations/animations.json";
    /** The last commit of main that changed the file: its sha names that exact version of the file. */
    private static final String ANIM_COMMITS = "https://api.github.com/repos/" + REPO
        + "/commits?sha=main&per_page=1&path=" + ANIM_PATH;

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Set once the app's own socket was refused: every download then goes through Android's. */
    private boolean viaSystem;
    private static final long DM_TIMEOUT_MS = 180000;
    /** Android's downloader given up after this long without a byte (waiting for a network). */
    private static final long DM_STALL_MS = 30000;
    private static final String NO_NETWORK = "pas de connexion Internet";

    Updater(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    static int installedVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** Starts the app update unless one runs; false if it already runs. */
    static synchronized boolean startApp(Context ctx) {
        if (appBusy) return false;
        appBusy = true;
        new Updater(ctx, null).run();
        return true;
    }

    /** Starts the animations update unless one runs; false if it already runs. */
    static synchronized boolean startAnimations(Context ctx) {
        if (animBusy) return false;
        animBusy = true;
        animText = "";
        animPercent = -1;
        new Updater(ctx, null).runAnimations(null);
        return true;
    }

    /** Check, and install if there is a newer release. */
    void run() {
        appBusy = true;
        appPercent = -1;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    if (!ctx.getPackageManager().canRequestPackageInstalls()) {
                        status("Autoriser d'abord « Installer des applis inconnues » pour Ruban LED : "
                            + "bouton « Mise à jour » sur l'écran de la voiture, une seule fois");
                        return;
                    }
                    check();
                } catch (Throwable t) {
                    Log.w(LOG, "update failed", t);
                    String msg = String.valueOf(t.getMessage());
                    status("Mise à jour impossible : " + msg
                        + (viaSystem && !msg.startsWith(NO_NETWORK) ?" (même via le gestionnaire de téléchargements d'Android). "
                            + "Redémarrer l'écran une fois corrige un accès réseau refusé." : ""));
                } finally {
                    appPercent = -1;
                    appBusy = false;
                }
            }
        }, "updater").start();
    }

    private void check() throws Exception {
        int current = installedVersion(ctx);
        status("Recherche d'une mise à jour…");
        JSONArray releases = new JSONArray(new String(fetch(RELEASES, "releases.json", 1 << 20), "UTF-8"));
        JSONObject best = null;
        int bestCode = current;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject r = releases.getJSONObject(i);
            if (r.optBoolean("draft")) continue;
            String tag = r.optString("tag_name");
            if (!tag.startsWith(TAG_PREFIX)) continue;
            int code;
            try {
                code = Integer.parseInt(tag.substring(TAG_PREFIX.length()));
            } catch (NumberFormatException e) {
                continue;
            }
            if (code > bestCode) {
                best = r;
                bestCode = code;
            }
        }
        if (best == null) {
            status("Déjà à jour (version " + current + ")");
            return;
        }
        String apkUrl = null, shaUrl = null;
        JSONArray assets = best.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (APK.equals(a.optString("name"))) apkUrl = a.optString("browser_download_url");
            if (SHA.equals(a.optString("name"))) shaUrl = a.optString("browser_download_url");
        }
        if (apkUrl == null || shaUrl == null) {
            status("Release " + best.optString("tag_name") + " incomplète");
            return;
        }
        String expected = new String(fetch(shaUrl, SHA, 1024), "UTF-8").trim().split("\\s+")[0].toLowerCase();
        status("Téléchargement de la version " + bestCode + "…");
        final int code = bestCode;
        byte[] apk = fetch(apkUrl, APK, 50 << 20, new Progress() {
            @Override public void bytes(long done, long total) {
                appPercent = total > 0 ? (int) Math.min(100, done * 100 / total) : -2;
                status("Téléchargement de la version " + code + " : " + kb(done) + (total > 0 ? " sur " + kb(total) : ""));
            }
        });
        appPercent = -1;
        String actual = hex(MessageDigest.getInstance("SHA-256").digest(apk));
        if (!actual.equals(expected)) {
            status("Fichier téléchargé corrompu (SHA-256), mise à jour annulée");
            return;
        }
        install(apk);
        status("Version " + bestCode + " prête : confirmer l'installation sur l'écran de la voiture "
            + "(fenêtre d'Android, ou notification « Mise à jour prête »)");
    }

    // ---- animations (0.28): only animations/animations.json, no APK

    private final java.util.List<String> animLines = new java.util.ArrayList<>();
    private AnimListener animListener;
    /** The step in progress, to name it in an error. */
    private volatile String animStep = "";

    /** Downloads animations/animations.json from main, checks it and puts it in place. */
    void runAnimations(AnimListener l) {
        animListener = l;
        animBusy = true;
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    animations();
                } catch (Throwable t) {
                    Log.w(LOG, "animations update failed", t);
                    String msg = String.valueOf(t.getMessage());
                    setLast("✗ " + animStep + " : " + msg + (viaSystem && !msg.startsWith(NO_NETWORK)
                        ? " (même via le gestionnaire de téléchargements d'Android)" : "")
                        + ". Les animations actuelles restent en place.", -1, true);
                }
            }
        }, "animations").start();
    }

    private void animations() throws Exception {
        addLine("Recherche sur GitHub", -1);
        JSONArray commits = new JSONArray(new String(fetch(ANIM_COMMITS, "commits.json", 1 << 20, null), "UTF-8"));
        if (commits.length() == 0) throw new Exception("pas de fichier " + ANIM_PATH + " sur GitHub");
        JSONObject last = commits.getJSONObject(0);
        String sha = last.getString("sha");
        JSONObject commit = last.optJSONObject("commit");
        String when = "", what = "";
        if (commit != null) {
            JSONObject who = commit.optJSONObject("committer");
            if (who != null) when = who.optString("date", "").replace('T', ' ').replace("Z", " UTC");
            what = commit.optString("message", "").split("\n")[0];
        }
        setLast("✓ Trouvé : modifié le " + when + (what.isEmpty() ? "" : " (« " + what + " »)"), -1, false);
        if (sha.equals(Animations.installedSha(ctx))) {
            addLine("✓ Déjà à jour : ce fichier est déjà en place (" + Animations.get(ctx).summary() + ")", -1);
            setLast("✓ Déjà à jour : ce fichier est déjà en place (" + Animations.get(ctx).summary() + ")", -1, true);
            return;
        }
        addLine("Téléchargement", 0);
        // this exact version of the file (by its commit), never a copy cached by GitHub
        String url = "https://raw.githubusercontent.com/" + REPO + "/" + sha + "/" + ANIM_PATH;
        byte[] data = fetch(url, "animations.json", Animations.MAX_BYTES, new Progress() {
            @Override public void bytes(long done, long total) {
                int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : -2;
                setLast("…  Téléchargement : " + kb(done) + (total > 0 ? " sur " + kb(total) : ""), pct, false);
            }
        });
        setLast("✓ Reçu : " + kb(data.length), 100, false);
        addLine("Vérification du fichier", 100);
        Animations a;
        try {
            a = Animations.parse(data);
        } catch (Exception e) {
            setLast("✗ Fichier refusé : " + e.getMessage() + ". Les animations actuelles restent en place.", -1, true);
            return;
        }
        StringBuilder names = new StringBuilder();
        for (Animations.Recorded r : a.recorded) names.append(names.length() == 0 ? " (" : ", ").append(r.name);
        if (names.length() > 0) names.append(')');
        setLast("✓ Vérifié : " + a.summary() + names + (a.description.isEmpty() ? "" : " : « " + a.description + " »"), 100, false);
        addLine("Mise en place", 100);
        Animations.install(ctx, data, sha);
        setLast("✓ En place : la prochaine animation d'arrêt l'utilise (« Boutons de test » → « Animations à l'arrêt » pour la voir)", -1, true);
    }

    private static String kb(long n) {
        return n < 1024 ? n + " o" : String.format(java.util.Locale.FRANCE, "%.1f Ko", n / 1024.0);
    }

    /** A step that starts (shown with "…"). */
    private void addLine(String line, int percent) {
        boolean result = line.startsWith("✓") || line.startsWith("✗");
        if (!result) animStep = line;
        synchronized (animLines) { animLines.add(result ? line : "…  " + line); }
        sendAnim(percent, false);
    }

    /** The current step, its result. */
    private void setLast(String line, int percent, boolean done) {
        synchronized (animLines) {
            if (animLines.isEmpty()) animLines.add(line); else animLines.set(animLines.size() - 1, line);
        }
        sendAnim(percent, done);
    }


    private void sendAnim(final int percent, final boolean done) {
        final String text;
        synchronized (animLines) { text = android.text.TextUtils.join("\n", animLines); }
        animText = text;
        animPercent = done ? -1 : percent;
        if (done) animBusy = false;
        main.post(new Runnable() {
            @Override public void run() { if (animListener != null) animListener.onAnimSteps(text, percent, done); }
        });
    }

    private void install(byte[] apk) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
            new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(ctx.getPackageName());   // only this app can be installed
        params.setSize(apk.length);
        int id = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(id);
        try {
            OutputStream out = session.openWrite(APK, 0, apk.length);
            out.write(apk);
            session.fsync(out);
            out.close();
            Intent done = new Intent(ctx, Result.class);
            PendingIntent pending = PendingIntent.getBroadcast(ctx, id, done, PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pending.getIntentSender());
        } finally {
            session.close();
        }
    }

    /** Android's answer to the install: shows its confirm screen when asked to. */
    public static final class Result extends BroadcastReceiver {
        @Override public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) return;
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                // started from the iPhone page, the app may be in the background, where Android 10
                // can refuse to open a screen: the notification opens it too
                try {
                    android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                    nm.createNotificationChannel(new android.app.NotificationChannel(UPDATE_CHANNEL, "Mise à jour",
                        android.app.NotificationManager.IMPORTANCE_HIGH));
                    PendingIntent tap = PendingIntent.getActivity(c, 1, confirm, PendingIntent.FLAG_UPDATE_CURRENT);
                    nm.notify(UPDATE_NOTE, new android.app.Notification.Builder(c, UPDATE_CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("Ruban LED : mise à jour prête")
                        .setContentText("Toucher pour installer")
                        .setContentIntent(tap)
                        .setAutoCancel(true)
                        .build());
                } catch (Throwable t) {
                    Log.w(LOG, "update notification", t);
                }
                try {
                    c.startActivity(confirm);
                } catch (Throwable t) {
                    Log.w(LOG, "install screen refused", t);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                Log.w(LOG, "install failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    }

    /** The app's own connection first; Android's DownloadManager if the socket is refused. */
    private byte[] fetch(String url, String name, int max) throws Exception {
        return fetch(url, name, max, null);
    }

    private byte[] fetch(String url, String name, int max, Progress progress) throws Exception {
        if (!viaSystem) {
            try {
                return get(url, max, progress);
            } catch (java.net.SocketException | java.net.UnknownHostException | SecurityException e) {
                // also what no network looks like: Android's downloader then gives up quickly
                // (it reports it waits for a network, or nothing arrives), see viaDownloadManager
                Log.w(LOG, "own connection refused, using DownloadManager", e);
                viaSystem = true;
                // normal until the dash restarts once after an update that added the network
                // permission (Android 10 applies it at boot): not an error, so a plain message
                status("Téléchargement…");
            }
        }
        return viaDownloadManager(url, name, max, progress);
    }

    private byte[] viaDownloadManager(String url, String name, int max, Progress progress) throws Exception {
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        File dir = ctx.getExternalFilesDir("update");
        if (dm == null || dir == null) throw new Exception("gestionnaire de téléchargements indisponible");
        File file = new File(dir, name);
        file.delete();
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
            .setDestinationUri(Uri.fromFile(file))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
            .setVisibleInDownloadsUi(false)
            .addRequestHeader("User-Agent", "ruban-led-updater")
            .addRequestHeader("Accept", "application/vnd.github+json, application/octet-stream");
        long id = dm.enqueue(r);
        try {
            long now = System.currentTimeMillis();
            long until = now + DM_TIMEOUT_MS, lastProgress = now, lastBytes = -1;
            while (true) {
                Cursor c = dm.query(new DownloadManager.Query().setFilterById(id));
                int st = -1, reason = 0;
                long bytes = 0, total = -1;
                try {
                    if (c != null && c.moveToFirst()) {
                        st = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_STATUS));
                        reason = c.getInt(c.getColumnIndex(DownloadManager.COLUMN_REASON));
                        bytes = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        total = c.getLong(c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    }
                } finally {
                    if (c != null) c.close();
                }
                now = System.currentTimeMillis();
                if (bytes != lastBytes) {
                    lastBytes = bytes;
                    lastProgress = now;
                    if (progress != null) progress.bytes(bytes, total);
                }
                if (st == DownloadManager.STATUS_SUCCESSFUL) break;
                if (st == DownloadManager.STATUS_FAILED || st == -1) throw new Exception("téléchargement Android en échec (" + reason + ")");
                // without a network Android just waits for one (paused, or pending): give up
                if (st == DownloadManager.STATUS_PAUSED
                        && (reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK
                            || reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI)
                        && now - lastProgress > 5000) throw new Exception(NO_NETWORK);
                if (now - lastProgress > DM_STALL_MS) throw new Exception(NO_NETWORK + " (aucune donnée reçue)");
                if (now > until) throw new Exception("téléchargement trop long");
                Thread.sleep(400);
            }
            if (file.length() > max) throw new Exception("fichier trop gros");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = new FileInputStream(file);
            try {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            } finally {
                in.close();
            }
            return buf.toByteArray();
        } finally {
            dm.remove(id);
            file.delete();
        }
    }

    private static byte[] get(String url, int max, Progress progress) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        c.setRequestProperty("User-Agent", "ruban-led-updater");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        long total = c.getContentLengthLong();
        InputStream in = c.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        long shown = 0;
        while ((n = in.read(b)) > 0) {
            buf.write(b, 0, n);
            if (buf.size() > max) throw new Exception("fichier trop gros");
            long now = System.currentTimeMillis();
            if (progress != null && now - shown > 100) { shown = now; progress.bytes(buf.size(), total); }
        }
        if (progress != null) progress.bytes(buf.size(), total);
        in.close();
        return buf.toByteArray();
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    private void status(final String text) {
        appText = text;
        if (listener == null) return;
        main.post(new Runnable() {
            @Override public void run() { listener.onUpdateStatus(text); }
        });
    }
}
