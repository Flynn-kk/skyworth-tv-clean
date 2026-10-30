package com.ccos.cast;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * 一个点击壳：桌面（普通应用）看不到 uid 1000 的系统投屏应用，
 * 所以用它当入口，点开直接进「爱投屏」。
 */
public class MainActivity extends Activity {

    private static final String TAG = "TvCastEntry";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean ok = tryStart("com.tianci.de", "com.tianci.de.skycast.StartUpPageActivity")
                || tryStart("com.tianci.de", "com.tianci.de.skycast.HomeActivity")
                || tryStart("swaiotos.channel.iot", "swaiotos.channel.iot.tv.MainActivity");
        if (!ok) {
            Toast.makeText(this, "没找到投屏应用（爱投屏 / 跨屏互动）", Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private boolean tryStart(String pkg, String cls) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(pkg, cls);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Log.i(TAG, "started " + pkg + "/" + cls);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "cannot start " + pkg + "/" + cls + ": " + t);
            return false;
        }
    }
}
