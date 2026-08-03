package ch.jeanrichard.nfcspoolwriter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ch.jeanrichard.nfcspoolwriter.ui.navigation.AppNavigation
import ch.jeanrichard.nfcspoolwriter.ui.theme.NfcSpoolWriterTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NfcSpoolWriterTheme {
                AppNavigation(container = appContainer)
            }
        }
    }
}
