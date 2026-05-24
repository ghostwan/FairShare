package com.fairshare.domain.model

/**
 * The 8 default categories shipped with the app. They are NOT
 * persisted in the database and NOT CRDT-synced: every device builds
 * the same list from this file, so referencing them by id from an
 * [Expense.categoryId] field is sufficient. Adding entries here is
 * backward compatible (older devices simply render the unknown id
 * as "Autre"); removing or renaming ids would break existing tags
 * on already-synced expenses, so treat the id set as append-only.
 *
 * Colors are picked to read well on both light and dark Material 3
 * surface backgrounds at ~20% alpha (the badge tint).
 */
object DefaultCategories {

    val FOOD = Category(
        id = "default.food",
        name = "Alimentation",
        emoji = "🥗",
        color = 0xFF66BB6A,
        isDefault = true,
    )
    val RESTAURANT = Category(
        id = "default.restaurant",
        name = "Restaurant",
        emoji = "🍽️",
        color = 0xFFEF6C00,
        isDefault = true,
    )
    val TRANSPORT = Category(
        id = "default.transport",
        name = "Transport",
        emoji = "🚆",
        color = 0xFF1E88E5,
        isDefault = true,
    )
    val LODGING = Category(
        id = "default.lodging",
        name = "Hébergement",
        emoji = "🏨",
        color = 0xFF8E24AA,
        isDefault = true,
    )
    val LEISURE = Category(
        id = "default.leisure",
        name = "Loisirs",
        emoji = "🎉",
        color = 0xFFD81B60,
        isDefault = true,
    )
    val SHOPPING = Category(
        id = "default.shopping",
        name = "Courses",
        emoji = "🛒",
        color = 0xFF00897B,
        isDefault = true,
    )
    val DRINKS = Category(
        id = "default.drinks",
        name = "Boissons",
        emoji = "🍻",
        color = 0xFFFFB300,
        isDefault = true,
    )
    val OTHER = Category(
        id = "default.other",
        name = "Autre",
        emoji = "📦",
        color = 0xFF607D8B,
        isDefault = true,
    )

    val ALL: List<Category> = listOf(
        FOOD, RESTAURANT, TRANSPORT, LODGING,
        LEISURE, SHOPPING, DRINKS, OTHER,
    )

    val BY_ID: Map<String, Category> = ALL.associateBy { it.id }

    /**
     * Looks up a default category by id, falling back to a synthetic
     * "Autre" entry that preserves the unknown id so the badge can
     * still render something meaningful (eg. if a future version
     * removes a default the user already tagged).
     */
    fun resolve(id: String?): Category? =
        if (id == null) null else BY_ID[id]
}
