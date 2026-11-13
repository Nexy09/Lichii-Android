// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.settings.model;

public abstract class NativeConfig {
    public static native boolean deleteKey(int i, String str, String str2, String str3);
    public static native boolean getBoolean(int i, String str, String str2, String str3, boolean z);
    public static native float getFloat(int i, String str, String str2, String str3, float f);
    public static native int getInt(int i, String str, String str2, String str3, int i2);
    public static native String getString(int i, String str, String str2, String str3, String str4);
    public static native boolean isOverridden(String str, String str2, String str3);
    public static native boolean isSettingSaveable(String str, String str2, String str3);
    public static native void loadGameInis(String str, int i);
    public static native void save(int i);
    public static native void setBoolean(int i, String str, String str2, String str3, boolean z);
    public static native void setFloat(int i, String str, String str2, String str3, float f);
    public static native void setInt(int i, String str, String str2, String str3, int i2);
    public static native void setString(int i, String str, String str2, String str3, String str4);
    public static native void unloadGameInis();
}
