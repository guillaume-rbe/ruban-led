package app.rubanled;

import android.service.notification.NotificationListenerService;

/**
 * Only there so Android lets Ruban LED see other apps' media sessions (the CarPlay app's, for
 * {@link CarPlayAutoPlay}): that needs notification access. No notification is ever read.
 */
public class MediaListener extends NotificationListenerService {
    @Override public void onListenerConnected() {
        // access just granted (or the listener restarted): the service sets the auto play up again
        LedService.send(this, LedService.ACTION_RELOAD);
    }
}
