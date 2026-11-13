// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.cheats.model;

import androidx.annotation.Keep;

public class GraphicsModGroup {

    @Keep
    private final long mPointer;

    @Keep
    private GraphicsModGroup(long j) {
        this.mPointer = j;
    }

    public static native GraphicsModGroup load(String str);
    public native void finalize();
    public native GraphicsMod[] getMods();
    public native void save();
}
