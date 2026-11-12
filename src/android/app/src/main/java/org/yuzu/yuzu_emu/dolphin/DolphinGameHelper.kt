// SPDX-FileCopyrightText: Copyright 2026 Lichii Android Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.dolphin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import androidx.preference.PreferenceManager
import org.yuzu.yuzu_emu.YuzuApplication
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.utils.FileUtil
import org.yuzu.yuzu_emu.utils.Log
import java.io.InputStream
import kotlin.math.abs

object DolphinGameHelper {

    val GAMECUBE_EXCLUSIVE_EXTENSIONS = setOf("gcm", "gcz", "ciso")
    val WII_EXCLUSIVE_EXTENSIONS = setOf("wbfs", "wad", "wia")
    val SHARED_EXTENSIONS = setOf("iso", "rvz", "dol", "elf")
    val ALL_DOLPHIN_EXTENSIONS = GAMECUBE_EXCLUSIVE_EXTENSIONS + WII_EXCLUSIVE_EXTENSIONS + SHARED_EXTENSIONS

    private val KNOWN_DOLPHIN_PACKAGES = listOf(
        "org.dolphinemu.dolphinemu",
        "org.dolphinemu.dolphinemu.beta",
        "org.dolphinemu.dolphinemu.dev",
        "org.dolphinemu.mmjr",
        "org.dolphinemu.mmj",
        "org.dolphinemu.handheld"
    )

    fun isDolphinExtension(extension: String): Boolean {
        return ALL_DOLPHIN_EXTENSIONS.contains(extension.lowercase())
    }

    fun isGameCubePath(path: String): Boolean {
        val ext = FileUtil.getExtension(Uri.parse(path)).lowercase()
        if (GAMECUBE_EXCLUSIVE_EXTENSIONS.contains(ext)) return true
        if (WII_EXCLUSIVE_EXTENSIONS.contains(ext)) return false
        if (SHARED_EXTENSIONS.contains(ext)) {
            return detectDiscSystem(path) == DiscSystem.GAMECUBE
        }
        return false
    }

    fun isWiiPath(path: String): Boolean {
        val ext = FileUtil.getExtension(Uri.parse(path)).lowercase()
        if (WII_EXCLUSIVE_EXTENSIONS.contains(ext)) return true
        if (GAMECUBE_EXCLUSIVE_EXTENSIONS.contains(ext)) return false
        if (SHARED_EXTENSIONS.contains(ext)) {
            return detectDiscSystem(path) == DiscSystem.WII
        }
        return false
    }

    enum class DiscSystem {
        GAMECUBE,
        WII,
        UNKNOWN
    }

    fun detectDiscSystem(path: String): DiscSystem {
        val uri = Uri.parse(path)
        val ext = FileUtil.getExtension(uri).lowercase()

        if (GAMECUBE_EXCLUSIVE_EXTENSIONS.contains(ext)) return DiscSystem.GAMECUBE
        if (WII_EXCLUSIVE_EXTENSIONS.contains(ext)) return DiscSystem.WII

        // Inspect disc header bytes (offset 0x00 to 0x24)
        try {
            val context = YuzuApplication.appContext
            val stream: InputStream? = if (uri.scheme == "content") {
                context.contentResolver.openInputStream(uri)
            } else {
                java.io.File(uri.path ?: path).inputStream()
            }

            stream?.use { input ->
                val header = ByteArray(64)
                val read = input.read(header)
                if (read >= 32) {
                    val magic = ((header[0x1C].toLong() and 0xFF) shl 24) or
                        ((header[0x1D].toLong() and 0xFF) shl 16) or
                        ((header[0x1E].toLong() and 0xFF) shl 8) or
                        (header[0x1F].toLong() and 0xFF)

                    // GameCube Magic: 0xC2339F3D
                    if (magic == 0xC2339F3DL) {
                        return DiscSystem.GAMECUBE
                    }
                    // Wii Magic: 0x5D1C9EA3
                    if (magic == 0x5D1C9EA3L) {
                        return DiscSystem.WII
                    }

                    // Check Game ID first char
                    val firstChar = header[0].toInt().toChar()
                    if (firstChar == 'G' || firstChar == 'D' || firstChar == 'P') {
                        return DiscSystem.GAMECUBE
                    }
                    if (firstChar == 'R' || firstChar == 'S' || firstChar == 'W' || firstChar == 'H') {
                        return DiscSystem.WII
                    }
                }
            }
        } catch (t: Throwable) {
            Log.warning("[DolphinGameHelper] Could not inspect disc header for '$path': ${t.message}")
        }

        // Fallback by filename hints
        val nameLower = FileUtil.getFilename(uri).lowercase()
        if (nameLower.contains("gamecube") || nameLower.contains("gcn") || nameLower.contains("(gc)")) {
            return DiscSystem.GAMECUBE
        }
        if (nameLower.contains("wii") || nameLower.contains("(wii)")) {
            return DiscSystem.WII
        }

        return if (ext == "gcm" || ext == "gcz" || ext == "iso") DiscSystem.GAMECUBE else DiscSystem.WII
    }

    fun createDolphinGame(
        uri: Uri,
        filePath: String,
        extension: String,
        addedToLibrary: Boolean
    ): Game {
        val system = detectDiscSystem(filePath)
        val isGc = (system == DiscSystem.GAMECUBE)
        val rawName = FileUtil.getFilename(uri)
        val cleanTitle = cleanGameTitle(rawName, extension)
        val prefix = if (isGc) "GC" else "WII"
        val stableId = "${prefix}_${abs(filePath.hashCode()).toString(16).uppercase().padStart(8, '0')}"
        val developer = if (isGc) "Nintendo GameCube" else "Nintendo Wii"
        val version = if (isGc) "GameCube • Dolphin 2609b (${extension.uppercase()})" else "Wii • Dolphin 2609b (${extension.uppercase()})"

        val game = Game(
            title = cleanTitle,
            path = filePath,
            programId = stableId,
            developer = developer,
            version = version,
            isHomebrew = extension.equals("dol", ignoreCase = true) || extension.equals("elf", ignoreCase = true)
        )

        if (addedToLibrary) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(YuzuApplication.appContext)
            val addedTime = preferences.getLong(game.keyAddedToLibraryTime, 0L)
            if (addedTime == 0L) {
                preferences.edit()
                    .putLong(game.keyAddedToLibraryTime, System.currentTimeMillis())
                    .apply()
            }
        }

        return game
    }

    private fun cleanGameTitle(filename: String, extension: String): String {
        val withoutExt = if (filename.lowercase().endsWith(".${extension.lowercase()}")) {
            filename.dropLast(extension.length + 1)
        } else {
            filename.substringBeforeLast('.')
        }
        return withoutExt
            .replace(Regex("\\[.*?\\]"), "")
            .replace(Regex("\\(.*?\\)"), "")
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "Nintendo Game" }
    }

    fun generateGameCubeIcon(title: String): Bitmap {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // GameCube deep indigo purple gradient
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                size.toFloat(),
                size.toFloat(),
                Color.rgb(107, 72, 185), // Indigo
                Color.rgb(22, 12, 45),   // Deep Dark Purple
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        // GameCube pill badge at top
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 255, 255)
            style = Paint.Style.FILL
        }
        val pillRect = RectF(36f, 26f, 220f, 74f)
        canvas.drawRoundRect(pillRect, 24f, 24f, pillPaint)

        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(107, 72, 185)
            textSize = 28f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val badgeOffset = (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
        canvas.drawText("GameCube", pillRect.centerX(), pillRect.centerY() - badgeOffset, badgeTextPaint)

        // Initials in center
        val words = title.split(" ").filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
        val initials = when {
            words.size >= 2 -> "${words[0][0].uppercaseChar()}${words[1][0].uppercaseChar()}"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> "GC"
        }

        val initialsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 80f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val initOffset = (initialsPaint.descent() + initialsPaint.ascent()) / 2f
        canvas.drawText(initials, size / 2f, 168f - initOffset, initialsPaint)

        return bitmap
    }

    fun generateWiiIcon(title: String): Bitmap {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Wii crisp cyan / blue-white gradient
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                size.toFloat(),
                size.toFloat(),
                Color.rgb(0, 174, 239), // Wii Cyan
                Color.rgb(12, 38, 70),   // Navy Blue
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        // Wii pill badge at top
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 255, 255)
            style = Paint.Style.FILL
        }
        val pillRect = RectF(56f, 26f, 200f, 74f)
        canvas.drawRoundRect(pillRect, 24f, 24f, pillPaint)

        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 160, 230)
            textSize = 34f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val badgeOffset = (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
        canvas.drawText("Wii", pillRect.centerX(), pillRect.centerY() - badgeOffset, badgeTextPaint)

        // Initials in center
        val words = title.split(" ").filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
        val initials = when {
            words.size >= 2 -> "${words[0][0].uppercaseChar()}${words[1][0].uppercaseChar()}"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> "WII"
        }

        val initialsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 80f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val initOffset = (initialsPaint.descent() + initialsPaint.ascent()) / 2f
        canvas.drawText(initials, size / 2f, 168f - initOffset, initialsPaint)

        return bitmap
    }

    fun getInstalledDolphinPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in KNOWN_DOLPHIN_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
            }
        }
        return null
    }

    fun buildDolphinLaunchIntent(context: Context, game: Game): Intent? {
        val installedPackage = getInstalledDolphinPackage(context)
        val uri = Uri.parse(game.path)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (installedPackage != null) {
                setPackage(installedPackage)
            }
            putExtra("AutoStart", true)
        }
        return intent
    }
}
