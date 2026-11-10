// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.updater

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class APKDownloader(private val url: String, private val outputFile: File) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun download(onProgress: (Int) -> Unit, onComplete: (Boolean) -> Unit) {
        downloadWithRetry(retriesLeft = 2, onProgress = onProgress, onComplete = onComplete)
    }

    private fun downloadWithRetry(
        retriesLeft: Int,
        onProgress: (Int) -> Unit,
        onComplete: (Boolean) -> Unit
    ) {
        val resolvedUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "https://github.com$url"
        }

        val request = Request.Builder()
            .url(resolvedUrl)
            .header("User-Agent", "Lichii-Android-AutoUpdater/1.0")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (retriesLeft > 0) {
                    downloadWithRetry(retriesLeft - 1, onProgress, onComplete)
                } else {
                    e.printStackTrace()
                    onComplete(false)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        if (retriesLeft > 0 && resp.code >= 500) {
                            downloadWithRetry(retriesLeft - 1, onProgress, onComplete)
                        } else {
                            onComplete(false)
                        }
                        return
                    }

                    val body = resp.body
                    if (body == null) {
                        onComplete(false)
                        return
                    }

                    val contentLength = body.contentLength()
                    try {
                        val tempFile = File(outputFile.parentFile, "${outputFile.name}.part")
                        body.byteStream().use { inputStream ->
                            FileOutputStream(tempFile).use { outputStream ->
                                val buffer = ByteArray(16 * 1024)
                                var bytesRead: Int
                                var totalBytesRead: Long = 0
                                var lastReportedProgress = -1

                                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                    outputStream.write(buffer, 0, bytesRead)
                                    totalBytesRead += bytesRead
                                    if (contentLength > 0) {
                                        val progress =
                                            ((totalBytesRead * 100) / contentLength).toInt().coerceIn(0, 100)
                                        if (progress != lastReportedProgress) {
                                            lastReportedProgress = progress
                                            onProgress(progress)
                                        }
                                    }
                                }
                                outputStream.flush()
                            }
                        }
                        if (outputFile.exists()) {
                            outputFile.delete()
                        }
                        val renamed = tempFile.renameTo(outputFile)
                        onComplete(renamed && outputFile.length() > 1024 * 1024)
                    } catch (e: IOException) {
                        if (retriesLeft > 0) {
                            downloadWithRetry(retriesLeft - 1, onProgress, onComplete)
                        } else {
                            e.printStackTrace()
                            onComplete(false)
                        }
                    }
                }
            }
        })
    }
}
