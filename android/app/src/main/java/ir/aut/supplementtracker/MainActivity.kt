package ir.aut.supplementtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ir.aut.supplementtracker.core.data.HttpProductRepository
import ir.aut.supplementtracker.core.designsystem.SupplementTheme
import ir.aut.supplementtracker.core.domain.RegisterProductUseCase
import ir.aut.supplementtracker.feature.manufacturerregister.ManufacturerRegisterScreen
import ir.aut.supplementtracker.feature.manufacturerregister.ManufacturerRegisterUiEffect
import ir.aut.supplementtracker.feature.manufacturerregister.ManufacturerRegisterViewModel
import kotlinx.coroutines.flow.collectLatest

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val registerProduct = RegisterProductUseCase(HttpProductRepository())
        setContent {
            SupplementTheme {
                val snackbarHostState = remember { SnackbarHostState() }
                val viewModel: ManufacturerRegisterViewModel =
                    viewModel(factory = ManufacturerRegisterViewModel.factory(registerProduct))
                val state by viewModel.state.collectAsStateWithLifecycle()

                LaunchedEffect(viewModel) {
                    viewModel.effects.collectLatest { effect ->
                        when (effect) {
                            is ManufacturerRegisterUiEffect.ShowMessage ->
                                snackbarHostState.showSnackbar(effect.message)
                        }
                    }
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { innerPadding ->
                    ManufacturerRegisterScreen(
                        state = state,
                        onEvent = viewModel::onEvent,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}
