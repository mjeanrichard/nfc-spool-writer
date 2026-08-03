package ch.jeanrichard.nfcspoolwriter

import android.app.Application
import android.content.Context

class NfcSpoolWriterApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}

/** Convenience accessor for reaching the container from an Activity or Composable context. */
val Context.appContainer: AppContainer
    get() = (applicationContext as NfcSpoolWriterApplication).container
