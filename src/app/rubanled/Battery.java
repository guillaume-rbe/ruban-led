package app.rubanled;

/**
 * Battery % from the CAN app's code 11, as Binnacle does it (app/src/app/binnacle/BatteryDecoder.java,
 * copied here): on the owner's car "batterylevel" is stuck at 77, but "jhmail" moves one step per %
 * and its exact value identifies the % (table recorded through a 13 % -> 100 % Supercharge,
 * 30 Oct 2026). batterylevel is used instead once it has been seen to change (other cars).
 */
final class Battery {
    private static final int[] BASE = {
            4, 3325, 6646, 9967, 13288, 16609, 19930, 23251, 26572, 29893, 33214, 36535, 39856, 43177,
            46498, 49819, 53140, 56461, 59782, 63103, 888, 4209, 7530, 10851, 14172, 17493, 20814,
            24135, 27456, 30777, 34098, 37419, 828, 4149, 7470, 10791, 14112, 17433, 20754, 24075,
            27396, 30717, 34038, 37359, 40680, 44001, 47322, 50643, 53964, 57285, 60606, 63927, 1712,
            5033, 8354, 11675, 14996, 18317, 21638, 24958, 28279, 31600, 34921, 38242, 1652, 4973,
            8294, 11615, 14936, 18257, 21578, 24899, 28220, 31541, 34862, 38183, 41504, 44825, 48146,
            51467, 54788, 58109, 61430, 64750, 2535, 5856, 9177, 12498, 15819, 19140, 22461, 25782,
            29103, 32424, 35745, 39066, 2476, 5797, 9118, 12439, 15760
    };
    private static final int[] JITTER = {0, 25, 26, -25, -26};
    private static final int TOLERANCE = 4;
    private static final long HOLD_MS = 10_000;

    private int baseline = -1, current = -1;
    private long currentSince;
    private boolean levelMoves;

    /** The % for this code 11 reading (-1 if unknown). */
    int percent(int jhmail, int batterylevel, long nowMs) {
        watch(batterylevel, nowMs);
        if (levelMoves && batterylevel >= 0 && batterylevel <= 100) return batterylevel;
        return fromJhmail(jhmail);
    }

    /** batterylevel counts as working once a second value has held for HOLD_MS. */
    private void watch(int level, long nowMs) {
        if (levelMoves) return;
        if (level < 0 || level > 100) { current = -1; return; }
        if (level != current) { current = level; currentSince = nowMs; return; }
        if (nowMs - currentSince < HOLD_MS) return;
        if (baseline < 0) baseline = level;
        else if (level != baseline) levelMoves = true;
    }

    static int fromJhmail(int jhmail) {
        if (jhmail < 0) return -1;
        int found = -1;
        for (int p = 0; p < BASE.length; p++) {
            for (int j : JITTER) {
                if (Math.abs(jhmail - (BASE[p] + j)) <= TOLERANCE) {
                    if (found >= 0 && found != p) return -1;
                    found = p;
                }
            }
        }
        return found;
    }
}
