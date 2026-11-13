// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.utils;

import androidx.annotation.Keep;
import java.io.File;

public class IniFile {

    @Keep
    private long mPointer;

    public class Section {
        @Keep
        private long mPointer;

        @Keep
        private Section(long j) {
            this.mPointer = j;
        }

        public native boolean delete(String str);
        public native boolean exists(String str);
        public native boolean getBoolean(String str, boolean z);
        public native float getFloat(String str, float f);
        public native int getInt(String str, int i);
        public native String getString(String str, String str2);
        public native void setBoolean(String str, boolean z);
        public native void setFloat(String str, float f);
        public native void setInt(String str, int i);
        public native void setString(String str, String str2);
    }

    public IniFile() {
        this.mPointer = newIniFile();
    }

    public IniFile(File file) {
        this();
        if (file.exists()) {
            load(file.getPath(), false);
        }
    }

    private native long newIniFile();
    public native boolean deleteKey(String str, String str2);
    public native boolean exists(String str);
    public native void finalize();
    public native int getInt(String str, String str2, int i);
    public native Section getOrCreateSection(String str);
    public native String getString(String str, String str2, String str3);
    public native boolean load(String str, boolean z);
    public native boolean save(String str);
    public native void setBoolean(String str, String str2, boolean z);
    public native void setInt(String str, String str2, int i);
    public native void setString(String str, String str2, String str3);
}
