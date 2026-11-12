package com.vesper.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.vesper.app.ui.MainViewModel
import com.vesper.app.ui.VesperApp

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        val app = application as VesperApplication
        MainViewModel.Factory(applicationContext, app.container)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VesperApp(viewModel = viewModel)
        }
    }
}
