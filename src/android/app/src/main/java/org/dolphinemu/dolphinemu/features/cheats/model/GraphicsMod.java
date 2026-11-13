// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.cheats.model;

import androidx.annotation.Keep;

public class GraphicsMod extends ReadOnlyCheat {

    @Keep
    private final GraphicsModGroup mParent;

    @Keep
    private final long mPointer;

    @Keep
    private GraphicsMod(long j, GraphicsModGroup graphicsModGroup) {
        this.mPointer = j;
        this.mParent = graphicsModGroup;
    }

    @Override
    public native String getCreator();

    @Override
    public native boolean getEnabled();

    @Override
    public native String getName();

    @Override
    public native String getNotes();

    @Override
    public final boolean getUserDefined() {
        return false;
    }

    @Override
    public native void setEnabledImpl(boolean z);

    @Override
    public final boolean supportsCode() {
        return false;
    }

    @Override
    public final boolean supportsCreator() {
        return true;
    }

    @Override
    public final boolean supportsNotes() {
        return true;
    }
}
