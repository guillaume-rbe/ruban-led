package app.rubanled;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the service when the dash boots, and again right after the app is updated. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        LedService.send(ctx, null);
    }
}
