package com.owo233.tcqt.core.sms

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import java.util.regex.Pattern

class SmsInboxProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val result = MatrixCursor(arrayOf("code", "date"))
        val ctx = context ?: return result
        if (!isAllowedCaller(ctx.packageManager)) return result
        if (ctx.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return result
        }

        val now = System.currentTimeMillis()
        val sinceMs = uri.getQueryParameter("since")?.toLongOrNull() ?: (now - DEFAULT_LOOKBACK_MS)
        val inboxUri = Uri.parse("content://sms/inbox")

        runCatching {
            ctx.contentResolver.query(
                inboxUri,
                arrayOf("body", "date"),
                "date >= ?",
                arrayOf(sinceMs.toString()),
                "date DESC",
            )?.use { cursor ->
                val bodyIdx = cursor.getColumnIndex("body")
                val dateIdx = cursor.getColumnIndex("date")
                var scanned = 0
                while (cursor.moveToNext() && scanned < MAX_SCAN_ROWS) {
                    scanned++
                    val body = if (bodyIdx >= 0) cursor.getString(bodyIdx).orEmpty() else ""
                    val date = if (dateIdx >= 0) cursor.getLong(dateIdx) else 0L
                    if (!isRelevantSms(body)) continue
                    val matcher = CODE_PATTERN.matcher(body)
                    if (matcher.find()) {
                        result.addRow(arrayOf<Any>(matcher.group(1).orEmpty(), date))
                        break
                    }
                }
            }
        }
        return result
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.owo233.tcqt.sms"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun isAllowedCaller(pm: PackageManager): Boolean {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return true
        val packages = pm.getPackagesForUid(uid) ?: return false
        return packages.any { it in ALLOWED_PACKAGES }
    }

    private fun isRelevantSms(body: String): Boolean {
        if (body.isBlank()) return false
        return body.contains("腾讯") ||
            body.contains("QQ", ignoreCase = true) ||
            body.contains("验证码")
    }

    companion object {
        private const val DEFAULT_LOOKBACK_MS = 180_000L
        private const val MAX_SCAN_ROWS = 15
        private val CODE_PATTERN: Pattern = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)")
        private val ALLOWED_PACKAGES = setOf(
            "com.tencent.mobileqq",
            "com.tencent.tim",
        )
    }
}
