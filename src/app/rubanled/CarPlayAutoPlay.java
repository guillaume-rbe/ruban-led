package app.rubanled;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.util.Log;

import java.util.List;

/**
 * Starts the music when CarPlay connects (owner, 17 Nov): the iPhone resumes its last audio app
 * (YouTube Music for the owner) if nothing is playing a few seconds after the connection.
 *
 * The CarPlay app (xcertplay) keeps a media session for the iPhone's player. Its playback actions
 * say whether a phone is connected (measured on 17 Nov: 128 = no phone, 692 / 690 = connected,
 * with play / pause). On the change to "connected", after FIRST_MS, if it isn't playing, "play"
 * goes to that session, which sends it to the iPhone; tried up to TRIES times.
 *
 * Only Android's media sessions are used: nothing is sent to the car. Seeing another app's media
 * session needs notification access for {@link MediaListener} (the user grants it once in
 * Android's settings); no notification is ever read.
 */
final class CarPlayAutoPlay {
    interface Events {
        void event(String what);
    }

    static final String CARPLAY = "com.shilapi.xcertplay";
    private static final String TAG = "rubanled";
    private static final long FIRST_MS = 3000;
    private static final long RETRY_MS = 4000;
    private static final int TRIES = 3;
    private static final long CONNECTED_ACTIONS =
        PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE;

    /** Shown in the app. */
    static volatile String text = "";

    private final Context ctx;
    private final Handler handler;
    private final Events events;
    private MediaSessionManager manager;
    private MediaController controller;
    private boolean enabled;
    private boolean connected;
    private int tries;

    CarPlayAutoPlay(Context ctx, Handler handler, Events events) {
        this.ctx = ctx.getApplicationContext();
        this.handler = handler;
        this.events = events;
    }

    static ComponentName listener(Context c) {
        return new ComponentName(c, MediaListener.class);
    }

    /** Whether the user gave Ruban LED notification access (needed to see CarPlay's session). */
    static boolean hasAccess(Context c) {
        String s = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(listener(c).flattenToString());
    }

    /** On the handler's thread. */
    void setEnabled(boolean on) {
        enabled = on;
        stop();
        if (!on) {
            text = "Lecture auto CarPlay : désactivée";
            return;
        }
        if (!hasAccess(ctx)) {
            text = "Lecture auto CarPlay : autoriser l'accès aux notifications pour Ruban LED";
            // granted later in Android's screen: picked up within ACCESS_RETRY_MS (17 Nov: the
            // listener's own callback didn't reach the service)
            handler.postDelayed(retryAccess, ACCESS_RETRY_MS);
            return;
        }
        try {
            manager = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            manager.addOnActiveSessionsChangedListener(sessions, listener(ctx), handler);
            sessions.onActiveSessionsChanged(manager.getActiveSessions(listener(ctx)));
        } catch (SecurityException e) {
            manager = null;
            text = "Lecture auto CarPlay : accès aux notifications refusé par Android";
            Log.w(TAG, "media sessions", e);
        }
    }

    private static final long ACCESS_RETRY_MS = 15000;

    private final Runnable retryAccess = new Runnable() {
        @Override public void run() {
            if (enabled && manager == null) setEnabled(true);
        }
    };

    void stop() {
        handler.removeCallbacks(check);
        handler.removeCallbacks(retryAccess);
        if (manager != null) {
            try { manager.removeOnActiveSessionsChangedListener(sessions); } catch (Throwable ignored) {}
            manager = null;
        }
        detach();
    }

    private void detach() {
        if (controller != null) {
            try { controller.unregisterCallback(callback); } catch (Throwable ignored) {}
            controller = null;
        }
        connected = false;
    }

    private static boolean isConnected(PlaybackState s) {
        return s != null && (s.getActions() & CONNECTED_ACTIONS) != 0;
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions =
        new MediaSessionManager.OnActiveSessionsChangedListener() {
            @Override public void onActiveSessionsChanged(List<MediaController> list) {
                if (!enabled) return;
                MediaController found = null;
                if (list != null) for (MediaController c : list) if (CARPLAY.equals(c.getPackageName())) found = c;
                if (found == null) {
                    detach();
                    text = "Lecture auto CarPlay : en attente de l'app CarPlay";
                    return;
                }
                if (controller != null && controller.getSessionToken().equals(found.getSessionToken())) return;
                detach();
                controller = found;
                controller.registerCallback(callback, handler);
                // the state it is in now is not a connection: only a change to "connected" counts
                connected = isConnected(controller.getPlaybackState());
                text = "Lecture auto CarPlay : " + (connected ? "iPhone connecté" : "en attente de l'iPhone");
            }
        };

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) {
            boolean now = isConnected(state);
            if (now == connected) return;
            connected = now;
            handler.removeCallbacks(check);
            if (now) {
                tries = 0;
                text = "Lecture auto CarPlay : iPhone connecté";
                events.event("CarPlay connecté");
                handler.postDelayed(check, FIRST_MS);
            } else {
                text = "Lecture auto CarPlay : en attente de l'iPhone";
            }
        }

        @Override public void onSessionDestroyed() {
            detach();
            text = "Lecture auto CarPlay : en attente de l'app CarPlay";
        }
    };

    private final Runnable check = new Runnable() {
        @Override public void run() {
            if (!enabled || controller == null || !connected) return;
            PlaybackState s = controller.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                if (tries > 0) events.event("Musique lancée");
                return;                                         // already playing: nothing to do
            }
            if (tries >= TRIES) {
                events.event("Lecture auto : pas de musique après " + TRIES + " essais");
                return;
            }
            tries++;
            controller.getTransportControls().play();
            events.event("Lecture auto CarPlay (essai " + tries + ")");
            handler.postDelayed(this, RETRY_MS);
        }
    };
}
