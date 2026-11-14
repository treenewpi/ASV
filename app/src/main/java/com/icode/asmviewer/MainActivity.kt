package com.icode.asmviewer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.icode.asmviewer.data.ViewerViewModel
import com.icode.asmviewer.ui.MainScreen
import com.icode.asmviewer.ui.theme.AsmViewerTheme

class MainActivity : AppCompatActivity() {
    private val viewModel: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AsmViewerTheme(darkTheme = viewModel.darkTheme) {
                MainScreen(viewModel)
            }
        }
    }
}
