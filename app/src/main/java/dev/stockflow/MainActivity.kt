package dev.stockflow

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import dev.stockflow.presentation.app.StockFlowApp
import dev.stockflow.presentation.app.WorkspaceViewModel
import dev.stockflow.presentation.theme.StockFlowTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: WorkspaceViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            StockFlowTheme { StockFlowApp(viewModel) }
        }
    }
}
