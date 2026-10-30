package com.ccos.tv.homepage;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Kept only so the declared component still exists (the manifest is fixed; this APK is
 * re-dexed, not rebuilt). Re-arms on start and stops immediately: no long-lived process.
 */
public class BootWatchService extends Service {

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            BootReceiver.arm();
            Log.i(BootReceiver.TAG, "service armed, stopping");
        } catch (Throwable t) {
            Log.e(BootReceiver.TAG, "service arm failed", t);
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }
}
