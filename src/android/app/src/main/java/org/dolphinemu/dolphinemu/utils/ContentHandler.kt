// SPDX-License-Identifier: GPL-2.0-or-later

package org.dolphinemu.dolphinemu.utils

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import androidx.annotation.Keep
import androidx.core.net.toUri
import org.dolphinemu.dolphinemu.DolphinApplication
import java.io.FileNotFoundException

object ContentHandler {
    private const val TAG = "DolphinContentHandler"

    @JvmStatic
    fun isContentUri(pathOrUri: String): Boolean {
        return pathOrUri.startsWith("content://")
    }

    @Keep
    @JvmStatic
    fun openFd(uri: String, mode: String): Int {
        try {
            return getContentResolver().openFileDescriptor(unmangle(uri), mode)?.detachFd() ?: -1
        } catch (e: SecurityException) {
            Log.e(TAG, "Tried to open $uri without permission: ${e.message}")
        } catch (_: Exception) {
        }
        return -1
    }

    @Keep
    @JvmStatic
    fun delete(uri: String): Boolean {
        try {
            return DocumentsContract.deleteDocument(getContentResolver(), unmangle(uri))
        } catch (_: FileNotFoundException) {
            return true
        } catch (e: SecurityException) {
            Log.e(TAG, "Tried to delete $uri without permission: ${e.message}")
        } catch (_: Exception) {
        }
        return false
    }

    @JvmStatic
    fun exists(uri: String): Boolean {
        try {
            val documentUri = treeToDocument(unmangle(uri))
            val projection = arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
            getContentResolver().query(documentUri, projection, null, null, null)?.use { cursor ->
                return cursor.count > 0
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Tried to check if $uri exists without permission: ${e.message}")
        } catch (_: Exception) {
        }
        return false
    }

    @Keep
    @JvmStatic
    fun getSizeAndIsDirectory(uri: String): Long {
        try {
            val documentUri = treeToDocument(unmangle(uri))
            val projection = arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
            getContentResolver().query(documentUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return if (Document.MIME_TYPE_DIR == cursor.getString(0)) {
                        -2
                    } else {
                        if (cursor.isNull(1)) 0 else cursor.getLong(1)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Tried to get metadata for $uri without permission: ${e.message}")
        } catch (_: Exception) {
        }
        return -1
    }

    @Keep
    @JvmStatic
    fun getDisplayName(uri: String): String? {
        try {
            return getDisplayName(unmangle(uri))
        } catch (_: Exception) {
        }
        return null
    }

    @JvmStatic
    fun getDisplayName(uri: Uri): String? {
        val projection = arrayOf(Document.COLUMN_DISPLAY_NAME)
        val documentUri = treeToDocument(uri)
        try {
            getContentResolver().query(documentUri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Tried to get display name of $uri without permission: ${e.message}")
        } catch (_: Exception) {
        }
        return null
    }

    @Keep
    @JvmStatic
    fun getChildNames(uri: String, recursive: Boolean): Array<String> {
        val childNames = mutableListOf<String>()
        try {
            doGetChildNames(uri, recursive) { name, _, _ ->
                childNames.add(name)
            }
        } catch (_: Exception) {
        }
        return childNames.toTypedArray()
    }

    private fun doGetChildNames(
        uri: String,
        recursive: Boolean,
        callback: (String, String, Boolean) -> Unit
    ) {
        try {
            val unmangled = unmangle(uri)
            val parentId = DocumentsContract.getDocumentId(treeToDocument(unmangled))
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(unmangled, parentId)
            val projection = arrayOf(
                Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE,
                Document.COLUMN_DOCUMENT_ID
            )
            getContentResolver().query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)
                    val mime = cursor.getString(1)
                    val isDir = Document.MIME_TYPE_DIR == mime
                    callback(name, cursor.getString(2), isDir)
                    if (recursive && isDir) {
                        doGetChildNames("$uri/$name", true, callback)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Tried to get children of $uri without permission: ${e.message}")
        } catch (_: Exception) {
        }
    }

    @Throws(FileNotFoundException::class, SecurityException::class)
    private fun getChild(parentUri: Uri, childName: String): Uri {
        val parentId = DocumentsContract.getDocumentId(treeToDocument(parentUri))
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentId)
        val projection = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_DOCUMENT_ID)
        val selection = "${Document.COLUMN_DISPLAY_NAME}=?"
        val selectionArgs = arrayOf(childName)
        try {
            getContentResolver().query(childrenUri, projection, selection, selectionArgs, null)
                ?.use { cursor ->
                    while (cursor.moveToNext()) {
                        if (childName == cursor.getString(0)) {
                            return DocumentsContract.buildDocumentUriUsingTree(
                                parentUri, cursor.getString(1)
                            )
                        }
                    }
                }
        } catch (e: SecurityException) {
            Log.e(TAG, "Tried to get child $childName of $parentUri without permission: ${e.message}")
        } catch (_: Exception) {
        }
        throw FileNotFoundException("$parentUri/$childName")
    }

    @Throws(FileNotFoundException::class, SecurityException::class)
    @JvmStatic
    fun unmangle(uri: String): Uri {
        val lastComponentEnd = getLastComponentEnd(uri)
        val lastComponentStart = getLastComponentStart(uri, lastComponentEnd)

        return if (lastComponentStart == 0) {
            uri.substring(0, lastComponentEnd).toUri()
        } else {
            val parentUri = unmangle(uri.substring(0, lastComponentStart))
            val childName = uri.substring(lastComponentStart, lastComponentEnd)
            getChild(parentUri, childName)
        }
    }

    private fun getLastComponentEnd(uri: String): Int {
        var i = uri.length
        while (i > 0 && uri[i - 1] == '/') {
            i--
        }
        return i
    }

    private fun getLastComponentStart(uri: String, lastComponentEnd: Int): Int {
        var i = lastComponentEnd
        while (i > 0 && uri[i - 1] != '/') {
            i--
            if (uri[i] == '%') {
                return 0
            }
        }
        var j = i
        while (j > 0) {
            j--
            if (uri[j] == '%') {
                return i
            }
        }
        return 0
    }

    private fun treeToDocument(uri: Uri): Uri {
        return if (isTreeUri(uri)) {
            val documentId = DocumentsContract.getTreeDocumentId(uri)
            DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
        } else {
            uri
        }
    }

    private fun isTreeUri(uri: Uri): Boolean {
        val pathSegments = uri.pathSegments
        return pathSegments.size == 2 && pathSegments[0] == "tree"
    }

    private fun getContentResolver(): ContentResolver {
        return DolphinApplication.getAppContext().contentResolver
    }
}
