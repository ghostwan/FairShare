package com.fairshare.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Materialized state of an [com.fairshare.domain.model.Event].
 *
 * [encryptionKey] is the 32-byte symmetric key bound to the event, used
 * to integrity-stamp invitation bundles and to encrypt op
 * payloads bound for the Cloudflare Worker (DESIGN.md §2.3 / §7).
 *
 * The key never leaves the device except inside an invitation URL. It
 * is deliberately stored on the materialized [EventEntity] (not on an
 * op snapshot) so it does not travel through the op log: every joining
 * device receives the key out-of-band, from the link that brought it
 * into the event.
 */
@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String?,
    val currency: String,
    val createdAt: Long,
    val encryptionKey: ByteArray,
    val archived: Boolean = false,
) {
    // ByteArray invalidates the data class equals/hashCode; override so
    // Room's diff-against-current works correctly.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EventEntity) return false
        return id == other.id &&
            name == other.name &&
            description == other.description &&
            currency == other.currency &&
            createdAt == other.createdAt &&
            encryptionKey.contentEquals(other.encryptionKey) &&
            archived == other.archived
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (description?.hashCode() ?: 0)
        result = 31 * result + currency.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + encryptionKey.contentHashCode()
        result = 31 * result + archived.hashCode()
        return result
    }
}
