// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu

import android.app.Application
import android.content.Context

class DolphinApplication : Application() {
    companion object {
        @JvmField
        var application: Application? = null

        @JvmStatic
        fun getAppContext(): Context {
            return application?.applicationContext
                ?: throw IllegalStateException("DolphinApplication has not been initialized.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        application = this
    }
}
