// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.cheats.model;

import androidx.annotation.Keep;

public class ARCheat extends AbstractCheat {

    @Keep
    private final long mPointer;

    public ARCheat() {
        this.mPointer = createNew();
    }

    @Keep
    private ARCheat(long j) {
        this.mPointer = j;
    }

    private native long createNew();
    public static native ARCheat[] loadCodes(String str, int i);
    public static native void saveCodes(String str, int i, ARCheat[] aRCheatArr);
    public native void finalize();

    @Override
    public native String getCode();

    @Override
    public native boolean getEnabled();

    @Override
    public native String getName();

    @Override
    public native boolean getUserDefined();

    @Override
    public native void setEnabledImpl(boolean z);

    @Override
    public final boolean supportsCreator() {
        return false;
    }

    @Override
    public final boolean supportsNotes() {
        return false;
    }

    @Override
    public native int trySetImpl(String str, String str2, String str3, String str4);
}
