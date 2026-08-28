package com.skyler.pokedexbinder.ui.navigation

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.skyler.pokedexbinder.MainActivity
import com.skyler.pokedexbinder.data.local.PersonalCollectionCache
import com.skyler.pokedexbinder.data.local.PersonalCollectionDao
import com.skyler.pokedexbinder.repository.SettingsRepository
import com.skyler.pokedexbinder.ui.personalcollection.PERSONAL_COLLECTION_SECTIONS
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Covers the "drawer order" half of gap #1 from `gaps.md`'s test-coverage gaps -- the route-typo
 * half is covered separately (and fully) by the JVM-level AppNavigationRouteTest class (a
 * different, unreachable-from-here source set -- see specs.md's Architecture Decision for why
 * these two risks are split across tiers). Drawer order and click-to-navigate reachability cannot
 * be verified at the JVM level: the drawer's `NavigationDrawerItem` calls are inline Composable
 * statements inside `AppNavigation()` with no separately-testable data structure to assert
 * against -- verifying actual rendering requires composing the real UI tree.
 *
 * First Hilt-instrumented test in this project (see [com.skyler.pokedexbinder.CustomTestRunner]).
 * Reuses the app's real Hilt graph via [MainActivity] (already `@AndroidEntryPoint`) for
 * everything except the database, which [com.skyler.pokedexbinder.di.FakeDatabaseModule] replaces
 * with an in-memory Room instance -- per review-verdict.md P1-1, running this test against the
 * real on-device `pokedex_binder.db` reversed an explicit prior decision (documented in
 * `DatabaseModule` and `DatabaseModuleWiringTest`) that this is unacceptable for a feature whose
 * entire purpose is protecting that file from data loss.
 *
 * A `ModalNavigationDrawer`'s drawer content stays composed (just translated off-screen) even
 * while closed, so several of these assertions can face a same-text collision: "Pokédex" also
 * labels the bottom-nav item, and each drawer entry's label is identical to its destination's
 * `TopAppBar` title. [assertOnScreenTextExists] disambiguates by filtering to nodes whose
 * `boundsInRoot.left` is non-negative -- a closed drawer's content is translated to a negative
 * left coordinate, while on-screen content (bottom nav, `TopAppBar`) is not.
 *
 * NOTE: this is an androidTest and requires a device/emulator to execute. No AVD/emulator was
 * available in the sandbox this file was authored in -- it compiles and is structurally correct,
 * but was not run live here. Execution (and confirmation the above disambiguation actually holds
 * against this exact `ModalNavigationDrawer` version's real layout behavior) is pending Skyler's
 * own `connectedAndroidTest` run, same standing constraint as every other instrumented test in
 * this project.
 */
@HiltAndroidTest
class AppNavigationScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var personalCollectionDao: PersonalCollectionDao

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            // P1-3: useCameraScanner is persisted, user-togglable DataStore state, not a fixed
            // default -- set it explicitly rather than assume the device/emulator's ambient
            // value (which, on Skyler's own device, may well have been toggled on already).
            settingsRepository.setUseCameraScanner(false)

            // P1-2: PersonalCollectionViewModel.init runs `if (repository.cacheCount() == 0)
            // refreshAll()`, which fans out real pokemontcg.io/TCGdex network calls and renders
            // an indefinite CircularProgressIndicator while in flight -- a fresh (in-memory,
            // per FakeDatabaseModule) database always has an empty cache, so this always fired
            // before. Seeding one cache row per section makes cacheCount() != 0, so refreshAll()
            // never runs and the reachability test below never touches the network or an
            // indefinite spinner that could hang Compose's idle-sync.
            personalCollectionDao.upsertCache(
                PERSONAL_COLLECTION_SECTIONS.map { section ->
                    PersonalCollectionCache(
                        cardId = "seed_${section.key}",
                        pokemonKey = section.key,
                        name = section.title,
                        imageUrl = "",
                        setName = "",
                        releaseDate = ""
                    )
                }
            )
        }
        composeTestRule.waitForIdle()
    }

    private fun assertOnScreenTextExists(label: String) {
        val onScreenMatches = composeTestRule.onAllNodesWithText(label)
            .fetchSemanticsNodes()
            .count { it.boundsInRoot.left >= 0f }
        assertTrue(
            "expected an on-screen node with text \"$label\"",
            onScreenMatches > 0
        )
    }

    /**
     * "Pokédex" labels both the drawer's own first item and the always-present bottom-nav item,
     * so [onNodeWithText] alone is ambiguous once the drawer is open (both are on-screen
     * simultaneously). The drawer's entry renders near the top of the screen while the bottom-nav
     * bar sits at the very bottom -- the same disambiguation [drawerShowsAllFiveItemsInTopToBottomOrder]
     * already relies on -- so the node with the smallest `boundsInRoot.top` is the drawer's.
     */
    private fun clickDrawerPokedexItem() {
        val nodes = composeTestRule.onAllNodesWithText("Pokédex").fetchSemanticsNodes()
        val drawerNodeIndex = nodes.indices.minByOrNull { nodes[it].boundsInRoot.top }!!
        composeTestRule.onAllNodesWithText("Pokédex")[drawerNodeIndex].performClick()
    }

    @Test
    fun drawerShowsAllFiveItemsInTopToBottomOrder() {
        composeTestRule.onNodeWithContentDescription("Menu").performClick()

        // Guard against a silently-failed open: a closed ModalNavigationDrawer keeps its content
        // composed (just off-screen), so every lookup below would resolve identically whether or
        // not the drawer actually opened. "Connecting Art" has no bottom-nav duplicate, so this
        // only passes once the drawer is genuinely on-screen.
        assertOnScreenTextExists("Connecting Art")

        // "Pokédex" also labels the bottom-nav item, which always exists regardless of drawer
        // state -- disambiguate by taking the smallest top (the drawer's own "Pokédex" entry is
        // the drawer's first item, well above the bottom-nav bar).
        val pokedexTop = composeTestRule.onAllNodesWithText("Pokédex")
            .fetchSemanticsNodes()
            .minOf { it.boundsInRoot.top }
        val connectingArtTop =
            composeTestRule.onNodeWithText("Connecting Art").fetchSemanticsNode().boundsInRoot.top
        val personalCollectionTop =
            composeTestRule.onNodeWithText("Personal Collection").fetchSemanticsNode().boundsInRoot.top
        val unownTop =
            composeTestRule.onNodeWithText("Unown").fetchSemanticsNode().boundsInRoot.top
        val cardHistoryTop =
            composeTestRule.onNodeWithText("Card History").fetchSemanticsNode().boundsInRoot.top

        val tops = listOf(pokedexTop, connectingArtTop, personalCollectionTop, unownTop, cardHistoryTop)
        assertEquals("drawer items must render top-to-bottom in the declared order", tops, tops.sorted())
    }

    @Test
    fun drawerPokedexItemNavigatesToMainBinderScreen() {
        // Navigate away from the start destination first -- asserting "Pokédex Binder" appears
        // without ever having left it would be a tautology, since MainBinderScreen is already
        // rendered on launch.
        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Unown").performClick()
        assertOnScreenTextExists("Unown")

        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        clickDrawerPokedexItem()

        assertOnScreenTextExists("Pokédex Binder")
    }

    @Test
    fun drawerConnectingArtItemNavigatesToConnectingArtScreen() {
        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Connecting Art").performClick()

        assertOnScreenTextExists("Connecting Art")
    }

    @Test
    fun drawerPersonalCollectionItemNavigatesToPersonalCollectionScreen() {
        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Personal Collection").performClick()

        assertOnScreenTextExists("Personal Collection")
    }

    @Test
    fun drawerUnownItemNavigatesToUnownScreen() {
        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Unown").performClick()

        assertOnScreenTextExists("Unown")
    }

    @Test
    fun drawerCardHistoryItemNavigatesToSecondaryBinderScreen() {
        composeTestRule.onNodeWithContentDescription("Menu").performClick()
        composeTestRule.onNodeWithText("Card History").performClick()

        assertOnScreenTextExists("Card History")
    }

    @Test
    fun bottomNavShowsPokedexAndHidesScanByDefault() {
        // useCameraScanner is set to false explicitly in setUp() above, not assumed from
        // whatever the device/emulator's ambient DataStore value happens to be.
        assertOnScreenTextExists("Pokédex")
        composeTestRule.onNodeWithContentDescription("Scan").assertDoesNotExist()
    }
}
