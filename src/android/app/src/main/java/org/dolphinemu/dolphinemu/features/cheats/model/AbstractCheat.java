// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.cheats.model;

public abstract class AbstractCheat extends ReadOnlyCheat {
    @Override
    public final boolean supportsCode() {
        return true;
    }

    @Override
    public final int trySet(String str, String str2, String str3, String str4) {
        if (!str4.isEmpty() && str4.charAt(0) == '$') {
            int indexOf = str4.indexOf('\n');
            if (indexOf == -1) {
                str = str4.substring(1);
                str4 = "";
            } else {
                str = str4.substring(1, indexOf);
                str4 = str4.substring(indexOf + 1);
            }
        }
        if (str.isEmpty()) {
            return -1;
        }
        int res = trySetImpl(str, str2, str3, str4);
        if (res == 0 && mChangedCallback != null) {
            mChangedCallback.run();
        }
        return res;
    }

    public abstract int trySetImpl(String str, String str2, String str3, String str4);
}
