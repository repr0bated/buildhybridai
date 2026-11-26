package com.example.callguardian.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.example.callguardian.ui.theme.Brand
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.callguardian.ui.screens.ActivityScreen
import com.example.callguardian.ui.screens.EventDetailScreen
import com.example.callguardian.ui.screens.HomeScreen
import com.example.callguardian.ui.screens.LabScreen
import com.example.callguardian.ui.screens.ListsScreen
import com.example.callguardian.ui.screens.ReceiptDetailScreen
import com.example.callguardian.ui.screens.ReceiptsScreen
import com.example.callguardian.ui.screens.SearchScreen
import com.example.callguardian.ui.screens.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("activity", "Activity", Icons.Default.Shield),
    Tab("logs", "Logs", Icons.Default.Call),
    Tab("watchlist", "Watchlist", Icons.Default.Visibility),
    Tab("settings", "Settings", Icons.Default.Settings),
)

data class SetupActions(
    val requestScreeningRole: () -> Unit,
    val requestSms: () -> Unit,
    val requestContacts: () -> Unit,
    val requestNotifications: () -> Unit,
    val openAppSettings: () -> Unit,
    val requestPermissions: (List<String>) -> Unit = {},
    val openNotificationAccess: () -> Unit = {},
    val requestSmsRole: () -> Unit = {},
    val openSmsRoleSettings: () -> Unit = {},
    val unlockPrivate: () -> Unit = {},
)

/** Faint terminal grid behind every screen; glass cards sit on top of it. */
private fun DrawScope.gridBackdrop() {
    val step = 28.dp.toPx()
    val c = Brand.Line.copy(alpha = 0.22f)
    var x = 0f; while (x < size.width) { drawLine(c, Offset(x, 0f), Offset(x, size.height), 1f); x += step }
    var y = 0f; while (y < size.height) { drawLine(c, Offset(0f, y), Offset(size.width, y), 1f); y += step }
    drawRect(Brush.radialGradient(listOf(Brand.Lime.copy(alpha = 0.07f), Color.Transparent), center = Offset(size.width * 0.85f, 0f), radius = size.width))
}

@Composable
fun CallGuardianApp(vm: AppViewModel, actions: SetupActions, openEventId: Long?, onEventOpened: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val marks by vm.privacyKeys.collectAsState()
    val unlocked by vm.privacyUnlocked.collectAsState()
    val owned by vm.ownedAccounts.collectAsState()
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(marks.isNotEmpty()) {
        val window = (view.context as? android.app.Activity)?.window
        if (marks.isNotEmpty()) window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        else window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.example.callguardian.privacy.LocalPrivacy provides com.example.callguardian.privacy.PrivacyUiState(
            marks = marks, unlocked = unlocked, onUnlock = actions.unlockPrivate,
        ),
        com.example.callguardian.ui.components.LocalOwned provides com.example.callguardian.ui.components.OwnedLookup(owned),
    ) {
    CallGuardianNav(vm, actions, openEventId, onEventOpened, nav, route)
    }
}

@Composable
private fun CallGuardianNav(vm: AppViewModel, actions: SetupActions, openEventId: Long?, onEventOpened: () -> Unit, nav: androidx.navigation.NavHostController, route: String?) {

    LaunchedEffect(openEventId) {
        if (openEventId != null && openEventId > 0) { nav.navigate("event/$openEventId"); onEventOpened() }
    }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.background(Brand.Void).drawBehind { gridBackdrop() },
        bottomBar = {
            if (tabs.any { it.route == route }) Column {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Brand.Lime.copy(alpha = 0.5f), Color.Transparent))))
            NavigationBar(containerColor = Brand.Void.copy(alpha = 0.96f), tonalElevation = 0.dp) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = route == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true; restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label.lowercase(), maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Brand.Lime, selectedTextColor = Brand.Lime,
                            indicatorColor = Brand.Lime.copy(alpha = 0.14f),
                            unselectedIconColor = Brand.InkDim, unselectedTextColor = Brand.InkDim,
                        ),
                    )
                }
            }
            }
        },
    ) { pad ->
        NavHost(
            nav, startDestination = "home", modifier = Modifier.padding(pad).consumeWindowInsets(pad),
            enterTransition = { fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 24 } },
            exitTransition = { fadeOut(tween(140)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(140)) + slideOutVertically(tween(200)) { it / 24 } },
        ) {
            composable("home") { HomeScreen(vm, actions, onEvent = { nav.navigate("event/$it") }, onTab = { nav.navigate(it) }, onSearch = { nav.navigate("search") }) }
            composable("activity") { ActivityScreen(vm, onEvent = { nav.navigate("event/$it") }, onReceipt = { nav.navigate("receipt/$it") }, onSearch = { nav.navigate("search") }) }
            composable("activity/{tab}", arguments = listOf(navArgument("tab") { type = NavType.StringType })) {
                ActivityScreen(vm, onEvent = { nav.navigate("event/$it") }, onReceipt = { nav.navigate("receipt/$it") }, initialTab = it.arguments?.getString("tab"), onSearch = { nav.navigate("search") })
            }
            composable("logs") { LabScreen(vm, onEvent = { nav.navigate("event/$it") }, onReceipt = { nav.navigate("receipt/$it") }, onSearch = { nav.navigate("search") }) }
            composable("subscriptions") { LabScreen(vm, onEvent = { nav.navigate("event/$it") }, onReceipt = { nav.navigate("receipt/$it") }, startSubscriptions = true, onSearch = { nav.navigate("search") }) }
            composable("watchlist") { LabScreen(vm, onEvent = { nav.navigate("event/$it") }, watchlist = true, onSearch = { nav.navigate("search") }) }
            composable("search") { SearchScreen(vm, onBack = { nav.popBackStack() }, onEvent = { nav.navigate("event/$it") }, onReceipt = { nav.navigate("receipt/$it") }, onSubscriptions = { nav.navigate("subscriptions") }) }
            composable("settings") { SettingsScreen(vm, actions, onLists = { nav.navigate("lists") }) }
            composable("lists") { ListsScreen(vm, onBack = { nav.popBackStack() }) }
            composable("event/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                EventDetailScreen(vm, it.arguments?.getLong("id") ?: -1, onBack = { nav.popBackStack() }, onReceipt = { r -> nav.navigate("receipt/$r") })
            }
            composable("receipt/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                ReceiptDetailScreen(vm, it.arguments?.getLong("id") ?: -1, onBack = { nav.popBackStack() }, onEvent = { e -> nav.navigate("event/$e") })
            }
        }
    }
}
