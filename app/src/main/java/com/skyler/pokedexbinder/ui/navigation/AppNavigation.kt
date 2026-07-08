package com.skyler.pokedexbinder.ui.navigation

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.skyler.pokedexbinder.R
import com.skyler.pokedexbinder.ui.connectingart.ConnectingArtScreen
import com.skyler.pokedexbinder.ui.connectingart.ConnectingArtViewModel
import com.skyler.pokedexbinder.ui.mainbinder.MainBinderScreen
import com.skyler.pokedexbinder.ui.manualsearch.ManualSearchScreen
import com.skyler.pokedexbinder.ui.personalcollection.PersonalCollectionScreen
import com.skyler.pokedexbinder.ui.quickscan.QuickScanScreen
import com.skyler.pokedexbinder.ui.scanner.ScannerScreen
import com.skyler.pokedexbinder.ui.secondarybinder.SecondaryBinderScreen
import com.skyler.pokedexbinder.ui.secondarybinder.SecondaryBinderViewModel
import com.skyler.pokedexbinder.ui.settings.SettingsScreen
import com.skyler.pokedexbinder.ui.settings.SettingsViewModel
import com.skyler.pokedexbinder.ui.slotdetail.SlotDetailScreen
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {
    object MainBinder : Screen("main_binder")
    object SecondaryBinder : Screen("secondary_binder")
    object ConnectingArt : Screen("connecting_art")
    object ConnectingArtSearch : Screen("connecting_art_search/{slotId}") {
        fun createRoute(slotId: Int) = "connecting_art_search/$slotId"
    }
    object PersonalCollection : Screen("personal_collection")
    object Settings : Screen("settings")
    object SlotDetail : Screen("slot_detail/{slotId}") {
        fun createRoute(slotId: String) = "slot_detail/$slotId"
    }
    object QuickScan : Screen("quick_scan?slotId={slotId}&replacing={replacing}") {
        fun createRoute(slotId: String = "", replacing: Boolean = false): String {
            val enc = StandardCharsets.UTF_8.toString()
            fun encode(s: String) = URLEncoder.encode(s, enc).replace("+", "%20")
            return "quick_scan?slotId=${encode(slotId)}&replacing=$replacing"
        }
    }
    object Scanner : Screen("scanner?slotId={slotId}&pokemonName={pokemonName}&isSecondary={isSecondary}") {
        fun createRoute(slotId: String = "", pokemonName: String = "", isSecondary: Boolean = false): String {
            val enc = StandardCharsets.UTF_8.toString()
            fun encode(s: String) = URLEncoder.encode(s, enc).replace("+", "%20")
            return "scanner?slotId=${encode(slotId)}&pokemonName=${encode(pokemonName)}&isSecondary=$isSecondary"
        }
    }
    object AddToSecondary : Screen("add_to_secondary")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = navBackStackEntry?.destination

    val settingsVm: SettingsViewModel = hiltViewModel()
    val settings by settingsVm.settings.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openDrawer() = scope.launch { drawerState.open() }
    fun closeDrawer() = scope.launch { drawerState.close() }

    fun isSelected(route: String) =
        currentDest?.hierarchy?.any { it.route == route } == true

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                NavigationDrawerItem(
                    label = { Text("Pokédex") },
                    selected = isSelected(Screen.MainBinder.route),
                    onClick = { closeDrawer(); navigateTo(Screen.MainBinder.route) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("Connecting Art") },
                    selected = isSelected(Screen.ConnectingArt.route),
                    onClick = { closeDrawer(); navigateTo(Screen.ConnectingArt.route) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("Personal Collection") },
                    selected = isSelected(Screen.PersonalCollection.route),
                    onClick = { closeDrawer(); navigateTo(Screen.PersonalCollection.route) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                NavigationDrawerItem(
                    label = { Text("Card History") },
                    selected = isSelected(Screen.SecondaryBinder.route),
                    onClick = { closeDrawer(); navigateTo(Screen.SecondaryBinder.route) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentDest?.hierarchy?.any { it.route == Screen.MainBinder.route } == true,
                        onClick = { navigateTo(Screen.MainBinder.route) },
                        icon = { Icon(painterResource(R.drawable.ic_pokeball), contentDescription = "Binder") },
                        label = { Text("Pokédex") }
                    )
                    if (settings.useCameraScanner) {
                        NavigationBarItem(
                            selected = false,
                            onClick = {
                                val isOnSecondary = currentDest?.hierarchy
                                    ?.any { it.route == Screen.SecondaryBinder.route } == true
                                navController.navigate(Screen.Scanner.createRoute(isSecondary = isOnSecondary))
                            },
                            icon = { Icon(Icons.Default.CameraAlt, contentDescription = "Scan") },
                            label = { Text("Scan") }
                        )
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Screen.MainBinder.route,
                modifier = Modifier.padding(padding)
            ) {
                composable(Screen.MainBinder.route) {
                    MainBinderScreen(
                        onSlotClick = { slot ->
                            if (slot.isOccupied) {
                                navController.navigate(Screen.SlotDetail.createRoute(slot.id))
                            } else {
                                navController.navigate(Screen.QuickScan.createRoute(slotId = slot.id))
                            }
                        },
                        onSettingsClick = { navController.navigate(Screen.Settings.route) },
                        onOpenDrawer = { openDrawer() }
                    )
                }
                composable(Screen.SecondaryBinder.route) {
                    SecondaryBinderScreen(
                        onScanCard = {
                            navController.navigate(Screen.Scanner.createRoute(isSecondary = true))
                        },
                        onSearchCard = { navController.navigate(Screen.AddToSecondary.route) }
                    )
                }
                composable(Screen.ConnectingArt.route) {
                    ConnectingArtScreen(
                        onOpenDrawer = { openDrawer() },
                        onOpenSlotSearch = { slotId ->
                            navController.navigate(Screen.ConnectingArtSearch.createRoute(slotId))
                        }
                    )
                }
                composable(
                    route = Screen.ConnectingArtSearch.route,
                    arguments = listOf(navArgument("slotId") { type = NavType.IntType })
                ) { backStack ->
                    val parentEntry = remember(backStack) {
                        navController.getBackStackEntry(Screen.ConnectingArt.route)
                    }
                    val artVm: ConnectingArtViewModel = hiltViewModel(parentEntry)
                    ManualSearchScreen(
                        onCardSelected = { card ->
                            artVm.assignPendingCard(card)
                            navController.popBackStack()
                        },
                        onBack = {
                            artVm.cancelAssign()
                            navController.popBackStack()
                        }
                    )
                }
                composable(Screen.PersonalCollection.route) {
                    PersonalCollectionScreen(onOpenDrawer = { openDrawer() })
                }
                composable(Screen.Settings.route) {
                    SettingsScreen(onBack = { navController.popBackStack() })
                }
                composable(
                    route = Screen.SlotDetail.route,
                    arguments = listOf(navArgument("slotId") { type = NavType.StringType })
                ) { backStack ->
                    val slotId = backStack.arguments?.getString("slotId") ?: ""
                    SlotDetailScreen(
                        pokemonId = slotId,
                        onBack = { navController.popBackStack() },
                        onSearch = { _, sid, replacing ->
                            navController.navigate(Screen.QuickScan.createRoute(sid, replacing))
                        }
                    )
                }
                composable(
                    route = Screen.QuickScan.route,
                    arguments = listOf(
                        navArgument("slotId") { type = NavType.StringType; defaultValue = "" },
                        navArgument("replacing") { type = NavType.BoolType; defaultValue = false }
                    )
                ) {
                    QuickScanScreen(
                        onDone = { navController.popBackStack(Screen.MainBinder.route, false) },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(
                    route = Screen.Scanner.route,
                    arguments = listOf(
                        navArgument("slotId") { type = NavType.StringType; defaultValue = "" },
                        navArgument("pokemonName") { type = NavType.StringType; defaultValue = "" },
                        navArgument("isSecondary") { type = NavType.BoolType; defaultValue = false }
                    )
                ) { backStack ->
                    val isSecondary = backStack.arguments?.getBoolean("isSecondary") ?: false
                    ScannerScreen(
                        onDone = {
                            if (isSecondary) navController.popBackStack()
                            else navController.popBackStack(Screen.MainBinder.route, false)
                        },
                        onSearchManually = {
                            navController.popBackStack()
                            if (isSecondary) navController.navigate(Screen.AddToSecondary.route)
                            else navController.navigate(Screen.QuickScan.createRoute())
                        },
                        onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Screen.AddToSecondary.route) {
                    val secondaryVm: SecondaryBinderViewModel = hiltViewModel()
                    ManualSearchScreen(
                        onCardSelected = { card ->
                            secondaryVm.addCard(card)
                            navController.popBackStack()
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }
    }
}
