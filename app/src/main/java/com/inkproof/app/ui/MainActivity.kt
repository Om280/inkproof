package com.inkproof.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.inkproof.app.ui.editor.EditorScreen
import com.inkproof.app.ui.library.LibraryScreen
import com.inkproof.app.ui.settings.SettingsScreen
import com.inkproof.app.ui.theme.InkProofTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as com.inkproof.app.InkProofApp
        setContent {
            val settings by app.settingsStore.settings
                .collectAsState(initial = com.inkproof.app.data.settings.Settings())
            InkProofTheme(themeMode = settings.appTheme) {
                InkProofNavHost()
            }
        }
    }
}

@Composable
fun InkProofNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                onOpenNotebook = { notebookId ->
                    navController.navigate("editor/$notebookId")
                },
                onOpenSettings = { navController.navigate("settings") }
            )
        }
        composable(
            route = "editor/{notebookId}",
            arguments = listOf(navArgument("notebookId") { type = NavType.StringType })
        ) { backStackEntry ->
            val notebookId = backStackEntry.arguments?.getString("notebookId").orEmpty()
            EditorScreen(
                notebookId = notebookId,
                onBack = { navController.popBackStack() }
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
