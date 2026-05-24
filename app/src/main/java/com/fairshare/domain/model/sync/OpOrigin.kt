package com.fairshare.domain.model.sync

/**
 * Origin of an [Operation] stored in the local op log.
 *
 * - [LOCAL] : emitted by this device.
 * - [SNEAKERNET] : received from a Share Intent / pasted link / scanned QR.
 * - [CLOUD] : received from the Cloudflare Worker transport.
 */
enum class OpOrigin { LOCAL, SNEAKERNET, CLOUD }
