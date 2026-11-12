// SPDX-FileCopyrightText: Copyright 2026 Lichii Android Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.wiiu

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
import kotlin.math.abs

object WiiUGameHelper {
    val WIIU_EXTENSIONS = setOf("wua", "wud", "wux", "rpx", "wuhb")

    fun isWiiUExtension(extension: String): Boolean {
        return WIIU_EXTENSIONS.contains(extension.lowercase())
    }

    fun isWiiUPath(path: String): Boolean {
        val ext = FileUtil.getExtension(Uri.parse(path)).lowercase()
        return isWiiUExtension(ext)
    }

    fun createWiiUGame(
        uri: Uri,
        filePath: String,
        extension: String,
        addedToLibrary: Boolean
    ): Game {
        val rawName = FileUtil.getFilename(uri)
        val cleanTitle = cleanGameTitle(rawName, extension)
        val stableId = "WIIU_${abs(filePath.hashCode()).toString(16).uppercase().padStart(8, '0')}"

        val game = Game(
            title = cleanTitle,
            path = filePath,
            programId = stableId,
            developer = "Nintendo Wii U",
            version = "Wii U • Cemu 2.6 (${extension.uppercase()})",
            isHomebrew = extension.equals("wuhb", ignoreCase = true)
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
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "Wii U Game" }
    }

    fun generateWiiUIcon(title: String): Bitmap {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                size.toFloat(),
                size.toFloat(),
                Color.rgb(0, 118, 190),
                Color.rgb(13, 33, 63),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        // Wii U pill badge at top
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 255, 255)
            style = Paint.Style.FILL
        }
        val pillRect = RectF(44f, 28f, 212f, 76f)
        canvas.drawRoundRect(pillRect, 24f, 24f, pillPaint)

        val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 130, 205)
            textSize = 32f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val badgeOffset = (badgeTextPaint.descent() + badgeTextPaint.ascent()) / 2f
        canvas.drawText("Wii U", pillRect.centerX(), pillRect.centerY() - badgeOffset, badgeTextPaint)

        // Game Initials in center
        val words = title.split(" ").filter { it.isNotEmpty() && it[0].isLetterOrDigit() }
        val initials = when {
            words.size >= 2 -> "${words[0][0].uppercaseChar()}${words[1][0].uppercaseChar()}"
            words.size == 1 -> words[0].take(2).uppercase()
            else -> "WU"
        }

        val initialsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 82f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val initOffset = (initialsPaint.descent() + initialsPaint.ascent()) / 2f
        canvas.drawText(initials, size / 2f, 168f - initOffset, initialsPaint)

        return bitmap
    }
}
