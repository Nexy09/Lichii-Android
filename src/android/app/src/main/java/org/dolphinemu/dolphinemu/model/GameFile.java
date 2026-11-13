// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.model;

import androidx.annotation.Keep;

public class GameFile {

    @Keep
    private long mPointer;

    @Keep
    private GameFile(long j) {
        this.mPointer = j;
    }

    public static native GameFile parse(String str);
    public native void finalize();
    public native int[] getBanner();
    public native int getBannerHeight();
    public native int getBannerWidth();
    public native int getBlobType();
    public native long getBlockSize();
    public native String getCompany();
    public native String getCompressionMethod();
    public native int getCountry();
    public native String getDescription();
    public native int getDiscNumber();
    public native String getFileFormatName();
    public native long getFileSize();
    public native String getGameId();
    public native String getGameTdbId();
    public native String getPath();
    public native int getPlatform();
    public native int getRegion();
    public native int getRevision();
    public native String getTitle();
    public native String getWiiSavePath();
    public native boolean isDatelDisc();
    public native boolean isNKit();
    public native boolean shouldAllowConversion();
    public native boolean shouldShowFileFormatDetails();
}
