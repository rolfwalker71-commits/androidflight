package de.rolfwalker.flightbuddy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import de.rolfwalker.flightbuddy.feature.airport.AirportBoardScreen
import de.rolfwalker.flightbuddy.feature.airport.AirportBoardViewModel
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
fun FlightBuddyRoot(
    tablet: Boolean,
    openFlightId: String?,
    openAlerts: Boolean,
    settingsVm: SettingsViewModel,
    onExportBackup: (String) -> Unit,
    onImportBackup: () -> Unit,
) {
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
    val showDock = !tablet &&
        route?.startsWith("flight/") != true &&
        route?.startsWith("airport/") != true &&
        route != "add"

    LaunchedEffect(openFlightId, openAlerts) {
        if (openFlightId != null) nav.navigate("flight/$openFlightId")
        else if (openAlerts) nav.navigate("alerts")
    }

    Scaffold(
        contentWindowInsets = if (showDock) {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
        } else {
            WindowInsets.safeDrawing
        },
        bottomBar = {
            if (showDock) {
                CompactDock(
                    items = items,
                    selectedRoute = route,
                    onSelect = { dest ->
                        nav.navigate(dest.route) {
                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!tablet && route == "home") {
                val addLabel = stringResource(R.string.home_add)
                FloatingActionButton(
                    onClick = { nav.navigate("add") },
                    shape = RoundedCornerShape(28.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
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
                    LogbookScreen(vm, language = homeState.prefs.language, units = homeState.prefs.units)
                }
                composable("alerts") {
                    val vm: AlertsViewModel = koinViewModel()
                    AlertsScreen(vm, onOpen = { id -> if (id != null) nav.navigate("flight/$id") })
                }
                composable("settings") {
                    SettingsScreen(
                        vm = settingsVm,
                        onExportBackup = onExportBackup,
                        onImportBackup = onImportBackup,
                    )
                }
                composable("flight/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("id") ?: return@composable
                    val vm: de.rolfwalker.flightbuddy.feature.flights.FlightDetailViewModel = koinViewModel(parameters = { parametersOf(id) })
                    FlightDetailScreen(
                        tablet = tablet,
                        vm = vm,
                        onBack = { nav.popBackStack() },
                        onAirport = { code, arrivals ->
                            nav.navigate("airport/$code/${if (arrivals) "arr" else "dep"}")
                        },
                    )
                }
                composable(
                    "airport/{code}/{dir}",
                    arguments = listOf(
                        navArgument("code") { type = NavType.StringType },
                        navArgument("dir") { type = NavType.StringType },
                    ),
                ) { entry ->
                    val code = entry.arguments?.getString("code") ?: return@composable
                    val arrivals = entry.arguments?.getString("dir") == "arr"
                    val vm: AirportBoardViewModel = koinViewModel(parameters = { parametersOf(code, arrivals) })
                    AirportBoardScreen(vm = vm, onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

/** Flush MY3 dock: 56dp items + system nav inset only (no 80dp NavigationBar + double inset). */
@Composable
private fun CompactDock(
    items: List<Dest>,
    selectedRoute: String?,
    onSelect: (Dest) -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { dest ->
                val selected = selectedRoute == dest.route
                val label = stringResource(dest.label)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(dest) }
                        .semantics { contentDescription = label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 14.dp, vertical = 3.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            dest.icon,
                            contentDescription = label,
                            modifier = Modifier.size(22.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}
