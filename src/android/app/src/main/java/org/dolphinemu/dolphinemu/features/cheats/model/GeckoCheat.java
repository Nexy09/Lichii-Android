// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.cheats.model;

import androidx.annotation.Keep;

public class GeckoCheat extends AbstractCheat {

    @Keep
    private final long mPointer;

    public GeckoCheat() {
        this.mPointer = createNew();
    }

    @Keep
    private GeckoCheat(long j) {
        this.mPointer = j;
    }

    private native long createNew();
    public static native GeckoCheat[] downloadCodes(String str);
    public static native GeckoCheat[] loadCodes(String str, int i);
    public static native void saveCodes(String str, int i, GeckoCheat[] geckoCheatArr);
    public native boolean equalsImpl(GeckoCheat geckoCheat);
    public native void finalize();

    @Override
    public boolean equals(Object obj) {
        return obj != null && getClass() == obj.getClass() && equalsImpl((GeckoCheat) obj);
    }

    @Override
    public native String getCode();

    @Override
    public native String getCreator();

    @Override
    public native boolean getEnabled();

    @Override
    public native String getName();

    @Override
    public native String getNotes();

    @Override
    public native boolean getUserDefined();

    @Override
    public native void setEnabledImpl(boolean z);

    @Override
    public final boolean supportsCreator() {
        return true;
    }

    @Override
    public final boolean supportsNotes() {
        return true;
    }

    @Override
    public native int trySetImpl(String str, String str2, String str3, String str4);
}
