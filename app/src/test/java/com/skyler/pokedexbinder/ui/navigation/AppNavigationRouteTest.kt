package com.skyler.pokedexbinder.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers gap #1 ("route wiring") from `gaps.md`'s test-coverage gaps: a route-string typo or a
 * `createRoute()` encoding regression would previously only be caught by manually clicking
 * through the app. `Screen` and its `createRoute()` functions are plain Kotlin (string building +
 * `java.net.URLEncoder`), so this is fully JVM-testable with no Android/Compose/Hilt dependency.
 *
 * Drawer order / click-to-navigate reachability is covered separately by the Hilt-instrumented
 * `AppNavigationScreenTest` — that half genuinely requires composing the real UI tree and cannot
 * be verified at this level (see specs.md's Architecture Decision).
 */
class AppNavigationRouteTest {

    @Test
    fun `static routes are all unique`() {
        val routes = listOf(
            Screen.MainBinder.route,
            Screen.SecondaryBinder.route,
            Screen.ConnectingArt.route,
            Screen.Unown.route,
            Screen.PersonalCollection.route,
            Screen.Settings.route,
            Screen.AddToSecondary.route
        )
        assertEquals(routes.size, routes.toSet().size)
    }

    @Test
    fun `static route values match expected strings`() {
        assertEquals("main_binder", Screen.MainBinder.route)
        assertEquals("secondary_binder", Screen.SecondaryBinder.route)
        assertEquals("connecting_art", Screen.ConnectingArt.route)
        assertEquals("unown", Screen.Unown.route)
        assertEquals("personal_collection", Screen.PersonalCollection.route)
        assertEquals("settings", Screen.Settings.route)
        assertEquals("add_to_secondary", Screen.AddToSecondary.route)
    }

    @Test
    fun `ConnectingArtSearch createRoute encodes slotId into the route template position`() {
        assertEquals("connecting_art_search/42", Screen.ConnectingArtSearch.createRoute(42))
    }

    @Test
    fun `UnownSearch createRoute handles plain letters`() {
        assertEquals("unown_search/A", Screen.UnownSearch.createRoute("A"))
    }

    @Test
    fun `UnownSearch createRoute encodes the real special letter IDs used by UnownBinderRepository`() {
        // UnownBinderRepository.LETTER_IDS is ('A'..'Z') + "!" + "?" -- these are the actual
        // letter IDs this route is called with in production, not arbitrary test strings.
        assertEquals("unown_search/%21", Screen.UnownSearch.createRoute("!"))
        assertEquals("unown_search/%3F", Screen.UnownSearch.createRoute("?"))
    }

    @Test
    fun `UnownSearch createRoute encodes spaces as percent-20, not plus`() {
        assertEquals("unown_search/A%20B", Screen.UnownSearch.createRoute("A B"))
    }

    @Test
    fun `SlotDetail createRoute encodes slotId into the route template position`() {
        assertEquals("slot_detail/bulbasaur", Screen.SlotDetail.createRoute("bulbasaur"))
    }

    @Test
    fun `QuickScan createRoute with defaults matches navArgument default values`() {
        // AppNavigation.kt declares navArgument("slotId") { defaultValue = "" } and
        // navArgument("replacing") { defaultValue = false } -- this must match exactly.
        assertEquals("quick_scan?slotId=&replacing=false", Screen.QuickScan.createRoute())
    }

    @Test
    fun `QuickScan createRoute encodes a non-empty slotId with a space`() {
        assertEquals(
            "quick_scan?slotId=slot%20a&replacing=true",
            Screen.QuickScan.createRoute(slotId = "slot a", replacing = true)
        )
    }

    @Test
    fun `Scanner createRoute with defaults matches navArgument default values`() {
        // AppNavigation.kt declares defaultValue = "" for slotId/pokemonName and false for
        // isSecondary -- this must match exactly.
        assertEquals(
            "scanner?slotId=&pokemonName=&isSecondary=false",
            Screen.Scanner.createRoute()
        )
    }

    @Test
    fun `Scanner createRoute encodes pokemonName with special characters`() {
        // Real call sites (ScannerScreen/SlotDetailScreen) pass Pokemon names that can contain
        // spaces and punctuation, e.g. "Mr. Mime" (used throughout the existing test fixtures).
        assertEquals(
            "scanner?slotId=&pokemonName=Mr.%20Mime&isSecondary=true",
            Screen.Scanner.createRoute(pokemonName = "Mr. Mime", isSecondary = true)
        )
    }

    @Test
    fun `parameterized route templates contain their expected placeholder names`() {
        // These expected names are hardcoded here on purpose -- they mirror AppNavigation.kt's
        // own navArgument(...) calls as of when this test was written. NOTE: this only checks
        // Screen's own route template against a hardcoded literal; it does NOT read
        // AppNavigation.kt's actual navArgument(...) declarations, so it cannot catch the
        // "navArgument renamed, route template left untouched" half of the route-typo risk named
        // in the spec -- a genuine cross-check would need those names extracted into shared
        // constants, which is a production change out of scope for a coverage-only pass.
        assertTrue(Screen.ConnectingArtSearch.route.contains("{slotId}"))
        assertTrue(Screen.UnownSearch.route.contains("{letterId}"))
        assertTrue(Screen.SlotDetail.route.contains("{slotId}"))
        assertTrue(Screen.QuickScan.route.contains("{slotId}"))
        assertTrue(Screen.QuickScan.route.contains("{replacing}"))
        assertTrue(Screen.Scanner.route.contains("{slotId}"))
        assertTrue(Screen.Scanner.route.contains("{pokemonName}"))
        assertTrue(Screen.Scanner.route.contains("{isSecondary}"))
    }
}
