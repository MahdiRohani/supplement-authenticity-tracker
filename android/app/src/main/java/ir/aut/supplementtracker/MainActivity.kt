package ir.aut.supplementtracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import ir.aut.supplementtracker.core.designsystem.SupplementSpacing
import ir.aut.supplementtracker.core.designsystem.SupplementTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SupplementTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BrandHome(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(SupplementSpacing.Md),
                    )
                }
            }
        }
    }
}

@Composable
fun BrandHome(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.app_name),
        modifier = modifier,
    )
}

@Preview(showBackground = true)
@Composable
fun BrandHomePreview() {
    SupplementTheme {
        BrandHome()
    }
}
