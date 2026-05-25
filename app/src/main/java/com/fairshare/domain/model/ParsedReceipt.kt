package com.fairshare.domain.model

/**
 * Result of OCR-based receipt parsing: the extracted line items plus the
 * optional merchant name detected at the top of the receipt.
 *
 * [merchant] is null when no plausible merchant could be extracted (no header,
 * too noisy, …). Callers should treat null as "leave the title alone".
 */
data class ParsedReceipt(
    val merchant: String?,
    val items: List<ReceiptItem>,
)
