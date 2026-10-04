package ch.jeanrichard.nfcspoolwriter

import android.app.Application
import android.content.Context
import ch.jeanrichard.nfcspoolwriter.data.report.CrashRecordingHandler

class NfcSpoolWriterApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
        CrashRecordingHandler.install(container.crashLog)
    }
}

/** Convenience accessor for reaching the container from an Activity or Composable context. */
val Context.appContainer: AppContainer
    get() = (applicationContext as NfcSpoolWriterApplication).container
