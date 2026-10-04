package ch.jeanrichard.nfcspoolwriter.data.nfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.MifareClassic
import java.io.IOException

/**
 * The real [MifareSession], wrapping `android.nfc.tech.MifareClassic`.
 *
 * Deliberately thin — it forwards calls and nothing else. Every decision worth testing lives in
 * [MifareTagReaderWriter]; this class is the one piece unit tests cannot cover, so the less it
 * does, the smaller the untested surface. Its behaviour is checked manually on real hardware.
 */
class MifareClassicSession private constructor(
    private val tech: MifareClassic,
) : MifareSession {

    override val uid: ByteArray get() = tech.tag.id

    override fun connect() = tagCall { tech.connect() }

    override fun reconnect() {
        // close() then connect() is the documented way to reset a tag technology connection; the
        // close is tolerant because the tag may already have dropped.
        close()
        connect()
    }

    override fun authenticateSectorWithKeyA(sector: Int, key: ByteArray): Boolean =
        tagCall { tech.authenticateSectorWithKeyA(sector, key) }

    override fun readBlock(block: Int): ByteArray = tagCall { tech.readBlock(block) }

    override fun writeBlock(block: Int, data: ByteArray) = tagCall { tech.writeBlock(block, data) }

    /**
     * Never throws: close runs on failure paths where the tag is often already gone, and an
     * exception here would mask the real error that triggered the cleanup.
     */
    override fun close() {
        try {
            tagCall { tech.close() }
        } catch (_: IOException) {
            // The tag was already out of range; nothing to release.
        }
    }

    /**
     * Reports a stale tag handle as the lost tag it is.
     *
     * Once a tag leaves the field, Android invalidates its `Tag` object, and any further call on it
     * throws `SecurityException` ("Tag ... is out of date") instead of [TagLostException]. That
     * happens whenever a handle outlives its tap — confirming an overwrite after lifting the phone
     * away, for one. [MifareSession] promises an [IOException] for a lost tag, and callers rely on
     * that to turn it into a retryable failure rather than a crash.
     */
    private inline fun <T> tagCall(call: () -> T): T = try {
        call()
    } catch (e: SecurityException) {
        throw TagLostException(e.message).apply { initCause(e) }
    }

    companion object {
        /**
         * @return a session for [tag], or null if the tag does not support MIFARE Classic at all.
         *
         * A null here means *wrong tag* (an NTAG21x or Ultralight, say) and is distinct from the
         * device-level [DeviceCompatibility] check, which means *wrong phone*. Both produce a null
         * or negative result on an incompatible NXP-less phone, which is why the device check must
         * run first — otherwise every tag would look individually broken (REQUIREMENTS.md §3).
         */
        fun open(tag: Tag): MifareClassicSession? =
            MifareClassic.get(tag)?.let(::MifareClassicSession)
    }
}
