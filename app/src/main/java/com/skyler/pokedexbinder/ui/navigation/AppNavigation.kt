package com.skyler.pokedexbinder.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.skyler.pokedexbinder.ui.mainbinder.MainBinderScreen
import com.skyler.pokedexbinder.ui.secondarybinder.SecondaryBinderScreen
import com.skyler.pokedexbinder.ui.slotdetail.SlotDetailScreen

sealed class Screen(val route: String) {
    object MainBinder : Screen("main_binder")
    object SecondaryBinder : Screen("secondary_binder")
    object SlotDetail : Screen("slot_detail/{pokemonId}") {
        fun createRoute(pokemonId: String) = "slot_detail/$pokemonId"
    }
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
            composable(Screen.SecondaryBinder.route) { SecondaryBinderScreen() }
            composable(
                route = Screen.SlotDetail.route,
                arguments = listOf(navArgument("pokemonId") { type = NavType.StringType })
            ) { backStack ->
                SlotDetailScreen(
                    pokemonId = backStack.arguments?.getString("pokemonId") ?: "",
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
