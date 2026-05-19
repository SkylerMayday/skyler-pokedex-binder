package com.skyler.pokedexbinder.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
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
import com.skyler.pokedexbinder.data.model.PokemonSlot
import com.skyler.pokedexbinder.ui.mainbinder.MainBinderScreen
import com.skyler.pokedexbinder.ui.manualsearch.ManualSearchScreen
import com.skyler.pokedexbinder.ui.quickscan.QuickScanScreen
import com.skyler.pokedexbinder.ui.scanner.ScannerScreen
import com.skyler.pokedexbinder.ui.secondarybinder.SecondaryBinderScreen
import com.skyler.pokedexbinder.ui.secondarybinder.SecondaryBinderViewModel
import com.skyler.pokedexbinder.ui.settings.SettingsScreen
import com.skyler.pokedexbinder.ui.settings.SettingsViewModel
import com.skyler.pokedexbinder.ui.slotdetail.SlotDetailScreen
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed class Screen(val route: String) {
    object MainBinder : Screen("main_binder")
    object SecondaryBinder : Screen("secondary_binder")
    object Settings : Screen("settings")
    object SlotDetail : Screen("slot_detail/{slotId}") {
        fun createRoute(slotId: String) = "slot_detail/$slotId"
    }
    object QuickScan : Screen("quick_scan?pokemonName={pokemonName}&slotId={slotId}&replacing={replacing}") {
        fun createRoute(pokemonName: String = "", slotId: String = "", replacing: Boolean = false): String {
            val enc = StandardCharsets.UTF_8.toString()
            fun encode(s: String) = URLEncoder.encode(s, enc).replace("+", "%20")
            return "quick_scan?pokemonName=${encode(pokemonName)}&slotId=${encode(slotId)}&replacing=$replacing"
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

    var scanChoiceSlot by remember { mutableStateOf<PokemonSlot?>(null) }

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentDest?.hierarchy?.any { it.route == Screen.MainBinder.route } == true,
                    onClick = { navigateTo(Screen.MainBinder.route) },
                    icon = { Icon(painterResource(R.drawable.ic_pokeball), contentDescription = "Binder") },
                    label = { Text("Pokédex") }
                )
                if (settings.showSecondaryBinder) {
                    NavigationBarItem(
                        selected = currentDest?.hierarchy?.any { it.route == Screen.SecondaryBinder.route } == true,
                        onClick = { navigateTo(Screen.SecondaryBinder.route) },
                        icon = { Icon(Icons.Default.Menu, contentDescription = "Secondary") },
                        label = { Text("Secondary") }
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
                            scanChoiceSlot = slot
                        }
                    },
                    onSettingsClick = { navController.navigate(Screen.Settings.route) }
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
                    onSearch = { pokemonName, sid, replacing ->
                        navController.navigate(Screen.QuickScan.createRoute(pokemonName, sid, replacing))
                    }
                )
            }
            composable(
                route = Screen.QuickScan.route,
                arguments = listOf(
                    navArgument("pokemonName") { type = NavType.StringType; defaultValue = "" },
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
                        // For main binder: popping back returns to the scan/search choice sheet
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

    // Scan / Search choice bottom sheet — shown when an empty main binder slot is tapped
    scanChoiceSlot?.let { slot ->
        ModalBottomSheet(onDismissRequest = { scanChoiceSlot = null }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text("Add Card for ${slot.name}", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        scanChoiceSlot = null
                        navController.navigate(
                            Screen.Scanner.createRoute(slotId = slot.id, pokemonName = slot.name)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Scan Card") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        scanChoiceSlot = null
                        navController.navigate(
                            Screen.QuickScan.createRoute(pokemonName = slot.name, slotId = slot.id)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Search Manually") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
