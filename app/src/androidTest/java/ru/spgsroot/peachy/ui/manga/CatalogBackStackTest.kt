package ru.spgsroot.peachy.ui.manga

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.spgsroot.peachy.manga.TagFilter
import ru.spgsroot.peachy.manga.TagMode

class CatalogBackStackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nestedArtistSearchesReturnToTheSameGalleryAndArtistEntries() {
        lateinit var nav: NavHostController
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = "player") {
                composable("player") {}
                composable(MANGA_HUB_ROUTE) {}
                composable("manga/{id}") {}
            }
        }
        compose.runOnIdle {
            val artistA = listOf(TagFilter("artist", "Alice / A", TagMode.Include))
            val artistB = listOf(TagFilter("artist", "Bob | B", TagMode.Include))
            nav.navigate(MANGA_HUB_ROUTE)
            val library = nav.currentBackStackEntry!!
            nav.navigate("manga/1")
            val firstGallery = nav.currentBackStackEntry!!
            nav.searchCatalog(artistA)
            val firstArtist = nav.currentBackStackEntry!!
            nav.navigate("manga/2")
            val secondGallery = nav.currentBackStackEntry!!
            nav.searchCatalog(artistB)
            assertEquals(artistB, decodeFilters(nav.currentBackStackEntry!!.savedStateHandle.get<List<String>>(TAG_FILTERS_KEY).orEmpty()))

            assertTrue(nav.popBackStack())
            assertSame(secondGallery, nav.currentBackStackEntry)
            assertTrue(nav.popBackStack())
            assertSame(firstArtist, nav.currentBackStackEntry)
            assertEquals(artistA, decodeFilters(firstArtist.savedStateHandle.get<List<String>>(TAG_FILTERS_KEY).orEmpty()))
            assertTrue(nav.popBackStack())
            assertSame(firstGallery, nav.currentBackStackEntry)
            assertTrue(nav.popBackStack())
            assertSame(library, nav.currentBackStackEntry)
        }
    }
}
