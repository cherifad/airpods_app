package com.airbuds.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.airbuds.companion.ui.BudsApp
import com.airbuds.companion.ui.theme.BudsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BudsTheme {
                BudsApp()
            }
        }
    }
}
