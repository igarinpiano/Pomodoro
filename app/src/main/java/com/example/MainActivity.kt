package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.HomeScreen
import com.example.ui.theme.PomodoroTheme
import com.example.viewmodel.PomodoroViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: PomodoroViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val timerState by viewModel.timerState.collectAsStateWithLifecycle()
            PomodoroTheme(
                themeMode = timerState.settings.themeMode,
                dynamicColor = true
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen(viewModel = viewModel)
                }
            }
        }
    }
}
