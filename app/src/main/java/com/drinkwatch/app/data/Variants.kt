package com.drinkwatch.app.data

/**
 * The "other sizes" chips the add screen shows once a drink is chosen.
 *
 * The catalogue holds one row per brand+flavour+size+pack, so picking "V Blue"
 * from a search answers only part of the question. The server returns the rows
 * that share the flavour; this orders them the way a person reads a shelf — by
 * size, then by how many are in the carton — and gives each a label short
 * enough for a chip.
 */
object Variants {

    /** Size, then carton count, with a row whose size is unstated last. */
    fun sorted(siblings: List<Product>): List<Product> =
        siblings
            .distinctBy { it.slug }
            .sortedWith(
                compareBy(
                    { it.sizeMl == null },
                    { it.sizeMl ?: 0 },
                    { it.packCount },
                )
            )

    /**
     * "250ml", "250ml x4", "Size not stated".
     *
     * No space before "ml": a chip is one line on a phone, and "500ml x4" is the
     * spelling the rest of the app uses.
     */
    fun chipLabel(sizeMl: Int?, packCount: Int): String {
        val size = sizeMl?.let { "${it}ml" } ?: "Size not stated"
        return if (packCount > 1) "$size x$packCount" else size
    }

    fun chipLabel(product: Product): String = chipLabel(product.sizeMl, product.packCount)
}
