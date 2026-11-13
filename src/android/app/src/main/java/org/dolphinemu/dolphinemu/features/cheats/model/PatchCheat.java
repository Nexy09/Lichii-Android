// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.cheats.model;

import androidx.annotation.Keep;

public class PatchCheat extends AbstractCheat {

    @Keep
    private final long mPointer;

    public PatchCheat() {
        this.mPointer = createNew();
    }

    @Keep
    private PatchCheat(long j) {
        this.mPointer = j;
    }

    private native long createNew();
    public static native PatchCheat[] loadCodes(String str, int i);
    public static native void saveCodes(String str, int i, PatchCheat[] patchCheatArr);
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
