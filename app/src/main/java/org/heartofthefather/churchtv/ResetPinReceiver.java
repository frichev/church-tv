package org.heartofthefather.churchtv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Clears a forgotten control-page PIN. Only a computer connected over ADB can trigger this:
 * the receiver requires the DUMP permission, which the ADB shell has and normal apps cannot get.
 *
 *   adb shell am broadcast -n org.heartofthefather.churchtv/.ResetPinReceiver
 */
public class ResetPinReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        Store.get(ctx).resetPin();
        Log.i("ChurchTV", "Control-page PIN cleared over ADB");
        setResultData("PIN cleared");
    }
}
