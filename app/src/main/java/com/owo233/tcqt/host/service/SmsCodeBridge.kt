package com.owo233.tcqt.host.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.owo233.tcqt.core.log.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal object SmsCodeBridge {

    const val ACTION_SUBMIT_SMS_CODE = "com.owo233.tcqt.ACTION_SUBMIT_SMS_CODE"
    const val ACTION_SMS_CHALLENGE = "com.owo233.tcqt.ACTION_SMS_CHALLENGE"
    const val EXTRA_CODE = "code"
    const val EXTRA_UIN = "uin"
    const val EXTRA_PHONE = "phone"
    const val EXTRA_TIMESTAMP = "timestamp"

    private const val TAG = "SmsCodeBridge"
    private const val DEFAULT_CODE_REGEX = "(?<!\\d)(\\d{6})(?!\\d)"
    private val PROVIDER_URI: Uri = Uri.parse("content://com.owo233.tcqt.sms/latest")

    data class ChallengeInfo(
        val uin: String,
        val maskedPhone: String,
        val timestampMs: Long,
    )

    data class CodeEntry(
        val code: String,
        val timestampMs: Long,
    )

    @Volatile
    var pendingChallenge: ChallengeInfo? = null
        private set

    @Volatile
    private var pendingCode: CodeEntry? = null

    fun updateChallenge(
        context: Context?,
        uin: String,
        maskedPhone: String,
        timestampMs: Long = System.currentTimeMillis(),
        broadcast: Boolean = false,
    ) {
        pendingChallenge = ChallengeInfo(uin = uin, maskedPhone = maskedPhone, timestampMs = timestampMs)
        if (broadcast && context != null) {
            runCatching {
                context.sendBroadcast(
                    Intent(ACTION_SMS_CHALLENGE).apply {
                        setPackage(context.packageName)
                        putExtra(EXTRA_UIN, uin)
                        putExtra(EXTRA_PHONE, maskedPhone)
                        putExtra(EXTRA_TIMESTAMP, timestampMs)
                    }
                )
            }
        }
    }

    fun clearChallenge() {
        pendingChallenge = null
        pendingCode = null
    }

    fun submitCode(
        context: Context?,
        rawCode: String,
        timestampMs: Long = System.currentTimeMillis(),
        broadcast: Boolean = false,
    ): String? {
        val code = Regex("\\d{4,8}").find(rawCode.trim())?.value ?: return null
        pendingCode = CodeEntry(code = code, timestampMs = timestampMs)
        if (broadcast && context != null) {
            runCatching {
                context.sendBroadcast(
                    Intent(ACTION_SUBMIT_SMS_CODE).apply {
                        setPackage(context.packageName)
                        putExtra(EXTRA_CODE, code)
                        putExtra(EXTRA_TIMESTAMP, timestampMs)
                    }
                )
            }.onFailure {
                Log.w("$TAG: broadcast submitCode failed: ${it.message}")
            }
        }
        return code
    }

    fun consumeSubmittedCode(sinceMs: Long): String? {
        val entry = pendingCode ?: return null
        if (entry.timestampMs < sinceMs) return null
        pendingCode = null
        return entry.code
    }

    fun queryLocalProvider(context: Context, sinceMs: Long): String? {
        return runCatching {
            val queryUri = PROVIDER_URI.buildUpon()
                .appendQueryParameter("since", sinceMs.toString())
                .build()
            context.contentResolver.query(queryUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex("code")
                    if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    fun fetchFromHttpUrl(
        urlTemplate: String,
        regexPattern: String,
        uin: String,
        maskedPhone: String,
    ): String? {
        val rawUrl = urlTemplate.trim()
        if (rawUrl.isEmpty()) return null
        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) return null

        val resolvedUrl = rawUrl
            .replace("{uin}", URLEncoder.encode(uin, "UTF-8"))
            .replace("{phone}", URLEncoder.encode(maskedPhone, "UTF-8"))

        return runCatching {
            val conn = (URL(resolvedUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
            }
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            conn.disconnect()
            if (status !in 200..299 || body.isBlank()) return null

            val pattern = runCatching {
                Regex(regexPattern.trim().ifEmpty { DEFAULT_CODE_REGEX })
            }.getOrElse {
                Regex(DEFAULT_CODE_REGEX)
            }
            val match = pattern.find(body) ?: return null
            val groupVal = if (match.groupValues.size > 1) match.groupValues[1] else match.value
            groupVal.takeIf { it.length in 4..8 && it.all(Char::isDigit) }
        }.onFailure {
            Log.d("$TAG: fetchFromHttpUrl failed: ${it.message}")
        }.getOrNull()
    }
}
