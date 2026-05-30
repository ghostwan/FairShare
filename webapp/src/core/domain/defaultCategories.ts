import type { Category } from "./models";

/**
 * The 8 default categories shipped with the app. Mirrored verbatim from
 * `com.fairshare.domain.model.DefaultCategories` — ids must stay
 * byte-identical because they travel through the sync log as opaque
 * strings referenced by `Expense.categoryId`.
 *
 * Colors are kept as ARGB numbers like on Android; the picker / badge
 * converts them to `#RRGGBB` at render time.
 */

function def(
  id: string,
  name: string,
  emoji: string,
  color: number,
): Category {
  return { id, eventId: "", name, emoji, color, isDefault: true };
}

export const DefaultCategories = {
  FOOD: def("default.food", "Alimentation", "🥗", 0xff66bb6a),
  RESTAURANT: def("default.restaurant", "Restaurant", "🍽️", 0xffef6c00),
  TRANSPORT: def("default.transport", "Transport", "🚆", 0xff1e88e5),
  LODGING: def("default.lodging", "Hébergement", "🏨", 0xff8e24aa),
  LEISURE: def("default.leisure", "Loisirs", "🎉", 0xffd81b60),
  SHOPPING: def("default.shopping", "Courses", "🛒", 0xff00897b),
  DRINKS: def("default.drinks", "Boissons", "🍻", 0xffffb300),
  OTHER: def("default.other", "Autre", "📦", 0xff607d8b),
} as const;

export const DEFAULT_CATEGORIES: Category[] = [
  DefaultCategories.FOOD,
  DefaultCategories.RESTAURANT,
  DefaultCategories.TRANSPORT,
  DefaultCategories.LODGING,
  DefaultCategories.LEISURE,
  DefaultCategories.SHOPPING,
  DefaultCategories.DRINKS,
  DefaultCategories.OTHER,
];

export const DEFAULT_CATEGORIES_BY_ID: ReadonlyMap<string, Category> = new Map(
  DEFAULT_CATEGORIES.map((c) => [c.id, c]),
);

/** Returns the default category, or `undefined` if the id is unknown. */
export function resolveDefaultCategory(id: string | null): Category | undefined {
  if (id == null) return undefined;
  return DEFAULT_CATEGORIES_BY_ID.get(id);
}
