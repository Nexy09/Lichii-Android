// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.utils;

import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.SparseArray;

public abstract class Rumble {
    public static final SparseArray<Vibrator> emuVibrators = new SparseArray<>();
    public static Vibrator phoneVibrator;

    public static void doRumble(Vibrator vibrator) {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT < 26) {
            vibrator.vibrate(100L);
        } else {
            vibrator.vibrate(VibrationEffect.createOneShot(100L, VibrationEffect.DEFAULT_AMPLITUDE));
        }
    }
}
