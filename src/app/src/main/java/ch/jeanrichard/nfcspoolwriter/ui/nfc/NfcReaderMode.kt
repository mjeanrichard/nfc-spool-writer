package ch.jeanrichard.nfcspoolwriter.ui.nfc

import android.nfc.NfcAdapter
import android.nfc.Tag
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Enables NFC reader mode for as long as this composable is in the composition.
 *
 * Reader mode is the app's *only* route to a tag — no `TECH_DISCOVERED` filter is declared in the
 * manifest, so a tap can never launch or foreground the app (REQUIREMENTS.md §6). It also keeps tag
 * handling scoped to the screen that wants it, avoids the platform's own tag-discovered sound and
 * Activity restart, and lets us skip the NDEF check that would otherwise slow every tap down.
 *
 * [onTag] is invoked on a binder thread, not the main thread.
 */
@Composable
fun NfcReaderEffect(onTag: (Tag) -> Unit) {
    val activity = LocalActivity.current ?: return
    // Keep the latest callback without tearing down reader mode on every recomposition.
    val currentOnTag by rememberUpdatedState(onTag)

    DisposableEffect(activity) {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
        val callback = NfcAdapter.ReaderCallback { tag -> currentOnTag(tag) }

        adapter?.enableReaderMode(activity, callback, READER_FLAGS, null)

        onDispose { adapter?.disableReaderMode(activity) }
    }
}

/**
 * MIFARE Classic is an ISO 14443 Type A tag, so NFC-A is the only technology worth polling.
 * Skipping the NDEF check matters because these tags hold no NDEF data — the platform would spend
 * time on every tap looking for something that is never there.
 */
private const val READER_FLAGS =
    NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
