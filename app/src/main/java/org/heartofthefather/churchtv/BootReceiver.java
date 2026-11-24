package org.heartofthefather.churchtv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/** Starts Church TV when the TV powers up (or after the app is updated). */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        final Context app = ctx.getApplicationContext();
        TvService.start(app);
        // Give the Fire TV home screen a few seconds to finish loading, then take over.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent i = new Intent(app, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            try { app.startActivity(i); } catch (Exception ignored) { }
        }, 6000);
    }
}
