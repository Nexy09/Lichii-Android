// SPDX-FileCopyrightText: Copyright 2026 Lichii Android Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.activities

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.BundleCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.dolphinemu.dolphinemu.NativeLibrary
import org.yuzu.yuzu_emu.dolphin.DolphinEngineManager
import org.yuzu.yuzu_emu.dolphin.DolphinOverlayView
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.utils.Log
import java.lang.ref.WeakReference
import kotlin.math.abs

class DolphinEmulationActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var surfaceView: SurfaceView
    private lateinit var overlayView: DolphinOverlayView

    private var launchPath: String = ""
    private var gameTitle: String = "GameCube / Wii"
    private var isWii: Boolean = false
    private var hasEmulationStarted: Boolean = false
    private var isGamePaused: Boolean = false

    companion object {
        const val EXTRA_SELECTED_GAME = "SelectedGame"
        const val EXTRA_LAUNCH_PATH = "LaunchPath"
        const val EXTRA_IS_WII = "IsWii"

        fun launch(context: Context, game: Game) {
            val intent = Intent(context, DolphinEmulationActivity::class.java).apply {
                putExtra(EXTRA_SELECTED_GAME, game)
                putExtra(EXTRA_LAUNCH_PATH, game.path)
                putExtra(EXTRA_IS_WII, game.isWii)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setFullscreenImmersive()

        val selectedGame = intent.extras?.let {
            BundleCompat.getParcelable(it, EXTRA_SELECTED_GAME, Game::class.java)
        }
        val rawPath = selectedGame?.path ?: intent.getStringExtra(EXTRA_LAUNCH_PATH).orEmpty()
        isWii = selectedGame?.isWii ?: intent.getBooleanExtra(EXTRA_IS_WII, false)
        gameTitle = selectedGame?.title ?: (if (isWii) "Nintendo Wii" else "Nintendo GameCube")

        if (!DolphinEngineManager.ensureInitialized(this)) {
            Toast.makeText(this, "Erreur d'initialisation du moteur Dolphin", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        NativeLibrary.sEmulationActivity = WeakReference(this)
        launchPath = DolphinEngineManager.toDolphinLaunchPath(rawPath)
        Log.info("[DolphinEmulationActivity] Starting in-app Dolphin emulation for '$gameTitle' ($launchPath)")

        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            keepScreenOn = true
        }

        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)

        overlayView = DolphinOverlayView(
            context = this,
            isWiiMode = isWii,
            onOpenQuickMenu = { showQuickMenu() }
        )

        rootLayout.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        rootLayout.addView(
            overlayView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(rootLayout)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    showQuickMenu()
                }
            }
        )
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.info("[DolphinEmulationActivity] Surface created.")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.info("[DolphinEmulationActivity] Surface changed ($width x $height).")
        NativeLibrary.SurfaceChanged(holder.surface)

        if (!hasEmulationStarted) {
            hasEmulationStarted = true
            NativeLibrary.SetIsBooting()
            Thread({
                try {
                    Log.info("[DolphinEmulationActivity] NativeLibrary.Run starting for: $launchPath")
                    NativeLibrary.Run(arrayOf(launchPath), false)
                    Log.info("[DolphinEmulationActivity] NativeLibrary.Run finished.")
                } catch (t: Throwable) {
                    Log.error("[DolphinEmulationActivity] Native emulation crash: ${t.message}")
                } finally {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            finish()
                        }
                    }
                }
            }, "DolphinNativeEmulation").start()
        } else if (!isGamePaused) {
            NativeLibrary.UnPauseEmulation()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.info("[DolphinEmulationActivity] Surface destroyed.")
        NativeLibrary.SurfaceDestroyed()
    }

    override fun onResume() {
        super.onResume()
        setFullscreenImmersive()
        if (hasEmulationStarted && !isGamePaused && NativeLibrary.HasSurface()) {
            NativeLibrary.UnPauseEmulation()
        }
    }

    override fun onPause() {
        super.onPause()
        if (hasEmulationStarted && NativeLibrary.IsRunningAndUnpaused()) {
            NativeLibrary.PauseEmulation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (hasEmulationStarted) {
            try {
                NativeLibrary.StopEmulation()
            } catch (t: Throwable) {
                Log.warning("[DolphinEmulationActivity] Error stopping emulation: ${t.message}")
            }
        }
        NativeLibrary.sEmulationActivity.clear()
    }

    private fun showQuickMenu() {
        val wasRunning = NativeLibrary.IsRunningAndUnpaused()
        if (wasRunning) {
            NativeLibrary.PauseEmulation()
            isGamePaused = true
        }

        val items = arrayOf(
            "Reprendre la partie",
            "Sauvegarder l'état (Slot 1)",
            "Charger l'état (Slot 1)",
            "Prendre une capture d'écran",
            "Afficher / Masquer les boutons tactiles",
            "Quitter vers Lichii"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(gameTitle)
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> {
                        isGamePaused = false
                        NativeLibrary.UnPauseEmulation()
                        dialog.dismiss()
                    }
                    1 -> {
                        NativeLibrary.SaveState(1, false)
                        Toast.makeText(this, "État sauvegardé (Slot 1)", Toast.LENGTH_SHORT).show()
                        isGamePaused = false
                        NativeLibrary.UnPauseEmulation()
                        dialog.dismiss()
                    }
                    2 -> {
                        NativeLibrary.LoadState(1)
                        Toast.makeText(this, "État chargé (Slot 1)", Toast.LENGTH_SHORT).show()
                        isGamePaused = false
                        NativeLibrary.UnPauseEmulation()
                        dialog.dismiss()
                    }
                    3 -> {
                        NativeLibrary.SaveScreenShot()
                        Toast.makeText(this, "Capture d'écran enregistrée", Toast.LENGTH_SHORT).show()
                        isGamePaused = false
                        NativeLibrary.UnPauseEmulation()
                        dialog.dismiss()
                    }
                    4 -> {
                        overlayView.controlsVisible = !overlayView.controlsVisible
                        val state = if (overlayView.controlsVisible) "affichés" else "masqués"
                        Toast.makeText(this, "Contrôles tactiles $state", Toast.LENGTH_SHORT).show()
                        isGamePaused = false
                        NativeLibrary.UnPauseEmulation()
                        dialog.dismiss()
                    }
                    5 -> {
                        dialog.dismiss()
                        finish()
                    }
                }
            }
            .setOnCancelListener {
                isGamePaused = false
                NativeLibrary.UnPauseEmulation()
            }
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val source = event.source
        val isGamepad = (source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
            (source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) ||
            (source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD)

        if (isGamepad && event.keyCode != KeyEvent.KEYCODE_BACK) {
            val action = if (event.action == KeyEvent.ACTION_DOWN) 1 else 0
            val descriptor = event.device?.descriptor ?: "Gamepad"
            NativeLibrary.onGamePadEvent(descriptor, event.keyCode, action)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val device = event.device
            val descriptor = device?.descriptor ?: "Joystick"
            if (device != null) {
                for (motionRange in device.motionRanges) {
                    val axis = motionRange.axis
                    var scaleAxis = event.getAxisValue(axis)
                    if (abs(scaleAxis) <= motionRange.flat) {
                        scaleAxis = 0f
                    }
                    NativeLibrary.onGamePadMoveEvent(descriptor, axis, scaleAxis)
                }
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun setFullscreenImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        WindowInsetsControllerCompat(window, window.decorView).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
