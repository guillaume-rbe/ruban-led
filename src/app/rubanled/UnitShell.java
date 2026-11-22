package app.rubanled;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Runs one of a FIXED set of shell commands on the dash itself, through its own ADB daemon on
 * 127.0.0.1:5555 (network ADB kept on since 12 Nov, persist.adb.tcp.port = 5555, and not
 * authenticated on this unit), as the shell user. Adapted from Binnacle's LocalShell (GPL-3.0).
 *
 * Only the commands in {@link Command} can run: nothing comes from outside the app, an update is
 * the only way to change them. None touches the car, the MCU or the CAN app. Blocking: call off
 * the rules' thread.
 */
final class UnitShell {
    /** Marker every command echoes last, only if the steps before it succeeded. */
    static final String OK = "RUBAN_OK";

    enum Command {
        /**
         * What the dash has now, for the log: location mode (0 off, 1 GPS only, 2 network only,
         * 3 high accuracy), providers, and the screen in front (to name the popup if it still shows).
         */
        STATE("echo mode=$(settings get secure location_mode) fournisseurs=$(settings get secure location_providers_allowed)"
            + " devant=$(dumpsys activity activities | grep -m1 mResumedActivity | sed 's/.* u0 //; s/ .*//'); echo " + OK),
        /**
         * Owner, 22 Nov: Google's "Find My Device" popup at every start ("L'accès à la position de
         * l'appareil étant désactivé…", Fermer / Activer la localisation) comes from location being
         * off. No GPS module is plugged in, so location goes on in network-only mode (2): no GPS,
         * and with Google Location Accuracy off (Binnacle's UNIT-CHANGES) no Wi-Fi scans either.
         * The GPS provider is also removed explicitly. Undo: LOCATION_MODE with the old mode.
         */
        LOCATION_NETWORK_ONLY("settings put secure location_mode 2 && settings put secure location_providers_allowed -gps && echo " + OK),
        /** Puts back the mode read before the first change (%d: 0-3 only, checked in run). */
        LOCATION_MODE("settings put secure location_mode %d && echo " + OK);

        final String shell;
        Command(String shell) { this.shell = shell; }
    }

    /** LOCATION_MODE with its mode: only 0-3, never other text. */
    static String run(Command cmd, int mode) throws IOException {
        if (cmd != Command.LOCATION_MODE || mode < 0 || mode > 3) throw new IOException("commande refusée");
        return run(cmd.shell.replace("%d", Integer.toString(mode)));
    }

    static String run(Command cmd) throws IOException {
        if (cmd == Command.LOCATION_MODE) throw new IOException("commande refusée");
        return run(cmd.shell);
    }

    private static final int A_CNXN = 0x4e584e43, A_OPEN = 0x4e45504f, A_OKAY = 0x59414b4f,
            A_CLSE = 0x45534c43, A_WRTE = 0x45545257, A_AUTH = 0x48545541;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096, LOCAL_ID = 1;
    private static final int PORT = 5555, TIMEOUT_MS = 10000;
    /** A whole command gets at most 30 s and 16 KB of output. */
    private static final long DEADLINE_MS = 30000;
    private static final int MAX_OUTPUT = 16 * 1024;

    private UnitShell() {}

    /** Runs one of the commands above and returns its output (without the marker); throws if it didn't succeed. */
    private static String run(String shell) throws IOException {
        long deadline = android.os.SystemClock.elapsedRealtime() + DEADLINE_MS;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 3000);
            s.setSoTimeout(TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();

            send(out, A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.US_ASCII));
            Msg m = read(s, in, deadline);
            if (m.cmd == A_AUTH) throw new IOException("l'ADB de l'écran demande une clé");
            if (m.cmd != A_CNXN) throw new IOException("réponse ADB inattendue");

            send(out, A_OPEN, LOCAL_ID, 0, ("shell:" + shell + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            int remote = 0;
            while (true) {
                m = read(s, in, deadline);
                if (m.cmd == A_OKAY) { remote = m.arg0; continue; }
                if (m.cmd == A_WRTE) {
                    if (result.size() + m.data.length > MAX_OUTPUT) throw new IOException("trop de sortie");
                    result.write(m.data);
                    send(out, A_OKAY, LOCAL_ID, m.arg0, new byte[0]);
                    continue;
                }
                if (m.cmd == A_CLSE) {
                    send(out, A_CLSE, LOCAL_ID, remote != 0 ? remote : m.arg0, new byte[0]);
                    break;
                }
            }
            String text = result.toString("UTF-8").trim();
            if (!text.endsWith(OK)) throw new IOException(text.isEmpty() ? "pas de réponse du shell" : text);
            return text.substring(0, text.length() - OK.length()).trim();
        }
    }

    private static final class Msg { int cmd, arg0, arg1; byte[] data; }

    private static void send(OutputStream out, int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~cmd);
        out.write(h.array());
        out.write(data);
        out.flush();
    }

    private static Msg read(Socket s, InputStream in, long deadline) throws IOException {
        byte[] head = new byte[24];
        readFully(s, in, head, deadline);
        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt();
        m.arg0 = h.getInt();
        m.arg1 = h.getInt();
        int len = h.getInt();
        if (len < 0 || len > MAX_OUTPUT) throw new IOException("message ADB invalide");
        m.data = new byte[len];
        readFully(s, in, m.data, deadline);
        return m;
    }

    private static void readFully(Socket s, InputStream in, byte[] b, long deadline) throws IOException {
        int off = 0;
        while (off < b.length) {
            long left = deadline - android.os.SystemClock.elapsedRealtime();
            if (left <= 0) throw new IOException("le shell a mis trop de temps");
            s.setSoTimeout((int) Math.min(TIMEOUT_MS, left));
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new java.io.EOFException();
            off += n;
        }
    }
}
