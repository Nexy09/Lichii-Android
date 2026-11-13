// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.utils;

public abstract class WiiUtils {
    public static native int doDiscUpdate(String str, WiiUpdateCallback wiiUpdateCallback);
    public static native int doOnlineUpdate(String str, WiiUpdateCallback wiiUpdateCallback);
    public static native String getSystemMenuVersion();
    public static native void importNANDBin(String str);
    public static native int importWiiSave(String str, BooleanSupplier booleanSupplier);
    public static native boolean installWAD(String str);
    public static native boolean isSystemMenuInstalled();
    public static native boolean isSystemMenuvWii();
    public static native boolean syncSdFolderToSdImage();
    public static native boolean syncSdImageToSdFolder();
}
