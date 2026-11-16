package app.rubanled;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Updates from the GitHub releases of the owner's fork: the newest release tagged
 * {@code ruban-led-v<versionCode>} with a higher versionCode than this one, whose
 * {@code ruban-led.apk} matches the SHA-256 in {@code ruban-led.apk.sha256}. Installed through
 * Android's PackageInstaller, locked to this package, so Android shows its own confirm screen and
 * only accepts an APK signed with the same key. Runs only when the user taps the button.
 */
final class Updater {
    interface Listener {
        void onUpdateStatus(String text);
    }

    static final String REPO = "guillaume-rbe/binnacle";
    private static final String RELEASES = "https://api.github.com/repos/" + REPO + "/releases?per_page=30";
    private static final String TAG_PREFIX = "ruban-led-v";
    private static final String APK = "ruban-led.apk";
    private static final String SHA = "ruban-led.apk.sha256";
    private static final String LOG = "rubanled";

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

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

    /** Check, and install if there is a newer release. */
    void run() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    check();
                } catch (Throwable t) {
                    Log.w(LOG, "update failed", t);
                    status("Mise à jour impossible : " + t.getMessage());
                }
            }
        }, "updater").start();
    }

    private void check() throws Exception {
        int current = installedVersion(ctx);
        status("Recherche d'une mise à jour…");
        JSONArray releases = new JSONArray(new String(get(RELEASES, 1 << 20), "UTF-8"));
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
        String expected = new String(get(shaUrl, 1024), "UTF-8").trim().split("\\s+")[0].toLowerCase();
        status("Téléchargement de la version " + bestCode + "…");
        byte[] apk = get(apkUrl, 50 << 20);
        String actual = hex(MessageDigest.getInstance("SHA-256").digest(apk));
        if (!actual.equals(expected)) {
            status("Fichier téléchargé corrompu (SHA-256), mise à jour annulée");
            return;
        }
        install(apk);
        status("Version " + bestCode + " prête : confirmer l'installation à l'écran");
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
                if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                Log.w(LOG, "install failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
            }
        }
    }

    private static byte[] get(String url, int max) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        c.setRequestProperty("User-Agent", "ruban-led-updater");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) throw new Exception("HTTP " + code);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) > 0) {
            buf.write(b, 0, n);
            if (buf.size() > max) throw new Exception("fichier trop gros");
        }
        in.close();
        return buf.toByteArray();
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    private void status(final String text) {
        main.post(new Runnable() {
            @Override public void run() { listener.onUpdateStatus(text); }
        });
    }
}
