package com.fairshare.domain.model.sync

/**
 * Origin of an [Operation] stored in the local op log.
 *
 * - [LOCAL] : emitted by this device, including ops materialized from
 *   an accepted invitation (see `InvitationImporter`). They are
 *   re-pushed to the Worker on the next sync pass; opId is the
 *   primary key so duplicates are dedup'd server-side.
 * - [CLOUD] : received from the Cloudflare Worker transport.
 */
enum class OpOrigin { LOCAL, CLOUD }
