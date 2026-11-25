package app.rubanled;

import java.util.Random;

/**
 * Animations played while the car waits on Auto Hold (owner, 22 Nov), drawn for the dashboard:
 * its 15 segments, segment 1 (index 0) at the right end, 15 at the left end, the middle on
 * segment 8. The doors show segments 1-10 too (same signal, see README), so they follow the right
 * two thirds of the dashboard. Pure maths, no Android calls: one frame every frameMs().
 *
 * Their settings come from Animations (animations.json, 0.28), and so do the recorded ones (kinds
 * after the five built in). Since 0.28 Croisement, Du centre vers les bouts and Scintillement have
 * a dark background (only the moving lights are lit) and Respiration lente goes down to 5 %
 * (owner, 25 Nov, tried in the simulator).
 */
final class HoldShow {
    static final String[] NAMES = {
        "Respiration lente", "Croisement", "Du centre vers les bouts", "Scintillement", "Vague de couleur",
    };
    /** Dashboard segments. */
    static final int N = 15;

    private final int kind;
    private final Random rnd;
    private final Animations a;
    /** Scintillement: each segment's spark (0..1) and whether it is still rising. */
    private final double[] spark = new double[N];
    private final boolean[] rising = new boolean[N];

    HoldShow(int kind, Random rnd, Animations a) {
        this.kind = kind;
        this.rnd = rnd;
        this.a = a;
    }

    long frameMs() {
        return kind >= NAMES.length ? a.recorded.get(kind - NAMES.length).frameMs : a.frameMs;
    }

    /**
     * Frame f in the ambient colour (hue, sat) at brightness pct: one hue, saturation and
     * brightness (0-100) per dashboard segment.
     */
    void frame(int f, int hue, int sat, int pct, int[] hues, int[] sats, int[] vals) {
        double t = f * frameMs() / 1000.0;    // seconds
        int[] rec = kind >= NAMES.length ? recordedFrame(f) : null;
        for (int i = 0; i < N; i++) {
            double x = (double) i / (N - 1);  // 0 right end .. 1 left end
            double level;
            int h = hue, s = sat;
            if (rec != null) {
                level = rec[i] / 100.0;     // recorded: the level of each segment, in the ambient colour
            } else switch (kind) {
                case 0:   // slow breathing, from full down to breathMin and back
                    level = a.breathMin + (1 - a.breathMin) * (0.5 + 0.5 * Math.cos(2 * Math.PI * t / a.breathS));
                    break;
                case 1: { // one light from each end; they meet in the middle and go on to the other end
                    double p = phase(t, a.crossMoveS, a.crossPauseS) * 1.4 - 0.2;
                    level = Math.max(a.crossBg, Math.min(1, comet(x, p, 1, a.crossTail) + comet(x, 1 - p, -1, a.crossTail)));
                    break;
                }
                case 2: { // a light leaves the middle for each end, again and again
                    double ph = phase(t, a.centreMoveS, a.centrePauseS);
                    double r = ph * 0.7;
                    double glow = Math.exp(-ph * 6) * Math.exp(-sq((x - 0.5) / 0.08));   // the start, in the middle
                    level = Math.max(a.centreBg, Math.min(1, comet(x, 0.5 + r, 1, a.centreTail)
                        + comet(x, 0.5 - r, -1, a.centreTail) + glow));
                    break;
                }
                case 3:   // stars: segments light up at random and fade away (dark between them)
                    if (i == 0) twinkle();
                    level = spark[i] < 0.1 ? a.twinkleBg : a.twinkleBg + (1 - a.twinkleBg) * spark[i];
                    break;
                default:  // the colour shifts back and forth around the ambient one, rolling along
                    h = hue + (int) Math.round(a.waveSwing * Math.sin(2 * Math.PI * (x - t / a.waveS)));
                    s = Math.max(sat, 70);
                    level = 1;
                    break;
            }
            hues[i] = ((h % 360) + 360) % 360;
            sats[i] = s;
            vals[i] = (int) Math.round(pct * level);
        }
    }

    /** Recorded animations loop over their frames until the car moves off. */
    private int[] recordedFrame(int f) {
        int[][] frames = a.recorded.get(kind - NAMES.length).frames;
        return frames[f % frames.length];
    }

    /** 0..1 over `move` seconds, then 1 (gone off the end) during `pause`, over and over. */
    private static double phase(double t, double move, double pause) {
        double m = t % (move + pause);
        return Math.min(1, m / move);
    }

    /** A point of light at p moving in direction dir (+1 towards the left end), with a tail. */
    private static double comet(double x, double p, int dir, double tail) {
        double d = (p - x) * dir;              // > 0: behind the head
        if (d < 0) return Math.exp(-sq(d / 0.05));
        return Math.exp(-d / tail);
    }

    private void twinkle() {
        for (int i = 0; i < N; i++) {
            if (rising[i]) {
                spark[i] = Math.min(1, spark[i] + 0.5);
                if (spark[i] >= 1) rising[i] = false;
            } else {
                spark[i] *= a.twinkleDecay;
                if (spark[i] < 0.05 && rnd.nextDouble() < a.twinkleChance) rising[i] = true;
            }
        }
    }

    private static double sq(double v) {
        return v * v;
    }
}
