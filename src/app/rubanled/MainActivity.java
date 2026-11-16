package app.rubanled;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings and status, built in code (plain SDK build, no layouts): a dark screen of cards.
 * Left: status and the two colours. Right: actions, rules (switches save at once) and the strip.
 */
public final class MainActivity extends Activity {
    // palette (dark theme, res/values/styles.xml)
    private static final int BG = 0xFF0F1114;
    private static final int CARD = 0xFF1B1E23;
    private static final int CARD_LINE = 0xFF2A2F36;
    private static final int TEXT = 0xFFECEFF1;
    private static final int MUTED = 0xFF9AA3AD;
    private static final int ACCENT = 0xFFE53935;
    private static final int OK = 0xFF43A047;
    private static final int WARN = 0xFFFFB300;

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView linkLine, stripLine, doorsLine, ruleLine, updateText, mirrorLine, learnLine, eventsLine;
    private View linkDot;
    private LinearLayout choices;
    private String[] shownChoices = new String[0];
    private EditText address, blinkMs, blinkMax, repeat, nightWinter, nightSummer, nightEnd, nightPct;
    private static boolean askedBattery;
    /** While dragging, at most one colour every this often (the strip acks each in ~150 ms). */
    private static final long COLOUR_THROTTLE_MS = 200;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LedService.send(this, null);
        askBatteryExemption();
        Settings s = Settings.load(this);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(14), dp(20), dp(20));
        page.setBackgroundColor(BG);
        page.addView(header());

        LinearLayout columns = new LinearLayout(this);
        LinearLayout left = column(), right = column();
        columns.addView(left, weight(1.15f, dp(14)));
        columns.addView(right, weight(1f, 0));
        page.addView(columns);

        // ---- left: status, colours
        LinearLayout status = card(left, "État");
        LinearLayout linkRow = new LinearLayout(this);
        linkRow.setGravity(Gravity.CENTER_VERTICAL);
        linkDot = new View(this);
        linkRow.addView(linkDot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        linkLine = text("", 17, TEXT, true);
        linkLine.setPadding(dp(10), 0, 0, 0);
        linkRow.addView(linkLine);
        status.addView(linkRow);
        stripLine = text("", 15, MUTED, false);
        doorsLine = text("", 15, MUTED, false);
        ruleLine = text("", 15, WARN, true);
        status.addView(stripLine);
        status.addView(doorsLine);
        status.addView(ruleLine);
        mirrorLine = text("", 15, MUTED, false);
        status.addView(mirrorLine);

        LinearLayout mirrors = card(left, "Rétroviseurs");
        mirrors.addView(text("Rétros dépliés : animation d'allumage ×3 puis couleur permanente. Rétros repliés : "
            + "animation d'extinction ×3 puis ruban éteint, jauge de recharge masquée.", 14, MUTED, false));
        mirrors.addView(text("Détection : rétros dépliés, touchez 1 ; repliez-les depuis l'écran de la Tesla, "
            + "puis touchez 2 et choisissez la donnée qui a changé.", 14, MUTED, false));
        LinearLayout mr = new LinearLayout(this);
        mr.setPadding(0, dp(8), 0, 0);
        mr.addView(action("1. Rétros dépliés", LedService.ACTION_LEARN_OPEN, true), weight(1, dp(8)));
        mr.addView(action("2. Rétros repliés", LedService.ACTION_LEARN_FOLDED, true), weight(1, dp(8)));
        mr.addView(action("Oublier", LedService.ACTION_MIRROR_FORGET, false), weight(0.6f, 0));
        mirrors.addView(mr);
        learnLine = text("", 15, WARN, false);
        learnLine.setPadding(0, dp(8), 0, 0);
        mirrors.addView(learnLine);
        choices = column();
        mirrors.addView(choices);

        LinearLayout colours = card(left, "Couleurs");
        colours.addView(new ColourPanel(LedService.TARGET_STEADY, "Permanente",
            s.defaultHue, s.defaultSat, s.dayPct).view);
        View sep = new View(this);
        sep.setBackgroundColor(CARD_LINE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        sp.setMargins(0, dp(14), 0, dp(14));
        colours.addView(sep, sp);
        colours.addView(new ColourPanel(LedService.TARGET_ANIM, "Animations (entrée, clignotement, warnings)",
            s.blinkHue, s.blinkSat, s.blinkPct).view);

        LinearLayout log = card(left, "Derniers événements");
        eventsLine = text("", 14, MUTED, false);
        eventsLine.setTypeface(Typeface.MONOSPACE);
        log.addView(eventsLine);

        // ---- right: actions, rules, strip
        LinearLayout actions = card(right, "Actions");
        LinearLayout row1 = new LinearLayout(this), row2 = new LinearLayout(this);
        row1.addView(action("Allumage", LedService.ACTION_INTRO, true), weight(1, dp(8)));
        row1.addView(action("Extinction", LedService.ACTION_EXIT, true), weight(1, dp(8)));
        row1.addView(action("Clignotement", LedService.ACTION_TEST_BLINK, true), weight(1, 0));
        row2.addView(action("Allumer", LedService.ACTION_ON, false), weight(1, dp(8)));
        row2.addView(action("Éteindre", LedService.ACTION_OFF, false), weight(1, dp(8)));
        row2.addView(action("Couleur permanente", LedService.ACTION_DEFAULT, false), weight(1.4f, 0));
        actions.addView(row1);
        LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        r2.topMargin = dp(8);
        actions.addView(row2, r2);

        LinearLayout rules = card(right, "Règles");
        toggle(rules, "Animation d'entrée", "À la première porte ouverte après le réveil de la voiture",
            s.intro, new Toggle() { public void set(Settings x, boolean v) { x.intro = v; } });
        toggle(rules, "Clignoter quand une porte s'ouvre", null,
            s.doorBlink, new Toggle() { public void set(Settings x, boolean v) { x.doorBlink = v; } });
        toggle(rules, "… aussi coffre et frunk", null,
            s.bootAndFrunk, new Toggle() { public void set(Settings x, boolean v) { x.bootAndFrunk = v; } });
        toggle(rules, "Clignoter avec les warnings", null,
            s.hazard, new Toggle() { public void set(Settings x, boolean v) { x.hazard = v; } });
        toggle(rules, "Mode nuit", "Hiver dès " + s.nightStartWinter + " h, été dès " + s.nightStartSummer
                + " h, jusqu'à " + s.nightEnd + " h : tout à " + s.nightPct + " %",
            s.night, new Toggle() { public void set(Settings x, boolean v) { x.night = v; } });
        LinearLayout n1 = new LinearLayout(this), n2 = new LinearLayout(this);
        blinkMs = number(n1, "Clignotement (ms)", s.blinkMs);
        blinkMax = number(n1, "Durée max (s)", s.blinkMaxSec);
        repeat = number(n1, "Animations ×", s.animRepeat);
        nightWinter = number(n2, "Nuit hiver (h)", s.nightStartWinter);
        nightSummer = number(n2, "Nuit été (h)", s.nightStartSummer);
        nightEnd = number(n2, "jusqu'à (h)", s.nightEnd);
        nightPct = number(n2, "Lum. nuit (%)", s.nightPct);
        rules.addView(n1);
        rules.addView(n2);

        LinearLayout strip = card(right, "Ruban et application");
        strip.addView(text("Adresse Bluetooth du ruban", 14, MUTED, false));
        address = new EditText(this);
        address.setText(s.address);
        address.setSingleLine(true);
        address.setTextColor(TEXT);
        address.setHint("AA:BB:CC:DD:EE:FF");
        address.setHintTextColor(0xFF5F6770);
        strip.addView(address);
        LinearLayout r3 = new LinearLayout(this);
        Button save = pill("Enregistrer", true);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        Button update = pill("Mise à jour", false);
        update.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { update(); }
        });
        r3.addView(save, weight(1, dp(8)));
        r3.addView(update, weight(1, 0));
        strip.addView(r3);
        updateText = text("Version " + Updater.installedVersion(this) + "  ·  github.com/" + Updater.REPO, 13, MUTED, false);
        updateText.setPadding(0, dp(8), 0, 0);
        strip.addView(updateText);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(page);
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
            String link = LedService.linkText;
            boolean ok = "Ruban connecté".equals(link);
            linkLine.setText(link);
            dot(linkDot, ok ? OK : link.startsWith("Service") ? MUTED : WARN);
            stripLine.setText(LedService.stripText);
            doorsLine.setText(LedService.doorsText + "   ·   " + LedService.feedText);
            String rule = LedService.ruleText;
            ruleLine.setText(rule);
            ruleLine.setVisibility(rule.isEmpty() ? View.GONE : View.VISIBLE);
            mirrorLine.setText(LedService.mirrorText);
            String learn = LedService.learnText;
            learnLine.setText(learn);
            learnLine.setVisibility(learn.isEmpty() ? View.GONE : View.VISIBLE);
            eventsLine.setText(LedService.eventsText.isEmpty() ? "—" : LedService.eventsText);
            String[] c = LedService.learnChoices;
            if (!java.util.Arrays.equals(c, shownChoices)) {
                shownChoices = c;
                choices.removeAllViews();
                for (int i = 0; i < c.length; i++) {
                    final int index = i;
                    Button b = pill(c[i], i == 0);
                    b.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            Intent pick = new Intent(MainActivity.this, LedService.class)
                                .setAction(LedService.ACTION_LEARN_PICK).putExtra("index", index);
                            if (Build.VERSION.SDK_INT >= 26) startForegroundService(pick);
                            else startService(pick);
                        }
                    });
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = dp(6);
                    choices.addView(b, lp);
                }
            }
            main.postDelayed(this, 1000);
        }
    };

    // ---- actions

    private void save() {
        try {
            Settings s = Settings.load(this);
            s.address = address.getText().toString().trim().toUpperCase();
            s.blinkMs = clamp(num(blinkMs), 150, 5000);
            s.blinkMaxSec = clamp(num(blinkMax), 5, 3600);
            s.animRepeat = clamp(num(repeat), 1, 10);
            s.nightStartWinter = clamp(num(nightWinter), 0, 23);
            s.nightStartSummer = clamp(num(nightSummer), 0, 23);
            s.nightEnd = clamp(num(nightEnd), 0, 23);
            s.nightPct = clamp(num(nightPct), 1, 100);
            s.save(this);
            LedService.send(this, LedService.ACTION_RELOAD);
            Toast.makeText(this, "Enregistré", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Un nombre est invalide", Toast.LENGTH_SHORT).show();
        }
    }

    private void update() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            // Android's own switch: Install unknown apps → Ruban LED
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "Autoriser « Installer des applis inconnues », puis réessayer", Toast.LENGTH_LONG).show();
            return;
        }
        new Updater(this, new Updater.Listener() {
            @Override public void onUpdateStatus(String text) { updateText.setText(text); }
        }).run();
    }

    /**
     * Once per launch: Android's own prompt to leave the app out of battery optimisation, so the
     * service keeps running (the unit's power saver also needs appPowerSaveConfig, see README).
     */
    private void askBatteryExemption() {
        if (askedBattery) return;
        askedBattery = true;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName())));
            }
        } catch (Throwable ignored) {
        }
    }

    private interface Toggle {
        void set(Settings s, boolean v);
    }

    // ---- building blocks

    private LinearLayout header() {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setPadding(dp(4), 0, 0, dp(12));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getDrawable(R.mipmap.ic_launcher));
        h.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView t = text("Ruban LED", 26, TEXT, true);
        t.setPadding(dp(12), 0, 0, 0);
        h.addView(t);
        return h;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        return c;
    }

    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(14), dp(18), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(16));
        g.setStroke(dp(1), CARD_LINE);
        c.setBackground(g);
        TextView t = text(title.toUpperCase(), 13, MUTED, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, 0, 0, dp(10));
        c.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        parent.addView(c, lp);
        return c;
    }

    private TextView text(String s, int sp, int colour, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(colour);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private Button pill(String label, boolean filled) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(filled ? Color.WHITE : TEXT);
        b.setStateListAnimator(null);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(24));
        if (filled) {
            g.setColor(ACCENT);
        } else {
            g.setColor(0xFF262A30);
            g.setStroke(dp(1), CARD_LINE);
        }
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), g, null));
        b.setMinHeight(dp(52));
        return b;
    }

    private Button action(String label, final String action, boolean filled) {
        Button b = pill(label, filled);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { LedService.send(MainActivity.this, action); }
        });
        return b;
    }

    /** A switch row that saves at once and tells the service. */
    private void toggle(LinearLayout parent, String title, String sub, boolean value, final Toggle t) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        LinearLayout texts = column();
        texts.addView(text(title, 17, TEXT, false));
        if (sub != null) texts.addView(text(sub, 13, MUTED, false));
        row.addView(texts, weight(1, 0));
        Switch sw = new Switch(this);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton b, boolean v) {
                Settings s = Settings.load(MainActivity.this);
                t.set(s, v);
                s.save(MainActivity.this);
                LedService.send(MainActivity.this, LedService.ACTION_RELOAD);
            }
        });
        row.addView(sw);
        parent.addView(row);
    }

    /** A small labelled number field added to a row (saved with « Enregistrer »). */
    private EditText number(LinearLayout row, String label, int value) {
        LinearLayout box = column();
        box.setPadding(0, dp(6), dp(12), 0);
        box.addView(text(label, 13, MUTED, false));
        EditText e = new EditText(this);
        e.setText("" + value);
        e.setSingleLine(true);
        e.setTextColor(TEXT);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        box.addView(e);
        row.addView(box, weight(1, 0));
        return e;
    }

    private void dot(View v, int colour) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(colour);
        v.setBackground(g);
    }

    private LinearLayout.LayoutParams weight(float w, int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
        lp.rightMargin = rightMargin;
        return lp;
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
            row.setGravity(Gravity.CENTER_VERTICAL);
            ColourWheel wheel = new ColourWheel(MainActivity.this);
            wheel.setColour(hue, sat);
            row.addView(wheel, new LinearLayout.LayoutParams(dp(170), dp(170)));

            LinearLayout right = column();
            right.setPadding(dp(18), 0, 0, 0);
            right.addView(text(title, 17, TEXT, true));
            swatch = new View(MainActivity.this);
            LinearLayout.LayoutParams sw = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
            sw.topMargin = dp(8);
            right.addView(swatch, sw);
            pctLabel = text("", 15, MUTED, false);
            pctLabel.setPadding(0, dp(10), 0, 0);
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
            right.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
            row.addView(right, weight(1, 0));

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
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(10));
            g.setColor(Color.HSVToColor(new float[] {hue, sat / 100f, 0.25f + 0.75f * pct / 100f}));
            swatch.setBackground(g);
            pctLabel.setText("Luminosité  " + pct + " %");
        }

        private void send(boolean done) {
            long now = SystemClock.elapsedRealtime();
            if (!done && now - lastSentMs < COLOUR_THROTTLE_MS) return;
            lastSentMs = now;
            Intent i = new Intent(MainActivity.this, LedService.class)
                .setAction(LedService.ACTION_SET_COLOUR)
                .putExtra("target", target)
                .putExtra("hue", hue).putExtra("sat", sat).putExtra("pct", pct);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        }
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
