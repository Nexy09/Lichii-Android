// SPDX-FileCopyrightText: Copyright 2026 Lichii Android Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.wiiu

import android.content.Context
import android.net.Uri
import info.cemu.cemu.nativeinterface.NativeAccount
import info.cemu.cemu.nativeinterface.NativeActiveSettings
import info.cemu.cemu.nativeinterface.NativeEmulation
import info.cemu.cemu.nativeinterface.NativeFiles
import info.cemu.cemu.nativeinterface.NativeGraphicPacks
import info.cemu.cemu.nativeinterface.NativeInput
import info.cemu.cemu.nativeinterface.NativeSettings
import info.cemu.cemu.nativeinterface.NativeSwkbd
import info.cemu.cemu.nativeinterface.toNativePath
import org.yuzu.yuzu_emu.utils.Log
import java.io.File
import java.io.IOException
import java.util.regex.Pattern

object CemuEngineManager {
    @Volatile
    private var initialized = false

    @Volatile
    private var systemsInitialized = false

    @Synchronized
    fun ensureInitialized(context: Context): Boolean {
        if (initialized) {
            return true
        }
        return try {
            System.loadLibrary("CemuAndroid")
            val appContext = context.applicationContext

            NativeFiles.initialize(appContext.contentResolver)

            val cemuRoot = File(appContext.filesDir, "cemu")
            val dataDir = File(cemuRoot, "data")
            cemuRoot.mkdirs()
            dataDir.mkdirs()

            // Ensure MLC01 directory structure exists for Wii U saves & installed titles
            File(cemuRoot, "mlc01/usr/save").mkdirs()
            File(cemuRoot, "mlc01/usr/title").mkdirs()
            File(cemuRoot, "mlc01/sys/title").mkdirs()

            extractDataAssetsIfNeeded(appContext, dataDir)

            val userFolderPath = cemuRoot.absolutePath
            val dataFolderPath = dataDir.absolutePath

            NativeEmulation.setDPI(appContext.resources.displayMetrics.density)
            NativeActiveSettings.initializeActiveSettings(
                userDataPath = userFolderPath,
                configPath = userFolderPath,
                dataPath = dataFolderPath,
                cachePath = userFolderPath
            )
            NativeActiveSettings.setNativeLibDir(appContext.applicationInfo.nativeLibraryDir)
            NativeActiveSettings.setInternalDir(appContext.dataDir.absolutePath)
            NativeEmulation.initializeEmulation()
            NativeSwkbd.initializeSwkbd()
            NativeGraphicPacks.refreshGraphicPacks()

            applyExynos2200OptimalSettings()

            initialized = true
            Log.info("[CemuEngineManager] Wii U engine initialized with Cemu 2.6 + Exynos 2200 RDNA 2 profile.")
            true
        } catch (t: Throwable) {
            Log.error("[CemuEngineManager] Failed to initialize Wii U engine: ${t.message}")
            false
        }
    }

    @Synchronized
    fun ensureSystemsInitialized() {
        if (!systemsInitialized) {
            NativeEmulation.initializeSystems()
            systemsInitialized = true
        }
    }

    /**
     * Applies optimal Vulkan, CPU, Audio, and Controller settings tailored for
     * Samsung Galaxy S22 Ultra (Exynos 2200 / AMD Xclipse 920 RDNA 2).
     */
    private fun applyExynos2200OptimalSettings() {
        try {
            // AMD Xclipse 920 RDNA 2 Vulkan optimizations
            NativeSettings.setAsyncShaderCompile(true)
            NativeSettings.setAccurateBarriers(false)
            NativeSettings.setVsyncMode(NativeSettings.VSyncMode.TRIPLE_BUFFERING)
            NativeSettings.setUpscalingFilter(NativeSettings.ScalingFilter.BICUBIC_FILTER)
            NativeSettings.setDownscalingFilter(NativeSettings.ScalingFilter.BILINEAR_FILTER)
            NativeSettings.setFullscreenScaling(NativeSettings.FullscreenScaling.KEEP_ASPECT_RATIO)

            // Low-latency stereo audio via OpenSL ES / Cubeb
            NativeSettings.setAudioDeviceEnabled(true, true)
            NativeSettings.setAudioDeviceChannels(NativeSettings.AudioChannels.STEREO, true)
            NativeSettings.setAudioDeviceVolume(100, true)
            NativeSettings.setAudioLatency(24)

            // Clean FPS & shader compilation overlay
            NativeSettings.setOverlayPosition(NativeSettings.OverlayScreenPosition.TOP_LEFT)
            NativeSettings.setOverlayTextScalePercentage(100)
            NativeSettings.setOverlayFPSEnabled(true)
            NativeSettings.setNotificationsPosition(NativeSettings.OverlayScreenPosition.TOP_RIGHT)
            NativeSettings.setNotificationShaderCompilerEnabled(true)

            // Ensure a default Wii U account exists so games never block on Mii selection
            val accounts = NativeAccount.getAccounts()
            if (accounts.isEmpty()) {
                val defaultId = NativeAccount.MIN_PERSISTENT_ID.toInt()
                NativeAccount.createAccount(defaultId, "Lichii")
                NativeSettings.setAccountPersistentId(defaultId)
            }

            // Configure Player 1 as Wii U GamePad (VPAD) by default
            if (NativeInput.isControllerDisabled(0)) {
                NativeInput.setControllerType(0, NativeInput.EmulatedControllerType.VPAD)
                NativeInput.saveInputs()
            }

            NativeSettings.saveSettings()
        } catch (t: Throwable) {
            Log.warning("[CemuEngineManager] Non-fatal error applying default settings: ${t.message}")
        }
    }

    private fun extractDataAssetsIfNeeded(context: Context, dataFolder: File) {
        val assets = context.assets
        val hashFileName = "hash.txt"
        val hashFile = File(dataFolder, hashFileName)
        val oldHash = if (hashFile.isFile) hashFile.readText().trim() else "invalid"

        val newHash = try {
            assets.open(hashFileName).use { it.reader().readText().trim() }
        } catch (_: IOException) {
            return
        }

        if (oldHash == newHash) {
            return
        }

        dataFolder.deleteRecursively()
        dataFolder.mkdirs()
        hashFile.writeText(newHash)

        val filePatterns = arrayOf(
            Pattern.compile("gameProfiles/.*"),
            Pattern.compile("resources/.*")
        )

        fun isFileValid(file: String): Boolean {
            return filePatterns.any { pattern -> pattern.matcher(file).matches() }
        }

        fun extractAssetPath(path: String) {
            val children = assets.list(path) ?: return
            if (children.isEmpty()) {
                if (isFileValid(path)) {
                    val outFile = File(dataFolder, path)
                    outFile.parentFile?.mkdirs()
                    assets.open(path).use { input ->
                        outFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                return
            }
            for (child in children) {
                val childPath = if (path.isEmpty()) child else "$path/$child"
                extractAssetPath(childPath)
            }
        }

        extractAssetPath("gameProfiles")
        extractAssetPath("resources")
    }

    fun toCemuLaunchPath(uriString: String): String {
        return try {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "content") {
                uri.toNativePath()
            } else {
                uri.path ?: uriString
            }
        } catch (_: Exception) {
            uriString
        }
    }
}
