// SPDX-FileCopyrightText: Copyright 2026 Lichii Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.updater

import android.app.job.JobParameters
import android.app.job.JobService

class UpdateCheckJobService : JobService() {
    @Volatile
    private var workerThread: Thread? = null

    override fun onStartJob(params: JobParameters?): Boolean {
        if (!AutoUpdateManager.shouldRunPeriodicCheck(applicationContext)) {
            return false
        }

        workerThread = Thread {
            try {
                val release = AutoUpdateManager.checkForUpdateBlocking(applicationContext)
                if (release != null) {
                    AutoUpdateManager.postUpdateNotification(applicationContext, release)
                }
            } catch (_: Throwable) {
            } finally {
                jobFinished(params, false)
            }
        }.also { it.start() }

        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        workerThread?.interrupt()
        workerThread = null
        return true
    }
}
