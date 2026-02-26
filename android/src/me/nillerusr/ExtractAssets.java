package me.nillerusr;

import android.content.SharedPreferences;
import java.io.FileOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import android.util.Log;
import android.content.Context;
import android.content.pm.ApplicationInfo;

public class ExtractAssets {

    public static String TAG = "ExtractAssets";
    static SharedPreferences mPref;

    public static final String VPK_NAME = "extras_dir.vpk";
    public static int PAK_VERSION = 9;

    private static int chmod(String path, int mode) {
        int ret = -1;

        try {
            ret = Runtime.getRuntime().exec("chmod " + Integer.toOctalString(mode) + " " + path).waitFor();
            Log.d(TAG, "chmod " + Integer.toOctalString(mode) + " " + path + ": " + ret);
        } catch (Exception e) {
            ret = -1;
            Log.d(TAG, "chmod: Runtime not worked: " + e.toString());
        }

        try {
            Class fileUtils = Class.forName("android.os.FileUtils");
            Method setPermissions = fileUtils.getMethod("setPermissions", String.class, int.class, int.class, int.class);
            ret = (Integer) setPermissions.invoke(null, path, mode, -1, -1);
        } catch (Exception e) {
            ret = -1;
            Log.d(TAG, "chmod: FileUtils not worked: " + e.toString());
        }

        return ret;
    }

    public static void extractVPK(Context context, Boolean force) {
        ApplicationInfo appinf = context.getApplicationInfo();

        if (mPref == null) {
            mPref = context.getSharedPreferences("mod", 0);
        }

        File file = new File(context.getFilesDir().getPath() + "/" + VPK_NAME);
        if (!file.exists()) {
            force = true;
        }

        if (mPref.getInt("pakversion", 0) == PAK_VERSION && !force) {
            return;
        }
        extractSingleFile(context, VPK_NAME);
        try {
            String[] assetFiles = context.getAssets().list("");
            for (String assetFile : assetFiles) {
                if (!assetFile.equals(VPK_NAME)) {
                    extractSingleFile(context, assetFile);
                }
            }
        } catch (Exception e) {
            Log.e("SRCAPK", "Failed to list assets: " + e.toString());
        }

        SharedPreferences.Editor editor = mPref.edit();
        editor.putInt("pakversion", PAK_VERSION);
        editor.commit();

        chmod(appinf.dataDir, 0777);
        chmod(context.getFilesDir().getPath(), 0777);
        chmod(context.getFilesDir().getPath() + "/" + VPK_NAME, 0777);
    }

    private static void extractSingleFile(Context context, String fileName) {
        FileOutputStream os = null;
        InputStream is = null;
        try {
            is = context.getAssets().open(fileName);
            os = new FileOutputStream(context.getFilesDir().getPath() + "/" + fileName);
            byte[] buffer = new byte[8192];
            while (true) {
                int length = is.read(buffer);
                if (length <= 0) {
                    break;
                }

                os.write(buffer, 0, length);
            }

            chmod(context.getFilesDir().getPath() + "/" + fileName, 0777);
            Log.d(TAG, "Extracted: " + fileName);
        } catch (Exception e) {
            Log.e("SRCAPK", "Failed to extract " + fileName + ": " + e.toString());
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
                if (os != null) {
                    os.close();
                }
            } catch (Exception e) {
                Log.e("SRCAPK", "Error closing streams: " + e.toString());
            }
        }
    }
}
