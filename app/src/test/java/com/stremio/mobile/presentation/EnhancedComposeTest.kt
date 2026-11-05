package com.stremio.mobile.presentation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.stremio.mobile.data.model.CatalogItem
import com.stremio.mobile.player.subsync.SubtitleSyncStatus
import com.stremio.mobile.presentation.components.HeroBanner
import com.stremio.mobile.presentation.components.SpoilerArtwork
import com.stremio.mobile.presentation.components.SpoilerBlurLevel
import com.stremio.mobile.presentation.screens.player.SubtitleSyncPanel
import com.stremio.mobile.presentation.screens.player.describeSyncStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EnhancedComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private fun item(id: String, name: String) = CatalogItem(
        id = id, type = "movie", name = name, poster = null, background = "https://example.invalid/$id.jpg",
        releaseInfo = "2024", imdbRating = "8.1", description = "Description of $name",
        genres = listOf("Drama", "Thriller"),
    )

    @Test
    fun heroShowsMetadataAndRoutesActions() {
        val watched = mutableListOf<String>()
        val info = mutableListOf<String>()
        compose.setContent {
            HeroBanner(
                items = listOf(item("a", "Alpha"), item("b", "Beta")),
                onWatch = { watched += it.id },
                onMoreInfo = { info += it.id },
                reducedMotion = true,
            )
        }
        compose.onNodeWithText("Alpha").assertIsDisplayed()
        compose.onNodeWithText("Description of Alpha").assertIsDisplayed()
        compose.onNodeWithText("2024  ·  Drama  ·  Thriller").assertIsDisplayed()
        compose.onNodeWithContentDescription("IMDb rating 8.1").assertIsDisplayed()
        compose.onNodeWithText("Watch now").performClick()
        compose.onNodeWithText("More info").performClick()
        assertEquals(listOf("a"), watched)
        assertEquals(listOf("a"), info)
    }

    @Test
    fun heroDoesNotAutoRotateWithReducedMotion() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HeroBanner(items = listOf(item("a", "Alpha"), item("b", "Beta")), onWatch = {}, onMoreInfo = {}, reducedMotion = true)
        }
        compose.mainClock.advanceTimeBy(20_000)
        compose.onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun heroAutoRotatesWhenMotionAllowed() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            HeroBanner(items = listOf(item("a", "Alpha"), item("b", "Beta")), onWatch = {}, onMoreInfo = {}, reducedMotion = false)
        }
        compose.mainClock.advanceTimeBy(9_000)
        compose.onNodeWithText("Beta").assertIsDisplayed()
    }

    @Test
    fun spoilerArtworkMarksHiddenEpisodes() {
        compose.setContent {
            SpoilerArtwork(
                url = "https://example.invalid/thumb.jpg",
                hidden = true,
                level = SpoilerBlurLevel.STRONG,
                contentDescription = "Episode artwork hidden until watched",
            )
        }
        compose.onNodeWithContentDescription("Episode artwork hidden until watched").assertExists()
    }

    @Test
    fun spoilerArtworkLeavesWatchedEpisodesClear() {
        compose.setContent {
            SpoilerArtwork(url = "https://example.invalid/thumb.jpg", hidden = false, level = SpoilerBlurLevel.STRONG, contentDescription = "clear")
        }
        compose.onNodeWithContentDescription("clear").assertExists()
        assertEquals(0, compose.onAllNodesWithContentDescription("Episode artwork hidden until watched").fetchSemanticsNodes().size)
    }

    @Test
    fun syncPanelShowsStatusAndToggles() {
        var auto = true
        var resets = 0
        compose.setContent {
            SubtitleSyncPanel(
                status = SubtitleSyncStatus.Synced(shiftMs = 2350, scale = 1.0, zScore = 7.0, corrections = 1),
                autoSync = true,
                driftCorrection = true,
                onAutoSyncChange = { auto = it },
                onDriftCorrectionChange = {},
                onReset = { resets++ },
            )
        }
        compose.onNodeWithText("Synced +2.35 s").assertIsDisplayed()
        compose.onNodeWithText("Reset auto sync").performClick()
        compose.onNodeWithText("AUTO SYNC").performClick()
        assertEquals(1, resets)
        assertEquals(false, auto)
    }

    @Test
    fun syncStatusDescriptions() {
        assertTrue(describeSyncStatus(SubtitleSyncStatus.Listening(0.5f)).first.startsWith("Listening… 50%"))
        assertEquals("Not confident", describeSyncStatus(SubtitleSyncStatus.Uncertain("x")).first)
        val drift = describeSyncStatus(SubtitleSyncStatus.Synced(-1200, 25.0 / 23.976, 6.0, 2))
        assertEquals("Synced −1.20 s · drift +4.27%", drift.first)
        assertEquals("2 corrections applied", drift.second)
    }
}
