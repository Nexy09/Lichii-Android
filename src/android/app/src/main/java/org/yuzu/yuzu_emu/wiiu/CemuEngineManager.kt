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
            provisionDefaultKeysIfNeeded(cemuRoot)
            sanitizeConflictingUserProfiles(cemuRoot)

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
            // Strict accurate barriers prevent Vulkan synchronization crashes in Twilight Princess HD and complex shaders
            NativeSettings.setAccurateBarriers(true)
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

    private fun provisionDefaultKeysIfNeeded(cemuRoot: File) {
        try {
            val keysFile = File(cemuRoot, "keys.txt")
            val defaultKeysHeader = """
                # Wii U Common Keys and Game Disc Keys for Cemu
                # Provisioned by Lichii Android for seamless title loading

            """.trimIndent()

            val essentialKeys = listOf(
                "d7b00402659ba2abd2cb0db27fa2b656 # Wii U Common Key",
                // The Legend of Zelda: Twilight Princess HD
                "291a27e70ea39c438ba6653ea95fae2f # The Legend of Zelda: Twilight Princess HD (USA)",
                "48b704d9c727581561bf7000bfdfbc74 # The Legend of Zelda: Twilight Princess HD (EUR)",
                "2e464c8c7cbe22b513dcda256e632b70 # The Legend of Zelda: Twilight Princess HD (JPN)",
                // The Legend of Zelda: The Wind Waker HD
                "2160b73489feea3aa728e5e8e8fb4504 # The Legend of Zelda: The Wind Waker HD (USA)",
                "d7da9ff605e557b447bd7a1b46a2a0ec # The Legend of Zelda: The Wind Waker HD (EUR)",
                "a9d32b5b1574a49c9fbaf529a65f4ffc # The Legend of Zelda: The Wind Waker HD (JPN)",
                // The Legend of Zelda: Breath of the Wild
                "2ddb4b89e35955078508734b22c92e92 # The Legend of Zelda: Breath of the Wild (USA)",
                "1024345f7823f666f22ed1c73a466d73 # The Legend of Zelda: Breath of the Wild (EUR)",
                "943eb4399e095a5563914a1c5d9bf31a # The Legend of Zelda: Breath of the Wild (JPN)",
                // Mario Kart 8
                "c51c52b4759695be246d89280d5b94f6 # Mario Kart 8 (USA)",
                "a15ee4fd7e70783309ea8fb5625bf5a4 # Mario Kart 8 (EUR)",
                "541b9889519b27d363cd21604b97c67a # Mario Kart 8 (JPN)",
                // Super Smash Bros. for Wii U
                "a8848a58a74e2cf864cefa371e626786 # Super Smash Bros. for Wii U (USA)",
                "7a0c8bb5444ca93297a7e804f997cb24 # Super Smash Bros. for Wii U (EUR)",
                "da606ba328b9d4c7a6e11894d76b1f23 # Super Smash Bros. for Wii U (JPN)",
                // Super Mario 3D World
                "ef6b8cf9ab860d5dd85a0656a8fb914e # Super Mario 3D World (USA)",
                "e1cbf72960f5451a2d6ccb2326efc6df # Super Mario 3D World (EUR)",
                "c07ee9a239b56f2a74c7c80525df8f5f # Super Mario 3D World (JPN)",
                // Donkey Kong Country: Tropical Freeze
                "5e927cb0f61f77d33fc6c39735492d3f # Donkey Kong Country: Tropical Freeze (USA)",
                "f685f0ef3045fbc5b3762ae10fc3d7b9 # Donkey Kong Country: Tropical Freeze (EUR)",
                // Xenoblade Chronicles X
                "f0b6e927f87a3891d4e68e0d4c9fb605 # Xenoblade Chronicles X (USA)",
                "487c53d100780282bc7fbdb2ec799980 # Xenoblade Chronicles X (EUR)"
            )

            if (!keysFile.exists()) {
                keysFile.writeText(defaultKeysHeader + essentialKeys.joinToString("\n") + "\n")
                Log.info("[CemuEngineManager] Initialized default keys.txt with Wii U common and disc keys.")
            } else {
                val existingText = keysFile.readText()
                val keysToAppend = essentialKeys.filter { entry ->
                    val hexKey = entry.substringBefore('#').trim()
                    !existingText.contains(hexKey, ignoreCase = true)
                }
                if (keysToAppend.isNotEmpty()) {
                    keysFile.appendText("\n" + keysToAppend.joinToString("\n") + "\n")
                    Log.info("[CemuEngineManager] Appended ${keysToAppend.size} missing disc keys to existing keys.txt.")
                }
            }
        } catch (t: Throwable) {
            Log.warning("[CemuEngineManager] Failed to provision default keys: ${t.message}")
        }
    }

    private fun sanitizeConflictingUserProfiles(cemuRoot: File) {
        try {
            val userGameProfilesDir = File(cemuRoot, "gameProfiles")
            if (!userGameProfilesDir.isDirectory) return

            val twilightPrincessIds = listOf(
                "000500001019e500",
                "0005000e1019e500",
                "000500001019e600",
                "0005000e1019e600",
                "000500001019c800",
                "0005000e1019c800"
            )

            for (id in twilightPrincessIds) {
                val profileFile = File(userGameProfilesDir, "$id.ini")
                if (profileFile.exists()) {
                    val content = profileFile.readText()
                    if (content.contains("MulticoreRecompiler", ignoreCase = true) ||
                        content.contains("threadQuantum = 100000")
                    ) {
                        profileFile.delete()
                        Log.info("[CemuEngineManager] Removed obsolete conflicting user profile for Twilight Princess ($id.ini).")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.warning("[CemuEngineManager] Failed to sanitize user game profiles: ${t.message}")
        }
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
