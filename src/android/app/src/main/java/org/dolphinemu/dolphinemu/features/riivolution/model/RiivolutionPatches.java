// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.riivolution.model;

import androidx.annotation.Keep;

public class RiivolutionPatches {
    public final int mDiscNumber;
    public final String mGameId;
    public final int mRevision;
    public boolean mUnsavedChanges = false;

    @Keep
    private long mPointer = initialize();

    public RiivolutionPatches(String str, int i, int i2) {
        this.mGameId = str;
        this.mRevision = i;
        this.mDiscNumber = i2;
    }

    private static native long initialize();
    private native void loadConfigImpl(String str, int i, int i2);
    private native void saveConfigImpl(String str);
    private native void setSelectedChoiceImpl(int i, int i2, int i3, int i4);

    public native void finalize();
    public native int getChoiceCount(int i, int i2, int i3);
    public native String getChoiceName(int i, int i2, int i3, int i4);
    public native int getDiscCount();
    public native String getDiscName(int i);
    public native int getOptionCount(int i, int i2);
    public native String getOptionName(int i, int i2, int i3);
    public native int getSectionCount(int i);
    public native String getSectionName(int i, int i2);
    public native int getSelectedChoice(int i, int i2, int i3);

    public final void loadConfig() {
        loadConfigImpl(this.mGameId, this.mRevision, this.mDiscNumber);
    }

    public final void saveConfig() {
        if (this.mUnsavedChanges) {
            this.mUnsavedChanges = false;
            saveConfigImpl(this.mGameId);
        }
    }

    public final void setSelectedChoice(int i, int i2, int i3, int i4) {
        this.mUnsavedChanges = true;
        setSelectedChoiceImpl(i, i2, i3, i4);
    }
}
