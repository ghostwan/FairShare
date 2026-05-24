package com.fairshare.data.sync

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders an arbitrary string (typically a `fairshare://` URL) into a square QR Bitmap.
 *
 * Uses ZXing core (pure JVM) to avoid pulling the Android-helper module. The bitmap is
 * generated as a monochrome `ARGB_8888` square of [sizePx] pixels.
 */
object QrCodeGenerator {

    /**
     * @param content URL/text to encode.
     * @param sizePx side length of the resulting bitmap in pixels.
     * @param errorCorrection ZXing error correction level. `L` keeps the QR small for long URLs.
     */
    fun generate(
        content: String,
        sizePx: Int,
        errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.L,
    ): Bitmap {
        require(sizePx > 0) { "sizePx must be positive" }
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to errorCorrection,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }
}
