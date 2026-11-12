// SPDX-FileCopyrightText: Copyright 2026 Lichii Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.ui.main.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object AutoUpdateManager {
    const val INSTALLED_BUILD_NUMBER = 130
    const val INSTALLED_BUILD_TAG = "build-160"
    const val UPSTREAM_REPO = "zortos293/Lichii"

    const val PREF_AUTO_UPDATE_ENABLED = "lichii_auto_update_enabled"
    const val PREF_AUTO_DOWNLOAD_INSTALL = "lichii_auto_download_install"
    const val PREF_WIFI_AND_BATTERY_SAVER = "lichii_wifi_and_battery_saver"
    const val PREF_UPDATE_INTERVAL_HOURS = "lichii_update_interval_hours"
    const val PREF_LAST_CHECK_TIME_MS = "lichii_last_update_check_ms"
    const val PREF_CUSTOM_UPDATE_REPO = "lichii_custom_update_repo"

    private const val JOB_ID_PERIODIC_UPDATE = 9130
    private const val NOTIFICATION_CHANNEL_ID = "lichii_updates_channel"
    private const val NOTIFICATION_ID_UPDATE = 9131

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun isAutoUpdateEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(PREF_AUTO_UPDATE_ENABLED, true)
    }

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putBoolean(PREF_AUTO_UPDATE_ENABLED, enabled)
            .apply()
        schedulePeriodicJob(context)
    }

    fun isAutoDownloadInstallEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(PREF_AUTO_DOWNLOAD_INSTALL, true)
    }

    fun isWifiAndBatterySaverEnabled(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(PREF_WIFI_AND_BATTERY_SAVER, true)
    }

    fun getCheckIntervalHours(context: Context): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getInt(PREF_UPDATE_INTERVAL_HOURS, 24)
    }

    fun getCustomRepo(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString(PREF_CUSTOM_UPDATE_REPO, "")?.trim() ?: ""
    }

    /**
     * Checks whether a background or startup update check is allowed right now without
     * draining battery or impacting gaming performance.
     */
    fun shouldRunPeriodicCheck(context: Context): Boolean {
        if (!isAutoUpdateEnabled(context)) return false
        val intervalHours = getCheckIntervalHours(context)
        if (intervalHours <= 0) return false

        // Never check while a game is running
        try {
            if (NativeLibrary.isRunning()) return false
        } catch (_: Throwable) {
        }

        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val lastCheckMs = prefs.getLong(PREF_LAST_CHECK_TIME_MS, 0L)
        val nowMs = System.currentTimeMillis()
        val intervalMs = intervalHours * 3600_000L
        if (nowMs - lastCheckMs < intervalMs) {
            return false
        }

        if (!passesPowerAndNetworkGuards(context, isWifiAndBatterySaverEnabled(context))) {
            return false
        }

        return true
    }

    fun passesPowerAndNetworkGuards(context: Context, requireWifiAndGoodBattery: Boolean): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return false
        }

        if (requireWifiAndGoodBattery) {
            val isUnmeteredOrWifi =
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            if (!isUnmeteredOrWifi) {
                return false
            }

            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            if (bm != null) {
                val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val isCharging = bm.isCharging
                if (batteryPct in 1..20 && !isCharging) {
                    return false
                }
            }
        }
        return true
    }

    fun recordCheckTimestamp(context: Context) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putLong(PREF_LAST_CHECK_TIME_MS, System.currentTimeMillis())
            .apply()
    }

    /**
     * Schedules (or cancels) the OS-level periodic JobScheduler task so Android only wakes the
     * updater once every N hours when on Wi-Fi and when the battery is not low.
     */
    fun schedulePeriodicJob(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler
            ?: return

        val enabled = isAutoUpdateEnabled(context)
        val intervalHours = getCheckIntervalHours(context)
        if (!enabled || intervalHours <= 0) {
            scheduler.cancel(JOB_ID_PERIODIC_UPDATE)
            return
        }

        val wifiAndBattery = isWifiAndBatterySaverEnabled(context)
        val component = ComponentName(context, UpdateCheckJobService::class.java)
        val intervalMs = (intervalHours.coerceAtLeast(1) * 3600_000L)

        val builder = JobInfo.Builder(JOB_ID_PERIODIC_UPDATE, component)
            .setPeriodic(intervalMs)
            .setRequiresBatteryNotLow(wifiAndBattery)
            .setRequiredNetworkType(
                if (wifiAndBattery) {
                    JobInfo.NETWORK_TYPE_UNMETERED
                } else {
                    JobInfo.NETWORK_TYPE_ANY
                }
            )

        scheduler.schedule(builder.build())
    }

    private fun parseBuildNumber(tag: String): Int? {
        val normalized = tag.trim().lowercase(Locale.ROOT)
        if (!normalized.startsWith("build-")) return null
        return normalized.removePrefix("build-").takeWhile { it.isDigit() }.toIntOrNull()
    }

    /**
     * Checks GitHub Releases (first the custom CI/CD fork repo if configured, then upstream
     * zortos293/Lichii) and returns the newest release > INSTALLED_BUILD_NUMBER.
     */
    fun checkForUpdateBlocking(context: Context): NativeLibrary.UpdateResult? {
        recordCheckTimestamp(context)
        val customRepo = getCustomRepo(context)
        val isAntutuFlavor = context.packageName == "com.antutu.ABenchMark"

        val reposToCheck = buildList {
            if (customRepo.contains("/") && !customRepo.equals(UPSTREAM_REPO, ignoreCase = true)) {
                add(customRepo)
            }
            add(UPSTREAM_REPO)
        }

        var bestCandidateWithApk: Pair<Int, NativeLibrary.UpdateResult>? = null
        var bestCandidateAny: Pair<Int, NativeLibrary.UpdateResult>? = null

        for (repo in reposToCheck) {
            val candidate = fetchNewestReleaseFromRepo(repo, isAntutuFlavor) ?: continue
            val buildNum = candidate.first
            val result = candidate.second

            if (result.assets.isNotEmpty()) {
                if (bestCandidateWithApk == null || buildNum > bestCandidateWithApk!!.first) {
                    bestCandidateWithApk = candidate
                }
            }
            if (bestCandidateAny == null || buildNum > bestCandidateAny!!.first) {
                bestCandidateAny = candidate
            }
        }

        if (bestCandidateWithApk != null) {
            return bestCandidateWithApk!!.second
        }

        // Also fallback to native C++ update checker if available
        try {
            if (NativeLibrary.isUpdateCheckerEnabled()) {
                val nativeResult = NativeLibrary.checkForUpdate()
                if (nativeResult != null && nativeResult.assets.isNotEmpty()) {
                    return nativeResult
                }
            }
        } catch (_: Throwable) {
        }

        return bestCandidateAny?.second
    }

    private fun fetchNewestReleaseFromRepo(
        repo: String,
        preferAntutuApk: Boolean
    ): Pair<Int, NativeLibrary.UpdateResult>? {
        val apiUrl = "https://api.github.com/repos/$repo/releases?per_page=15"
        val request = Request.Builder()
            .url(apiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Lichii-Android-AutoUpdater/1.0")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyStr = response.body?.string() ?: return null
                val jsonArray = JSONArray(bodyStr)

                var newestBuild = INSTALLED_BUILD_NUMBER
                var selectedResult: NativeLibrary.UpdateResult? = null

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    if (obj.optBoolean("draft", false)) continue

                    val tag = obj.optString("tag_name", "")
                    val buildNum = parseBuildNumber(tag) ?: continue
                    if (buildNum <= newestBuild) continue

                    val title = obj.optString("name", tag).ifEmpty { tag }
                    val body = obj.optString("body", "")
                    val htmlUrl = obj.optString(
                        "html_url",
                        "https://github.com/$repo/releases/tag/$tag"
                    )

                    val assetsJson = obj.optJSONArray("assets")
                    val apkUrls = mutableListOf<String>()
                    if (assetsJson != null) {
                        for (j in 0 until assetsJson.length()) {
                            val assetObj = assetsJson.optJSONObject(j) ?: continue
                            val downloadUrl = assetObj.optString("browser_download_url", "")
                            if (downloadUrl.endsWith(".apk", ignoreCase = true)) {
                                apkUrls.add(downloadUrl)
                            }
                        }
                    }

                    // Sort APK URLs so the matching flavor (antutu vs mainline) comes first
                    apkUrls.sortByDescending { url ->
                        val lower = url.lowercase(Locale.ROOT)
                        if (preferAntutuApk) {
                            lower.contains("antutu")
                        } else {
                            !lower.contains("antutu")
                        }
                    }

                    val res = NativeLibrary.UpdateResult(
                        tag = tag,
                        title = "$title ($repo)",
                        body = body,
                        url = htmlUrl,
                        assets = apkUrls
                    )

                    newestBuild = buildNum
                    selectedResult = res
                }

                if (selectedResult != null) {
                    Pair(newestBuild, selectedResult)
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun postUpdateNotification(context: Context, release: NativeLibrary.UpdateResult) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Mises à jour Lichii",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications lorsqu'une nouvelle version optimisée de Lichii est disponible"
            }
            nm.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("trigger_auto_update_check", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_yuzu)
            .setContentTitle(context.getString(R.string.update_available))
            .setContentText("Nouvelle version disponible : ${release.title}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            nm.notify(NOTIFICATION_ID_UPDATE, notification)
        } catch (_: SecurityException) {
        }
    }

    fun showAutoUpdateSettingsDialog(
        activity: AppCompatActivity,
        onManualCheckRequested: () -> Unit
    ) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val lastCheckMs = prefs.getLong(PREF_LAST_CHECK_TIME_MS, 0L)
        val lastCheckFormatted = if (lastCheckMs == 0L) {
            "Jamais"
        } else {
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(lastCheckMs))
        }

        val intervalHours = getCheckIntervalHours(activity)
        val intervalLabel = when (intervalHours) {
            12 -> "Toutes les 12 heures"
            24 -> "Toutes les 24 heures (Recommandé)"
            72 -> "Tous les 3 jours"
            168 -> "1 fois par semaine"
            else -> "Manuel uniquement (Désactivé)"
        }

        val customRepo = getCustomRepo(activity)
        val repoDisplay = if (customRepo.isEmpty()) {
            "$UPSTREAM_REPO (par défaut)"
        } else {
            "$customRepo + $UPSTREAM_REPO"
        }

        val dp = (activity.resources.displayMetrics.density * 20).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp, dp / 2, dp, 0)
        }

        val switchAutoCheck = SwitchMaterial(activity).apply {
            text = "Vérification périodique en arrière-plan"
            isChecked = isAutoUpdateEnabled(activity)
        }
        val switchAutoInstall = SwitchMaterial(activity).apply {
            text = "Télécharger et lancer la MAJ automatiquement"
            isChecked = isAutoDownloadInstallEnabled(activity)
        }
        val switchBatterySaver = SwitchMaterial(activity).apply {
            text = "Protection Batterie & Wi-Fi uniquement (> 20%, hors jeu)"
            isChecked = isWifiAndBatterySaverEnabled(activity)
        }

        val repoInput = EditText(activity).apply {
            hint = "Dépôt GitHub Actions (ex: pseudo/Lichii)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(customRepo)
        }

        container.addView(switchAutoCheck)
        container.addView(switchAutoInstall)
        container.addView(switchBatterySaver)
        container.addView(repoInput)

        val infoMessage = buildString {
            appendLine("• Version installée : Lichii $INSTALLED_BUILD_TAG (nx2 Optimisé Exynos)")
            appendLine("• Dernière vérification : $lastCheckFormatted")
            appendLine("• Fréquence actuelle : $intervalLabel")
            append("• Source : $repoDisplay")
        }

        MaterialAlertDialogBuilder(activity)
            .setTitle("Mises à jour automatiques (Lichii)")
            .setMessage(infoMessage)
            .setView(container)
            .setPositiveButton("Vérifier maintenant") { _, _ ->
                prefs.edit()
                    .putBoolean(PREF_AUTO_UPDATE_ENABLED, switchAutoCheck.isChecked)
                    .putBoolean(PREF_AUTO_DOWNLOAD_INSTALL, switchAutoInstall.isChecked)
                    .putBoolean(PREF_WIFI_AND_BATTERY_SAVER, switchBatterySaver.isChecked)
                    .putString(PREF_CUSTOM_UPDATE_REPO, repoInput.text?.toString()?.trim() ?: "")
                    .apply()
                schedulePeriodicJob(activity)
                Toast.makeText(
                    activity,
                    "Vérification des mises à jour Lichii en cours...",
                    Toast.LENGTH_SHORT
                ).show()
                onManualCheckRequested()
            }
            .setNeutralButton("Fréquence ($intervalHours h)") { _, _ ->
                prefs.edit()
                    .putBoolean(PREF_AUTO_UPDATE_ENABLED, switchAutoCheck.isChecked)
                    .putBoolean(PREF_AUTO_DOWNLOAD_INSTALL, switchAutoInstall.isChecked)
                    .putBoolean(PREF_WIFI_AND_BATTERY_SAVER, switchBatterySaver.isChecked)
                    .putString(PREF_CUSTOM_UPDATE_REPO, repoInput.text?.toString()?.trim() ?: "")
                    .apply()
                showIntervalPickerDialog(activity, onManualCheckRequested)
            }
            .setNegativeButton("Enregistrer") { _, _ ->
                prefs.edit()
                    .putBoolean(PREF_AUTO_UPDATE_ENABLED, switchAutoCheck.isChecked)
                    .putBoolean(PREF_AUTO_DOWNLOAD_INSTALL, switchAutoInstall.isChecked)
                    .putBoolean(PREF_WIFI_AND_BATTERY_SAVER, switchBatterySaver.isChecked)
                    .putString(PREF_CUSTOM_UPDATE_REPO, repoInput.text?.toString()?.trim() ?: "")
                    .apply()
                schedulePeriodicJob(activity)
                Toast.makeText(
                    activity,
                    "Paramètres de mise à jour enregistrés",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
    }

    private fun showIntervalPickerDialog(
        activity: AppCompatActivity,
        onManualCheckRequested: () -> Unit
    ) {
        val options = arrayOf(
            "Toutes les 24 heures (Recommandé — Économie batterie)",
            "Toutes les 12 heures",
            "Tous les 3 jours (72 h)",
            "1 fois par semaine (168 h)",
            "Manuel uniquement (Aucune vérif en arrière-plan)"
        )
        val values = intArrayOf(24, 12, 72, 168, 0)
        val current = getCheckIntervalHours(activity)
        val checkedIdx = values.indexOf(current).let { if (it >= 0) it else 0 }

        MaterialAlertDialogBuilder(activity)
            .setTitle("Fréquence de vérification")
            .setSingleChoiceItems(options, checkedIdx) { dialog, which ->
                val chosenHours = values[which]
                PreferenceManager.getDefaultSharedPreferences(activity)
                    .edit()
                    .putInt(PREF_UPDATE_INTERVAL_HOURS, chosenHours)
                    .putBoolean(PREF_AUTO_UPDATE_ENABLED, chosenHours > 0)
                    .apply()
                schedulePeriodicJob(activity)
                dialog.dismiss()
                showAutoUpdateSettingsDialog(activity, onManualCheckRequested)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
