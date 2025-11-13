package com.ericwang.example.backbehavior

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.ericwang.example.backbehavior.ui.theme.BackBehaviorTheme
import kotlinx.serialization.Serializable

@Serializable
data object HomeEntry : NavKey

@Serializable
data object SecondEntry : NavKey

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BackBehaviorTheme {
                val backStack = rememberNavBackStack(HomeEntry)
                NavDisplay(
                    backStack = backStack,
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    entryProvider = entryProvider {
                        entry<HomeEntry> {
                            HomeScreen(
                                onNavigateToSecond = {
                                    backStack.add(SecondEntry)
                                }
                            )
                        }
                        entry<SecondEntry> {
                            SecondScreen(
                                onBackClick = { backStack.removeLastOrNull() }
                            )
                        }
                    })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToSecond: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = "Home") }
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
        ) {

            Button(
                onClick = onNavigateToSecond,
            ) {
                Text(text = "Go to Second Screen")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecondScreen(
    onBackClick: () -> Unit,
) {
    BackHandler {
        onBackClick()
        Log.d("SecondScreen", "BackHandler invoked")
    }
    val secondViewModel = viewModel<SecondViewModel>()

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> {
                    Log.d("SecondScreen", "ON_CREATE")
                }

                Lifecycle.Event.ON_DESTROY -> {
                    Log.d("SecondScreen", "ON_DESTROY")
                }

                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = "Second") },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            onBackClick()
                            Log.d("SecondScreen", "Navigation icon clicked")
                        }
                    ) {
                        Text("<")
                    }
                }
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
        ) {
            Text(text = "This is the second screen")
        }
    }
}

class SecondViewModel : ViewModel() {
    init {
        Log.d("SecondViewModel", "init")
    }

    override fun onCleared() {
        Log.d("SecondViewModel", "onCleared called")
        super.onCleared()
    }
}