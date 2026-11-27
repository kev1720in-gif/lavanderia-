package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.local.LavanderiaDatabase
import com.example.data.repository.LavanderiaRepository
import com.example.lucy.ui.LucyViewModel
import com.example.lucy.vision.LucyAppCheckInitializer
import com.example.ui.navigation.LavanderiaApp
import com.example.ui.theme.LavanderiaControlTheme
import com.example.ui.viewmodel.BackupViewModel
import com.example.ui.viewmodel.DescargaViewModel
import com.example.ui.viewmodel.ImportacionViewModel
import com.example.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val repository by lazy {
        LavanderiaRepository(LavanderiaDatabase.getInstance(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LucyAppCheckInitializer.configureAppCheck(this, intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            LavanderiaControlTheme {
                val mainViewModel: MainViewModel = viewModel(
                    factory = MainViewModel.Factory(repository)
                )
                val importacionViewModel: ImportacionViewModel = viewModel(
                    factory = ImportacionViewModel.Factory(repository)
                )
                val backupViewModel: BackupViewModel = viewModel(
                    factory = BackupViewModel.Factory(repository)
                )
                val descargaViewModel: DescargaViewModel = viewModel(
                    factory = DescargaViewModel.Factory(repository)
                )
                val lucyViewModel: LucyViewModel = viewModel(
                    factory = LucyViewModel.Factory(
                        repository = repository,
                        appContext = applicationContext
                    )
                )
                LavanderiaApp(
                    mainViewModel = mainViewModel,
                    importacionViewModel = importacionViewModel,
                    backupViewModel = backupViewModel,
                    descargaViewModel = descargaViewModel,
                    lucyViewModel = lucyViewModel
                )
            }
        }
    }
}
