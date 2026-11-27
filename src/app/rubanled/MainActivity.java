package app.rubanled;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings and status, built in code (plain SDK build, no layouts): a dark screen of cards.
 * Top line: the strip's connection and the animations update. Left: status, colours, iPhone page, strip.
 * Right: actions, rules (switches save at once), then « Debug » (folded).
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
    private TextView remoteLine, remoteUrl, nightSummary;
    private TextView linkLine, stripLine, doorsLine, ruleLine, updateText, eventsLine, autoPlayLine, animState;
    private Button autoPlayAccess;
    private View linkDot;
    private EditText address, blinkMs, repeat, nightWinter, nightSummer, nightEnd, nightPct, journalCodes;
    /** Journal (0.20), under « Debug » since 0.29: certificate state, upload steps and progress. */
    private TextView journalState, journalSteps;
    private ProgressBar journalBar;
    /** Animations update (0.28), its steps and progress in a window since 0.29. */
    private TextView animSteps;
    private ProgressBar animBar;
    private AlertDialog animDialog;
    private Button animUpdate, animReset;
    private Button send24, sendAll;
    private static boolean askedBattery;
    /**
     * The animations on GitHub are looked at only when the owner opens the app (0.29): launcher,
     * recent apps or notification. Not when coming back from a screen the app opened itself
     * (Android's settings, the install screen), nor when the dash wakes with the app on top.
     */
    private boolean openedByOwner = true;
    /** Set just before the app opens one of Android's screens. */
    private boolean ownScreen;
    /** While dragging, at most one colour every this often (the strip acks each in ~150 ms). */
    private static final long COLOUR_THROTTLE_MS = 200;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LedService.send(this, null);
        askBatteryExemption();
        Settings s = Settings.load(this);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(12), dp(20), dp(20));
        page.setBackgroundColor(BG);
        page.addView(header());

        LinearLayout columns = new LinearLayout(this);
        LinearLayout left = column(), right = column();
        columns.addView(left, weight(1.15f, dp(14)));
        columns.addView(right, weight(1f, 0));
        page.addView(columns);

        // ---- left: status, colours, iPhone page, strip and app
        LinearLayout status = card(left, "État");
        stripLine = text("", 15, TEXT, false);
        doorsLine = text("", 15, MUTED, false);
        ruleLine = text("", 15, WARN, true);
        status.addView(stripLine);
        status.addView(doorsLine);
        status.addView(ruleLine);

        LinearLayout colours = card(left, "Couleurs");
        // locked by default (owner, 17 Nov): a scroll over a wheel must not change a colour
        lockButton = pill("", false);
        lockButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { setColoursLocked(!coloursLocked); }
        });
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lb.bottomMargin = dp(10);
        colours.addView(lockButton, lb);
        steadyPanel = new ColourPanel(LedService.TARGET_STEADY, "Permanente",
            s.defaultHue, s.defaultSat, s.dayPct);
        // the two colours side by side (0.29): half the height on the 10.25" screen
        LinearLayout pair = new LinearLayout(this);
        pair.setBaselineAligned(false);
        pair.addView(steadyPanel.view, weight(1, 0));
        View sep = new View(this);
        sep.setBackgroundColor(CARD_LINE);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT);
        sp.setMargins(dp(14), 0, dp(14), 0);
        pair.addView(sep, sp);
        alertPanel = new ColourPanel(LedService.TARGET_ANIM, "Alertes", s.blinkHue, s.blinkSat, s.blinkPct);
        pair.addView(alertPanel.view, weight(1, 0));
        colours.addView(pair);
        setColoursLocked(true);

        // the iPhone page on the CarPlay Wi-Fi (0.25, owner 23 Nov): the link, little else (0.29)
        LinearLayout phone = card(left, "Page iPhone");
        bind("remote", toggle(phone, "Page web sur le Wi-Fi CarPlay", null,
            s.remote, new Toggle() { public void set(Settings x, boolean v) { x.remote = v; } }));
        LinearLayout urlRow = new LinearLayout(this);
        urlRow.setGravity(Gravity.CENTER_VERTICAL);
        urlRow.setBaselineAligned(false);
        LinearLayout urlTexts = column();
        remoteUrl = text("", 20, TEXT, true);
        remoteUrl.setTextIsSelectable(true);
        urlTexts.addView(remoteUrl);
        remoteLine = text("", 13, MUTED, false);
        urlTexts.addView(remoteLine);
        urlRow.addView(urlTexts, weight(1, dp(10)));
        Button newCode = pill("Nouveau code", false);
        newCode.setPadding(dp(18), 0, dp(18), 0);
        newCode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Settings.newRemoteCode(MainActivity.this);
                LedService.send(MainActivity.this, LedService.ACTION_RELOAD);
                Toast.makeText(MainActivity.this, "Nouveau code : l'ancien lien ne marche plus", Toast.LENGTH_LONG).show();
            }
        });
        urlRow.addView(newCode);
        phone.addView(urlRow);

        // ---- right: actions, rules, debug
        LinearLayout actions = card(right, "Actions");
        LinearLayout row2 = new LinearLayout(this);
        // not aligned on the text's baseline: "Couleur permanente" on two lines sat lower (owner, 23 Nov)
        row2.setBaselineAligned(false);
        // test buttons: two per line, with names that say what they play (owner, 22 Nov)
        LinearLayout testRow = column(), t1 = new LinearLayout(this), t2 = new LinearLayout(this);
        t1.addView(action("Allumage", LedService.ACTION_INTRO, true), weight(1, dp(8)));
        t1.addView(action("Extinction", LedService.ACTION_EXIT, true), weight(1, 0));
        t2.addView(action("Clignotement", LedService.ACTION_TEST_BLINK, true), weight(1, dp(8)));
        t2.addView(action("Animation à l'arrêt", LedService.ACTION_HOLD_TEST, true), weight(1, 0));
        testRow.addView(t1);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(8);
        testRow.addView(t2, tp);
        row2.addView(action("Allumer", LedService.ACTION_ON, false), fill(weight(1, dp(8))));
        row2.addView(action("Éteindre", LedService.ACTION_OFF, false), fill(weight(1, dp(8))));
        row2.addView(action("Couleur permanente", LedService.ACTION_DEFAULT, false), fill(weight(1.4f, 0)));
        actions.addView(row2);
        // the test buttons are hidden until "Tests" is pressed (owner, 22 Nov)
        actions.addView(folder("Tests", testRow), topMargin(8));
        actions.addView(testRow, topMargin(8));

        LinearLayout rules = card(right, "Règles");
        bind("intro", toggle(rules, "Animations ×3", "Au déverrouillage et au verrouillage",
            s.intro, new Toggle() { public void set(Settings x, boolean v) { x.intro = v; } }));
        bind("holdAnim", toggle(rules, "Animation à l'arrêt", "À 0 km/h en D",
            s.holdAnim, new Toggle() { public void set(Settings x, boolean v) { x.holdAnim = v; } }));
        bind("apFlash", toggle(rules, "Bleu à l'Autosteer", null,
            s.apFlash, new Toggle() { public void set(Settings x, boolean v) { x.apFlash = v; } }));
        bind("reverseWhite", toggle(rules, "Marche arrière", "Clignote, jaune → rouge selon les radars",
            s.reverseWhite, new Toggle() { public void set(Settings x, boolean v) { x.reverseWhite = v; } }));
        // one option for every opening (owner, 23 Nov: doors and boot/frunk were two switches)
        bind("doorBlink", toggle(rules, "Alerte à l'ouverture", "Portières, coffre, frunk",
            s.doorBlink, new Toggle() { public void set(Settings x, boolean v) { x.doorBlink = v; x.bootAndFrunk = v; } }));
        bind("hazard", toggle(rules, "Clignoter avec les warnings", null,
            s.hazard, new Toggle() { public void set(Settings x, boolean v) { x.hazard = v; } }));
        bind("autoPlay", toggle(rules, "Musique à la connexion CarPlay", null,
            s.autoPlay, new Toggle() { public void set(Settings x, boolean v) { x.autoPlay = v; } }));
        autoPlayLine = text("", 13, MUTED, false);
        rules.addView(autoPlayLine);
        // seeing the CarPlay app's media session needs notification access (Android's own screen)
        autoPlayAccess = pill("Autoriser l'accès aux notifications", false);
        autoPlayAccess.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openOwnScreen(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                Toast.makeText(MainActivity.this, "Activer Ruban LED dans la liste", Toast.LENGTH_LONG).show();
            }
        });
        rules.addView(autoPlayAccess);
        bind("night", toggle(rules, "Mode nuit", null,
            s.night, new Toggle() { public void set(Settings x, boolean v) { x.night = v; } }));
        nightSummary = text(nightLines(s), 13, MUTED, false);
        rules.addView(nightSummary);
        // every number behind one button (owner, 23 Nov: too much room otherwise)
        LinearLayout tuning = column();
        LinearLayout n1 = new LinearLayout(this), n2 = new LinearLayout(this);
        nightWinter = number(n2, "Nuit hiver (h)", s.nightStartWinter);
        nightSummer = number(n2, "Nuit été (h)", s.nightStartSummer);
        nightEnd = number(n2, "Fin nuit (h)", s.nightEnd);
        nightPct = number(n2, "Nuit (%)", s.nightPct);
        blinkMs = number(n1, "Clignot. (ms)", s.blinkMs);
        repeat = number(n1, "Animations ×", s.animRepeat);
        // fields under a two-line label sat lower: no baseline alignment in these rows (owner, 23 Nov)
        for (LinearLayout r : new LinearLayout[] { n1, n2, t1, t2 }) r.setBaselineAligned(false);
        tuning.addView(n2);
        tuning.addView(n1);
        tuning.addView(saveButton(), topMargin(8));
        rules.addView(folder("Réglages", tuning), topMargin(6));
        rules.addView(tuning);

        LinearLayout strip = card(left, "Ruban et application");
        strip.addView(text("Adresse Bluetooth du ruban", 13, MUTED, false));
        LinearLayout ad = new LinearLayout(this);
        ad.setGravity(Gravity.CENTER_VERTICAL);
        ad.setBaselineAligned(false);
        address = new EditText(this);
        address.setText(s.address);
        address.setSingleLine(true);
        address.setTextColor(TEXT);
        address.setHint("AA:BB:CC:DD:EE:FF");
        address.setHintTextColor(0xFF5F6770);
        ad.addView(address, weight(1, dp(10)));
        Button saveAddr = saveButton();
        saveAddr.setPadding(dp(18), 0, dp(18), 0);
        ad.addView(saveAddr);
        strip.addView(ad);
        LinearLayout r3 = new LinearLayout(this);
        r3.setBaselineAligned(false);
        Button update = pill("Mise à jour de l'app", false);
        update.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { update(); }
        });
        animReset = pill("Animations d'origine", false);
        animReset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Updater.animBusy) return;
                Animations.reset(MainActivity.this);
                Toast.makeText(MainActivity.this, "Animations d'origine remises", Toast.LENGTH_SHORT).show();
                refreshAnimations();
            }
        });
        r3.addView(update, weight(1, dp(8)));
        r3.addView(animReset, weight(1, 0));
        strip.addView(r3, topMargin(8));
        updateText = text("", 13, MUTED, false);
        updateText.setPadding(0, dp(8), 0, 0);
        strip.addView(updateText);
        animState = text("", 13, MUTED, false);
        strip.addView(animState);
        refreshAnimations();

        // ---- debug (0.29): the journal and the last events, folded away until needed
        LinearLayout debug = column();
        right.addView(folder("Debug", debug));
        right.addView(debug, topMargin(10));
        LinearLayout journal = card(debug, "Journaux");
        journalState = text("", 13, MUTED, false);
        journal.addView(journalState);
        LinearLayout js = new LinearLayout(this);
        send24 = pill("Envoyer (24 h)", true);
        send24.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendLogs(24); }
        });
        sendAll = pill("Tout (7 jours)", false);
        sendAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { sendLogs(0); }
        });
        js.setBaselineAligned(false);
        js.addView(send24, weight(1.4f, dp(8)));
        js.addView(sendAll, weight(1, 0));
        journal.addView(js, topMargin(8));
        journalBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        journalBar.setMax(100);
        journalBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams jbp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14));
        jbp.topMargin = dp(10);
        journal.addView(journalBar, jbp);
        journalSteps = text("", 14, TEXT, false);
        journalSteps.setTypeface(Typeface.MONOSPACE);
        journalSteps.setVisibility(View.GONE);
        journal.addView(journalSteps);
        journal.addView(text("Il faut Internet et « Journaux Ruban LED » activé dans Home Assistant.", 13, MUTED, false));
        bind("journalFull", toggle(journal, "Journal détaillé", "Tout le CAN et le Bluetooth",
            s.journalFull, new Toggle() { public void set(Settings x, boolean v) { x.journalFull = v; } }));
        LinearLayout jc = new LinearLayout(this);
        jc.setGravity(Gravity.BOTTOM);
        jc.setBaselineAligned(false);
        journalCodes = number(jc, "Codes CAN en plus (ex. 2,3,4 ou 0-127)", 0);
        journalCodes.setText(s.journalCodes);
        journalCodes.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        Button saveCodes = saveButton();
        saveCodes.setPadding(dp(18), 0, dp(18), 0);
        jc.addView(saveCodes);
        journal.addView(jc);

        LinearLayout log = card(debug, "Derniers événements");
        eventsLine = text("", 14, MUTED, false);
        eventsLine.setTypeface(Typeface.MONOSPACE);
        log.addView(eventsLine);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.addView(page);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        main.post(refresh);
        if (openedByOwner && !ownScreen) checkAnimationsOnce();
        openedByOwner = false;
        ownScreen = false;
    }

    @Override protected void onStop() {
        super.onStop();
        // left for another app: the next time it shows, the owner opened it again; unless the
        // screen went dark (the car sleeps), or the app opened that screen itself
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        boolean screenOn = pm == null || pm.isInteractive();
        if (screenOn && !ownScreen) openedByOwner = true;
    }

    /** One of Android's screens, opened by the app: coming back from it is not an opening. */
    private void openOwnScreen(Intent i) {
        ownScreen = true;
        startActivity(i);
    }

    @Override protected void onPause() {
        main.removeCallbacks(refresh);
        setColoursLocked(true);              // locked again whenever the app is left
        super.onPause();
    }

    private ColourPanel steadyPanel, alertPanel;
    private Button lockButton;
    private boolean coloursLocked = true;
    /** Unlocked colours lock again after this long. */
    private static final long UNLOCK_MS = 2 * 60 * 1000L;

    private void setColoursLocked(boolean l) {
        coloursLocked = l;
        if (steadyPanel != null) steadyPanel.setLocked(l);
        if (alertPanel != null) alertPanel.setLocked(l);
        if (lockButton != null) {
            lockButton.setText(l ? "🔒  Modifier les couleurs" : "🔓  Verrouiller les couleurs");
        }
        main.removeCallbacks(relock);
        if (!l) main.postDelayed(relock, UNLOCK_MS);
    }

    private final Runnable relock = new Runnable() {
        @Override public void run() { setColoursLocked(true); }
    };

    private int refreshCount;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            // night or day can change on its own: the line under « Mode nuit » every 15 s
            if (refreshCount++ % 15 == 0) nightSummary.setText(nightLines(Settings.load(MainActivity.this)));
            String link = LedService.linkText;
            boolean ok = "Ruban connecté".equals(link);
            linkLine.setText(link);
            dot(linkDot, ok ? OK : link.startsWith("Service") ? MUTED : WARN);
            stripLine.setText(LedService.stripText);
            // the CAN app's state only when it is not the usual one (0.29)
            String feed = LedService.feedText;
            boolean feedOk = feed.isEmpty() || feed.contains("abonné");
            doorsLine.setText(LedService.doorsText + "   ·   " + LedService.presenceText + (feedOk ? "" : "\n" + feed));
            String rule = LedService.ruleText;
            ruleLine.setText(rule);
            ruleLine.setVisibility(rule.isEmpty() ? View.GONE : View.VISIBLE);
            eventsLine.setText(LedService.eventsText.isEmpty() ? "—" : LedService.eventsText);
            String play = CarPlayAutoPlay.text.replace("Lecture auto CarPlay : ", "");
            autoPlayLine.setText(play);
            boolean access = CarPlayAutoPlay.hasAccess(MainActivity.this);
            autoPlayLine.setVisibility(!access || play.isEmpty() || play.equals("désactivée") ? View.GONE : View.VISIBLE);
            if (seenChanges != RemoteServer.changes) {
                seenChanges = RemoteServer.changes;
                syncFromPhone();
            }
            // the link to open on the iPhone, big, and whether it answers right now
            boolean on = Settings.load(MainActivity.this).remote;
            String url = RemoteServer.url(MainActivity.this);
            remoteUrl.setText(url != null ? url : "Code " + Settings.remoteCode(MainActivity.this));
            remoteUrl.setAlpha(on ? 1f : 0.45f);
            remoteLine.setText(!on ? "Désactivée"
                : RemoteServer.live ? "● iPhone connecté"
                : url != null ? "Disponible quand l'iPhone est en CarPlay"
                : "Le lien s'affiche à la première connexion CarPlay");
            remoteLine.setTextColor(on && RemoteServer.live ? OK : MUTED);
            autoPlayAccess.setVisibility(access ? View.GONE : View.VISIBLE);
            refreshJournal();
            refreshUpdates();
            main.postDelayed(this, Journal.sending || Updater.animBusy || Updater.appBusy ? 250 : 1000);
        }
    };

    // ---- animations (0.28; header button, startup check and progress window since 0.29)

    private void updateAnimations() {
        if (!Updater.startAnimations(this)) { showAnimDialog(); return; }
        refreshUpdates();
    }

    /** On opening: if GitHub has other animations than those in place, ask whether to take them. */
    private void checkAnimationsOnce() {
        Updater.checkAnimations(this, new Updater.AnimCheck() {
            @Override public void onNewAnimations(final String sha, String when) {
                if (isFinishing() || isDestroyed() || Updater.animBusy || (animDialog != null && animDialog.isShowing())) return;
                if (sha.equals(getPreferences(MODE_PRIVATE).getString("animDeclined", ""))) return;
                new AlertDialog.Builder(MainActivity.this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                    .setTitle("Nouvelles animations")
                    .setMessage("Mettre à jour les animations ?" + (when.isEmpty() ? "" : "\n(version du " + when + ")"))
                    .setPositiveButton("Mettre à jour", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { updateAnimations(); }
                    })
                    .setNegativeButton("Plus tard", new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            // not asked again for this version: the button at the top stays there
                            getPreferences(MODE_PRIVATE).edit().putString("animDeclined", sha).apply();
                        }
                    })
                    .show();
            }
        });
    }

    /** The update's steps and progress bar, in a window over the screen. */
    private void showAnimDialog() {
        if (animDialog != null && animDialog.isShowing()) return;
        if (isFinishing() || isDestroyed()) return;
        LinearLayout box = column();
        box.setPadding(dp(24), dp(12), dp(24), dp(4));
        animBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        animBar.setMax(100);
        box.addView(animBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(14)));
        animSteps = text("", 16, TEXT, false);
        animSteps.setLineSpacing(dp(4), 1f);
        animSteps.setPadding(0, dp(10), 0, 0);
        box.addView(animSteps);
        animDialog = new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Mise à jour des animations")
            .setView(box)
            .setPositiveButton("Fermer", null)
            .show();
        refreshUpdates();
    }

    /**
     * The two updates, started here or from the iPhone page (0.28): the same steps on both,
     * read from Updater every refresh.
     */
    private boolean animWasBusy;
    private String appShown = null;

    private void refreshUpdates() {
        boolean busy = Updater.animBusy;
        if (busy && !animWasBusy) showAnimDialog();      // also when started from the iPhone
        if (animDialog != null && animDialog.isShowing()) {
            animSteps.setText(Updater.animText.isEmpty() ? "…" : Updater.animText);
            int pct = Updater.animPercent;
            animBar.setVisibility(busy && pct != -1 ? View.VISIBLE : View.INVISIBLE);
            animBar.setIndeterminate(pct == -2);
            if (pct >= 0) animBar.setProgress(pct);
        }
        animUpdate.setEnabled(!busy);
        animReset.setEnabled(!busy);
        animUpdate.setAlpha(busy ? 0.5f : 1f);
        animReset.setAlpha(busy ? 0.5f : 1f);
        if (animWasBusy && !busy) refreshAnimations();
        animWasBusy = busy;
        String app = Updater.appText;
        if (!app.equals(appShown)) {
            appShown = app;
            updateText.setText(app.isEmpty() ? "Version " + versionName()
                : app + (Updater.appPercent >= 0 ? "  (" + Updater.appPercent + " %)" : ""));
        }
    }

    /** The night hours, then the summer / winter time as the app sees it (0.30). */
    private String nightLines(Settings s) {
        return s.nightStateText(Settings.loadBeforeNight(this)) +"\n" + RemoteServer.nightText(s) + "\n" + s.clockText();
    }

    private void refreshAnimations() {
        animState.setText("Animations : " + Animations.get(this).summary());
        boolean file = !Animations.installedSha(this).isEmpty();
        animReset.setVisibility(file ? View.VISIBLE : View.GONE);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "" + Updater.installedVersion(this);
        }
    }

    // ---- journal (0.20)

    private void sendLogs(int hours) {
        if (Journal.sending) return;
        Journal.steps = "…  Envoi demandé";
        Journal.percent = 0;
        startService(new Intent(this, LedService.class).setAction(LedService.ACTION_SEND_LOGS).putExtra("hours", hours));
        refreshJournal();
    }

    private void refreshJournal() {
        Journal j = Journal.get(this);
        journalState.setText((j.configured() ? "Certificat installé" : "Certificat absent (à poser par ADB)") + " · " + j.sizeText());
        String steps = Journal.steps;
        journalSteps.setVisibility(steps.isEmpty() ? View.GONE : View.VISIBLE);
        journalSteps.setText(steps);
        int p = Journal.percent;
        journalBar.setVisibility(p < 0 || steps.isEmpty() ? View.GONE : View.VISIBLE);
        if (p >= 0) journalBar.setProgress(p);
        boolean busy = Journal.sending;
        send24.setEnabled(!busy);
        sendAll.setEnabled(!busy);
        send24.setAlpha(busy ? 0.5f : 1f);
        sendAll.setAlpha(busy ? 0.5f : 1f);
    }

    // ---- actions

    /** Every field at once (address, numbers, night, CAN codes): one « Enregistrer » since 0.29. */
    private void save() {
        try {
            Settings s = Settings.load(this);
            s.address = address.getText().toString().trim().toUpperCase();
            s.blinkMs = clamp(num(blinkMs), 150, 5000);
            s.animRepeat = clamp(num(repeat), 1, 10);
            s.nightStartWinter = clamp(num(nightWinter), 0, 23);
            s.nightStartSummer = clamp(num(nightSummer), 0, 23);
            s.nightEnd = clamp(num(nightEnd), 0, 23);
            s.nightPct = clamp(num(nightPct), 1, 100);
            s.journalCodes = journalCodes.getText().toString().replaceAll("[^0-9,;\\- ]", "").trim();
            s.save(this);
            nightSummary.setText(nightLines(s));
            LedService.send(this, LedService.ACTION_RELOAD);
            Toast.makeText(this, "Enregistré", Toast.LENGTH_SHORT).show();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Un nombre est invalide", Toast.LENGTH_SHORT).show();
        }
    }

    private Button saveButton() {
        Button b = pill("Enregistrer", true);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        return b;
    }

    private void update() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            // Android's own switch: Install unknown apps → Ruban LED
            openOwnScreen(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                android.net.Uri.parse("package:" + getPackageName())));
            Toast.makeText(this, "Autoriser « Installer des applis inconnues », puis réessayer", Toast.LENGTH_LONG).show();
            return;
        }
        ownScreen = true;            // Android's install screen comes up on its own if there is a new version
        Updater.startApp(this);
        refreshUpdates();
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
                openOwnScreen(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:" + getPackageName())));
            }
        } catch (Throwable ignored) {
        }
    }

    private interface Toggle {
        void set(Settings s, boolean v);
    }

    // ---- building blocks

    /** Title, the strip's connection and, on the right, the animations update (0.29). */
    private LinearLayout header() {
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBaselineAligned(false);
        h.setPadding(dp(4), 0, 0, dp(12));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getDrawable(R.mipmap.ic_launcher));
        h.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView t = text("Ruban LED", 26, TEXT, true);
        t.setPadding(dp(12), 0, dp(28), 0);
        h.addView(t);
        linkDot = new View(this);
        h.addView(linkDot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        linkLine = text("", 17, TEXT, true);
        linkLine.setPadding(dp(10), 0, dp(12), 0);
        linkLine.setSingleLine(true);
        linkLine.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.addView(linkLine, weight(1, 0));
        animUpdate = pill("Mise à jour des animations", true);
        animUpdate.setPadding(dp(22), 0, dp(22), 0);
        animUpdate.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { updateAnimations(); }
        });
        h.addView(animUpdate);
        return h;
    }

    /** A « Name ▸ » button that shows or hides box (hidden at first). */
    private Button folder(final String name, final View box) {
        final Button b = pill(name + " ▸", false);
        box.setVisibility(View.GONE);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean show = box.getVisibility() != View.VISIBLE;
                box.setVisibility(show ? View.VISIBLE : View.GONE);
                b.setText(name + (show ? " ▾" : " ▸"));
            }
        });
        return b;
    }

    private static LinearLayout.LayoutParams topMargin(int dpTop) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(dpTop * android.content.res.Resources.getSystem().getDisplayMetrics().density);
        return lp;
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
    /** Switches by setting name, so a change from the iPhone shows here at once (0.25). */
    private final java.util.HashMap<String, Switch> switches = new java.util.HashMap<>();
    /** Set while the screen copies a change from the iPhone: the switches don't save it again. */
    private boolean syncing;
    private int seenChanges = RemoteServer.changes;

    private void bind(String key, Switch sw) {
        switches.put(key, sw);
    }

    /** The iPhone page changed a colour or a rule: show it here too. */
    private void syncFromPhone() {
        Settings s = Settings.load(this);
        syncing = true;
        try {
            if (coloursLocked) {                // not while colours are being edited here
                steadyPanel.show(s.defaultHue, s.defaultSat, s.dayPct);
                alertPanel.show(s.blinkHue, s.blinkSat, s.blinkPct);
            }
            nightSummary.setText(nightLines(s));
            if (!nightWinter.hasFocus() && !nightSummer.hasFocus() && !nightEnd.hasFocus() && !nightPct.hasFocus()) {
                nightWinter.setText("" + s.nightStartWinter);
                nightSummer.setText("" + s.nightStartSummer);
                nightEnd.setText("" + s.nightEnd);
                nightPct.setText("" + s.nightPct);
            }
            for (java.util.Map.Entry<String, Switch> e : switches.entrySet()) {
                Boolean v = value(s, e.getKey());
                if (v != null && e.getValue().isChecked() != v) e.getValue().setChecked(v);
            }
        } finally {
            syncing = false;
        }
    }

    private static Boolean value(Settings s, String key) {
        switch (key) {
            case "intro": return s.intro;
            case "holdAnim": return s.holdAnim;
            case "apFlash": return s.apFlash;
            case "reverseWhite": return s.reverseWhite;
            case "unitQuiet": return s.unitQuiet;
            case "doorBlink": return s.doorBlink;
            case "hazard": return s.hazard;
            case "autoPlay": return s.autoPlay;
            case "night": return s.night;
            case "journalFull": return s.journalFull;
            case "remote": return s.remote;
            default: return null;
        }
    }

    private Switch toggle(LinearLayout parent, String title, String sub, boolean value, final Toggle t) {
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
                if (syncing) return;
                Settings s = Settings.load(MainActivity.this);
                t.set(s, v);
                s.save(MainActivity.this);
                LedService.send(MainActivity.this, LedService.ACTION_RELOAD);
            }
        });
        row.addView(sw);
        parent.addView(row);
        return sw;
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

    /** Same height for every button of a row. */
    private static LinearLayout.LayoutParams fill(LinearLayout.LayoutParams lp) {
        lp.height = LinearLayout.LayoutParams.MATCH_PARENT;
        return lp;
    }

    private LinearLayout.LayoutParams weight(float w, int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
        lp.rightMargin = rightMargin;
        return lp;
    }

    /** One colour setting: wheel on the left; title, preview and brightness gauge on the right. */
    /**
     * One colour as three bars, like the iPhone page (owner, 23 Nov): hue, saturation, brightness,
     * each drawn with its own gradient, and a swatch. Locked like before (a scroll must not change it).
     */
    private final class ColourPanel {
        final View view;
        private final String target;
        private final View swatch;
        private final TextView pctLabel;
        private final SeekBar hueBar, satBar, valBar;
        private int hue, sat, pct;
        private long lastSentMs;

        void setLocked(boolean l) {
            for (SeekBar b : new SeekBar[] {hueBar, satBar, valBar}) {
                b.setEnabled(!l);
                b.setAlpha(l ? 0.45f : 1f);
            }
        }

        ColourPanel(String target, String title, int hue0, int sat0, int pct0) {
            this.target = target;
            hue = hue0;
            sat = sat0;
            pct = pct0;
            LinearLayout box = column();
            LinearLayout head = new LinearLayout(MainActivity.this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            swatch = new View(MainActivity.this);
            head.addView(swatch, new LinearLayout.LayoutParams(dp(44), dp(44)));
            LinearLayout names = column();
            names.setPadding(dp(12), 0, 0, 0);
            names.addView(text(title, 17, TEXT, true));
            pctLabel = text("", 14, MUTED, false);
            names.addView(pctLabel);
            head.addView(names, weight(1, 0));
            box.addView(head);
            hueBar = bar(box, "Teinte", 359, hue);
            satBar = bar(box, "Saturation", 100, sat);
            valBar = bar(box, "Luminosité", 99, pct - 1);
            SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar b, int p, boolean user) {
                    hue = hueBar.getProgress();
                    sat = satBar.getProgress();
                    pct = valBar.getProgress() + 1;
                    preview();
                    if (user) send(false);
                }
                @Override public void onStartTrackingTouch(SeekBar b) {}
                @Override public void onStopTrackingTouch(SeekBar b) { send(true); }
            };
            hueBar.setOnSeekBarChangeListener(l);
            satBar.setOnSeekBarChangeListener(l);
            valBar.setOnSeekBarChangeListener(l);
            hueBar.setProgressDrawable(gradient(new int[] {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                0xFF0000FF, 0xFFFF00FF, 0xFFFF0000}));
            preview();
            setLocked(true);
            view = box;
        }

        /** A labelled bar 0..max at value, with a white round thumb. */
        private SeekBar bar(LinearLayout parent, String label, int max, int value) {
            TextView t = text(label, 13, MUTED, false);
            t.setPadding(0, dp(10), 0, dp(4));
            parent.addView(t);
            SeekBar b = new SeekBar(MainActivity.this);
            b.setMax(max);
            b.setProgress(value);
            b.setSplitTrack(false);
            b.setPadding(dp(16), 0, dp(16), 0);
            GradientDrawable thumb = new GradientDrawable();
            thumb.setShape(GradientDrawable.OVAL);
            thumb.setColor(Color.WHITE);
            thumb.setStroke(dp(3), 0x88000000);
            thumb.setSize(dp(30), dp(30));
            b.setThumb(thumb);
            b.setThumbOffset(dp(15));
            parent.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
            return b;
        }

        private GradientDrawable gradient(int[] colours) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colours);
            g.setCornerRadius(dp(15));
            g.setStroke(dp(1), CARD_LINE);
            return g;
        }

        /** A colour changed elsewhere (the iPhone): bars and swatch, nothing sent. */
        void show(int h, int s, int p) {
            if (h == hue && s == sat && p == pct) return;
            hue = h;
            sat = s;
            pct = p;
            hueBar.setProgress(h);
            satBar.setProgress(s);
            valBar.setProgress(p - 1);
            preview();
        }

        private void preview() {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(10));
            g.setStroke(dp(1), CARD_LINE);
            g.setColor(Color.HSVToColor(new float[] {hue, sat / 100f, 0.25f + 0.75f * pct / 100f}));
            swatch.setBackground(g);
            pctLabel.setText("Luminosité " + pct + " %");
            // the saturation bar from white to the pure hue, the brightness one from black to the colour
            satBar.setProgressDrawable(gradient(new int[] {Color.WHITE, Color.HSVToColor(new float[] {hue, 1f, 1f})}));
            valBar.setProgressDrawable(gradient(new int[] {Color.BLACK, Color.HSVToColor(new float[] {hue, sat / 100f, 1f})}));
        }

        private void send(boolean done) {
            long now = SystemClock.elapsedRealtime();
            if (!done && now - lastSentMs < COLOUR_THROTTLE_MS) return;
            lastSentMs = now;
            // still being edited: the automatic re-lock counts from the last change
            main.removeCallbacks(relock);
            main.postDelayed(relock, UNLOCK_MS);
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
