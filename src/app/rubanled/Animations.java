package app.rubanled;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The stop animations' settings and the recorded ones (0.28): read from files/animations.json,
 * which the "Mise à jour des animations" button downloads from animations/animations.json in the
 * GitHub repository (the simulator exports that file). Without the file, or with a broken one,
 * the built-in values below, which are those of animations/animations.json at 0.28.
 * Pure data and checks; the frames are drawn by HoldShow.
 */
final class Animations {
    static final String FILE = "animations.json";
    /** The GitHub commit the file came from (so the button knows when it is up to date). */
    static final String SHA_FILE = "animations.sha";
    static final int FORMAT = 1;
    static final int MAX_BYTES = 512 * 1024;
    static final int MAX_RECORDED = 20, MAX_FRAMES = 2000;

    /** Version of the file (0: built in), its date and what it changed. */
    int version;
    String date = "", description = "Animations intégrées";
    long frameMs = 150;

    /** Respiration lente: lowest level (0..1) and seconds per breath. */
    double breathMin = 0.05, breathS = 5;
    /** Croisement and Du centre vers les bouts: background (0..1), tail length, move and pause (s). */
    double crossBg = 0, crossTail = 0.07, crossMoveS = 3.2, crossPauseS = 0.6;
    double centreBg = 0, centreTail = 0.07, centreMoveS = 1.8, centrePauseS = 0.6;
    /** Scintillement: background (0..1), chance (0..1) per frame that a dark segment lights, decay per frame. */
    double twinkleBg = 0, twinkleChance = 0.03, twinkleDecay = 0.7;
    /** Vague de couleur: hue swing (degrees) and seconds per wave. */
    double waveSwing = 50, waveS = 4;
    /**
     * The stop animation plays one stop in holdEvery (0.30, owner 27 Nov: every time, and set by the
     * animations update rather than in the app; it was "Arrêt : 1 sur 4" in the settings).
     */
    int holdEvery = 1;
    /**
     * Reverse with an obstacle (0.30): the closest rear sensor level (1 closest .. 10) at or above
     * reverseFar is yellow, at or below reverseNear red, in between from yellow to red.
     */
    int reverseNear = 2, reverseFar = 8;
    /** Recorded animations: name, frame period, and per frame the 15 dashboard levels (0-100). */
    final List<Recorded> recorded = new ArrayList<>();

    static final class Recorded {
        String name;
        long frameMs;
        int[][] frames;
    }

    private static Animations current;
    private static long loadedStamp = -1;

    /** The animations in use: the file if it is there and valid, the built-in ones otherwise. */
    static synchronized Animations get(Context ctx) {
        File f = new File(ctx.getFilesDir(), FILE);
        long stamp = f.exists() ? f.lastModified() ^ f.length() : 0;
        if (current == null || stamp != loadedStamp) {
            Animations a = null;
            if (stamp != 0) {
                try {
                    a = parse(read(f));
                } catch (Exception e) {
                    a = null;   // a broken file: the built-in animations
                }
            }
            current = a != null ? a : new Animations();
            loadedStamp = stamp;
        }
        return current;
    }

    /** Puts a checked file in place (the next animation uses it). */
    static synchronized void install(Context ctx, byte[] data, String sha) throws Exception {
        File dir = ctx.getFilesDir(), tmp = new File(dir, FILE + ".tmp"), f = new File(dir, FILE);
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) throw new Exception("enregistrement impossible");
        current = null;
        FileOutputStream s = new FileOutputStream(new File(dir, SHA_FILE));
        try {
            s.write(sha.getBytes("UTF-8"));
        } finally {
            s.close();
        }
    }

    /** The commit of the file in place, "" for the built-in animations. */
    static String installedSha(Context ctx) {
        File f = new File(ctx.getFilesDir(), SHA_FILE);
        if (!f.exists() || !new File(ctx.getFilesDir(), FILE).exists()) return "";
        try {
            return new String(read(f), "UTF-8").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Back to the built-in animations. */
    static synchronized void reset(Context ctx) {
        new File(ctx.getFilesDir(), FILE).delete();
        new File(ctx.getFilesDir(), SHA_FILE).delete();
        current = null;
    }

    /** Throws with the reason if the file is not a valid animations file. */
    static Animations parse(byte[] data) throws Exception {
        if (data.length > MAX_BYTES) throw new Exception("fichier trop gros");
        JSONObject o = new JSONObject(new String(data, "UTF-8"));
        if (o.optInt("format", -1) != FORMAT) throw new Exception("format " + o.optInt("format", -1) + " inconnu (attendu " + FORMAT + ")");
        Animations a = new Animations();
        a.version = o.optInt("version", 0);
        a.date = o.optString("date", "");
        a.description = o.optString("description", "");
        a.frameMs = (long) range(o, "image_ms", a.frameMs, 40, 1000);
        JSONObject h = o.optJSONObject("arret");
        if (h != null) a.holdEvery = (int) range(h, "une_fois_sur", a.holdEvery, 1, 20);
        JSONObject ra = o.optJSONObject("marche_arriere");
        if (ra != null) {
            a.reverseNear = (int) range(ra, "rouge_a", a.reverseNear, 1, 10);
            a.reverseFar = (int) range(ra, "jaune_a", a.reverseFar, 1, 10);
            if (a.reverseFar <= a.reverseNear) throw new Exception("marche arrière : « jaune_a » doit être plus grand que « rouge_a »");
        }
        JSONObject r = o.optJSONObject("respiration");
        if (r != null) {
            a.breathMin = range(r, "min", a.breathMin * 100, 0, 100) / 100;
            a.breathS = range(r, "souffle_s", a.breathS, 0.5, 60);
        }
        JSONObject c = o.optJSONObject("croisement");
        if (c != null) {
            a.crossBg = range(c, "fond", a.crossBg * 100, 0, 100) / 100;
            a.crossTail = range(c, "traine", a.crossTail, 0.01, 1);
            a.crossMoveS = range(c, "trajet_s", a.crossMoveS, 0.3, 30);
            a.crossPauseS = range(c, "pause_s", a.crossPauseS, 0, 30);
        }
        JSONObject m = o.optJSONObject("centre");
        if (m != null) {
            a.centreBg = range(m, "fond", a.centreBg * 100, 0, 100) / 100;
            a.centreTail = range(m, "traine", a.centreTail, 0.01, 1);
            a.centreMoveS = range(m, "trajet_s", a.centreMoveS, 0.3, 30);
            a.centrePauseS = range(m, "pause_s", a.centrePauseS, 0, 30);
        }
        JSONObject t = o.optJSONObject("scintillement");
        if (t != null) {
            a.twinkleBg = range(t, "fond", a.twinkleBg * 100, 0, 100) / 100;
            a.twinkleChance = range(t, "chance", a.twinkleChance * 100, 0, 100) / 100;
            a.twinkleDecay = range(t, "descente", a.twinkleDecay, 0.1, 0.98);
        }
        JSONObject w = o.optJSONObject("vague");
        if (w != null) {
            a.waveSwing = range(w, "ecart", a.waveSwing, 0, 180);
            a.waveS = range(w, "periode_s", a.waveS, 0.5, 60);
        }
        JSONArray rec = o.optJSONArray("enregistrees");
        if (rec != null) {
            if (rec.length() > MAX_RECORDED) throw new Exception("plus de " + MAX_RECORDED + " animations enregistrées");
            for (int i = 0; i < rec.length(); i++) {
                JSONObject e = rec.getJSONObject(i);
                Recorded d = new Recorded();
                d.name = e.optString("nom", "").trim();
                if (d.name.isEmpty()) throw new Exception("animation enregistrée " + (i + 1) + " sans nom");
                d.frameMs = (long) range(e, "image_ms", a.frameMs, 40, 1000);
                JSONArray im = e.getJSONArray("images");
                if (im.length() < 1 || im.length() > MAX_FRAMES)
                    throw new Exception("« " + d.name + " » : " + im.length() + " images (1 à " + MAX_FRAMES + ")");
                d.frames = new int[im.length()][];
                for (int k = 0; k < im.length(); k++) {
                    JSONArray lv = im.getJSONArray(k);
                    if (lv.length() != HoldShow.N)
                        throw new Exception("« " + d.name + " », image " + (k + 1) + " : " + lv.length() + " valeurs (attendu " + HoldShow.N + ")");
                    d.frames[k] = new int[HoldShow.N];
                    for (int s = 0; s < HoldShow.N; s++) {
                        int v = lv.getInt(s);
                        if (v < 0 || v > 100) throw new Exception("« " + d.name + " », image " + (k + 1) + " : niveau " + v + " hors de 0-100");
                        d.frames[k][s] = v;
                    }
                }
                a.recorded.add(d);
            }
        }
        return a;
    }

    /** A number of the file, which must be within [lo, hi] if present. */
    private static double range(JSONObject o, String key, double def, double lo, double hi) throws Exception {
        if (!o.has(key)) return def;
        double v = o.getDouble(key);
        if (Double.isNaN(v) || v < lo || v > hi) throw new Exception("« " + key + " » = " + v + " hors de " + lo + "-" + hi);
        return v;
    }

    /** Built-in names, then the recorded ones. */
    int count() {
        return HoldShow.NAMES.length + recorded.size();
    }

    String name(int kind) {
        return kind < HoldShow.NAMES.length ? HoldShow.NAMES[kind] : recorded.get(kind - HoldShow.NAMES.length).name;
    }

    /** "à chaque arrêt" or "1 arrêt sur 3". */
    String holdText() {
        return holdEvery <= 1 ? "à chaque arrêt" : "1 arrêt sur " + holdEvery;
    }

    /** One line for the screen: "version 2 du 2026-11-25 · 5 réglées + 1 enregistrée". */
    String summary() {
        if (version == 0 && date.isEmpty()) return "d'origine · arrêt " + holdText();
        return "version " + version + (date.isEmpty() ? "" : " du " + date) + " · arrêt " + holdText();
    }

    private static byte[] read(File f) throws Exception {
        if (f.length() > MAX_BYTES) throw new Exception("fichier trop gros");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        } finally {
            in.close();
        }
        return buf.toByteArray();
    }
}
