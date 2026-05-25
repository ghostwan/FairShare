package com.fairshare.domain.repository

import android.net.Uri
import com.fairshare.domain.model.ParsedReceipt

/** OCR-based receipt parsing. */
interface ReceiptParser {
    suspend fun parse(imageUri: Uri): ParsedReceipt
}
