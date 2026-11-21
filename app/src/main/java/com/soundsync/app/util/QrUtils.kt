package com.soundsync.app.util

import android.graphics.Bitmap
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

data class QrConnectionPayload(
    val ip: String,
    val port: Int = 8889,
    val pin: String = "7294",
    val name: String = "Master Phone"
) {
    fun toJson(): String = Gson().toJson(this)

    companion object {
        fun fromString(str: String): QrConnectionPayload? {
            return try {
                val trimmed = str.trim()
                if (trimmed.startsWith("{")) {
                    Gson().fromJson(trimmed, QrConnectionPayload::class.java)
                } else if (trimmed.startsWith("soundsync://")) {
                    val uri = android.net.Uri.parse(trimmed)
                    QrConnectionPayload(
                        ip = uri.getQueryParameter("ip") ?: "",
                        port = uri.getQueryParameter("port")?.toIntOrNull() ?: 8889,
                        pin = uri.getQueryParameter("pin") ?: "",
                        name = uri.getQueryParameter("name") ?: "Master"
                    )
                } else if (trimmed.contains(":")) {
                    // Raw IP:port or IP
                    val parts = trimmed.split(":")
                    QrConnectionPayload(ip = parts[0], port = parts.getOrNull(1)?.toIntOrNull() ?: 8889)
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

object QrUtils {
    fun generateQrBitmap(content: String, size: Int = 600): Bitmap? {
        return try {
            val encoder = BarcodeEncoder()
            encoder.encodeBitmap(content, BarcodeFormat.QR_CODE, size, size)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
