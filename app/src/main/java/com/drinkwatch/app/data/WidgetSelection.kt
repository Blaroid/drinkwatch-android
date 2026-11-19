package com.drinkwatch.app.data

/** How many drinks the widget promises in the README, one row each. */
const val WIDGET_ROW_LIMIT = 4

/**
 * The widget's rows: up to [limit] followed drinks, cheapest first.
 *
 * /v1/widget returns rows in the order the slugs were requested, which is the
 * user's watchlist order, so taking the head of the list hides a cheaper can
 * further down and the widget stops matching its "four cheapest" description.
 * The sort is stable, so drinks at the same price keep the watchlist order used
 * elsewhere instead of shuffling between refreshes.
 *
 * Suggestion rows are recommendations shown *after* the watchlist on the home
 * screen, never drinks the user follows; a suggestion must not push a followed
 * drink out of the widget. The worker asks for no suggestions, so this only
 * guards against a payload built for the home screen.
 */
fun cheapestWidgetItems(items: List<WidgetItem>, limit: Int = WIDGET_ROW_LIMIT): List<WidgetItem> {
    if (limit <= 0) return emptyList()
    return items.filterNot { it.suggested }.sortedBy { it.priceCents }.take(limit)
}
