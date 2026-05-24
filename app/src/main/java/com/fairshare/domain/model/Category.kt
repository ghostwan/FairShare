package com.fairshare.domain.model

/**
 * Category used to tag an [Expense] for filtering and statistics. There
 * are two kinds:
 *
 * - **Default categories** (eg. `default.food`, `default.transport`) are
 *   hardcoded in [DefaultCategories] with stable ids, shipped with the
 *   app, and identical on every device. They never travel through the
 *   sync log — `categoryId` references them by id directly.
 * - **Custom categories** are user-created, scoped to a single event,
 *   CRDT-synced via [com.fairshare.domain.model.sync.OpPayload.CategoryUpsert]
 *   / [com.fairshare.domain.model.sync.OpPayload.CategoryDelete]. Ids
 *   are UUIDs.
 *
 * Both kinds share the same shape and the same picker; the
 * [isDefault] flag drives the "delete" affordance (off for defaults).
 */
data class Category(
    val id: String = "",
    /**
     * Event this category belongs to. Empty string for default
     * categories — they're not bound to any event and are not stored
     * in the database.
     */
    val eventId: String = "",
    val name: String,
    /** Short emoji rendered next to the name in the picker / badge. */
    val emoji: String,
    /** ARGB color used for the badge background. */
    val color: Long,
    val isDefault: Boolean = false,
)
