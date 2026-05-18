package com.skyler.pokedexbinder.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.skyler.pokedexbinder.ui.AssignmentViewModel
import com.skyler.pokedexbinder.ui.mainbinder.MainBinderScreen
import com.skyler.pokedexbinder.ui.manualsearch.ManualSearchScreen
import com.skyler.pokedexbinder.ui.scanner.ScannerScreen
import com.skyler.pokedexbinder.ui.secondarybinder.SecondaryBinderScreen
import com.skyler.pokedexbinder.ui.secondarybinder.SecondaryBinderViewModel
import com.skyler.pokedexbinder.ui.slotdetail.SlotDetailScreen

sealed class Screen(val route: String) {
    object MainBinder : Screen("main_binder")
    object SecondaryBinder : Screen("secondary_binder")
    object SlotDetail : Screen("slot_detail/{pokemonId}") {
        fun createRoute(pokemonId: String) = "slot_detail/$pokemonId"
    }
    object Scanner : Screen("scanner/{pokemonId}") {
        fun createRoute(pokemonId: String) = "scanner/$pokemonId"
    }
    object ManualSearch : Screen("manual_search/{pokemonId}") {
        fun createRoute(pokemonId: String) = "manual_search/$pokemonId"
    }
    object AddToSecondary : Screen("add_to_secondary")
}

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val bottomItems = listOf(
        Triple(Screen.MainBinder, "Binder", Icons.Default.Home),
        Triple(Screen.SecondaryBinder, "Secondary", Icons.Default.Menu)
    )

    Scaffold(
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDest = navBackStackEntry?.destination
                bottomItems.forEach { (screen, label, icon) ->
                    NavigationBarItem(
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                        selected = currentDest?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
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
                MainBinderScreen(onSlotClick = { pokemonId ->
                    navController.navigate(Screen.SlotDetail.createRoute(pokemonId))
                })
            }
            composable(Screen.SecondaryBinder.route) {
                SecondaryBinderScreen(
                    onAddCard = { navController.navigate(Screen.AddToSecondary.route) }
                )
            }
            composable(
                route = Screen.SlotDetail.route,
                arguments = listOf(navArgument("pokemonId") { type = NavType.StringType })
            ) { backStack ->
                val pokemonId = backStack.arguments?.getString("pokemonId") ?: ""
                SlotDetailScreen(
                    pokemonId = pokemonId,
                    onBack = { navController.popBackStack() },
                    onScanCard = { pid -> navController.navigate(Screen.Scanner.createRoute(pid)) },
                    onManualSearch = { pid -> navController.navigate(Screen.ManualSearch.createRoute(pid)) }
                )
            }
            composable(
                route = Screen.Scanner.route,
                arguments = listOf(navArgument("pokemonId") { type = NavType.StringType })
            ) { backStack ->
                val pokemonId = backStack.arguments?.getString("pokemonId") ?: ""
                val assignmentVm: AssignmentViewModel = hiltViewModel()
                ScannerScreen(
                    pokemonId = pokemonId,
                    onCardSelected = { card ->
                        assignmentVm.assign(pokemonId, card)
                        navController.popBackStack()
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = Screen.ManualSearch.route,
                arguments = listOf(navArgument("pokemonId") { type = NavType.StringType })
            ) { backStack ->
                val pokemonId = backStack.arguments?.getString("pokemonId") ?: ""
                val assignmentVm: AssignmentViewModel = hiltViewModel()
                ManualSearchScreen(
                    onCardSelected = { card ->
                        assignmentVm.assign(pokemonId, card)
                        navController.popBackStack()
                    },
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
