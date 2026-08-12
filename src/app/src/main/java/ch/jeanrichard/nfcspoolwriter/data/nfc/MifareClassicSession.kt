package ch.jeanrichard.nfcspoolwriter.data.nfc

import android.nfc.Tag
import android.nfc.tech.MifareClassic
import java.io.IOException

/**
 * The real [MifareSession], wrapping `android.nfc.tech.MifareClassic`.
 *
 * Deliberately thin — it forwards calls and nothing else. Every decision worth testing lives in
 * [MifareTagReaderWriter]; this class is the one piece Phase 2 cannot cover with unit tests, so the
 * less it does, the smaller the untested surface. Its behaviour is checked manually on real
 * hardware.
 */
class MifareClassicSession private constructor(
    private val tech: MifareClassic,
) : MifareSession {

    override val uid: ByteArray get() = tech.tag.id

    override fun connect() = tech.connect()

    override fun reconnect() {
        // close() then connect() is the documented way to reset a tag technology connection; the
        // close is tolerant because the tag may already have dropped.
        try {
            tech.close()
        } catch (_: IOException) {
            // Already closed or gone; connect() below reports the real problem.
        }
        tech.connect()
    }

    override fun authenticateSectorWithKeyA(sector: Int, key: ByteArray): Boolean =
        tech.authenticateSectorWithKeyA(sector, key)

    override fun readBlock(block: Int): ByteArray = tech.readBlock(block)

    override fun writeBlock(block: Int, data: ByteArray) = tech.writeBlock(block, data)

    /**
     * Never throws: close runs on failure paths where the tag is often already gone, and an
     * exception here would mask the real error that triggered the cleanup.
     */
    override fun close() {
        try {
            tech.close()
        } catch (_: IOException) {
            // The tag was already out of range; nothing to release.
        }
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
