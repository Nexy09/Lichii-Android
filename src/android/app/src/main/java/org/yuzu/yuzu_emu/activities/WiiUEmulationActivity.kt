// SPDX-FileCopyrightText: Copyright 2026 Lichii Android Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.activities

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeEmulation.PrepareTitleResult
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeInput.VPADButton
import info.cemu.cemu.nativeinterface.NativeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.utils.FileUtil
import org.yuzu.yuzu_emu.utils.Log
import org.yuzu.yuzu_emu.wiiu.CemuEngineManager
import org.yuzu.yuzu_emu.wiiu.WiiUOverlayView
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.system.exitProcess

class WiiUEmulationActivity : AppCompatActivity() {

    private lateinit var surfaceView: SurfaceView
    private lateinit var overlayView: WiiUOverlayView

    private var launchPath: String = ""
    private var gameTitle: String = "Wii U"
    @Volatile
    private var isEmulationLaunching = false
    @Volatile
    private var isEmulationRunning = false
    @Volatile
    private var isRendererInitialized = false
    @Volatile
    private var isGamePaused = false
    private var isPadViewReplaced = false
    private var launchJob: Job? = null

    companion object {
        const val EXTRA_SELECTED_GAME = "SelectedGame"
        const val EXTRA_LAUNCH_PATH = "LaunchPath"

        fun launch(context: Context, game: Game) {
            val intent = Intent(context, WiiUEmulationActivity::class.java).apply {
                putExtra(EXTRA_SELECTED_GAME, game)
                putExtra(EXTRA_LAUNCH_PATH, game.path)
                data = Uri.parse(game.path)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
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
        val rawPath = selectedGame?.path
            ?: intent.getStringExtra(EXTRA_LAUNCH_PATH)
            ?: intent.data?.toString().orEmpty()
        gameTitle = selectedGame?.title
            ?: if (rawPath.isNotEmpty()) {
                FileUtil.getFilename(Uri.parse(rawPath)).substringBeforeLast('.').replace('_', ' ')
            } else "Wii U"

        if (rawPath.isEmpty()) {
            Log.error("[WiiUEmulationActivity] No launch path provided!")
            Toast.makeText(this, "Chemin de jeu introuvable", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (!CemuEngineManager.ensureInitialized(this)) {
            val errorMsg = CemuEngineManager.lastInitError ?: "Erreur native inconnue"
            Log.error("[WiiUEmulationActivity] Failed to initialize Wii U engine: $errorMsg")
            MaterialAlertDialogBuilder(this)
                .setTitle("Erreur du moteur Wii U (Cemu)")
                .setMessage("Impossible d'initialiser le moteur Cemu :\n\n$errorMsg\n\nVérifiez l'espace disponible et l'intégrité de l'application.")
                .setCancelable(false)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
                .show()
            return
        }

        launchPath = CemuEngineManager.toCemuLaunchPath(rawPath)
        Log.info("[WiiUEmulationActivity] Launching Wii U title '$gameTitle' at path: $launchPath")

        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            keepScreenOn = true
        }

        surfaceView = SurfaceView(this)
        overlayView = WiiUOverlayView(
            context = this,
            onTogglePadView = {
                isPadViewReplaced = !isPadViewReplaced
                NativeEmulation.setReplaceTVWithPadView(isPadViewReplaced)
                val modeName = if (isPadViewReplaced) "Vue Wii U GamePad" else "Vue TV"
                Toast.makeText(this, modeName, Toast.LENGTH_SHORT).show()
                isPadViewReplaced
            },
            onOpenQuickMenu = { showQuickMenu() },
            onUnhandledTouch = { action, x, y ->
                val isTV = !isPadViewReplaced
                when (action) {
                    MotionEvent.ACTION_DOWN -> NativeInput.onTouchDown(x, y, isTV)
                    MotionEvent.ACTION_MOVE -> NativeInput.onTouchMove(x, y, isTV)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> NativeInput.onTouchUp(x, y, isTV)
                }
            }
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

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
            }

            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {
                try {
                    NativeEmulation.setSurfaceSize(width, height, true)
                    NativeEmulation.setSurface(holder.surface, true)

                    if (isEmulationRunning) {
                        if (isGamePaused) {
                            NativeEmulation.resumeTitle()
                            isGamePaused = false
                        }
                        return
                    }

                    if (!isEmulationLaunching) {
                        startEmulationWorker()
                    }
                } catch (t: Throwable) {
                    Log.error("[WiiUEmulationActivity] Error in surfaceChanged: ${t.message}")
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (isEmulationRunning) {
                    try {
                        NativeEmulation.pauseTitle()
                        isGamePaused = true
                    } catch (_: Throwable) {
                    }
                }
            }
        })

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    showQuickMenu()
                }
            }
        )
    }

    private fun startEmulationWorker() {
        if (isEmulationLaunching || isEmulationRunning || launchJob != null) return
        isEmulationLaunching = true

        launchJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.info("[WiiUEmulationActivity] Preparing Wii U title '$gameTitle' ($launchPath)...")
                val prepareResult = NativeEmulation.prepareTitle(launchPath)
                if (prepareResult != PrepareTitleResult.SUCCESSFUL) {
                    Log.error("[WiiUEmulationActivity] Failed to prepare title, code: $prepareResult")
                    isEmulationLaunching = false
                    withContext(Dispatchers.Main) {
                        showPrepareErrorDialog(prepareResult)
                    }
                    return@launch
                }

                Log.info("[WiiUEmulationActivity] Ensuring systems initialized...")
                CemuEngineManager.ensureSystemsInitialized()

                Log.info("[WiiUEmulationActivity] Initializing renderer & surface...")
                if (!isRendererInitialized) {
                    NativeEmulation.initializeRenderer()
                    isRendererInitialized = true
                }
                NativeEmulation.initializeSurface(true)

                Log.info("[WiiUEmulationActivity] Launching title on Cemu engine...")
                NativeEmulation.launchTitle()
                isEmulationRunning = true
                isEmulationLaunching = false
                Log.info("[WiiUEmulationActivity] Title successfully running.")
            } catch (t: Throwable) {
                isEmulationLaunching = false
                Log.error("[WiiUEmulationActivity] Native exception during emulation launch: ${t.message}")
                withContext(Dispatchers.Main) {
                    showLaunchErrorDialog(t.message ?: "Erreur native inconnue")
                }
            }
        }
    }

    private fun showPrepareErrorDialog(errorCode: Int) {
        if (isFinishing || isDestroyed) return
        val message = when (errorCode) {
            PrepareTitleResult.ERROR_GAME_BASE_FILES_NOT_FOUND ->
                "Fichiers de base du jeu Wii U introuvables.\n\nVérifiez que le fichier ($gameTitle) est complet (.wua, .rpx, ou dossier avec code/content/meta)."
            PrepareTitleResult.ERROR_NO_DISC_KEY ->
                "Clé de déchiffrement Wii U manquante dans keys.txt pour cette image de disque (.wud/.wux).\n\nPour Twilight Princess HD, utilisez de préférence une archive .wua déchiffrée ou ajoutez la clé du disque dans keys.txt."
            PrepareTitleResult.ERROR_NO_TITLE_TIK ->
                "Fichier title.tik manquant pour ce jeu Wii U."
            else ->
                "Impossible de préparer le titre Wii U (code : $errorCode).\n\nVérifiez la compatibilité et l'intégrité de la ROM Twilight Princess HD."
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Erreur de lancement Wii U")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
    }

    private fun showLaunchErrorDialog(errorMessage: String) {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle("Erreur du moteur Cemu")
            .setMessage("Une erreur s'est produite lors du lancement de $gameTitle :\n\n$errorMessage\n\nSi le problème persiste, videz le cache des shaders ou vérifiez les fichiers de jeu.")
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
    }

    private fun showQuickMenu() {
        if (isEmulationRunning && !isGamePaused) {
            isGamePaused = true
            try {
                NativeEmulation.pauseTitle()
            } catch (_: Throwable) {
            }
        }

        val items = arrayOf(
            if (isPadViewReplaced) "Basculer vers l'écran TV" else "Basculer vers l'écran Wii U GamePad",
            if (overlayView.controlsVisible) "Masquer la manette tactile" else "Afficher la manette tactile",
            if (NativeSettings.isOverlayFPSEnabled()) "Masquer le compteur FPS" else "Afficher le compteur FPS",
            if (NativeSettings.getAccurateBarriers()) "Barrières Vulkan strictes (ON - Stable)" else "Mode Boost RDNA 2 (Barrières OFF)",
            "Vider le cache de shaders (Dépannage)",
            "Reprendre le jeu",
            "Quitter vers Lichii"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Lichii • Wii U ($gameTitle)")
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> {
                        isPadViewReplaced = !isPadViewReplaced
                        NativeEmulation.setReplaceTVWithPadView(isPadViewReplaced)
                        overlayView.isPadViewActive = isPadViewReplaced
                        resumeEmulationIfNeeded()
                    }
                    1 -> {
                        overlayView.controlsVisible = !overlayView.controlsVisible
                        resumeEmulationIfNeeded()
                    }
                    2 -> {
                        NativeSettings.setOverlayFPSEnabled(!NativeSettings.isOverlayFPSEnabled())
                        NativeSettings.saveSettings()
                        resumeEmulationIfNeeded()
                    }
                    3 -> {
                        NativeSettings.setAccurateBarriers(!NativeSettings.getAccurateBarriers())
                        NativeSettings.saveSettings()
                        resumeEmulationIfNeeded()
                    }
                    4 -> {
                        clearShaderCache()
                        resumeEmulationIfNeeded()
                    }
                    5 -> {
                        dialog.dismiss()
                        resumeEmulationIfNeeded()
                    }
                    6 -> {
                        dialog.dismiss()
                        quitWiiUEmulation()
                    }
                }
            }
            .setOnCancelListener {
                resumeEmulationIfNeeded()
            }
            .show()
    }

    private fun clearShaderCache() {
        try {
            val cemuRoot = File(applicationContext.filesDir, "cemu")
            val cacheDir = File(cemuRoot, "shaderCache")
            if (cacheDir.exists()) {
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
            }
            Toast.makeText(this, "Cache des shaders vidé avec succès", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(this, "Impossible de vider le cache: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun resumeEmulationIfNeeded() {
        if (isEmulationRunning && isGamePaused) {
            isGamePaused = false
            try {
                NativeEmulation.resumeTitle()
            } catch (_: Throwable) {
            }
        }
        setFullscreenImmersive()
    }

    private fun quitWiiUEmulation() {
        finishAndRemoveTask()
        exitProcess(0)
    }

    override fun onPause() {
        super.onPause()
        if (isEmulationRunning && !isGamePaused) {
            isGamePaused = true
            try {
                NativeEmulation.pauseTitle()
            } catch (_: Throwable) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setFullscreenImmersive()
        if (isEmulationRunning && isGamePaused) {
            isGamePaused = false
            try {
                NativeEmulation.resumeTitle()
            } catch (_: Throwable) {
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        launchJob?.cancel()
        if (isEmulationRunning) {
            try {
                NativeEmulation.pauseTitle()
            } catch (_: Throwable) {
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val source = event.source
        val isGamepad = (source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD) ||
            (source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) ||
            (source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD)

        if (isGamepad && event.keyCode != KeyEvent.KEYCODE_BACK) {
            val pressed = event.action == KeyEvent.ACTION_DOWN
            val vpadButton = mapKeyCodeToVPAD(event.keyCode)
            if (vpadButton != -1) {
                NativeInput.onOverlayButton(0, vpadButton, pressed)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            val lx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_X))
            val ly = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Y))
            val rx = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_Z))
            val ry = applyDeadzone(event.getAxisValue(MotionEvent.AXIS_RZ))

            NativeInput.onOverlayAxis(0, VPADButton.STICKL_LEFT, max(0f, -lx))
            NativeInput.onOverlayAxis(0, VPADButton.STICKL_RIGHT, max(0f, lx))
            NativeInput.onOverlayAxis(0, VPADButton.STICKL_UP, max(0f, -ly))
            NativeInput.onOverlayAxis(0, VPADButton.STICKL_DOWN, max(0f, ly))

            NativeInput.onOverlayAxis(0, VPADButton.STICKR_LEFT, max(0f, -rx))
            NativeInput.onOverlayAxis(0, VPADButton.STICKR_RIGHT, max(0f, rx))
            NativeInput.onOverlayAxis(0, VPADButton.STICKR_UP, max(0f, -ry))
            NativeInput.onOverlayAxis(0, VPADButton.STICKR_DOWN, max(0f, ry))

            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            NativeInput.onOverlayButton(0, VPADButton.LEFT, hatX < -0.5f)
            NativeInput.onOverlayButton(0, VPADButton.RIGHT, hatX > 0.5f)
            NativeInput.onOverlayButton(0, VPADButton.UP, hatY < -0.5f)
            NativeInput.onOverlayButton(0, VPADButton.DOWN, hatY > 0.5f)

            val lTrigger = max(
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE)
            )
            val rTrigger = max(
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS)
            )
            NativeInput.onOverlayButton(0, VPADButton.ZL, lTrigger > 0.45f)
            NativeInput.onOverlayButton(0, VPADButton.ZR, rTrigger > 0.45f)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun applyDeadzone(value: Float, deadzone: Float = 0.12f): Float {
        return if (abs(value) < deadzone) 0f else value.coerceIn(-1f, 1f)
    }

    private fun mapKeyCodeToVPAD(keyCode: Int): Int {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_B -> VPADButton.A
            KeyEvent.KEYCODE_BUTTON_A -> VPADButton.B
            KeyEvent.KEYCODE_BUTTON_Y -> VPADButton.X
            KeyEvent.KEYCODE_BUTTON_X -> VPADButton.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> VPADButton.L
            KeyEvent.KEYCODE_BUTTON_R1 -> VPADButton.R
            KeyEvent.KEYCODE_BUTTON_L2 -> VPADButton.ZL
            KeyEvent.KEYCODE_BUTTON_R2 -> VPADButton.ZR
            KeyEvent.KEYCODE_BUTTON_START -> VPADButton.PLUS
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_MODE -> VPADButton.MINUS
            KeyEvent.KEYCODE_BUTTON_THUMBL -> VPADButton.STICKL
            KeyEvent.KEYCODE_BUTTON_THUMBR -> VPADButton.STICKR
            KeyEvent.KEYCODE_DPAD_UP -> VPADButton.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> VPADButton.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> VPADButton.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> VPADButton.RIGHT
            else -> -1
        }
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
