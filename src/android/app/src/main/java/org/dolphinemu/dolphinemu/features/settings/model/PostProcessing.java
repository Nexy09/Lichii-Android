// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.features.settings.model;

public abstract class PostProcessing {
    public static native String[] getAnaglyphShaderList();
    public static native String[] getShaderList();
}
