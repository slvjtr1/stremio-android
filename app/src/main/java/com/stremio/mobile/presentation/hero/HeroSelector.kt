package com.stremio.mobile.presentation.hero

import com.stremio.mobile.data.model.CatalogItem
import com.stremio.mobile.data.model.CatalogShelf

/**
 * Picks the titles shown in the home Hero Banner.
 *
 * Interleaves the first few loaded catalogs (so movies and series alternate), keeps only items
 * with landscape artwork, drops titles the user already watched or is currently watching, and
 * de-duplicates across catalogs. Rotation order is stable for a given [seed] so the banner
 * doesn't reshuffle on every recomposition, but varies between days.
 */
object HeroSelector {
    fun select(
        shelves: List<CatalogShelf>,
        continueWatchingIds: Set<String> = emptySet(),
        maxItems: Int = 8,
        maxShelves: Int = 3,
        seed: Long = 0L,
    ): List<CatalogItem> {
        val pools = shelves
            .filter { it.items.isNotEmpty() }
            .take(maxShelves)
            .map { shelf ->
                shelf.items.filter { item ->
                    !item.background.isNullOrBlank() && !item.watched && item.id !in continueWatchingIds
                }.take(maxItems * 2)
            }
        if (pools.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val picked = ArrayList<CatalogItem>(maxItems)
        var round = 0
        while (picked.size < maxItems && pools.any { round < it.size }) {
            for (pool in pools) {
                val item = pool.getOrNull(round) ?: continue
                if (seen.add(item.id)) picked.add(item)
                if (picked.size == maxItems) break
            }
            round++
        }
        if (picked.size <= 1 || seed == 0L) return picked
        // Deterministic rotation offset: start at a different title each day.
        val start = Math.floorMod(seed, picked.size.toLong()).toInt()
        return picked.drop(start) + picked.take(start)
    }
}
