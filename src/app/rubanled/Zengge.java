package app.rubanled;

/**
 * Zengge BLE LED strip protocol, as captured from the ZENGGE app 1.5.7 on 16 Nov 2026
 * (see ../zengge-protocole.md). Pure byte building and parsing, no Android calls.
 */
final class Zengge {
    static final String SERVICE = "0000ffff-0000-1000-8000-00805f9b34fb";
    static final String WRITE_CHAR = "0000ff01-0000-1000-8000-00805f9b34fb";
    static final String NOTIFY_CHAR = "0000ff02-0000-1000-8000-00805f9b34fb";
    static final String CCCD = "00002902-0000-1000-8000-00805f9b34fb";

    /** The strip answers with its colour as 20 identical segments. */
    static final int SEGMENTS = 20;

    private int seq;

    /** Envelope type of a command (0x0A is a query, see {@link #QUERY_STATE}). */
    static final int TYPE_COMMAND = 0x0b, TYPE_QUERY = 0x0a;

    /** Asks the strip for its state; it answers with a notification ({@link #parse}). */
    static final byte[] QUERY_STATE = {(byte) 0xea, (byte) 0x81};

    /** Envelope: 00 SEQ 80 00 00 LEN LEN+1 0B + command. */
    byte[] wrap(byte[] cmd) {
        return wrap(cmd, TYPE_COMMAND);
    }

    /** Envelope: 00 SEQ 80 00 00 LEN LEN+1 TYPE + payload. */
    byte[] wrap(byte[] cmd, int type) {
        seq = (seq + 1) & 0xff;
        byte[] out = new byte[8 + cmd.length];
        out[0] = 0x00;
        out[1] = (byte) seq;
        out[2] = (byte) 0x80;
        out[3] = 0x00;
        out[4] = 0x00;
        out[5] = (byte) cmd.length;
        out[6] = (byte) (cmd.length + 1);
        out[7] = (byte) type;
        System.arraycopy(cmd, 0, out, 8, cmd.length);
        return out;
    }

    /** 3B 23 (on) / 3B 24 (off) 00x7 32 00 00 checksum. */
    static byte[] power(boolean on) {
        byte[] c = new byte[13];
        c[0] = 0x3b;
        c[1] = (byte) (on ? 0x23 : 0x24);
        c[9] = 0x32;
        c[12] = checksum(c, 12);
        return c;
    }

    /** All segments one colour. hue 0-359, sat 0-100, val 0-100. */
    static byte[] colour(int hue, int sat, int val) {
        hue = clamp(hue, 0, 359);
        sat = clamp(sat, 0, 100);
        val = clamp(val, 0, 100);
        byte[] c = new byte[7 + SEGMENTS * 4];
        c[0] = (byte) 0xe1;
        c[1] = 0x03;
        c[2] = 0x00;
        c[3] = (byte) SEGMENTS;
        c[4] = 0x00;
        c[5] = 0x00;
        c[6] = (byte) SEGMENTS;
        for (int i = 0; i < SEGMENTS; i++) {
            int o = 7 + i * 4;
            c[o] = (byte) 0xa1;
            c[o + 1] = (byte) (hue >> 1);
            c[o + 2] = (byte) (((hue & 1) << 7) | sat);
            c[o + 3] = (byte) val;
        }
        return c;
    }

    /** One colour per segment (hues.length == SEGMENTS), full saturation. */
    static byte[] segments(int[] hues, int val) {
        byte[] c = colour(0, 100, val);
        for (int i = 0; i < SEGMENTS && i < hues.length; i++) {
            int hue = clamp(hues[i], 0, 359);
            int o = 7 + i * 4;
            c[o + 1] = (byte) (hue >> 1);
            c[o + 2] = (byte) (((hue & 1) << 7) | 100);
        }
        return c;
    }

    /** Same colour on every segment, each with its own brightness (vals.length == SEGMENTS). */
    static byte[] levels(int hue, int sat, int[] vals) {
        byte[] c = colour(hue, sat, 0);
        for (int i = 0; i < SEGMENTS && i < vals.length; i++) c[7 + i * 4 + 3] = (byte) clamp(vals[i], 0, 100);
        return c;
    }

    /** Parsed state notification, or null if this notification isn't a state report. */
    static State parse(byte[] v) {
        if (v == null || v.length < 22) return null;
        if ((v[8] & 0xff) != 0xea || (v[9] & 0xff) != 0x81) return null;
        State s = new State();
        s.on = (v[14] & 0xff) == 0x23;
        s.hasColour = (v[18] & 0xff) == 0xf0;
        if (s.hasColour) {
            int t1 = v[19] & 0xff, t2 = v[20] & 0xff;
            s.hue = (t1 << 1) | (t2 >> 7);
            s.sat = t2 & 0x7f;
            s.val = v[21] & 0xff;
        }
        return s;
    }

    static final class State {
        boolean on;
        boolean hasColour;
        int hue, sat, val;

        @Override public String toString() {
            return (on ? "allumé" : "éteint") + (hasColour ? " T" + hue + " S" + sat + " L" + val + "%" : "");
        }
    }

    private static byte checksum(byte[] c, int n) {
        int sum = 0;
        for (int i = 0; i < n; i++) sum += c[i] & 0xff;
        return (byte) sum;
    }

    private static int clamp(int x, int lo, int hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
