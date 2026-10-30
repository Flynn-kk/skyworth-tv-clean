package com.ccos.wallpaper;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** 把收到的图片缩放、裁切、压缩，写进桌面主题的 wallpaper.jpg，再让桌面重读一次。 */
public final class ApplyWallpaper {

    public static final String TAG = "TvWallpaper";

    static final String LAUNCHER_PKG = "com.coocaa.tvhome";
    static final String LAUNCHER_CLS = "com.gathin.tvhome.MainActivity";
    static final Uri CONFIG = Uri.parse("content://com.coocaa.tvhome.theme/config.json");
    static final Uri WALLPAPER = Uri.parse("content://com.coocaa.tvhome.theme/wallpaper.jpg");

    private ApplyWallpaper() {
    }

    /** 返回真正落盘的 JPEG 字节数；失败返回 -1。写完读回校验，防止 provider 静默不写。 */
    public static int apply(Context ctx, byte[] data) {
        try {
            Bitmap src = decode(data);
            if (src == null) {
                Log.e(TAG, "decode failed: not an image Android can read");
                return -1;
            }
            Bitmap out = cover(src, 1920, 1080);
            ByteArrayOutputStream bos = new ByteArrayOutputStream(512 * 1024);
            out.compress(Bitmap.CompressFormat.JPEG, 88, bos);
            byte[] jpeg = bos.toByteArray();
            write(ctx, WALLPAPER, jpeg);
            int back = sizeOf(ctx, WALLPAPER);
            if (back != jpeg.length) {
                Log.e(TAG, "write did not stick: wrote " + jpeg.length + " read " + back);
                return -1;
            }
            ensureWallpaperOn(ctx);
            if (src != out) src.recycle();
            out.recycle();
            return jpeg.length;
        } catch (Throwable t) {
            Log.e(TAG, "apply failed", t);
            return -1;
        }
    }

    private static int sizeOf(Context ctx, Uri uri) {
        try {
            InputStream is = ctx.getContentResolver().openInputStream(uri);
            if (is == null) return -1;
            byte[] buf = new byte[8192];
            int total = 0, n;
            while ((n = is.read(buf)) > 0) total += n;
            is.close();
            return total;
        } catch (Throwable t) {
            Log.e(TAG, "sizeOf failed", t);
            return -1;
        }
    }

    /** 桌面只在启动时读壁纸，所以必须让它重建一次 Activity。 */
    public static void reloadLauncher(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) am.killBackgroundProcesses(LAUNCHER_PKG);
        } catch (Throwable t) {
            Log.w(TAG, "killBackgroundProcesses refused", t);
        }
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setClassName(LAUNCHER_PKG, LAUNCHER_CLS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            ctx.startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "reload failed", t);
        }
    }

    private static Bitmap decode(byte[] data) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / sample > 2560 || bounds.outHeight / sample > 2560) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
    }

    /** 居中裁剪到目标比例再缩放，跟桌面自己的处理方式一致。 */
    private static Bitmap cover(Bitmap src, int w, int h) {
        int sw = src.getWidth(), sh = src.getHeight();
        double target = (double) w / h;
        int cw = sw, ch = sh;
        if ((double) sw / sh > target) {
            cw = (int) Math.round(sh * target);
        } else {
            ch = (int) Math.round(sw / target);
        }
        int x = (sw - cw) / 2, y = (sh - ch) / 2;
        Bitmap crop = Bitmap.createBitmap(src, x, y, cw, ch);
        if (cw == w && ch == h) return crop;
        Bitmap scaled = Bitmap.createScaledBitmap(crop, w, h, true);
        if (scaled != crop) crop.recycle();
        return scaled;
    }

    private static void write(Context ctx, Uri uri, byte[] bytes) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        // 先删一次：provider 新建文件时有开销，覆盖写更稳
        try {
            cr.delete(uri, null, null);
        } catch (Throwable ignored) {
        }
        OutputStream os = cr.openOutputStream(uri);
        if (os == null) throw new IllegalStateException("openOutputStream 返回 null");
        os.write(bytes);
        os.flush();
        os.close();
    }

    /** 保证主题里 wallpaper 是开着的，否则图写进去也不显示；照片壁纸关掉环境光染色。 */
    private static void ensureWallpaperOn(Context ctx) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            InputStream is = cr.openInputStream(CONFIG);
            if (is == null) return;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            if (bos.size() == 0) return;
            JSONObject json = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            boolean changed = false;
            if (!json.optBoolean("wallpaper", false)) {
                json.put("wallpaper", true);
                changed = true;
            }
            // 应用色环境光压在人像/风景上会糊成一片，照片壁纸一律关掉
            if (json.optBoolean("ambient_glow", true)) {
                json.put("ambient_glow", false);
                changed = true;
            }
            if (!changed) return;
            byte[] out = json.toString().getBytes("UTF-8");
            OutputStream os = cr.openOutputStream(CONFIG);
            if (os == null) return;
            os.write(out);
            os.flush();
            os.close();
            Log.i(TAG, "config.json updated: wallpaper=on, ambient_glow=off");
        } catch (Throwable t) {
            Log.w(TAG, "ensureWallpaperOn skipped: " + t);
        }
    }
    // ---- 桌面 dock 图标 -------------------------------------------------------

    /** 读当前 dock（4 个包名），读不到返回 null。 */
    public static String[] readDock(Context ctx) {
        try {
            String raw = readConfigString(ctx);
            if (raw == null) return null;
            JSONObject json = new JSONObject(raw);
            JSONArray arr = json.optJSONArray("dock_apps");
            String[] out = new String[4];
            if (arr != null) {
                for (int i = 0; i < Math.min(4, arr.length()); i++) out[i] = arr.optString(i, null);
            }
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "readDock failed", t);
            return null;
        }
    }

    /** 整组写 dock；读不到现有配置就不写，避免把问候语/壁纸等设置冲掉。 */
    public static boolean writeDock(Context ctx, String[] dock) {
        try {
            String raw = readConfigString(ctx);
            if (raw == null) return false;
            JSONObject json = new JSONObject(raw);
            JSONArray arr = new JSONArray();
            for (String s : dock) {
                if (s != null && s.length() > 0) arr.put(s);
            }
            json.put("dock_apps", arr);
            write(ctx, CONFIG, json.toString().getBytes("UTF-8"));
            int back = sizeOf(ctx, CONFIG);
            if (back <= 0) return false;
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "writeDock failed", t);
            return false;
        }
    }

    static String readConfigString(Context ctx) {
        try {
            InputStream is = ctx.getContentResolver().openInputStream(CONFIG);
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            if (bos.size() == 0) return null;
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            Log.e(TAG, "readConfigString failed", t);
            return null;
        }
    }
}
