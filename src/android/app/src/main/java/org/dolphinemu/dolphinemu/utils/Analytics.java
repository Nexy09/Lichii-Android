// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.utils;

import android.content.Context;
import android.os.Build;
import androidx.annotation.Keep;
import org.dolphinemu.dolphinemu.DolphinApplication;

public class Analytics {
    @Keep
    public static String getValue(String str) {
        if (str == null) return "";
        switch (str) {
            case "DEVICE_MODEL":
                return Build.MODEL;
            case "DEVICE_OS":
                return String.valueOf(Build.VERSION.SDK_INT);
            case "DEVICE_MANUFACTURER":
                return Build.MANUFACTURER;
            case "DEVICE_TYPE":
                try {
                    Context ctx = DolphinApplication.getAppContext();
                    if (ctx.getPackageManager().hasSystemFeature("android.software.leanback")) {
                        return "android-tv";
                    }
                } catch (Throwable ignored) {
                }
                return "android-mobile";
            default:
                return "";
        }
    }

    @Keep
    public static void sendReport(String str, byte[] bArr) {
        // No-op for privacy & embedded engine simplicity
    }
}
