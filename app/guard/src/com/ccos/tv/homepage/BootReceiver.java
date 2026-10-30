package com.ccos.tv.homepage;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Re-arms the Skyworth / Coocaa boot + HOME component properties.
 *
 * The firmware (com.tianci.system) reads persist.service.homepage.pkg/cls and
 * persist.service.model.pkg/cls whenever it handles the HOME key, and rewrites them
 * to the stock video homepage on every boot. Nothing else on this TV can keep them
 * pointing at our launcher, so this receiver does it: once at boot, a few times over
 * the next two minutes while the firmware finishes settling, then every ten minutes.
 */
public class BootReceiver extends BroadcastReceiver {

    public static final String ACTION_WATCH = "com.ccos.tv.homepage.WATCH";
    public static final String TAG = "CcHomeGuard";

    /** Launcher installed from cleanyourtv, renamed to look like the stock home. */
    static final String LAUNCHER_PKG = "com.coocaa.tvhome";
    static final String LAUNCHER_CLS = "com.gathin.tvhome.MainActivity";

    /** Delays are cleared on every boot, so the receiver schedules them again. */
    static final long[] DELAYS_MS = { 2000L, 8000L, 20000L, 45000L, 90000L };
    static final long REPEAT_MS = 10L * 60L * 1000L;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "onReceive action=" + action);
        try {
            arm();
        } catch (Throwable t) {
            Log.e(TAG, "arm failed", t);
        }
        if (context == null) {
            return;
        }
        if (ACTION_WATCH.equals(action)) {
            // Scheduled re-arm only; the repeating alarm is already in place.
            return;
        }
        schedule(context);
    }

    static void arm() {
        put("persist.service.homepage.pkg", LAUNCHER_PKG);
        put("persist.service.homepage.cls", LAUNCHER_CLS);
        put("persist.service.model.pkg", LAUNCHER_PKG);
        put("persist.service.model.cls", LAUNCHER_CLS);
        Log.i(TAG, "armed -> " + LAUNCHER_PKG + "/" + LAUNCHER_CLS);
    }

    private static void put(String key, String value) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            props.getMethod("set", String.class, String.class)
                    .invoke(null, key, value);
            return;
        } catch (Throwable ignored) {
            // Fall through to setprop: all we need is SELinux permissive, and it is.
        }
        try {
            Process p = Runtime.getRuntime().exec(new String[] { "setprop", key, value });
            p.waitFor();
        } catch (Throwable t) {
            Log.e(TAG, "setprop " + key + " failed", t);
        }
    }

    private static void schedule(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        for (int i = 0; i < DELAYS_MS.length; i++) {
            PendingIntent pi = pending(context, 1000 + i);
            try {
                am.set(AlarmManager.RTC, System.currentTimeMillis() + DELAYS_MS[i], pi);
            } catch (Throwable t) {
                Log.e(TAG, "schedule " + DELAYS_MS[i] + " failed", t);
            }
        }
        try {
            am.setRepeating(AlarmManager.RTC, System.currentTimeMillis() + REPEAT_MS,
                    REPEAT_MS, pending(context, 2000));
        } catch (Throwable t) {
            Log.e(TAG, "schedule repeat failed", t);
        }
        Log.i(TAG, "scheduled " + DELAYS_MS.length + " one-shots + repeat");
    }

    private static PendingIntent pending(Context context, int requestCode) {
        Intent intent = new Intent(ACTION_WATCH);
        intent.setClassName(context, "com.ccos.tv.homepage.BootReceiver");
        return PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
