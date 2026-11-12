// SPDX-FileCopyrightText: Copyright 2026 Lichii Android Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.BundleCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.yuzu.yuzu_emu.dolphin.DolphinGameHelper
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.utils.Log

class DolphinEmulationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED_GAME = "SelectedGame"

        fun launch(context: Context, game: Game) {
            val intent = Intent(context, DolphinEmulationActivity::class.java).apply {
                putExtra(EXTRA_SELECTED_GAME, game)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selectedGame = intent.extras?.let {
            BundleCompat.getParcelable(it, EXTRA_SELECTED_GAME, Game::class.java)
        }

        if (selectedGame == null) {
            Toast.makeText(this, "Erreur : Titre introuvable", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val consoleName = if (selectedGame.isGameCube) "Nintendo GameCube" else "Nintendo Wii"
        Log.info("[DolphinEmulationActivity] Launching $consoleName game '${selectedGame.title}' at ${selectedGame.path}")

        val dolphinPkg = DolphinGameHelper.getInstalledDolphinPackage(this)
        if (dolphinPkg != null) {
            // Dolphin is installed, launch directly!
            try {
                val launchIntent = DolphinGameHelper.buildDolphinLaunchIntent(this, selectedGame)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                    finish()
                    return
                }
            } catch (t: Throwable) {
                Log.error("[DolphinEmulationActivity] Failed to launch with Dolphin package '$dolphinPkg': ${t.message}")
            }
        }

        // Dolphin is not installed yet or launch failed -> show guided launcher dialog
        showDolphinSetupDialog(selectedGame, consoleName)
    }

    private fun showDolphinSetupDialog(game: Game, consoleName: String) {
        val items = arrayOf(
            "Télécharger Dolphin Android officiel (ARM64)",
            "Ouvrir avec une autre application compatible",
            "Voir les optimisations recommandées (Exynos 2200 / RDNA 2)",
            "Annuler"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("$consoleName • ${game.title}")
            .setMessage("Ce jeu requiert le moteur Dolphin (GameCube & Wii). Installez Dolphin Android pour lancer le jeu directement depuis Lichii en 1 clic.")
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> {
                        val browserIntent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://dolphin-emu.org/download/")
                        )
                        startActivity(browserIntent)
                        finish()
                    }
                    1 -> {
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(Uri.parse(game.path), "application/octet-stream")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(viewIntent, "Ouvrir avec..."))
                        finish()
                    }
                    2 -> {
                        showOptimizationsDialog(game, consoleName)
                    }
                    3 -> {
                        dialog.dismiss()
                        finish()
                    }
                }
            }
            .setOnCancelListener {
                finish()
            }
            .show()
    }

    private fun showOptimizationsDialog(game: Game, consoleName: String) {
        val message = """
            Optimisations recommandées pour Samsung Galaxy S22 Ultra (Exynos 2200 / AMD Xclipse 920 RDNA 2) :

            1. Backend Vidéo : Vulkan
            2. Moteur CPU : Émulation double cœur (Dual Core) activée
            3. Compilation des Shaders : Asynchrone (Spécialisée ou UberShaders Hybride)
            4. Résolution Interne : 2x (720p) ou 3x (1080p) avec EFB Scaled Copy
            5. Calcul de profondeur rapide (Fast Depth) : Activé
            6. Utilisez l'APK 'lichii-android-arm64-antutu.apk' pour empêcher le bridage thermique Samsung GOS.
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("Optimisations $consoleName")
            .setMessage(message)
            .setPositiveButton("Compris") { _, _ ->
                finish()
            }
            .show()
    }
}
