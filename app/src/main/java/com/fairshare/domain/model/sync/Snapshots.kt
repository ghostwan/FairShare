package com.fairshare.domain.model.sync

import kotlinx.serialization.Serializable

/**
 * Wire-format snapshot of an [com.fairshare.domain.model.Event].
 *
 * Snapshots are decoupled from the in-app domain models so that the on-wire
 * representation stays stable even when the UI-facing model evolves. All
 * identifiers are [String] UUIDs (see DESIGN.md §2.1).
 */
@Serializable
data class EventSnapshot(
    val id: String,
    val name: String,
    val description: String? = null,
    val currency: String = "EUR",
    val createdAt: Long,
    /**
     * Archive flag. Travels through LWW like every other snapshot field:
     * the latest `EventUpsert` wins, so flipping it on any device
     * propagates via the standard sync pipeline. Defaulted to `false`
     * for wire-format backward compatibility (ignoreUnknownKeys is on,
     * older payloads without this field decode as archived = false).
     */
    val archived: Boolean = false,
)

/** Wire-format snapshot of a [com.fairshare.domain.model.Participant]. */
@Serializable
data class ParticipantSnapshot(
    val id: String,
    val eventId: String,
    val name: String,
)

/**
 * Wire-format snapshot of an [com.fairshare.domain.model.Expense].
 *
 * The whole tree (shares + items + assignments) is included because the chosen
 * CRDT granularity is per-Expense (see DESIGN.md §3.2). Concurrent edits on the
 * same expense resolve via LWW at this level.
 */
@Serializable
data class ExpenseSnapshot(
    val id: String,
    val eventId: String,
    val title: String,
    val amountCents: Long,
    val payerId: String,
    val date: Long,
    val shares: List<ExpenseShareSnapshot> = emptyList(),
    val items: List<ExpenseItemSnapshot> = emptyList(),
    /**
     * Marks the expense as a settlement (i.e. a real-world reimbursement
     * between two participants) rather than a shared cost. Settlements
     * are stored as plain expenses with payer = debtor and a single
     * share targeting the creditor, so the balance algorithm naturally
     * zeroes them out. The flag only changes the UI rendering. Defaulted
     * to `false` for wire-format backward compatibility.
     */
    val isSettlement: Boolean = false,
    /**
     * Optional category id tagging this expense. Either a
     * [com.fairshare.domain.model.DefaultCategories] stable id
     * (eg. `"default.food"`) or a UUID pointing to a custom
     * `CategorySnapshot` on the same event. Defaulted to `null` for
     * wire-format backward compatibility — old payloads without this
     * field decode as uncategorized.
     */
    val categoryId: String? = null,
)

/**
 * Wire-format snapshot of a [com.fairshare.domain.model.Category].
 *
 * Only custom (user-created) categories travel through the sync log;
 * default categories are hardcoded in [com.fairshare.domain.model.DefaultCategories]
 * and shared across devices by ID alone.
 */
@Serializable
data class CategorySnapshot(
    val id: String,
    val eventId: String,
    val name: String,
    val emoji: String,
    val color: Long,
)

@Serializable
data class ExpenseShareSnapshot(
    val id: String,
    val participantId: String,
    val amountCents: Long,
)

@Serializable
data class ExpenseItemSnapshot(
    val id: String,
    val label: String,
    val priceCents: Long,
    val quantity: Int = 1,
    /** Participant ids this item is assigned to. */
    val assignedTo: Set<String> = emptySet(),
)
