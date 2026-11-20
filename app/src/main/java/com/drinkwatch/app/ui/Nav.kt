package com.drinkwatch.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.ShoppingBasket
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.drinkwatch.app.data.ThemeMode
import com.drinkwatch.app.ui.screens.AddScreen
import com.drinkwatch.app.ui.screens.BasketScreen
import com.drinkwatch.app.ui.screens.BudgetScreen
import com.drinkwatch.app.ui.screens.HomeScreen
import com.drinkwatch.app.ui.screens.SettingsScreen
import com.drinkwatch.app.ui.screens.YouScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("prices", "Prices", Icons.Filled.LocalDrink),
    Tab("add", "Add", Icons.Outlined.AddCircleOutline),
    Tab("basket", "Basket", Icons.Filled.ShoppingBasket),
    Tab("budget", "Budget", Icons.Filled.Savings),
    Tab("you", "You", Icons.Filled.Person),
)

@Composable
fun AppRoot(
    themeMode: ThemeMode,
    onThemeMode: (ThemeMode) -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBar = currentRoute in tabs.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = backStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "prices",
            modifier = Modifier.padding(padding),
        ) {
            composable("prices") {
                HomeScreen(onOpenSettings = { navController.navigate("settings") })
            }
            composable("add") { AddScreen() }
            composable("basket") { BasketScreen() }
            composable("budget") { BudgetScreen() }
            composable("you") {
                YouScreen(onOpenSettings = { navController.navigate("settings") })
            }
            composable("settings") {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    themeMode = themeMode,
                    onThemeMode = onThemeMode,
                )
            }
        }
    }
}
