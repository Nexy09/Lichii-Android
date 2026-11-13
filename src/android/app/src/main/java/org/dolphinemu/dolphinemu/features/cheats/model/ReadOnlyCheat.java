// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.cheats.model;

public abstract class ReadOnlyCheat {
    public Runnable mChangedCallback = null;

    public String getCode() {
        return "";
    }

    public String getCreator() {
        return "";
    }

    public abstract boolean getEnabled();
    public abstract String getName();

    public String getNotes() {
        return "";
    }

    public abstract boolean getUserDefined();
    public abstract void setEnabledImpl(boolean z);
    public abstract boolean supportsCode();
    public abstract boolean supportsCreator();
    public abstract boolean supportsNotes();

    public int trySet(String str, String str2, String str3, String str4) {
        throw new UnsupportedOperationException();
    }
}
