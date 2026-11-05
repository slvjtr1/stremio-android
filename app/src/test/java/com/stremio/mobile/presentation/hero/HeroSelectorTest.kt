package com.stremio.mobile.presentation.hero

import com.stremio.mobile.data.model.CatalogItem
import com.stremio.mobile.data.model.CatalogShelf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroSelectorTest {
    private fun item(id: String, bg: Boolean = true, watched: Boolean = false) = CatalogItem(
        id = id, type = "movie", name = id, poster = "p", background = if (bg) "b/$id" else null,
        releaseInfo = null, imdbRating = null, watched = watched,
    )

    private fun shelf(vararg items: CatalogItem) = CatalogShelf(title = "s", items = items.toList())

    @Test
    fun interleavesShelvesAndDeduplicates() {
        val movies = shelf(item("m1"), item("m2"), item("shared"))
        val series = shelf(item("s1"), item("shared"), item("s2"))
        val picked = HeroSelector.select(listOf(movies, series), maxItems = 10)
        assertEquals(listOf("m1", "s1", "m2", "shared", "s2"), picked.map { it.id })
    }

    @Test
    fun skipsItemsWithoutArtworkWatchedOrInProgress() {
        val s = shelf(item("noart", bg = false), item("seen", watched = true), item("cw"), item("ok"))
        val picked = HeroSelector.select(listOf(s), continueWatchingIds = setOf("cw"))
        assertEquals(listOf("ok"), picked.map { it.id })
    }

    @Test
    fun respectsLimitsAndSeedRotation() {
        val s = shelf(*(1..20).map { item("i$it") }.toTypedArray())
        val picked = HeroSelector.select(listOf(s), maxItems = 5)
        assertEquals(5, picked.size)
        val rotated = HeroSelector.select(listOf(s), maxItems = 5, seed = 2)
        assertEquals(picked.toSet(), rotated.toSet())
        assertEquals("i3", rotated.first().id)
    }

    @Test
    fun emptyInputGivesEmptyResult() {
        assertTrue(HeroSelector.select(emptyList()).isEmpty())
        assertTrue(HeroSelector.select(listOf(CatalogShelf(title = "x"))).isEmpty())
    }
}
