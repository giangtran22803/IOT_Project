package com.example.iotproject

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.iotproject.ui.theme.IOTProjectTheme
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.composable
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost



class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IOTProjectTheme {
                MyAppNavHost()
            }
        }
    }
}

object AppDestinations {
    const val LOGIN_ROUTE = "login"
    const val HOME_ROUTE = "home"
    const val DEVICES_ROUTE = "devices"
}

@Composable
fun MyAppNavHost() {
    val navController = androidx.navigation.compose.rememberNavController()

    NavHost(navController = navController, startDestination = AppDestinations.LOGIN_ROUTE) {
        composable(AppDestinations.LOGIN_ROUTE) {
            LoginScreen(
                authViewModel = authViewModel(),
                onLoginSuccess = {
                    navController.navigate(AppDestinations.DEVICES_ROUTE) {
                        popUpTo(AppDestinations.LOGIN_ROUTE) {
                            inclusive = true // Remove the login screen from back stack
                        }
                        launchSingleTop = true // Avoid multiple copies of home if already there
                    }
                }
            )
        }
        composable(AppDestinations.HOME_ROUTE) {
            // Your HomeScreen Composable
            SensorViewScreen()
        }
        composable(AppDestinations.DEVICES_ROUTE) {
            DeviceListScreen(onClickListener = {
                navController.navigate(AppDestinations.HOME_ROUTE) {
                    popUpTo(AppDestinations.LOGIN_ROUTE) {
                        inclusive = true // Remove the login screen from back stack
                    }
                    launchSingleTop = true // Avoid multiple copies of home if already there
                }
            })
        }
    }
}


