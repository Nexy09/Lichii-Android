// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.utils

import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import org.dolphinemu.dolphinemu.NativeLibrary
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object DirectoryInitialization {
    private const val TAG = "DolphinDirectoryInit"

    enum class DirectoryInitializationState {
        NOT_YET_INITIALIZED, INITIALIZING, DOLPHIN_DIRECTORIES_INITIALIZED
    }

    val directoryState = MutableLiveData(DirectoryInitializationState.NOT_YET_INITIALIZED)

    @Volatile
    var areDirectoriesAvailable = false
        private set

    private lateinit var userPath: String

    @JvmStatic
    fun start(context: Context) {
        if (directoryState.value == DirectoryInitializationState.DOLPHIN_DIRECTORIES_INITIALIZED) {
            return
        }
        directoryState.postValue(DirectoryInitializationState.INITIALIZING)
        try {
            init(context)
            directoryState.postValue(DirectoryInitializationState.DOLPHIN_DIRECTORIES_INITIALIZED)
        } catch (t: Throwable) {
            Log.e(TAG, "Initialization failed: ${t.message}", t)
        }
    }

    @Synchronized
    fun init(context: Context) {
        if (areDirectoriesAvailable) return

        val dolphinDir = File(context.filesDir, "dolphin")
        val userDir = File(dolphinDir, "User")
        val sysDir = File(dolphinDir, "Sys")

        userDir.mkdirs()
        sysDir.mkdirs()

        userPath = userDir.absolutePath
        NativeLibrary.SetUserDirectory(userPath)

        extractSysDirectory(context, sysDir)

        try {
            SetSysDirectory(sysDir.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "SetSysDirectory native call: ${t.message}")
        }

        NativeLibrary.Initialize()
        areDirectoriesAvailable = true
    }

    @JvmStatic
    fun getUserDirectoryPath(context: Context?): File? {
        if (context == null) return null
        val dolphinDir = File(context.filesDir, "dolphin")
        val userDir = File(dolphinDir, "User")
        userDir.mkdirs()
        return userDir
    }

    private fun extractSysDirectory(context: Context, targetSysDir: File) {
        val markerFile = File(targetSysDir, ".sys_extracted")
        if (markerFile.exists()) {
            return
        }

        try {
            copyAssetFolder(context, "dolphin/Sys", targetSysDir)
            markerFile.createNewFile()
            Log.i(TAG, "Dolphin Sys assets successfully extracted to ${targetSysDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed extracting Dolphin Sys directory: ${e.message}")
        }
    }

    private fun copyAssetFolder(context: Context, assetFolder: String, targetFolder: File) {
        val assetManager = context.assets
        val files = assetManager.list(assetFolder) ?: return
        targetFolder.mkdirs()

        for (file in files) {
            val subAsset = "$assetFolder/$file"
            val subFiles = assetManager.list(subAsset)
            if (subFiles != null && subFiles.isNotEmpty()) {
                copyAssetFolder(context, subAsset, File(targetFolder, file))
            } else {
                val outFile = File(targetFolder, file)
                try {
                    assetManager.open(subAsset).use { input ->
                        FileOutputStream(outFile).use { output ->
                            copyFile(input, output)
                        }
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Error copying asset $subAsset: ${e.message}")
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun copyFile(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(4096)
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
        }
    }

    @Suppress("FunctionName")
    @JvmStatic
    private external fun SetSysDirectory(path: String)

    @Suppress("FunctionName")
    @JvmStatic
    private external fun SetGpuDriverDirectories(path: String, libPath: String)
}
