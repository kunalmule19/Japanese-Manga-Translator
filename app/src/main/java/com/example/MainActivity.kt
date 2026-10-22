package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.example.service.LiveMangaOverlayService
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.ReaderScreen
import com.example.ui.screens.ScannerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.VocabScreen
import com.example.ui.theme.MangaCrimson
import com.example.ui.theme.MangaIndigoPrimary
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.MangaViewModel

sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Reader : Screen("reader", "Reader", Icons.AutoMirrored.Filled.MenuBook)
    object Scanner : Screen("scanner", "Scanner", Icons.Default.CameraAlt)
    object Vocab : Screen("vocab", "Study", Icons.Default.Style)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object Library : Screen("library", "Library", Icons.AutoMirrored.Filled.MenuBook)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MangaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                MainAppContent(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainAppContent(viewModel: MangaViewModel) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val isOverlayRunning by LiveMangaOverlayService.isRunningFlow.collectAsState()
    val context = LocalContext.current

    val navItems = listOf(
        Screen.Home,
        Screen.Reader,
        Screen.Scanner,
        Screen.Vocab,
        Screen.Settings
    )

    // Hide bottom navigation in full scanner mode for immersive camera viewfinder
    val showBottomBar = currentRoute != Screen.Scanner.route

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.testTag("bottom_nav_bar")
                ) {
                    navItems.forEach { screen ->
                        val selected = currentRoute == screen.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (currentRoute != screen.route) {
                                    navController.navigate(screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = screen.icon,
                                    contentDescription = screen.title,
                                    modifier = Modifier.size(22.dp)
                                )
                            },
                            label = { Text(screen.title, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MangaIndigoPrimary.copy(alpha = 0.2f),
                                selectedIconColor = MangaIndigoPrimary,
                                selectedTextColor = MangaIndigoPrimary
                            ),
                            modifier = Modifier.testTag("nav_${screen.route}")
                        )
                    }
                }
            }
        },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isOverlayRunning) {
                Surface(
                    color = MangaCrimson,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("active_overlay_banner")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "● Live Overlay Active",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Button(
                            onClick = {
                                LiveMangaOverlayService.stop(context)
                                viewModel.setLiveOverlayActive(false)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = MangaCrimson
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier
                                .height(32.dp)
                                .testTag("banner_stop_overlay_btn")
                        ) {
                            Text("Stop Overlay", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }

            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    viewModel = viewModel,
                    onNavigateToReader = { navController.navigate(Screen.Reader.route) },
                    onNavigateToScanner = { navController.navigate(Screen.Scanner.route) },
                    onNavigateToLibrary = { navController.navigate(Screen.Library.route) },
                    onNavigateToVocab = { navController.navigate(Screen.Vocab.route) },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
                )
            }

            composable(Screen.Reader.route) {
                ReaderScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Scanner.route) {
                ScannerScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onNavigateToReader = {
                        navController.navigate(Screen.Reader.route) {
                            popUpTo(Screen.Home.route)
                        }
                    }
                )
            }

            composable(Screen.Library.route) {
                LibraryScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    onNavigateToReader = { navController.navigate(Screen.Reader.route) }
                )
            }

            composable(Screen.Vocab.route) {
                VocabScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Settings.route) {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
}
