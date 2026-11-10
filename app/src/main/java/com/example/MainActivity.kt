package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.DocuSnapApp
import com.example.ui.DocuSnapViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: DocuSnapViewModel = viewModel(
                factory = DocuSnapViewModel.provideFactory(application)
            )
            DocuSnapApp(viewModel = viewModel)
        }
    }
}
