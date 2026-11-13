// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;
import androidx.annotation.Keep;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.concurrent.Semaphore;
import org.dolphinemu.dolphinemu.utils.CompressCallback;
import org.dolphinemu.dolphinemu.utils.Rumble;

public final class NativeLibrary {
    private static final String TAG = "DolphinNativeLibrary";

    public static final Semaphore sAlertMessageSemaphore = new Semaphore(0);
    public static boolean sIsShowingAlertMessage = false;
    public static WeakReference<Activity> sEmulationActivity = new WeakReference<>(null);

    // Native methods
    public static native void ChangeDisc(String str);
    public static native boolean CheckIntegrity(String str, String str2);
    public static native boolean ConvertDiscImage(String str, String str2, int i, int i2, int i3, int i4, int i5, boolean z, CompressCallback compressCallback);
    public static native int DefaultCPUCore();
    public static native String FormatSize(long j, int i);
    public static native String GetDefaultGraphicsBackendName();
    public static native float GetGameAspectRatio();
    public static native String GetGitRevision();
    public static native double GetInputRadiusAtAngle(int i, int i2, double d);
    public static native LinkedHashMap<String, String> GetLogTypeNames();
    public static native int GetMaxLogLevel();
    public static native long GetUnixTimeOfStateSlot(int i);
    public static native String GetUserDirectory();
    public static native String GetVersionString();
    public static native boolean HasSurface();
    public static native void Initialize();
    public static native boolean IsGameMetadataValid();
    public static native boolean IsRunning();
    public static native boolean IsRunningAndStarted();
    public static native boolean IsRunningAndUnpaused();
    public static native void LoadState(int i);
    public static native void LoadStateAs(String str);
    public static native void PauseEmulation();
    public static native void RefreshWiimotes();
    public static native void ReloadConfig();
    public static native void ReloadLoggerConfig();
    public static native void ReloadWiimoteConfig();
    public static native void ReportStartToAnalytics();
    public static native void Run(String[] strArr, boolean z);
    public static native void Run(String[] strArr, boolean z, String str, boolean z2);
    public static native void RunSystemMenu();
    public static native void SaveScreenShot();
    public static native void SaveState(int i, boolean z);
    public static native void SaveStateAs(String str, boolean z);
    public static native void SetIsBooting();
    public static native void SetMotionSensorsEnabled(boolean z, boolean z2);
    public static native void SetObscuredPixelsLeft(int i);
    public static native void SetObscuredPixelsTop(int i);
    public static native void SetUserDirectory(String str);
    public static native void StopEmulation();
    public static native void SurfaceChanged(Surface surface);
    public static native void SurfaceDestroyed();
    public static native void UnPauseEmulation();
    public static native void UpdateGCAdapterScanThread();
    public static native void WriteJitBlockLogDump();
    public static native void eglBindAPI(int i);
    public static native boolean getHotkeyState(int i);
    public static native boolean onGamePadEvent(String str, int i, int i2);
    public static native void onGamePadMoveEvent(String str, int i, float f);
    public static native boolean onHotkeyEvent(int i, boolean z);
    public static native void setNativeTheme(String str);

    private static native String GetCurrentGameIDUnchecked();
    private static native String GetCurrentTitleDescriptionUnchecked();
    private static native boolean IsEmulatingWiiUnchecked();

    public static String GetCurrentGameID() {
        if (IsGameMetadataValid()) {
            return GetCurrentGameIDUnchecked();
        }
        return "";
    }

    public static String GetCurrentTitleDescription() {
        if (IsGameMetadataValid()) {
            return GetCurrentTitleDescriptionUnchecked();
        }
        return "";
    }

    public static boolean IsEmulatingWii() {
        if (IsGameMetadataValid()) {
            return IsEmulatingWiiUnchecked();
        }
        return false;
    }

    // Callbacks from native code
    @Keep
    public static boolean displayAlertMsg(final String caption, final String text, final boolean yesNo, final boolean warning, boolean z3) {
        Log.i(TAG, "[displayAlertMsg] " + caption + ": " + text);
        return true;
    }

    @Keep
    public static void displayToastMsg(final String str, final boolean isLong) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Context context = DolphinApplication.getAppContext();
                Toast.makeText(context, str, isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Log.w(TAG, "Cannot display toast: " + t.getMessage());
            }
        });
    }

    @Keep
    public static void finishEmulationActivity() {
        Activity activity = sEmulationActivity.get();
        if (activity != null) {
            activity.finish();
        }
    }

    @Keep
    public static void rumble(int pad, double strength) {
        Rumble.doRumble(Rumble.phoneVibrator);
    }

    @Keep
    public static void onTitleChanged() {
        Log.i(TAG, "[onTitleChanged] Title: " + GetCurrentTitleDescription());
    }

    @Keep
    public static float getRenderSurfaceScale() {
        Activity activity = sEmulationActivity.get();
        if (activity != null) {
            DisplayMetrics metrics = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
            return metrics.scaledDensity;
        }
        return 1.0f;
    }

    @Keep
    public static void updateTouchPointer() {
        // Callback to update on-screen Wii IR touch pointer
    }
}
