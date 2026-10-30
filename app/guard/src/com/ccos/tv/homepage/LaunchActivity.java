package com.ccos.tv.homepage;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Manual trigger: arms the properties and brings the launcher forward, then goes away.
 * Handy after a firmware update, or when something has taken HOME back.
 */
public class LaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            BootReceiver.arm();
        } catch (Throwable t) {
            Log.e(BootReceiver.TAG, "activity arm failed", t);
        }
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setClassName(BootReceiver.LAUNCHER_PKG, BootReceiver.LAUNCHER_CLS);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(home);
        } catch (Throwable t) {
            Log.e(BootReceiver.TAG, "activity start launcher failed", t);
        }
        finish();
    }
}
