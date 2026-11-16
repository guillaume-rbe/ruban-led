package app.rubanled;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/** Settings and status, built in code (no layouts to keep the build to plain SDK tools). */
public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private EditText address, blinkMs, blinkMax, nightStart, nightEnd, nightPct;
    private CheckBox doorBlink, bootFrunk, hazard, intro, night;
    /** While dragging, at most one colour every this often (the strip acks each in ~150 ms). */
    private static final long COLOUR_THROTTLE_MS = 200;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LedService.send(this, null);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        col.setPadding(pad, dp(8), pad, pad);

        status = new TextView(this);
        status.setTextSize(15);
        col.addView(status);
        updateText = new TextView(this);
        updateText.setTextSize(13);
        updateText.setText("Version " + Updater.installedVersion(this) + " · mises à jour : github.com/" + Updater.REPO);
        col.addView(updateText);

        Settings s = Settings.load(this);
        LinearLayout wheels = new LinearLayout(this);
        wheels.setPadding(0, dp(8), 0, dp(4));
        wheels.addView(new ColourPanel(LedService.TARGET_STEADY, "Couleur permanente",
                s.defaultHue, s.defaultSat, s.dayPct).view,
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        wheels.addView(new ColourPanel(LedService.TARGET_ANIM, "Couleur des animations (entrée, clignotement)",
                s.blinkHue, s.blinkSat, s.blinkPct).view,
            new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        col.addView(wheels);

        LinearLayout buttons = new LinearLayout(this);
        Button update = new Button(this);
        update.setText("Mise à jour");
        update.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { update(); }
        });
        buttons.addView(update);
        buttons.addView(button("Animation", LedService.ACTION_INTRO));
        buttons.addView(button("Test clignotement", LedService.ACTION_TEST_BLINK));
        buttons.addView(button("Couleur permanente", LedService.ACTION_DEFAULT));
        buttons.addView(button("Allumer", LedService.ACTION_ON));
        buttons.addView(button("Éteindre", LedService.ACTION_OFF));
        col.addView(buttons);

        address = field(col, "Adresse Bluetooth du ruban", s.address, false);
        doorBlink = check(col, "Clignoter quand une porte est ouverte", s.doorBlink);
        bootFrunk = check(col, "… aussi pour le coffre et le frunk", s.bootAndFrunk);
        hazard = check(col, "Clignoter avec les warnings", s.hazard);
        blinkMs = field(col, "Vitesse du clignotement (ms)", "" + s.blinkMs, true);
        blinkMax = field(col, "Durée maximale du clignotement (s)", "" + s.blinkMaxSec, true);
        intro = check(col, "Animation d'allumage à la première porte ouverte en montant", s.intro);
        night = check(col, "Baisser la luminosité la nuit", s.night);
        nightStart = field(col, "Début de la nuit (heure 0-23)", "" + s.nightStart, true);
        nightEnd = field(col, "Fin de la nuit (heure 0-23)", "" + s.nightEnd, true);
        nightPct = field(col, "Luminosité la nuit (%)", "" + s.nightPct, true);

        Button save = new Button(this);
        save.setText("Enregistrer");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        col.addView(save);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        main.post(refresh);
    }

    @Override protected void onPause() {
        main.removeCallbacks(refresh);
        super.onPause();
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            String rule = LedService.ruleText;
            status.setText(LedService.linkText + " · " + LedService.stripText + "\n" + LedService.doorsText
                + " · " + LedService.feedText + (rule.isEmpty() ? "" : " · " + rule));
            main.postDelayed(this, 1000);
        }
    };

    /** Version line under the status; updated by the Updater. */
    private TextView updateText;

    private void update() {
        if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            // Android's own switch: Install unknown apps → Ruban LED
            startActivity(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "Autoriser « Installer des applis inconnues », puis réessayer", Toast.LENGTH_LONG).show();
            return;
        }
        new Updater(this, new Updater.Listener() {
            @Override public void onUpdateStatus(String text) { updateText.setText(text); }
        }).run();
    }

    private void save() {
        try {
            Settings s = Settings.load(this);
            s.address = address.getText().toString().trim().toUpperCase();
            s.doorBlink = doorBlink.isChecked();
            s.bootAndFrunk = bootFrunk.isChecked();
            s.hazard = hazard.isChecked();
            s.blinkMs = clamp(num(blinkMs), 150, 5000);
            s.blinkMaxSec = clamp(num(blinkMax), 5, 3600);
            s.intro = intro.isChecked();
            s.night = night.isChecked();
            s.nightStart = clamp(num(nightStart), 0, 23);
            s.nightEnd = clamp(num(nightEnd), 0, 23);
            s.nightPct = clamp(num(nightPct), 1, 100);
            s.save(this);
            LedService.send(this, LedService.ACTION_RELOAD);
            Toast.makeText(this, "Enregistré", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Un nombre est invalide", Toast.LENGTH_SHORT).show();
        }
    }

    /** One colour setting: wheel on the left; title, preview and brightness gauge on the right. */
    private final class ColourPanel {
        final View view;
        private final String target;
        private final View swatch;
        private final TextView pctLabel;
        private int hue, sat, pct;
        private long lastSentMs;

        ColourPanel(String target, String title, int hue0, int sat0, int pct0) {
            this.target = target;
            hue = hue0;
            sat = sat0;
            pct = pct0;
            LinearLayout row = new LinearLayout(MainActivity.this);
            ColourWheel wheel = new ColourWheel(MainActivity.this);
            wheel.setColour(hue, sat);
            row.addView(wheel, new LinearLayout.LayoutParams(dp(210), dp(210)));

            LinearLayout right = new LinearLayout(MainActivity.this);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setPadding(dp(16), 0, dp(16), 0);
            TextView t = new TextView(MainActivity.this);
            t.setText(title);
            t.setTextSize(16);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            right.addView(t);
            swatch = new View(MainActivity.this);
            LinearLayout.LayoutParams sw = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
            sw.topMargin = dp(8);
            right.addView(swatch, sw);
            pctLabel = new TextView(MainActivity.this);
            pctLabel.setTextSize(16);
            pctLabel.setPadding(0, dp(8), 0, 0);
            right.addView(pctLabel);
            SeekBar bar = new SeekBar(MainActivity.this);
            bar.setMax(99);
            bar.setProgress(pct - 1);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                    pct = p + 1;
                    preview();
                    if (user) send(false);
                }
                @Override public void onStartTrackingTouch(SeekBar b) {}
                @Override public void onStopTrackingTouch(SeekBar b) { send(true); }
            });
            right.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
            row.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            wheel.setListener(new ColourWheel.Listener() {
                @Override public void onColour(int h, int s, boolean done) {
                    hue = h;
                    sat = s;
                    preview();
                    send(done);
                }
            });
            preview();
            view = row;
        }

        private void preview() {
            swatch.setBackgroundColor(Color.HSVToColor(new float[] {hue, sat / 100f, 0.25f + 0.75f * pct / 100f}));
            pctLabel.setText("Luminosité : " + pct + " %");
        }

        private void send(boolean done) {
            long now = SystemClock.elapsedRealtime();
            if (!done && now - lastSentMs < COLOUR_THROTTLE_MS) return;
            lastSentMs = now;
            android.content.Intent i = new android.content.Intent(MainActivity.this, LedService.class)
                .setAction(LedService.ACTION_SET_COLOUR)
                .putExtra("target", target)
                .putExtra("hue", hue).putExtra("sat", sat).putExtra("pct", pct);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        }
    }

    private Button button(String text, final String action) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { LedService.send(MainActivity.this, action); }
        });
        return b;
    }

    private EditText field(LinearLayout col, String label, String value, boolean number) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setPadding(0, dp(12), 0, 0);
        col.addView(l);
        EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        col.addView(e);
        return e;
    }

    private CheckBox check(LinearLayout col, String label, boolean value) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(value);
        c.setPadding(0, dp(8), 0, 0);
        col.addView(c);
        return c;
    }

    private static int num(EditText e) {
        return Integer.parseInt(e.getText().toString().trim());
    }

    private static int clamp(int x, int lo, int hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
