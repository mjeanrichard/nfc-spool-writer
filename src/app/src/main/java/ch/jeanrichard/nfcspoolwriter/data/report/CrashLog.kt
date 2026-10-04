package ch.jeanrichard.nfcspoolwriter.data.report

import java.io.File
import java.io.IOException

/**
 * Keeps the most recent crash on the device until the user decides whether to send it.
 *
 * One file, overwritten by each crash: a report is offered on the next launch, so an older one that
 * was never answered is superseded rather than queued. The file should live outside backed-up
 * storage — it describes this install, and must not reappear on a restored phone.
 *
 * @param appVersion recorded with the crash because the app may be updated before the next launch,
 *   and the report must name the version that actually crashed.
 */
class CrashLog(private val file: File, private val appVersion: String) {

    /** Never throws: it runs while the process is already dying, and must not change how. */
    fun record(thread: Thread, error: Throwable) {
        try {
            file.writeText(
                "App version at crash: $appVersion\n" +
                    "Thread: ${thread.name}\n\n" +
                    error.stackTraceToString()
            )
        } catch (_: IOException) {
            // Nothing can be done about it now; the crash itself still reaches the system handler.
        }
    }

    /** @return the recorded crash, or null if there is none or it cannot be read. */
    fun read(): String? = try {
        file.takeIf(File::exists)?.readText()
    } catch (_: IOException) {
        null
    }

    fun clear() {
        file.delete()
    }
}

/**
 * Records an uncaught exception to [log], then hands it to [next] — normally the system handler,
 * which shows the crash dialog and ends the process exactly as it would without this one.
 */
class CrashRecordingHandler(
    private val log: CrashLog,
    private val next: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, error: Throwable) {
        log.record(thread, error)
        next?.uncaughtException(thread, error)
    }

    companion object {
        /** Installs a recording handler in front of whatever default handler is in place. */
        fun install(log: CrashLog) {
            Thread.setDefaultUncaughtExceptionHandler(
                CrashRecordingHandler(log, Thread.getDefaultUncaughtExceptionHandler())
            )
        }
    }
}
