package com.ccos.wallpaper;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** 打开就显示二维码，手机扫码上传壁纸。 */
public class MainActivity extends Activity implements UploadServer.Callback {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private UploadServer server;
    private TextView status;
    private TextView urlText;
    private ImageView qr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        int pad = dp(40);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0E14"));
        root.setPadding(pad, pad, pad, pad);

        qr = new ImageView(this);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(dp(340), dp(340));
        qr.setLayoutParams(qlp);
        qr.setBackgroundColor(Color.WHITE);
        qr.setPadding(dp(10), dp(10), dp(10), dp(10));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.setMargins(dp(44), 0, 0, 0);
        col.setLayoutParams(clp);

        TextView title = text("手机扫码换壁纸", 30, "#FFFFFF", true);
        TextView sub = text("扫左边二维码 → 选一张图 → 上传\n上传完成前不要按返回/主页，切走就断了", 17, "#8B96AD", false);
        sub.setPadding(0, dp(10), 0, dp(22));
        urlText = text("", 20, "#5F7FD8", false);
        status = text("", 16, "#8B96AD", false);
        status.setPadding(0, dp(16), 0, 0);

        col.addView(title);
        col.addView(sub);
        col.addView(urlText);
        col.addView(status);

        root.addView(qr);
        root.addView(col);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (server != null && server.isRunning()) return;
        server = new UploadServer(this);
        server.start();
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshUrl();
            }
        }, 350);
    }

    @Override
    protected void onDestroy() {
        // 只在 App 真正结束时停服务：中途按 Home 或短暂切走不该把上传打断
        if (server != null) {
            server.stop();
            server = null;
        }
        super.onDestroy();
    }

    private void refreshUrl() {
        if (server == null) return;
        String ip = localIp();
        int port = server.getPort();
        if (port == 0) {
            status.setText("服务没起来，退出去重进一次");
            return;
        }
        String url = "http://" + ip + ":" + port;
        urlText.setText(url);
        qr.setImageBitmap(qrBitmap(url));
    }

    @Override
    public void onStatus(final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                status.setText(text);
            }
        });
    }

    @Override
    public int onImage(byte[] data) {
        int bytes = ApplyWallpaper.apply(this, data);
        onStatus(bytes > 0 ? ("已写入 " + (bytes / 1024) + " KB，准备切回桌面")
                : "写入失败，检查权限（pm grant WRITE_SECURE_SETTINGS）");
        return bytes;
    }

    @Override
    public void onApplied(final int bytes) {
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean granted = checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (granted) {
                    onStatus("好了，正在切回桌面…");
                    ApplyWallpaper.reloadLauncher(MainActivity.this);
                } else {
                    onStatus("图已存，但缺少写入权限：电脑上跑 adb shell pm grant com.ccos.wallpaper "
                            + "android.permission.WRITE_SECURE_SETTINGS");
                }
            }
        }, 900);
    }

    @Override
    public String appsJson() {
        try {
            java.util.Map<String, String> found = new java.util.LinkedHashMap<>();
            android.content.Intent[] intents = new android.content.Intent[]{
                    new android.content.Intent(android.content.Intent.ACTION_MAIN)
                            .addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER),
                    new android.content.Intent(android.content.Intent.ACTION_MAIN)
                            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            };
            for (android.content.Intent it : intents) {
                for (android.content.pm.ResolveInfo ri :
                        getPackageManager().queryIntentActivities(it, 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg == null || found.containsKey(pkg)) continue;
                    String label;
                    try {
                        label = String.valueOf(ri.loadLabel(getPackageManager()));
                    } catch (Throwable t) {
                        label = pkg;
                    }
                    found.put(pkg, label);
                }
            }
            org.json.JSONArray arr = new org.json.JSONArray();
            for (java.util.Map.Entry<String, String> e : found.entrySet()) {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("p", e.getKey());
                o.put("n", e.getValue());
                arr.put(o);
            }
            return arr.toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    @Override
    public String dockJson() {
        org.json.JSONArray arr = new org.json.JSONArray();
        String[] dock = ApplyWallpaper.readDock(this);
        if (dock != null) {
            for (String s : dock) arr.put(s == null ? "" : s);
        }
        return arr.toString();
    }

    @Override
    public boolean setDock(String csv) {
        String[] parts = csv.split(",");
        String[] dock = new String[4];
        for (int i = 0; i < 4 && i < parts.length; i++) dock[i] = parts[i].trim();
        boolean ok = ApplyWallpaper.writeDock(this, dock);
        if (ok) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    ApplyWallpaper.reloadLauncher(MainActivity.this);
                }
            }, 1200);
        }
        return ok;
    }

    private TextView text(String s, int sp, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor(color));
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private Bitmap qrBitmap(String content) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 600, 600, hints);
            int[] px = new int[m.getWidth() * m.getHeight()];
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    px[y * m.getWidth() + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bmp = Bitmap.createBitmap(m.getWidth(), m.getHeight(), Bitmap.Config.ARGB_8888);
            bmp.setPixels(px, 0, m.getWidth(), 0, 0, m.getWidth(), m.getHeight());
            return bmp;
        } catch (Throwable t) {
            return null;
        }
    }

    private String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        String host = a.getHostAddress();
                        if (host != null && (ni.getName().startsWith("wlan")
                                || ni.getName().startsWith("eth"))) return host;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "127.0.0.1";   // 没拿到局域网地址时的兜底
    }
}
