package de.rolfwalker.flightbuddy.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.rolfwalker.flightbuddy.R
import de.rolfwalker.flightbuddy.core.ui.Wordmark
import de.rolfwalker.flightbuddy.feature.alerts.AlertsScreen
import de.rolfwalker.flightbuddy.feature.alerts.AlertsViewModel
import de.rolfwalker.flightbuddy.feature.flights.AddFlightSheet
import de.rolfwalker.flightbuddy.feature.flights.FlightDetailScreen
import de.rolfwalker.flightbuddy.feature.flights.HomeScreen
import de.rolfwalker.flightbuddy.feature.flights.HomeViewModel
import de.rolfwalker.flightbuddy.feature.logbook.LogbookScreen
import de.rolfwalker.flightbuddy.feature.logbook.LogbookViewModel
import de.rolfwalker.flightbuddy.feature.map.MapScreen
import de.rolfwalker.flightbuddy.feature.map.MapViewModel
import de.rolfwalker.flightbuddy.feature.settings.SettingsScreen
import de.rolfwalker.flightbuddy.feature.settings.SettingsViewModel
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private data class Dest(val route: String, val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun FlightBuddyRoot(tablet: Boolean, openFlightId: String?, openAlerts: Boolean) {
    val nav = rememberNavController()
    val phone = listOf(
        Dest("home", R.string.nav_flights, Icons.Outlined.Flight),
        Dest("map", R.string.nav_map, Icons.Outlined.Map),
        Dest("logbook", R.string.nav_log, Icons.Outlined.Book),
        Dest("settings", R.string.nav_me, Icons.Outlined.Person),
    )
    val rail = listOf(
        Dest("home", R.string.nav_flights, Icons.Outlined.Flight),
        Dest("map", R.string.nav_live_map, Icons.Outlined.Map),
        Dest("logbook", R.string.nav_logbook, Icons.Outlined.Book),
        Dest("alerts", R.string.nav_alerts, Icons.Outlined.Notifications),
        Dest("settings", R.string.nav_settings, Icons.Outlined.Settings),
    )
    val items = if (tablet) rail else phone
    val back by nav.currentBackStackEntryAsState()
    val route = back?.destination?.route
    val homeVm: HomeViewModel = koinViewModel()
    val homeState by homeVm.state.collectAsState()
    val unread = homeState.unread

    LaunchedEffect(openFlightId, openAlerts) {
        if (openFlightId != null) nav.navigate("flight/$openFlightId")
        else if (openAlerts) nav.navigate("alerts")
    }

    Scaffold(
        bottomBar = {
            if (!tablet && route?.startsWith("flight/") != true && route != "add") {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp) {
                    items.forEach { dest ->
                        val selected = back?.destination?.hierarchy?.any { it.route == dest.route } == true
                        val label = stringResource(dest.label)
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(dest.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = label) },
                            label = { Text(label) },
                            modifier = Modifier.semantics { contentDescription = label },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (!tablet && route == "home") {
                val addLabel = stringResource(R.string.home_add)
                FloatingActionButton(
                    onClick = { nav.navigate("add") },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.size(64.dp).semantics { contentDescription = addLabel },
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = addLabel)
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (tablet) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Box(Modifier.padding(16.dp)) { Wordmark() }
                    items.forEach { dest ->
                        val selected = back?.destination?.hierarchy?.any { it.route == dest.route } == true
                        val label = stringResource(dest.label)
                        NavigationRailItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(dest.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                if (dest.route == "alerts" && unread > 0) {
                                    BadgedBox(badge = { Badge() }) { Icon(dest.icon, contentDescription = label) }
                                } else Icon(dest.icon, contentDescription = label)
                            },
                            label = { Text(label) },
                        )
                    }
                }
            }
            NavHost(navController = nav, startDestination = "home", modifier = Modifier.weight(1f)) {
                composable("home") {
                    HomeScreen(
                        tablet = tablet,
                        vm = homeVm,
                        onAdd = { nav.navigate("add") },
                        onAlerts = { nav.navigate("alerts") },
                        onOpen = { nav.navigate("flight/$it") },
                    )
                }
                composable("add") { AddFlightSheet(onDone = { nav.popBackStack() }, onCancel = { nav.popBackStack() }) }
                composable("map") {
                    val vm: MapViewModel = koinViewModel()
                    MapScreen(tablet = tablet, vm = vm, onOpen = { nav.navigate("flight/$it") })
                }
                composable("logbook") {
                    val vm: LogbookViewModel = koinViewModel()
                    LogbookScreen(vm)
                }
                composable("alerts") {
                    val vm: AlertsViewModel = koinViewModel()
                    AlertsScreen(vm, onOpen = { id -> if (id != null) nav.navigate("flight/$id") })
                }
                composable("settings") {
                    val vm: SettingsViewModel = koinViewModel()
                    SettingsScreen(vm)
                }
                composable("flight/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("id") ?: return@composable
                    val vm: de.rolfwalker.flightbuddy.feature.flights.FlightDetailViewModel = koinViewModel(parameters = { parametersOf(id) })
                    FlightDetailScreen(tablet = tablet, vm = vm, onBack = { nav.popBackStack() })
                }
            }
        }
    }
}
