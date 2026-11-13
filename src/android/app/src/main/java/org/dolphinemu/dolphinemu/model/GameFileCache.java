// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.model;

import androidx.annotation.Keep;

public class GameFileCache {

    @Keep
    private long mPointer = newGameFileCache();

    public static native String[] getAllGamePaths(String[] strArr, boolean z);
    private static native long newGameFileCache();

    public native synchronized GameFile addOrGet(String str);
    public native void finalize();
    public native synchronized GameFile[] getAllGames();
    public native synchronized int getSize();
    public native synchronized boolean load();
    public native synchronized boolean save();
    public native synchronized boolean update(String[] strArr);
    public native synchronized boolean updateAdditionalMetadata();
}
