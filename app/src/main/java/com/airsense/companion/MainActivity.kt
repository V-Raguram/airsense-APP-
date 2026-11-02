package com.airsense.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airsense.companion.ui.AirsenseApp
import com.airsense.companion.ui.AirsenseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirsenseTheme {
                val model: AppViewModel = viewModel()
                AirsenseApp(model)
            }
        }
    }
}
