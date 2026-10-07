package dev.wearlink.wear.ui

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController

object Routes {
    const val MAIN = "main"
    const val SERVERS = "servers"
    const val APPS = "apps"
    const val ADD = "add"
    const val DELETE = "delete/{id}"
    fun delete(id: String) = "delete/$id"
}

@Composable
fun WearLinkApp() {
    MaterialTheme {
        AppScaffold {
            val nav = rememberSwipeDismissableNavController()
            SwipeDismissableNavHost(navController = nav, startDestination = Routes.MAIN) {
                composable(Routes.MAIN) { MainScreen(onNavigate = { nav.navigate(it) }) }
                composable(Routes.SERVERS) { ServersScreen(onDelete = { nav.navigate(Routes.delete(it)) }) }
                composable(Routes.APPS) { AppsScreen() }
                composable(Routes.ADD) { AddScreen(onDone = { nav.popBackStack() }) }
                composable(Routes.DELETE) { entry ->
                    DeleteScreen(id = entry.arguments?.getString("id").orEmpty(), onDone = { nav.popBackStack() })
                }
            }
        }
    }
}
